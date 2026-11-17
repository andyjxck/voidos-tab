#!/usr/bin/env python3
"""
The VoidOS boot logo: the still picture the MediaTek bootloader (LK) shows the
moment the tablet powers on, before Android's boot animation. It lives in the
`logo` partition, not in Android, so it is the one part of the boot that no
app, Magisk module or boot animation can reach.

This tool reads a dump of YOUR tablet's logo partition, swaps the full-screen
boot pictures for the VoidOS mark and writes a new image of the same layout:
same number of pictures, same pixel format, same byte size per picture, every
picture it does not replace copied byte for byte, and never bigger than the
partition. Then it checks its own output by unpacking it again.

It never talks to the tablet. Getting the dump and flashing the result are
manual steps in root/README.md ("Boot logo").

    python3 tools/mtk_logo.py info   logo.stock.bin
    python3 tools/mtk_logo.py unpack logo.stock.bin logo.d        # PNG of every full-screen picture
    python3 tools/mtk_logo.py build  logo.stock.bin logo-voidos.bin [--rotate 180] [--all-fullscreen]
    python3 tools/mtk_logo.py verify logo.stock.bin logo-voidos.bin

Format (MediaTek, the same since the MT65xx days):

    0x000  MTK partition header, 512 bytes: magic 0x58881688 (LE), a size,
           the name "LOGO"/"logo" at 0x08, 0xFF padding
    0x200  count    u32   number of pictures
    0x204  total    u32   bytes from 0x200 to the end of the last picture
    0x208  offsets  u32 x count, each from 0x200 (the first is 8 + 4*count)
    ...    pictures, each raw pixels squeezed with zlib (a few firmwares
           use raw deflate with no zlib header; both are handled)

The pixels have no width/height stored: a full-screen picture is simply
width*height*bytes-per-pixel long (800x1280 on the TB-X306F, so 4096000 bytes
for 32-bit, 2048000 for 16-bit). Picture 0 is the boot logo LK shows first and
the last picture is the "kernel logo" shown just before the boot animation;
the others are the off-mode charging and low-battery pictures.
"""
import argparse
import hashlib
import struct
import sys
import zlib
from dataclasses import dataclass, field
from pathlib import Path

MAGIC = 0x58881688
HDR = 512
DEFAULT_SIZE = (800, 1280)  # TB-X306F panel, natural (portrait) orientation


class LogoError(Exception):
    pass


# ------------------------------------------------------------------ parsing

@dataclass
class Picture:
    blob: bytes          # exactly as stored (compressed)
    raw: bytes           # decompressed pixels
    style: str           # "zlib" or "deflate" (raw deflate, no header)
    level: int = 9


@dataclass
class Logo:
    header: bytes                  # the 512-byte MTK header
    pictures: list = field(default_factory=list)
    file_len: int = 0              # length of the file we read (a partition dump is the partition's size)
    total: int = 0                 # the "total" field at 0x204
    hdr_size_rule: str = "total"   # how the header's size field relates to the data: total | file | fixed
    hdr_size: int = 0
    fill: int = 0                  # byte the stock image pads with after the last picture

    @property
    def name(self) -> str:
        return self.header[8:40].split(b"\0", 1)[0].decode("ascii", "replace")


def _inflate(blob: bytes):
    """Returns (pixels, style, unused tail length)."""
    for style, wbits in (("zlib", zlib.MAX_WBITS), ("deflate", -zlib.MAX_WBITS)):
        d = zlib.decompressobj(wbits)
        try:
            out = d.decompress(blob) + d.flush()
        except zlib.error:
            continue
        if d.eof:
            return out, style, len(d.unused_data)
    raise LogoError("a picture is neither zlib nor raw deflate")


def _deflate(raw: bytes, style: str, level: int = 9) -> bytes:
    if style == "zlib":
        return zlib.compress(raw, level)
    c = zlib.compressobj(level, zlib.DEFLATED, -zlib.MAX_WBITS)
    return c.compress(raw) + c.flush()


def parse(data: bytes) -> Logo:
    if len(data) < HDR + 8:
        raise LogoError("file is too small to be a logo partition")
    magic, hdr_size = struct.unpack_from("<II", data, 0)
    if magic != MAGIC:
        raise LogoError(f"no MediaTek header (magic {magic:#010x}, expected {MAGIC:#010x}): is this really the logo partition?")
    name = data[8:40].split(b"\0", 1)[0]
    if name.upper() != b"LOGO":
        raise LogoError(f"MediaTek header names partition {name!r}, not LOGO: refusing")
    count, total = struct.unpack_from("<II", data, HDR)
    if not 1 <= count <= 4096:
        raise LogoError(f"implausible picture count {count}")
    table_end = 8 + 4 * count
    if total < table_end or HDR + total > len(data):
        raise LogoError(f"total size {total} does not fit the file ({len(data)} bytes)")
    offsets = list(struct.unpack_from(f"<{count}I", data, HDR + 8))
    if offsets[0] != table_end:
        raise LogoError(f"first picture at {offsets[0]}, expected {table_end}: unknown layout")
    if any(b <= a for a, b in zip(offsets, offsets[1:])) or offsets[-1] >= total:
        raise LogoError("picture offsets are not in order: unknown layout")

    logo = Logo(header=data[:HDR], file_len=len(data), total=total, hdr_size=hdr_size)
    ends = offsets[1:] + [total]
    for a, b in zip(offsets, ends):
        blob = data[HDR + a:HDR + b]
        raw, style, tail = _inflate(blob)
        logo.pictures.append(Picture(blob=blob, raw=raw, style=style))

    if hdr_size == total:
        logo.hdr_size_rule = "total"
    elif hdr_size == len(data) - HDR:
        logo.hdr_size_rule = "file"
    else:
        logo.hdr_size_rule = "fixed"
    rest = data[HDR + total:]
    logo.fill = rest[0] if rest and rest.count(rest[0]) == len(rest) else 0
    return logo


def pack(logo: Logo, max_len: int | None = None) -> bytes:
    count = len(logo.pictures)
    off = 8 + 4 * count
    offsets, body = [], bytearray()
    for p in logo.pictures:
        offsets.append(off + len(body))
        body += p.blob
    total = off + len(body)
    hdr = bytearray(logo.header)
    if logo.hdr_size_rule == "total":
        struct.pack_into("<I", hdr, 4, total)
    out = bytearray(hdr)
    out += struct.pack(f"<II{count}I", count, total, *offsets)
    out += body
    limit = max_len if max_len is not None else logo.file_len
    if len(out) > limit:
        raise LogoError(f"new image is {len(out)} bytes, more than the {limit} available: refusing")
    # a dump of the partition is padded to the partition's size; keep it that size
    if logo.file_len > len(out) and max_len is None:
        out += bytes([logo.fill]) * (logo.file_len - len(out))
    if logo.hdr_size_rule == "file":
        struct.pack_into("<I", out, 4, len(out) - HDR)
    return bytes(out)


# ------------------------------------------------------------------ pixels

FORMATS = {4: "32-bit", 3: "24-bit", 2: "16-bit (RGB565)"}


def bpp_of(pic: Picture, size) -> int | None:
    w, h = size
    n = len(pic.raw)
    for bpp in (4, 2, 3):
        if n == w * h * bpp:
            return bpp
    return None


def alpha_slot(raw: bytes):
    """For 32-bit pictures: (index of the constant byte, its value), i.e. where the
    stock firmware keeps alpha and what it puts there. None if no byte is constant."""
    for slot in (3, 0):
        vals = raw[slot::4]
        if vals and vals.count(vals[0]) == len(vals):
            return slot, vals[0]
    return None


def encode(img, bpp: int, stock_raw: bytes, swap_rb: bool = False) -> bytes:
    """Pillow RGB image -> raw pixels in the same layout as the stock picture."""
    img = img.convert("RGB")
    if bpp == 4:
        slot = alpha_slot(stock_raw) or (3, 0xFF)
        a_idx, a_val = slot
        # MediaTek LK default is B,G,R in the three colour bytes
        order = "RGB" if swap_rb else "BGR"
        r, g, b = img.split()
        chans = {"R": r, "G": g, "B": b}
        from PIL import Image
        alpha = Image.new("L", img.size, a_val)
        planes = [chans[c] for c in order]
        planes.insert(a_idx, alpha)
        return Image.merge("RGBA", planes).tobytes()
    if bpp == 3:
        r, g, b = img.split()
        from PIL import Image
        return Image.merge("RGB", (r, g, b) if swap_rb else (b, g, r)).tobytes()
    if bpp == 2:
        out = bytearray()
        px = img.tobytes()
        for i in range(0, len(px), 3):
            r, g, b = px[i], px[i + 1], px[i + 2]
            if swap_rb:
                r, b = b, r
            v = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)
            out += struct.pack("<H", v)
        return bytes(out)
    raise LogoError(f"unsupported pixel size {bpp}")


def decode(raw: bytes, bpp: int, size, swap_rb: bool = False):
    """Raw stock pixels -> Pillow RGB image (for looking at, not for writing back)."""
    from PIL import Image
    w, h = size
    if bpp == 4:
        slot = alpha_slot(raw)
        a_idx = slot[0] if slot else 3
        im = Image.frombytes("RGBA", (w, h), raw)
        planes = list(im.split())
        planes.pop(a_idx)
        c0, c1, c2 = planes  # B,G,R by default
        return Image.merge("RGB", (c0, c1, c2) if swap_rb else (c2, c1, c0))
    if bpp == 3:
        im = Image.frombytes("RGB", (w, h), raw)
        c0, c1, c2 = im.split()
        return Image.merge("RGB", (c0, c1, c2) if swap_rb else (c2, c1, c0))
    if bpp == 2:
        px = bytearray()
        for (v,) in struct.iter_unpack("<H", raw):
            r, g, b = (v >> 11) << 3, ((v >> 5) & 63) << 2, (v & 31) << 3
            px += bytes((b, g, r) if swap_rb else (r, g, b))
        return Image.frombytes("RGB", (w, h), bytes(px))
    raise LogoError(f"unsupported pixel size {bpp}")


# ------------------------------------------------------------------ art

def render_art(size, rotate: int = 0):
    """The VoidOS boot logo: the mark, VOIDOS and TAB EDITION on the void, laid
    out like the last frame of the boot animation so the two follow on."""
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import voidmark as vm
    from PIL import Image, ImageDraw
    w, h = size
    if rotate in (90, 270):
        w, h = h, w
    ss = 2
    W, H = w * ss, h * ss
    img = Image.new("RGB", (W, H), vm.BG)
    d = ImageDraw.Draw(img)
    unit = min(W, H)
    c = (W / 2, H / 2 - unit * 0.08)
    r = unit * 0.16
    vm.draw_mark(d, c, r)
    wy = c[1] + r * 1.48 + unit * 0.12
    vm.spaced_text(d, W / 2, wy, "VOIDOS", vm.font(vm.FONT_KENNEY, unit * 0.075), vm.WHITE, 0.25)
    vm.spaced_text(d, W / 2, wy + unit * 0.05, "TAB EDITION", vm.font(vm.FONT_KENNEY, unit * 0.024), vm.TEAL, 0.5)
    img = img.resize((w, h), Image.LANCZOS)
    if rotate:
        # rotate the drawing so it reads upright on a panel that LK draws rotated
        img = img.rotate(-rotate, expand=True)
    return img


# ------------------------------------------------------------------ commands

def fullscreen_indices(logo: Logo, size):
    return [i for i, p in enumerate(logo.pictures) if bpp_of(p, size)]


def default_targets(logo: Logo, size):
    """Picture 0 (LK's boot logo) and the last picture (the kernel logo), when full-screen."""
    full = set(fullscreen_indices(logo, size))
    want = sorted({0, len(logo.pictures) - 1})
    return [i for i in want if i in full]


def build(stock: bytes, size=DEFAULT_SIZE, targets=None, rotate=0, swap_rb=False, max_len=None, art=None):
    logo = parse(stock)
    if targets is None:
        targets = default_targets(logo, size)
    if not targets:
        raise LogoError(f"no full-screen {size[0]}x{size[1]} picture to replace: wrong --size, or not this tablet's logo")
    art = art or render_art(size, rotate)
    if art.size != tuple(size):
        raise LogoError(f"art is {art.size}, panel is {size}")
    for i in targets:
        if not 0 <= i < len(logo.pictures):
            raise LogoError(f"there is no picture {i}")
        p = logo.pictures[i]
        bpp = bpp_of(p, size)
        if not bpp:
            raise LogoError(f"picture {i} is {len(p.raw)} bytes, not a full {size[0]}x{size[1]} picture: refusing to replace it")
        raw = encode(art, bpp, p.raw, swap_rb)
        assert len(raw) == len(p.raw)
        logo.pictures[i] = Picture(blob=_deflate(raw, p.style), raw=raw, style=p.style)
    return pack(logo, max_len), targets


def verify(stock: bytes, new: bytes, size=DEFAULT_SIZE, max_len=None):
    """Checks a built image against the stock one. Returns a list of report lines;
    raises LogoError on anything that would make it unsafe to flash."""
    a, b = parse(stock), parse(new)
    lines = []
    if a.header[8:40] != b.header[8:40] or a.header[:4] != b.header[:4]:
        raise LogoError("header magic/name changed")
    if len(a.pictures) != len(b.pictures):
        raise LogoError(f"picture count changed: {len(a.pictures)} -> {len(b.pictures)}")
    limit = max_len if max_len is not None else len(stock)
    if len(new) > limit:
        raise LogoError(f"new image {len(new)} bytes > {limit}")
    changed = []
    for i, (p, q) in enumerate(zip(a.pictures, b.pictures)):
        if len(p.raw) != len(q.raw):
            raise LogoError(f"picture {i} changed size: {len(p.raw)} -> {len(q.raw)} bytes")
        if p.style != q.style:
            raise LogoError(f"picture {i} changed compression: {p.style} -> {q.style}")
        if p.blob != q.blob:
            changed.append(i)
            if not bpp_of(p, size):
                raise LogoError(f"picture {i} changed but is not a full-screen picture")
    lines.append(f"pictures: {len(b.pictures)} (same as stock), all decompress, sizes and compression unchanged")
    lines.append(f"replaced: {changed or 'none'}; every other picture byte-identical to stock")
    lines.append(f"file: {len(new)} bytes (stock {len(stock)}), data {b.total} bytes, header size field {struct.unpack_from('<I', new, 4)[0]}")
    lines.append(f"sha256 stock {hashlib.sha256(stock).hexdigest()}")
    lines.append(f"sha256 new   {hashlib.sha256(new).hexdigest()}")
    return lines


def info(data: bytes, size=DEFAULT_SIZE):
    logo = parse(data)
    out = [f"partition name {logo.name!r}, {len(logo.pictures)} pictures, data {logo.total} bytes, file {logo.file_len} bytes",
           f"header size field {logo.hdr_size} ({logo.hdr_size_rule}), padding byte {logo.fill:#04x}"]
    for i, p in enumerate(logo.pictures):
        bpp = bpp_of(p, size)
        kind = f"FULL SCREEN {size[0]}x{size[1]} {FORMATS[bpp]}" if bpp else "part picture"
        extra = ""
        if bpp == 4:
            s = alpha_slot(p.raw)
            extra = f", constant byte {s[0]} = {s[1]:#04x}" if s else ", no constant byte"
        out.append(f"  #{i:<3} {len(p.blob):>9} -> {len(p.raw):>9} bytes  {p.style:<7} {kind}{extra}")
    out.append(f"would replace by default: {default_targets(logo, size)}")
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--size", default="800x1280", help="panel WIDTHxHEIGHT, natural orientation (default 800x1280, TB-X306F)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("info"); s.add_argument("logo")
    s = sub.add_parser("unpack"); s.add_argument("logo"); s.add_argument("outdir")
    s.add_argument("--swap-rb", action="store_true")
    s = sub.add_parser("build"); s.add_argument("logo"); s.add_argument("out")
    s.add_argument("--rotate", type=int, default=0, choices=(0, 90, 180, 270),
                   help="if the stock boot logo unpacks sideways/upside down, rotate VoidOS the same way")
    s.add_argument("--swap-rb", action="store_true", help="use R,G,B byte order instead of LK's usual B,G,R")
    g = s.add_mutually_exclusive_group()
    g.add_argument("--indices", help="comma-separated picture numbers to replace (default: first and last)")
    g.add_argument("--all-fullscreen", action="store_true", help="also replace the full-screen charging/low-battery pictures")
    s.add_argument("--max-size", type=int, help="partition size in bytes, when the input is not a full partition dump")
    s.add_argument("--preview", help="also save the VoidOS picture as PNG here")
    s = sub.add_parser("verify"); s.add_argument("logo"); s.add_argument("new")
    s.add_argument("--max-size", type=int)
    a = ap.parse_args(argv)
    size = tuple(int(v) for v in a.size.lower().split("x"))
    try:
        stock = Path(a.logo).read_bytes()
        if a.cmd == "info":
            print("\n".join(info(stock, size)))
        elif a.cmd == "unpack":
            logo = parse(stock)
            out = Path(a.outdir); out.mkdir(parents=True, exist_ok=True)
            for i, p in enumerate(logo.pictures):
                (out / f"{i:03d}.raw").write_bytes(p.raw)
                bpp = bpp_of(p, size)
                if bpp:
                    decode(p.raw, bpp, size, a.swap_rb).save(out / f"{i:03d}.png")
            print(f"{len(logo.pictures)} pictures in {out}; full-screen ones also as PNG: {fullscreen_indices(logo, size)}")
        elif a.cmd == "build":
            if a.all_fullscreen:
                targets = fullscreen_indices(parse(stock), size)
            elif a.indices:
                targets = [int(x) for x in a.indices.split(",")]
            else:
                targets = None
            art = render_art(size, a.rotate)
            if a.preview:
                art.save(a.preview)
            new, done = build(stock, size, targets, a.rotate, a.swap_rb, a.max_size, art)
            if a.max_size is None and len(stock) < 1 << 20:
                print("warning: the input is small, so it is probably not a full partition dump; "
                      "pass --max-size with the partition's size (blockdev --getsize64)", file=sys.stderr)
            Path(a.out).write_bytes(new)
            print(f"wrote {a.out}: replaced pictures {done}")
            print("\n".join(verify(stock, new, size, a.max_size)))
        elif a.cmd == "verify":
            print("\n".join(verify(stock, Path(a.new).read_bytes(), size, a.max_size)))
            print("OK")
    except LogoError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())

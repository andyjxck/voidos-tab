#!/usr/bin/env python3
"""
The VoidOS power-on and power-off pictures, drawn so that every stage hands
over to the next on the same frame:

  power on   still logo (logo partition, picture 0 and 38: tools/mtk_logo.py)
             -> boot animation part0 starts on that exact frame and the
                "void" wordmark balloons up (swells from the middle out,
                overshoots, settles)
             -> part1 loops: the balloon breathes while Android starts
             -> part2 (played to the end once Android is ready): it lets
                its air out and lands back on the still frame
             -> VoidOS (ui/Boot.kt) opens on that same still frame, waits for
                the boot animation to be gone, then balloons open into the hub
  power off  shutdown animation: a small last breath in, then the wordmark
             caves in on itself, swirling into the middle like it is being
             pulled into a void, ends as a teal point that blinks out, then
             black.

Everything is drawn at the panel's native size (800x1280 portrait on the
TB-X306F: the stock logo and the stock boot animation are both stored that
way, and the stock boot animation's last frame is pixel for pixel the stock
logo, so the two share one coordinate system).

The background is VoidOS's own (ui/Theme.kt V.Bg = #0B0B0D) so the app's
first frame matches too.

    python3 tools/void_balloon.py            # writes root/bootanimation.zip,
                                             # root/shutdownanimation.zip and
                                             # root/preview/void_still_800x1280.png
"""
import argparse
import io
import math
import sys
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import voidmark as vm  # noqa: E402

FPS = 30
SS = 3                    # supersampling
BG = (11, 11, 13)         # ui/Theme.kt V.Bg 0xFF0B0B0D
SEG = 0.7                 # longest polygon edge before warping, in logo units

# boot timing (frames at 30 fps)
INTRO = 42                # part0: hold 6 frames on the still logo, then balloon up
HOLD = 6
LOOP = 60                 # part1: one breath, 2 s (1.3.0 looped 72 frames on the tablet without trouble)
OUTRO = 18                # part2: let the air out back onto the still frame
# shutdown timing
SHUT_HOLD = 4
SHUT_BREATH = 9           # a small last breath in
SHUT_COLLAPSE = 33        # caving in
SHUT_SPARK = 12           # the point blinks out


def clamp(x, a=0.0, b=1.0):
    return max(a, min(b, x))


def ease_io(t):
    t = clamp(t)
    return t * t * (3 - 2 * t)


def ease_in(t):
    t = clamp(t)
    return t * t * t


def ease_out(t):
    t = clamp(t)
    return 1 - (1 - t) ** 3


# ------------------------------------------------------------------ geometry

_rings = None


def rings():
    """The wordmark's shapes with long edges cut into short ones, so a warp
    bends them instead of only moving their corners."""
    global _rings
    if _rings is None:
        geo = vm.geometry()
        out = []
        for sh in geo["shapes"]:
            rs = []
            for r in sh["rings"]:
                pts = []
                n = len(r)
                for i in range(n):
                    (x0, y0), (x1, y1) = r[i], r[(i + 1) % n]
                    k = max(1, int(math.ceil(math.hypot(x1 - x0, y1 - y0) / SEG)))
                    for j in range(k):
                        pts.append((x0 + (x1 - x0) * j / k, y0 + (y1 - y0) * j / k))
                rs.append(pts)
            col = vm.WHITE if sh["c"] == -1 else vm.LAP[sh["c"]]
            out.append((rs, col))
        _rings = out
    return _rings


def layout(W, H):
    """Same place and size as the app (tools/voidmark.py lockup_geometry,
    ui/Boot.kt): centre c, scale s px per unit, logo origin o."""
    unit, c, ww, wh, _ = vm.lockup_geometry(W, H)
    wW, wH = vm.word_size()
    s = ww / wW
    o = (c[0] - wW * s / 2, c[1] - wH * s / 2)
    return c, s, o, ww / 2, wh / 2


def balloon_warp(b, c, ax, ay):
    """b 0..1(+): how full of air. The middle swells most, like a balloon."""
    a = 0.20 * b
    sx = 1 + 0.025 * b
    sy = 1 + 0.06 * b

    def f(x, y):
        dx, dy = x - c[0], y - c[1]
        r2 = (dx / ax) ** 2 + (dy / ay) ** 2
        g = 1 + a / (1 + 1.6 * r2)
        return c[0] + dx * g * sx, c[1] + dy * g * sy
    return f


def collapse_warp(k, c, ax, ay):
    """k 0..1: caving in. The middle goes first and the ends follow, turning
    as they are pulled in, until it is all one point at c."""
    D = 0.45

    def f(x, y):
        dx, dy = x - c[0], y - c[1]
        u, v = dx / ax, dy / ay
        rn = min(1.0, math.hypot(u, v) / 1.15)
        e = clamp((k * (1 + D) - D * rn) / 1.0)
        g = 1 - ease_io(e) ** 0.85
        g = g * (1 - 0.35 * ease_in(k))          # and the whole thing shrinks
        th = 0.7 * ease_io(e) * (1 - 0.5 * rn)   # swirl, in the word's own proportions
        cu, su = math.cos(th), math.sin(th)
        u2, v2 = (u * cu - v * su) * g, (u * su + v * cu) * g
        return c[0] + u2 * ax, c[1] + v2 * ay
    return f


def draw_word(img, W, H, warp=None, glow=0.0, fade=1.0):
    d = ImageDraw.Draw(img)
    c, s, o, ax, ay = layout(W, H)
    if glow > 0:
        # a soft teal light inside the balloon
        for i in range(6, 0, -1):
            rx, ry = ax * (0.35 + 0.13 * i), ay * (0.55 + 0.22 * i)
            d.ellipse([c[0] - rx, c[1] - ry, c[0] + rx, c[1] + ry], fill=vm.mix(vm.TEAL, BG, 0.022 * glow * (7 - i) / 6 * fade))
    w = warp(c, ax, ay) if warp else None
    # shapes go on their own layer with see-through holes, so the glow shows
    # through the holes exactly
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    for rs, col in rings():
        polys = []
        for r in rs:
            pts = [(o[0] + x * s, o[1] + y * s) for x, y in r]
            if w:
                pts = [w(x, y) for x, y in pts]
            polys.append(pts)
        if len(polys[0]) >= 3:
            ld.polygon(polys[0], fill=tuple(vm.mix(col, BG, fade)) + (255,))
        for hole in polys[1:]:
            if len(hole) >= 3:
                ld.polygon(hole, fill=(0, 0, 0, 0))
    img.paste(layer, (0, 0), layer)
    return c


def render(w, h, warp=None, glow=0.0, fade=1.0, spark=None):
    W, H = w * SS, h * SS
    img = Image.new("RGB", (W, H), BG)
    if fade > 0:
        draw_word(img, W, H, warp, glow, fade)
    if spark is not None:
        c, _, _, ax, ay = layout(W, H)
        r, a = spark
        d = ImageDraw.Draw(img)
        for i in range(4, 0, -1):
            rr = r * (1 + 1.4 * i)
            d.ellipse([c[0] - rr, c[1] - rr, c[0] + rr, c[1] + rr], fill=vm.mix(vm.TEAL, BG, a * 0.07 * (5 - i)))
        if r > 0:
            d.ellipse([c[0] - r, c[1] - r, c[0] + r, c[1] + r], fill=vm.mix((235, 255, 255), BG, a))
    return img.resize((w, h), Image.LANCZOS)


# ------------------------------------------------------------------ timelines

def air_intro(f):
    """Air in the balloon at intro frame f: 0 on the still frame, up past 1
    with a little wobble, settling on 1 (where the loop starts)."""
    if f < HOLD:
        return 0.0
    t = (f - HOLD) / (INTRO - HOLD)          # 0..1 over the swell
    # damped spring onto 1, starting from 0 with zero speed
    x = t * 2.6
    return 1 - math.exp(-2.0 * x) * (math.cos(3.6 * x) + (2.0 / 3.6) * math.sin(3.6 * x))


def air_loop(f):
    """One breath: 1 -> 0.55 -> 1, smooth at both ends so the loop is seamless."""
    return 1 - 0.45 * (1 - math.cos(2 * math.pi * f / LOOP)) / 2


def air_outro(f):
    """1 -> 0, ending exactly on the still frame (last frame is air 0)."""
    return 1 - ease_io((f + 1) / OUTRO)


def boot_frames(w, h):
    """Yields (part, frame) in play order, one at a time (120 full frames do
    not all fit in memory at once)."""
    for f in range(INTRO):
        b = air_intro(f)
        yield "part0", render(w, h, (lambda c, ax, ay, b=b: balloon_warp(b, c, ax, ay)) if b else None, glow=clamp(b))
    for f in range(LOOP):
        b = air_loop(f)
        yield "part1", render(w, h, lambda c, ax, ay, b=b: balloon_warp(b, c, ax, ay), glow=clamp(b))
    for f in range(OUTRO):
        b = air_outro(f)
        yield "part2", render(w, h, (lambda c, ax, ay, b=b: balloon_warp(b, c, ax, ay)) if b > 0 else None, glow=clamp(b))


def shutdown_frames(w, h):
    for f in range(SHUT_HOLD):
        yield "part0", render(w, h)
    for f in range(SHUT_BREATH):
        b = 0.45 * math.sin(math.pi * (f + 1) / (SHUT_BREATH + 1) * 0.5)  # 0 -> 0.45, still rising
        yield "part0", render(w, h, lambda c, ax, ay, b=b: balloon_warp(b, c, ax, ay), glow=b)
    b0 = 0.45
    for f in range(SHUT_COLLAPSE):
        k = (f + 1) / SHUT_COLLAPSE
        b = b0 * (1 - ease_out(k / 0.35))  # the breath lets go as it starts caving in

        def warp(c, ax, ay, k=k, b=b):
            bw = balloon_warp(b, c, ax, ay)
            cw = collapse_warp(k, c, ax, ay)
            return lambda x, y: cw(*bw(x, y))
        sp = None
        if k > 0.75:  # the point lights up as the last of the word goes in
            sp = (3 * SS * ease_io((k - 0.75) / 0.25), ease_io((k - 0.75) / 0.25))
        yield "part0", render(w, h, warp, glow=b, fade=1.0, spark=sp)
    for f in range(SHUT_SPARK):
        t = (f + 1) / SHUT_SPARK
        r = 3 * SS * (1 + 0.6 * math.sin(math.pi * min(1, t / 0.4))) * (1 - ease_in(t))
        yield "part0", render(w, h, fade=0, spark=(r, 1 - ease_in(t)))
    yield "part1", render(w, h, fade=0)


def png(img):
    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def write_zip(path, w, h, frames, desc_lines, sheet_path):
    """Streams the frames into a stored (uncompressed) zip, as Android's
    bootanimation needs, and keeps small copies for a contact sheet (one row
    per part, up to 8 evenly spaced frames)."""
    counts, thumbs = {}, {}
    tw, th = w // 4, h // 4
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as z:
        z.writestr(zipfile.ZipInfo("desc.txt"), f"{w} {h} {FPS}\n" + "".join(l + "\n" for l in desc_lines))
        for name, im in frames:
            i = counts.get(name, 0)
            counts[name] = i + 1
            z.writestr(zipfile.ZipInfo(f"{name}/{i:05d}.png"), png(im))
            thumbs.setdefault(name, []).append(im.resize((tw, th)))
    rows = [v[:: max(1, len(v) // 8)][:8] for v in thumbs.values()]
    sheet = Image.new("RGB", (tw * 8, th * len(rows)), (40, 40, 40))
    for ri, row in enumerate(rows):
        for ci, im in enumerate(row):
            sheet.paste(im, (ci * tw, ri * th))
    sheet.save(sheet_path)
    return counts


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--size", default="800x1280")
    ap.add_argument("--out", default=str(vm.ROOT / "root"))
    a = ap.parse_args()
    w, h = map(int, a.size.split("x"))
    out = Path(a.out)
    (out / "preview").mkdir(parents=True, exist_ok=True)

    still = render(w, h)
    still.save(out / f"preview/void_still_{w}x{h}.png")

    # c = play to the end even once Android is ready, so the hand-over to
    # VoidOS always lands on the still frame: part1 loops (count 0) and stops
    # at the end of a breath once Android is ready; part2 then plays once.
    print("boot", write_zip(out / "bootanimation.zip", w, h, boot_frames(w, h),
                            ["c 1 0 part0", "c 0 0 part1", "c 1 0 part2"], out / "preview/boot_sheet.png"))
    # same structure as the stock Lenovo shutdown animation: part0 once, then
    # part1 (black) until the tablet is off
    print("shutdown", write_zip(out / "shutdownanimation.zip", w, h, shutdown_frames(w, h),
                                ["p 1 0 part0", "p 0 0 part1"], out / "preview/shutdown_sheet.png"))
    for p in ("bootanimation.zip", "shutdownanimation.zip"):
        print(p, f"{(out / p).stat().st_size / 1e6:.2f} MB")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Tests for mtk_logo.py against synthetic logo partitions built the way MediaTek
lays them out (no real TB-X306F dump is in the repo). Run: python3 tools/test_mtk_logo.py"""
import struct
import sys
import unittest
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mtk_logo as ml  # noqa: E402
from PIL import Image, ImageDraw  # noqa: E402

W, H = ml.DEFAULT_SIZE
PART = 8 * 1024 * 1024  # an 8 MiB partition dump


def fake_lenovo(bpp=4, alpha=0xFF, alpha_slot=3):
    """A stand-in for the stock boot logo: white bars on black, in LK's byte layout."""
    img = Image.new("RGB", (W, H), (0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([200, 600, 600, 680], fill=(230, 30, 40))  # a red bar (the 'lenovo' box)
    d.rectangle([300, 1150, 500, 1170], fill=(255, 255, 255))
    if bpp == 4:
        stock_like = bytes([alpha]) * 4  # tells encode() where alpha lives
        stock_like = bytearray(W * H * 4)
        for i in range(alpha_slot, len(stock_like), 4):
            stock_like[i] = alpha
        return ml.encode(img, 4, bytes(stock_like))
    return ml.encode(img, bpp, b"")


def make_logo(pictures, style="zlib", pad_to=PART, hdr_rule="total", fill=0x00):
    blobs = [ml._deflate(p, style) for p in pictures]
    count = len(blobs)
    off = 8 + 4 * count
    offsets, body = [], b""
    for b in blobs:
        offsets.append(off + len(body))
        body += b
    total = off + len(body)
    hdr = bytearray(b"\xff" * 512)
    struct.pack_into("<II", hdr, 0, ml.MAGIC, total)
    hdr[8:40] = b"logo".ljust(32, b"\0")
    data = bytearray(hdr) + struct.pack(f"<II{count}I", count, total, *offsets) + body
    if pad_to:
        data += bytes([fill]) * (pad_to - len(data))
    if hdr_rule == "file":
        struct.pack_into("<I", data, 4, len(data) - 512)
    elif hdr_rule == "fixed":
        struct.pack_into("<I", data, 4, 12345)
    return bytes(data)


def battery_pics(n=6, bpp=4):
    # small part-screen pictures, like the charging percentage digits
    return [bytes([i * 10 % 256]) * (60 * 90 * bpp) for i in range(n)]


def stock_set(bpp=4, **kw):
    full = fake_lenovo(bpp, **kw)
    return [full] + battery_pics(bpp=bpp) + [ml.encode(Image.new("RGB", (W, H), (0, 0, 0)), bpp, full), full]


class Parse(unittest.TestCase):
    def test_roundtrip_is_byte_identical(self):
        for style in ("zlib", "deflate"):
            for rule in ("total", "file", "fixed"):
                for fill in (0x00, 0xFF):
                    stock = make_logo(stock_set(), style=style, hdr_rule=rule, fill=fill)
                    self.assertEqual(ml.pack(ml.parse(stock)), stock, (style, rule, fill))

    def test_reads_layout(self):
        stock = make_logo(stock_set())
        logo = ml.parse(stock)
        self.assertEqual(len(logo.pictures), 9)
        self.assertEqual(logo.name, "logo")
        self.assertEqual(ml.fullscreen_indices(logo, (W, H)), [0, 7, 8])
        self.assertEqual(ml.default_targets(logo, (W, H)), [0, 8])
        self.assertTrue(any("FULL SCREEN" in l for l in ml.info(stock)))

    def test_rejects_wrong_partitions(self):
        stock = bytearray(make_logo(stock_set()))
        bad = bytearray(stock); bad[0] ^= 1
        with self.assertRaises(ml.LogoError):
            ml.parse(bytes(bad))
        bad = bytearray(stock); bad[8:12] = b"boot"
        with self.assertRaises(ml.LogoError):
            ml.parse(bytes(bad))
        bad = bytearray(stock); struct.pack_into("<I", bad, 512 + 4, len(stock))  # total past the end
        with self.assertRaises(ml.LogoError):
            ml.parse(bytes(bad))
        with self.assertRaises(ml.LogoError):
            ml.parse(b"\0" * 100)


class Build(unittest.TestCase):
    def check(self, stock, bpp, **kw):
        new, done = ml.build(stock, **kw)
        lines = ml.verify(stock, new, max_len=kw.get("max_len"))
        a, b = ml.parse(stock), ml.parse(new)
        for i, (p, q) in enumerate(zip(a.pictures, b.pictures)):
            if i in done:
                self.assertNotEqual(p.raw, q.raw)
                self.assertEqual(len(p.raw), len(q.raw))
            else:
                self.assertEqual(p.blob, q.blob)
        return new, done, b, lines

    def test_32bit_default_targets_and_size(self):
        stock = make_logo(stock_set())
        new, done, b, lines = self.check(stock, 4)
        self.assertEqual(done, [0, 8])
        self.assertEqual(len(new), len(stock))  # still exactly the partition's size
        # alpha kept where the stock had it, colours in B,G,R: the void background is #05070B-ish
        px = b.pictures[0].raw[:4]
        self.assertEqual(px[3], 0xFF)
        img = ml.decode(b.pictures[0].raw, 4, (W, H))
        r, g, bl = img.getpixel((5, 5))
        self.assertTrue(bl >= r, (r, g, bl))  # the void is blue-black, so B >= R once decoded right
        # the mark is in the middle: something bright and coloured there
        self.assertGreater(max(sum(img.getpixel((x, H // 2 - 100))) for x in range(0, W, 4)), 200)

    def test_alpha_zero_firmware(self):
        stock = make_logo(stock_set(alpha=0x00))
        new, done, b, _ = self.check(stock, 4)
        self.assertEqual(set(b.pictures[0].raw[3::4]), {0})

    def test_rgb565_and_raw_deflate(self):
        stock = make_logo(stock_set(bpp=2), style="deflate")
        new, done, b, _ = self.check(stock, 2)
        self.assertEqual(b.pictures[0].style, "deflate")
        self.assertEqual(len(b.pictures[0].raw), W * H * 2)

    def test_all_fullscreen(self):
        stock = make_logo(stock_set())
        logo = ml.parse(stock)
        new, done, b, _ = self.check(stock, 4, targets=ml.fullscreen_indices(logo, (W, H)))
        self.assertEqual(done, [0, 7, 8])

    def test_refuses_part_pictures_and_overflow(self):
        stock = make_logo(stock_set())
        with self.assertRaises(ml.LogoError):
            ml.build(stock, targets=[1])  # a battery digit, not full screen
        with self.assertRaises(ml.LogoError):
            ml.build(stock, targets=[99])
        unpadded = make_logo(stock_set(), pad_to=0)
        with self.assertRaises(ml.LogoError):
            ml.build(unpadded, max_len=600)  # partition too small
        with self.assertRaises(ml.LogoError):
            ml.build(stock, size=(720, 1280))  # wrong panel: nothing full-screen

    def test_unpadded_with_max_size(self):
        stock = make_logo(stock_set(), pad_to=0)
        new, done = ml.build(stock, max_len=PART)
        self.assertLessEqual(len(new), PART)
        ml.verify(stock, new, max_len=PART)

    def test_verify_catches_tampering(self):
        stock = make_logo(stock_set())
        new, _ = ml.build(stock)
        logo = ml.parse(new)
        logo.pictures[1] = ml.Picture(blob=ml._deflate(b"\0" * 10, "zlib"), raw=b"\0" * 10, style="zlib")
        with self.assertRaises(ml.LogoError):
            ml.verify(stock, ml.pack(logo))

    def test_rotate_keeps_panel_size(self):
        for rot in (0, 90, 180, 270):
            self.assertEqual(ml.render_art((W, H), rot).size, (W, H))

    def test_cli(self):
        import tempfile
        with tempfile.TemporaryDirectory() as t:
            t = Path(t)
            (t / "stock.bin").write_bytes(make_logo(stock_set()))
            self.assertEqual(ml.main(["info", str(t / "stock.bin")]), 0)
            self.assertEqual(ml.main(["unpack", str(t / "stock.bin"), str(t / "d")]), 0)
            self.assertTrue((t / "d/000.png").exists())
            self.assertEqual(ml.main(["build", str(t / "stock.bin"), str(t / "new.bin"), "--rotate", "180"]), 0)
            self.assertEqual(ml.main(["verify", str(t / "stock.bin"), str(t / "new.bin")]), 0)
            (t / "boot.img").write_bytes(b"ANDROID!" + b"\0" * 4096)
            self.assertEqual(ml.main(["info", str(t / "boot.img")]), 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)

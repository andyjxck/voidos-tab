#!/usr/bin/env python3
"""Checks the boot and shutdown animations that tools/void_balloon.py wrote to
root/ (run it first):  python3 tools/test_void_balloon.py

What matters for a hand-over with no cut: the boot animation starts and ends
on exactly the still picture that the power-on logo (root/logo-voidos.bin,
pictures 0 and 38) shows, its breath loop has no seam, and no frame jumps."""
import io
import struct
import sys
import unittest
import zipfile
import zlib
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import void_balloon as vb  # noqa: E402
import voidmark as vm  # noqa: E402

ROOT = vm.ROOT / "root"
STILL = ROOT / "preview/void_still_800x1280.png"


def frames(z):
    parts = {}
    for n in sorted(n for n in z.namelist() if n.endswith(".png")):
        parts.setdefault(n.split("/")[0], []).append(n)
    return parts


def px(z, n):
    return np.asarray(Image.open(io.BytesIO(z.read(n))).convert("RGB")).astype(int)


class Boot(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.z = zipfile.ZipFile(ROOT / "bootanimation.zip")
        cls.p = frames(cls.z)
        cls.still = np.asarray(Image.open(STILL).convert("RGB")).astype(int)

    def test_desc_and_storage(self):
        self.assertEqual(self.z.read("desc.txt").decode(),
                         "800 1280 30\nc 1 0 part0\nc 0 0 part1\nc 1 0 part2\n")
        self.assertTrue(all(i.compress_type == zipfile.ZIP_STORED for i in self.z.infolist()))
        self.assertEqual({k: len(v) for k, v in self.p.items()},
                         {"part0": vb.INTRO, "part1": vb.LOOP, "part2": vb.OUTRO})

    def test_starts_and_ends_on_the_still_logo(self):
        self.assertEqual(np.abs(px(self.z, self.p["part0"][0]) - self.still).max(), 0)
        self.assertEqual(np.abs(px(self.z, self.p["part2"][-1]) - self.still).max(), 0)

    def test_no_seams(self):
        z, p = self.z, self.p
        step = lambda a, b: np.abs(px(z, a) - px(z, b)).mean()
        typical = step(p["part1"][10], p["part1"][11])
        self.assertLessEqual(step(p["part1"][-1], p["part1"][0]), typical * 1.5)   # loop
        self.assertLessEqual(step(p["part0"][-1], p["part1"][0]), typical * 1.5)   # intro -> loop
        self.assertLessEqual(step(p["part1"][-1], p["part2"][0]), 1.0)             # loop -> outro

    def test_still_logo_is_the_app_layout(self):
        # the still picture is the wordmark where VoidOS draws it
        bg = np.array(vb.BG)
        self.assertTrue((self.still[0, 0] == bg).all())
        m = np.abs(self.still - bg).sum(2) > 12
        ys, xs = np.nonzero(m)
        _, c, ww, wh, _ = vm.lockup_geometry(800, 1280)
        self.assertAlmostEqual(xs.max() - xs.min(), ww, delta=6)
        self.assertAlmostEqual((ys.min() + ys.max()) / 2, c[1], delta=6)


class Shutdown(unittest.TestCase):
    def test_collapses_to_black(self):
        z = zipfile.ZipFile(ROOT / "shutdownanimation.zip")
        self.assertEqual(z.read("desc.txt").decode(), "800 1280 30\np 1 0 part0\np 0 0 part1\n")
        self.assertTrue(all(i.compress_type == zipfile.ZIP_STORED for i in z.infolist()))
        p = frames(z)
        still = np.asarray(Image.open(STILL).convert("RGB")).astype(int)
        self.assertEqual(np.abs(px(z, p["part0"][0]) - still).max(), 0)
        bg = np.array(vb.BG)
        ink = [int((np.abs(px(z, n) - bg).sum(2) > 40).sum()) for n in p["part0"]]
        k0 = vb.SHUT_HOLD + vb.SHUT_BREATH
        # caving in: much less ink half way through the collapse, none at the end
        self.assertLess(ink[k0 + vb.SHUT_COLLAPSE // 2], ink[0] * 0.6)
        self.assertEqual(ink[-1], 0)
        self.assertEqual(np.abs(px(z, p["part1"][0]) - bg).max(), 0)


class Logo(unittest.TestCase):
    def test_logo_pictures_are_the_still_frame(self):
        f = ROOT / "logo-voidos.bin"
        if not f.exists():
            self.skipTest("no root/logo-voidos.bin")
        d = f.read_bytes()
        n, total = struct.unpack_from("<II", d, 512)
        offs = list(struct.unpack_from(f"<{n}I", d, 520)) + [total]
        self.assertEqual(len(d), 512 + total)
        self.assertLessEqual(len(d), 13631488)  # the TB-X306F logo partition
        still = np.asarray(Image.open(STILL).convert("RGB"))
        for i in (0, 38):
            raw = zlib.decompress(d[512 + offs[i]:512 + offs[i + 1]])
            a = np.frombuffer(raw, np.uint8).reshape(1280, 800, 4)
            self.assertTrue((a[:, :, [2, 1, 0]] == still).all())


if __name__ == "__main__":
    unittest.main()

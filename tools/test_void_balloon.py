#!/usr/bin/env python3
"""Checks the boot and shutdown animations that tools/void_balloon.py wrote to
root/ (run it first):  python3 tools/test_void_balloon.py

What matters for a hand-over with no cut: the boot animation starts and ends
on exactly the still picture that the power-on logo (root/logo-voidos.bin,
pictures 0 and 38) shows, its bouncing-dot loop has no seam, and no frame jumps.
The landscape versions (root/orient/90, root/orient/270) start on the same
portrait still and end on the landscape still that VoidOS draws."""
import io
import os
import shutil
import struct
import subprocess
import sys
import tempfile
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
    """A whole panel frame: the trimmed picture put back where trim.txt says,
    on the part's background colour, as Android's bootanimation draws it."""
    part, name = n.split("/")
    trims = z.read(f"{part}/trim.txt").decode().split()
    idx = sorted(m for m in z.namelist() if m.startswith(part + "/") and m.endswith(".png")).index(n)
    wh, x, y = trims[idx].split("+")
    w, h = map(int, wh.split("x"))
    crop = np.asarray(Image.open(io.BytesIO(z.read(n))).convert("RGB")).astype(int)
    assert crop.shape[:2] == (h, w)
    full = np.zeros((1280, 800, 3), int) + np.array(vb.BG)
    full[int(y):int(y) + h, int(x):int(x) + w] = crop
    return full


def seen(a, rot):
    """A panel frame as the person holding the tablet at rotation rot sees it."""
    return np.rot90(a, 1 if rot == 90 else 3 if rot == 270 else 0)


DESC_BOOT = "800 1280 30\nc 1 0 part0 #0b0b0d\nc 0 0 part1 #0b0b0d\nc 1 0 part2 #0b0b0d\n"
DESC_SHUT = "800 1280 30\np 1 0 part0 #0b0b0d\np 0 0 part1 #0b0b0d\n"


class Boot(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.z = zipfile.ZipFile(ROOT / "bootanimation.zip")
        cls.p = frames(cls.z)
        cls.still = np.asarray(Image.open(STILL).convert("RGB")).astype(int)

    def test_desc_and_storage(self):
        self.assertEqual(self.z.read("desc.txt").decode(), DESC_BOOT)
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
        self.assertLessEqual(step(p["part1"][-1], p["part2"][0]), typical * 1.5)   # loop -> drain
        steps = [step(a, b) for a, b in zip(p["part0"], p["part0"][1:])]
        self.assertLess(max(steps), 8.0)                                            # no jump in the intro

    def test_dot_bounces_between_the_lines(self):
        # the original loading animation: the teal dot of the i swings from
        # the purple line to the green one and back, and merges into each
        def dot_x(a):
            band = a[400:480]                           # the row of the bar and the dot
            teal = (band[..., 1] > 150) & (band[..., 2] > 150) & (band[..., 0] < 160)
            xs = np.nonzero(teal)[1]
            return xs.mean(), len(xs)
        xs = [dot_x(px(self.z, n))[0] for n in self.p["part1"]]
        rest = xs[0]                                    # frame 0: passing its own place
        self.assertGreater(max(xs) - rest, 20)          # right, into the green line
        self.assertGreater(rest - min(xs), 20)          # left, into the purple line
        self.assertGreater(np.argmin(xs), np.argmax(xs))  # right first, then left, as in 1.2.0
        # the plain word round it is the still word, ballooned: purple and
        # green still there in every loop frame
        a = px(self.z, self.p["part1"][30])
        purple = ((a[..., 2] > a[..., 1] + 60) & (a[..., 0] > 90)).sum()
        green = ((a[..., 1] > a[..., 2] + 40) & (a[..., 1] > a[..., 0] + 60)).sum()
        self.assertGreater(purple, 300)
        self.assertGreater(green, 300)

    def test_dot_is_the_original_loading_animation(self):
        # without the balloon, the loop is the 1.2.0/1.3.0 loading animation
        # (tools/logo_loading_anim.py) frame for frame
        import logo_loading_anim as lla
        self.assertEqual(vb.LOOP, round(lla.PERIOD * vb.FPS))
        flat = vb.DotLoop(800, 1280, air=0.0)
        a = np.asarray(flat.frame(0, 0.0)).astype(int)
        diff = np.abs(a - self.still).sum(2)
        self.assertLess(diff.mean(), 2.0)               # at rest it is the still word

    def test_still_logo_is_the_app_layout(self):
        # the still picture is the wordmark where VoidOS draws it
        bg = np.array(vb.BG)
        self.assertTrue((self.still[0, 0] == bg).all())
        m = np.abs(self.still - bg).sum(2) > 12
        ys, xs = np.nonzero(m)
        _, c, ww, wh, _ = vm.lockup_geometry(800, 1280)
        self.assertAlmostEqual(xs.max() - xs.min(), ww, delta=6)
        self.assertAlmostEqual((ys.min() + ys.max()) / 2, c[1], delta=6)


def blobs_in(m):
    m = m.copy()
    n = 0
    H, W = m.shape
    for y in range(H):
        for x in range(W):
            if m[y, x]:
                n += 1
                q = [(y, x)]
                m[y, x] = False
                while q:
                    a, b = q.pop()
                    for yy, xx in ((a + 1, b), (a - 1, b), (a, b + 1), (a, b - 1)):
                        if 0 <= yy < H and 0 <= xx < W and m[yy, xx]:
                            m[yy, xx] = False
                            q.append((yy, xx))
    return n


class Landscape(unittest.TestCase):
    """The versions for the tablet held landscape."""

    def test_each_rotation(self):
        still_p = np.asarray(Image.open(STILL).convert("RGB")).astype(int)
        still_l = np.asarray(vb.render(1280, 800)).astype(int)
        for rot in (90, 270):
            z = zipfile.ZipFile(ROOT / f"orient/{rot}/bootanimation.zip")
            p = frames(z)
            self.assertEqual(z.read("desc.txt").decode(), DESC_BOOT)
            # starts on the portrait power-on logo, pixel for pixel
            self.assertEqual(np.abs(px(z, p["part0"][0]) - still_p).max(), 0)
            # ends on the landscape still, upright for the person
            self.assertEqual(np.abs(seen(px(z, p["part2"][-1]), rot) - still_l).max(), 0)
            step = lambda a, b: np.abs(px(z, a) - px(z, b)).mean()
            typical = step(p["part1"][10], p["part1"][11])
            self.assertLessEqual(step(p["part1"][-1], p["part1"][0]), typical * 1.5)
            steps = [step(a, b) for a, b in zip(p["part0"], p["part0"][1:])]
            self.assertLess(max(steps), 8.0, f"rotation {rot}: a jump while turning")
            s = zipfile.ZipFile(ROOT / f"orient/{rot}/shutdownanimation.zip")
            self.assertEqual(s.read("desc.txt").decode(), DESC_SHUT)
            sp = frames(s)
            self.assertEqual(np.abs(seen(px(s, sp["part0"][0]), rot) - still_l).max(), 0)

    def test_turns_the_right_way(self):
        # Display rotation 90 draws content turned clockwise on the panel
        # (android.view.Display.getRotation), so on the panel the word's
        # end, the "d", is at the bottom for 90 and at the top for 270
        bg = np.array(vb.BG)
        for rot, top in ((90, False), (270, True)):
            z = zipfile.ZipFile(ROOT / f"orient/{rot}/bootanimation.zip")
            a = px(z, frames(z)["part2"][-1])
            ink = np.abs(a - bg).sum(2) > 40
            ys, xs = np.nonzero(ink)
            # as seen, more of the word's ink lies right of its middle (the
            # tall d) than left of it (the v)
            mid = (ys.min() + ys.max()) / 2
            upper = int((ys < mid).sum()); lower = int((ys > mid).sum())
            self.assertEqual(upper > lower, top, (rot, upper, lower))


class Orient(unittest.TestCase):
    """orient.sh, the script VoidOS runs as root to swap the versions in."""

    def setUp(self):
        import magisk_module as mm
        self.d = Path(tempfile.mkdtemp())
        m = self.d / "voidos_boot"
        for sub in ("system/media", "system/product/media", "orient/0", "orient/90", "orient/270"):
            (m / sub).mkdir(parents=True)
        for rot in ("0", "90", "270"):
            for n in ("bootanimation.zip", "shutdownanimation.zip"):
                (m / "orient" / rot / n).write_text(f"{n}-{rot}")
        for t in ("system/media/bootanimation.zip", "system/product/media/bootanimation.zip"):
            (m / t).write_text("bootanimation.zip-0")
        (m / "system/media/shutdownanimation.zip").write_text("shutdownanimation.zip-0")
        (m / "orient/boot.now").write_text("0\n")
        (m / "orient/shutdown.now").write_text("0\n")
        (m / "orient.sh").write_text(mm.ORIENT_SH)
        self.m = m
        self.bin = self.d / "bin"
        self.bin.mkdir()
        self.setprop("stopped")

    def tearDown(self):
        shutil.rmtree(self.d)

    def setprop(self, v):
        g = self.bin / "getprop"
        g.write_text(f"#!/bin/sh\necho {v}\n")
        g.chmod(0o755)

    def run_sh(self, *args):
        env = dict(os.environ, PATH=f"{self.bin}:{os.environ['PATH']}")
        return subprocess.run(["sh", str(self.m / "orient.sh"), *args], env=env).returncode

    def read(self, t):
        return (self.m / t).read_text()

    def test_swaps_in_place(self):
        ino = (self.m / "system/media/shutdownanimation.zip").stat().st_ino
        self.assertEqual(self.run_sh("90"), 0)
        self.assertEqual(self.read("system/media/bootanimation.zip"), "bootanimation.zip-90")
        self.assertEqual(self.read("system/product/media/bootanimation.zip"), "bootanimation.zip-90")
        self.assertEqual(self.read("system/media/shutdownanimation.zip"), "shutdownanimation.zip-90")
        # the same file, written into: what Magisk's mount over /system shows
        self.assertEqual((self.m / "system/media/shutdownanimation.zip").stat().st_ino, ino)
        self.assertEqual(self.read("orient/boot.now").strip(), "90")
        self.assertEqual(self.run_sh("270"), 0)
        self.assertEqual(self.read("system/media/shutdownanimation.zip"), "shutdownanimation.zip-270")
        self.assertEqual(self.run_sh("0"), 0)
        self.assertEqual(self.read("system/media/bootanimation.zip"), "bootanimation.zip-0")

    def test_never_under_a_playing_animation(self):
        self.setprop("running")
        self.assertEqual(self.run_sh("90"), 4)
        self.assertEqual(self.read("system/media/bootanimation.zip"), "bootanimation.zip-0")
        # at shutdown: only the boot animation, never the playing shutdown one
        self.assertEqual(self.run_sh("90", "boot"), 0)
        self.assertEqual(self.read("system/media/bootanimation.zip"), "bootanimation.zip-90")
        self.assertEqual(self.read("system/media/shutdownanimation.zip"), "shutdownanimation.zip-0")

    def test_bad_input_and_missing_files(self):
        self.assertEqual(self.run_sh("45"), 0)       # anything else counts as portrait
        self.assertEqual(self.read("system/media/bootanimation.zip"), "bootanimation.zip-0")
        shutil.rmtree(self.m / "orient/90")
        self.assertEqual(self.run_sh("90"), 3)


class Shutdown(unittest.TestCase):
    def test_collapses_to_black(self):
        z = zipfile.ZipFile(ROOT / "shutdownanimation.zip")
        self.assertEqual(z.read("desc.txt").decode(), DESC_SHUT)
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

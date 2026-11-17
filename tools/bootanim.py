#!/usr/bin/env python3
"""
Builds root/bootanimation.zip: the real Android boot animation (shown before
VoidOS starts). It follows the in-app start animation (ui/Boot.kt):

  part0, played once (2.4 s):  scan line, the mark's sides draw in, the core
                               pops, sixty ticks sweep round, VOIDOS types in
  part1, loops until Android has booted: the "starting hub" bar runs and the
                               core breathes

Installing it needs root or a flashed system image: see root/README.md.

Android wants: desc.txt ("WIDTH HEIGHT FPS" then one line per part), folders
of numbered PNGs, everything stored in the zip WITHOUT compression. The size
is the panel's native size in its natural orientation (portrait for most
tablets: 800x1280 for the Lenovo Tab M10 HD); check yours with
`adb shell wm size` and pass it with --size.

    python3 tools/bootanim.py                  # 800x1280
    python3 tools/bootanim.py --size 1200x1920 # a 1920x1200 tablet
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
SS = 2  # supersampling for smooth edges


def frame(w, h, s, loop_t=None):
    """One frame at time s seconds of the intro; loop_t (0..1) draws the looping part instead."""
    W, H = w * SS, h * SS
    img = Image.new("RGB", (W, H), vm.BG)
    d = ImageDraw.Draw(img)
    unit = min(W, H)
    c = (W / 2, H / 2 - unit * 0.08)
    r = unit * 0.16

    if loop_t is None and s < 0.5:  # scan line
        y = H * min(1.0, s / 0.45)
        d.line([(0, y), (W, y)], fill=vm.mix(vm.TEAL, vm.BG, 0.7 * (1 - s / 0.5)), width=max(1, int(unit * 0.004)))

    tick_p = 1.0 if loop_t is not None else max(0.0, min(1.0, (s - 1.5) / 1.0))
    for i in range(int(60 * tick_p)):
        a = -math.pi / 2 + i * 2 * math.pi / 60
        r0, r1 = r * 1.32, r * (1.48 if i % 5 == 0 else 1.40)
        d.line([(c[0] + r0 * math.cos(a), c[1] + r0 * math.sin(a)), (c[0] + r1 * math.cos(a), c[1] + r1 * math.sin(a))],
               fill=vm.mix(vm.LAP[(i // 10) % 6], vm.BG, 0.85), width=max(1, int(unit * 0.005)))

    if loop_t is None:
        prog = max(0.0, min(1.0, (s - 0.3) / 1.2))
        core_t = max(0.0, min(1.0, (s - 1.4) / 0.5))
        pop = 0.0 if core_t <= 0 else (1 + 0.35 * math.sin(core_t * math.pi)) * core_t
        glow = max(0.0, math.sin(core_t * math.pi))
    else:
        prog, pop = 1.0, 1.0
        glow = 0.5 - 0.5 * math.cos(loop_t * 2 * math.pi)  # breathes once per loop
    vm.draw_mark(d, c, r, prog, pop, glow)

    word = "VOIDOS"
    typed = len(word) if loop_t is not None else int(max(0.0, min(1.0, (s - 1.6) / 0.6)) * len(word))
    wy = c[1] + r * 1.48 + unit * 0.12
    if typed:
        vm.spaced_text(d, W / 2, wy, word[:typed], vm.font(vm.FONT_KENNEY, unit * 0.075), vm.WHITE, 0.25)
    sub_a = 1.0 if loop_t is not None else max(0.0, min(1.0, (s - 2.2) / 0.3))
    if sub_a > 0:
        vm.spaced_text(d, W / 2, wy + unit * 0.05, "TAB EDITION", vm.font(vm.FONT_KENNEY, unit * 0.024), vm.mix(vm.TEAL, vm.BG, sub_a), 0.5)

    if loop_t is not None:  # bar: a lap-coloured block runs along the track
        bw, bh = unit * 0.36, unit * 0.008
        bx, by = W / 2 - bw / 2, wy + unit * 0.11
        d.rectangle([bx, by, bx + bw, by + bh], fill=vm.LINE)
        seg = bw / 6
        x0 = bx + (bw + seg) * loop_t - seg
        lo, hi = max(bx, x0), min(bx + bw, x0 + seg)
        if hi > lo:
            d.rectangle([lo, by, hi, by + bh], fill=vm.LAP[int(loop_t * 6) % 6])
        vm.spaced_text(d, W / 2, by + unit * 0.045, "STARTING HUB", vm.font(vm.FONT_KENNEY, unit * 0.018), vm.MUTED, 0.3)

    return img.resize((w, h), Image.LANCZOS)


def png(img):
    b = io.BytesIO()
    img.save(b, "PNG", optimize=True)
    return b.getvalue()


def build(w, h, out):
    intro = [frame(w, h, i / FPS) for i in range(int(2.4 * FPS))]
    loop = [frame(w, h, 0, loop_t=i / (1.5 * FPS)) for i in range(int(1.5 * FPS))]
    desc = f"{w} {h} {FPS}\np 1 0 part0\np 0 0 part1\n"
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_STORED) as z:  # must be stored, not deflated
        z.writestr("desc.txt", desc)
        for i, f in enumerate(intro):
            z.writestr(f"part0/{i:05d}.png", png(f))
        for i, f in enumerate(loop):
            z.writestr(f"part1/{i:05d}.png", png(f))
    # previews for the README
    prev = out.parent / "preview"
    prev.mkdir(exist_ok=True)
    for name, f in [("bootanim_0.8s", intro[24]), ("bootanim_1.8s", intro[54]), ("bootanim_loop", loop[10])]:
        f.save(prev / f"{name}.png")
    return len(intro), len(loop)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--size", default="800x1280", help="WIDTHxHEIGHT in the panel's natural orientation")
    ap.add_argument("--out", default=str(vm.ROOT / "root/bootanimation.zip"))
    a = ap.parse_args()
    w, h = (int(v) for v in a.size.lower().split("x"))
    n0, n1 = build(w, h, Path(a.out))
    print(f"{a.out}: {w}x{h} @ {FPS} fps, part0 {n0} frames (once), part1 {n1} frames (loops)")

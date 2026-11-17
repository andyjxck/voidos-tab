#!/usr/bin/env python3
"""
Builds root/bootanimation.zip: the real Android boot animation (shown before
VoidOS starts). It follows the in-app start animation (ui/Boot.kt):

  part0, played once (2.4 s):  scan line, the "void" wordmark wipes in from
                               the left, the i's teal dot pops with a glow,
                               TAB EDITION fades in
  part1, loops until Android has booted: the approved loading animation,
                               the dot shuttling between the purple and green
                               bars like liquid glass (tools/logo_loading_anim.py)

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
SS = 4  # supersampling for smooth edges (matches the loop part, so frames join cleanly)


def ease(t):
    t = max(0.0, min(1.0, t))
    return 1 - (1 - t) ** 3


def frame(w, h, s):
    """One frame of the intro (part0) at time s seconds."""
    W, H = w * SS, h * SS
    img = Image.new("RGB", (W, H), vm.BG)
    d = ImageDraw.Draw(img)
    unit, c, ww, wh, sub_y = vm.lockup_geometry(W, H)

    if s < 0.5:  # scan line
        y = H * min(1.0, s / 0.45)
        d.line([(0, y), (W, y)], fill=vm.mix(vm.TEAL, vm.BG, 0.7 * (1 - s / 0.5)), width=max(1, int(unit * 0.004)))

    reveal = ease((s - 0.3) / 1.0)
    dot_t = max(0.0, min(1.0, (s - 1.3) / 0.5))
    dot = 0.0 if dot_t <= 0 else (1 + 0.35 * math.sin(dot_t * math.pi)) * dot_t
    glow = max(0.0, math.sin(dot_t * math.pi))
    sub_a = max(0.0, min(1.0, (s - 1.7) / 0.4))
    vm.draw_lockup(d, W, H, sub_alpha=sub_a, reveal=reveal, dot=dot, glow=glow)
    hub_a = max(0.0, min(1.0, (s - 1.95) / 0.4))  # fades in so the loop's line is already there
    if hub_a > 0:
        unit, _, _, _, sub_y = vm.lockup_geometry(W, H)
        vm.spaced_text(d, W / 2, sub_y + unit * 0.10, "STARTING HUB", vm.font(vm.FONT_KENNEY, unit * 0.018),
                       vm.mix(vm.MUTED, vm.BG, hub_a), 0.3)

    return img.resize((w, h), Image.LANCZOS)


def loop_frames(w, h):
    """part1: the approved loading animation (tools/logo_loading_anim.py): the
    i's dot shuttles between the purple and green bars like a drop of liquid
    glass, merging into each and pulling away. Drawn by that script's own
    renderer so it is the same animation, at this size and frame rate, with
    TAB EDITION and STARTING HUB under it."""
    import numpy as np
    import logo_loading_anim as lla
    lla.FPS = FPS  # one round trip = lla.PERIOD seconds at our frame rate
    shapes, _ = lla.wordmark()
    geo, static = lla.geometry(shapes)
    unit, c, ww, wh, sub_y = vm.lockup_geometry(w, h)
    # place the raw round-5 shapes exactly where voidmark draws the wordmark
    # (its geometry is the same drawing, simplified a hair and moved to 0,0)
    W0, _ = vm.word_size()
    sc = ww / W0
    ox0, oy0 = vm.geometry()["origin"]
    left, top = c[0] - ww / 2, c[1] - wh / 2
    import vgeom as g
    rx0, ry0, rx1, ry1 = g.bounds(shapes)
    box = (left + (rx0 - ox0) * sc, top + (ry0 - oy0) * sc, left + (rx1 - ox0) * sc, top + (ry1 - oy0) * sc)
    n, render, _ = lla.make_renderer(shapes, geo, static, w, h, box)
    # the text, drawn once at the intro's supersampling and laid over every frame
    txt = Image.new("RGB", (w * SS, h * SS), vm.BG)
    d = ImageDraw.Draw(txt)
    u2, _, _, _, sy2 = vm.lockup_geometry(w * SS, h * SS)
    vm.spaced_text(d, w * SS / 2, sy2, "TAB EDITION", vm.font(vm.FONT_KENNEY, u2 * 0.030), vm.TEAL, 0.5)
    vm.spaced_text(d, w * SS / 2, sy2 + u2 * 0.10, "STARTING HUB", vm.font(vm.FONT_KENNEY, u2 * 0.018), vm.MUTED, 0.3)
    txt = np.asarray(txt.resize((w, h), Image.LANCZOS))
    band = slice(int(sub_y - unit * 0.06), h)
    out = []
    for i in range(n):
        fr = render(i)
        fr[band] = txt[band]
        out.append(Image.fromarray(fr))
    return out


def png(img):
    b = io.BytesIO()
    img.save(b, "PNG", optimize=True)
    return b.getvalue()


def build(w, h, out):
    intro = [frame(w, h, i / FPS) for i in range(int(2.4 * FPS))]
    loop = loop_frames(w, h)
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
    for name, f in [("bootanim_0.8s", intro[24]), ("bootanim_1.5s", intro[45]), ("bootanim_loop", loop[len(loop) * 21 // 100])]:
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

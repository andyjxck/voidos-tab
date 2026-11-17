#!/usr/bin/env python3
"""
Exports the VoidOS logo (see ui/Logo.kt) as files: the mark on its own and
the mark over the VOIDOS wordmark, as PNG and as SVG, into docs/logo.

    python3 tools/logo.py
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import voidmark as vm  # noqa: E402

OUT = vm.ROOT / "docs/logo"


def mark_png(px, bg=None):
    ss = 4
    img = Image.new("RGBA", (px * ss, px * ss), (0, 0, 0, 0) if bg is None else bg + (255,))
    d = ImageDraw.Draw(img)
    c = (px * ss / 2, px * ss / 2)
    vm.draw_mark(d, c, px * ss / 2 * 0.96, bg=vm.BG)
    return img.resize((px, px), Image.LANCZOS)


def lockup_png(w, h):
    ss = 2
    W, H = w * ss, h * ss
    img = Image.new("RGB", (W, H), vm.BG)
    d = ImageDraw.Draw(img)
    unit = min(W, H)
    r = unit * 0.22
    c = (W / 2, H * 0.40)
    vm.draw_mark(d, c, r)
    wy = c[1] + r + unit * 0.20
    vm.spaced_text(d, W / 2, wy, "VOIDOS", vm.font(vm.FONT_KENNEY, unit * 0.12), vm.WHITE, 0.25)
    vm.spaced_text(d, W / 2, wy + unit * 0.08, "TAB EDITION", vm.font(vm.FONT_KENNEY, unit * 0.036), vm.TEAL, 0.5)
    return img.resize((w, h), Image.LANCZOS)


def hexc(c):
    return "#%02x%02x%02x" % c


def mark_svg(size=512):
    c = (size / 2, size / 2)
    r = size / 2 * 0.96
    ri = r * (1 - vm.THICK)
    parts = []
    for k, side in enumerate(vm.ORDER):
        pts = [vm.vertex(c, r, side), vm.vertex(c, r, side + 1), vm.vertex(c, ri, side + 1), vm.vertex(c, ri, side)]
        parts.append(f'<polygon fill="{hexc(vm.COLOURS[k])}" points="{" ".join(f"{x:.2f},{y:.2f}" for x, y in pts)}"/>')
    inner = " ".join(f"{x:.2f},{y:.2f}" for x, y in vm.oct_points(c, r * vm.INNER))
    core = " ".join(f"{x:.2f},{y:.2f}" for x, y in vm.oct_points(c, r * vm.CORE))
    parts.append(f'<polygon fill="{hexc(vm.PANEL)}" stroke="{hexc(vm.LINE)}" stroke-width="{r * 0.025:.2f}" points="{inner}"/>')
    parts.append(f'<polygon fill="#ffffff" points="{core}"/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}" width="{size}" height="{size}">\n  '
            + "\n  ".join(parts) + "\n</svg>\n")


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    mark_png(512).save(OUT / "voidos-mark-512.png")
    mark_png(1024).save(OUT / "voidos-mark-1024.png")
    lockup_png(1200, 1200).save(OUT / "voidos-lockup.png")
    (OUT / "voidos-mark.svg").write_text(mark_svg())
    print("wrote", ", ".join(sorted(p.name for p in OUT.iterdir())))

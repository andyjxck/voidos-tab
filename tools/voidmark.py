"""
The VoidOS mark, drawn with Pillow. Same geometry as ui/Logo.kt (Mark object):
seven thick octagon sides in the lap colours, the top-right side left open,
a dark inner octagon and a white octagonal core. Keep the two in step.
"""
import math
from pathlib import Path
from PIL import ImageFont

ROOT = Path(__file__).resolve().parent.parent
FONT_KENNEY = ROOT / "app/src/main/res/font/kenney_future.ttf"
FONT_JUPI = ROOT / "app/src/main/res/font/jupiteroid.ttf"

# ui/Theme.kt
BG = (8, 10, 18)
PANEL = (14, 19, 32)
LINE = (42, 51, 68)
WHITE = (255, 255, 255)
TEAL = (70, 216, 224)
CORAL = (240, 88, 110)
GREEN = (60, 200, 80)
YELLOW = (235, 200, 40)
PURPLE = (155, 108, 230)
ORANGE = (230, 140, 60)
MUTED = (138, 148, 168)
LAP = [CORAL, PURPLE, TEAL, GREEN, YELLOW, ORANGE]

ORDER = [7, 0, 1, 2, 3, 4, 5]          # sides, clockwise from the right-hand vertical
COLOURS = [CORAL, PURPLE, TEAL, GREEN, YELLOW, ORANGE, CORAL]
THICK, INNER, CORE = 0.17, 0.60, 0.17


def vertex(c, r, i):
    a = math.radians(22.5 + 45.0 * (i % 8))
    return (c[0] + r * math.cos(a), c[1] + r * math.sin(a))


def oct_points(c, r):
    """Flat-topped octagon with apothem r (ui/Kit.kt octPath)."""
    k = r * 0.4142
    x, y = c
    return [(x - k, y - r), (x + k, y - r), (x + r, y - k), (x + r, y + k),
            (x + k, y + r), (x - k, y + r), (x - r, y + k), (x - r, y - k)]


def lerp(a, b, t):
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)


def mix(col, bg, a):
    """col at alpha a over bg (frames are opaque, so blend by hand)."""
    return tuple(int(round(bg[i] + (col[i] - bg[i]) * a)) for i in range(3))


def draw_mark(d, c, r, progress=1.0, core=1.0, glow=0.0, fade=1.0, bg=BG):
    ri = r * (1 - THICK)
    n = len(ORDER)
    for k, side in enumerate(ORDER):
        t = max(0.0, min(1.0, progress * n - k))
        if t <= 0:
            continue
        o0, o1 = vertex(c, r, side), vertex(c, r, side + 1)
        i0, i1 = vertex(c, ri, side), vertex(c, ri, side + 1)
        d.polygon([o0, lerp(o0, o1, t), lerp(i0, i1, t), i0], fill=mix(COLOURS[k], bg, fade))
    d.polygon(oct_points(c, r * INNER), fill=mix(PANEL, bg, fade), outline=mix(LINE, bg, fade), width=max(1, int(r * 0.025)))
    if glow > 0:
        for i in range(4, 0, -1):
            rr = r * CORE * (1 + i * 0.55)
            d.ellipse([c[0] - rr, c[1] - rr, c[0] + rr, c[1] + rr], fill=mix(TEAL, mix(PANEL, bg, fade), 0.06 * glow * (5 - i)))
    if core > 0:
        d.polygon(oct_points(c, r * CORE * core), fill=mix(WHITE, bg, fade))


def font(path, px):
    return ImageFont.truetype(str(path), max(1, int(px)))


def spaced_text(d, centre_x, y, text, fnt, fill, spacing):
    """Centred text with letter spacing (spacing is a fraction of the font size)."""
    widths = [d.textlength(ch, font=fnt) for ch in text]
    gap = fnt.size * spacing
    total = sum(widths) + gap * (len(text) - 1)
    x = centre_x - total / 2
    for ch, w in zip(text, widths):
        d.text((x, y), ch, font=fnt, fill=fill, anchor="ls")
        x += w + gap

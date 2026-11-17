"""
The VoidOS logo, drawn with Pillow. Same drawing as ui/Logo.kt: both read the
geometry tools/logo_build.py writes from the approved square-root wordmark
(here tools/voidmark_geometry.json, there ui/LogoData.kt).

  draw_word    the wordmark "void": the square root is the V, "oid" sits under
               the broken six-colour bar, the i's dot is the teal pill
  draw_mark    the square root and the o, for small square places
  draw_lockup  the wordmark over TAB EDITION, as the boot screens end

Needs only Pillow, so it can travel with tools/mtk_logo.py on its own: put
voidmark_geometry.json and kenney_future.ttf next to it.
"""
import json
import math
from pathlib import Path

from PIL import ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent


def _find(*cands):
    for c in cands:
        if c.exists():
            return c
    return cands[0]


FONT_KENNEY = _find(ROOT / "app/src/main/res/font/kenney_future.ttf", HERE / "kenney_future.ttf")
FONT_JUPI = _find(ROOT / "app/src/main/res/font/jupiteroid.ttf", HERE / "jupiteroid.ttf")
GEOMETRY = HERE / "voidmark_geometry.json"

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

_geo = None


def geometry():
    global _geo
    if _geo is None:
        _geo = json.loads(GEOMETRY.read_text())
    return _geo


def mix(col, bg, a):
    """col at alpha a over bg (frames are opaque, so blend by hand)."""
    return tuple(int(round(bg[i] + (col[i] - bg[i]) * a)) for i in range(3))


def _clip(poly, xmax):
    """Part of a ring left of x = xmax (Sutherland-Hodgman on one edge)."""
    out = []
    n = len(poly)
    for i in range(n):
        p, q = poly[i], poly[(i + 1) % n]
        fp, fq = p[0] - xmax, q[0] - xmax
        if fp <= 0:
            out.append(p)
        if (fp < 0) != (fq < 0) and fp != fq:
            t = fp / (fp - fq)
            out.append((p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t))
    return out


def _fill(d, rings, fill, hole):
    if len(rings[0]) >= 3:
        d.polygon(rings[0], fill=fill)
    for h in rings[1:]:
        if len(h) >= 3:
            d.polygon(h, fill=hole)


def _draw(d, ox, oy, s, only_mark=False, reveal=None, dot=1.0, glow=0.0, fade=1.0, bg=BG, mono=None):
    """Draw the logo with drawing unit (0,0) at (ox, oy), s pixels per unit.
    reveal: wipe in from the left, as an x in drawing units (None = all).
    dot: scale of the i's dot (0 hides it). Holes are filled with bg, or left
    transparent on an RGBA picture."""
    geo = geometry()
    rgba = getattr(d, "mode", "RGB") == "RGBA"
    hole = (0, 0, 0, 0) if rgba else bg
    dc = geo["dot"]
    if glow > 0 and not only_mark and dot > 0:
        cx, cy = ox + dc["cx"] * s, oy + dc["cy"] * s
        for i in range(4, 0, -1):
            rr = s * 11 * (0.6 + i * 0.45) * dot
            d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=mix(TEAL, bg, 0.05 * glow * (5 - i) * fade))
    for sh in geo["shapes"]:
        if only_mark and not sh["mark"]:
            continue
        rings = sh["rings"]
        if sh["dot"]:
            if dot <= 0:
                continue
            rings = [[(dc["cx"] + (x - dc["cx"]) * dot, dc["cy"] + (y - dc["cy"]) * dot) for x, y in r] for r in rings]
        if reveal is not None:
            rings = [_clip(r, reveal) for r in rings]
        col = mono or (WHITE if sh["c"] == -1 else LAP[sh["c"]])
        fill = mix(col, bg, fade) if not rgba else tuple(col) + (int(255 * fade),)
        _fill(d, [[(ox + x * s, oy + y * s) for x, y in r] for r in rings], fill, hole)


def word_size():
    w = geometry()["word"]
    return w["w"], w["h"]


def draw_word(d, c, width, reveal=1.0, dot=1.0, glow=0.0, fade=1.0, bg=BG, mono=None):
    """The wordmark centred on c, width pixels wide. reveal 0..1 wipes it in
    from the left; dot 0..1+ scales the i's dot; glow 0..1 haloes it."""
    W, H = word_size()
    s = width / W
    ox, oy = c[0] - W * s / 2, c[1] - H * s / 2
    rv = None if reveal >= 1 else W * reveal
    if rv is not None and rv <= 0:
        return
    _draw(d, ox, oy, s, reveal=rv, dot=dot, glow=glow, fade=fade, bg=bg, mono=mono)


def draw_mark(d, c, r, progress=1.0, fade=1.0, bg=BG, mono=None):
    """The square root and the o, fitted in a 2r square centred on c.
    progress 0..1 wipes it in from the left."""
    m = geometry()["mark"]
    mw, mh = m["x1"] - m["x0"], m["y1"] - m["y0"]
    s = 2 * r * 0.96 / max(mw, mh)
    ox = c[0] - (m["x0"] + mw / 2) * s
    oy = c[1] - (m["y0"] + mh / 2) * s
    rv = None if progress >= 1 else m["x0"] + mw * progress
    if rv is not None and progress <= 0:
        return
    _draw(d, ox, oy, s, only_mark=True, reveal=rv, fade=fade, bg=bg, mono=mono)


# Layout shared by the boot animation's last frame, the power-on logo and the
# lockup, so one follows the other without a jump. Fractions of min(W, H).
WORD_W = 0.70        # wordmark width
WORD_UP = 0.06       # wordmark centre above the screen centre
SUB_GAP = 0.085      # wordmark bottom to the TAB EDITION baseline


def lockup_geometry(W, H):
    unit = min(W, H)
    ww = unit * WORD_W
    wW, wH = word_size()
    wh = ww * wH / wW
    c = (W / 2, H / 2 - unit * WORD_UP)
    sub_y = c[1] + wh / 2 + unit * SUB_GAP
    return unit, c, ww, wh, sub_y


def draw_lockup(d, W, H, sub="TAB EDITION", sub_alpha=1.0, bg=BG, **word):
    unit, c, ww, wh, sub_y = lockup_geometry(W, H)
    draw_word(d, c, ww, bg=bg, **word)
    if sub and sub_alpha > 0:
        spaced_text(d, W / 2, sub_y, sub, font(FONT_KENNEY, unit * 0.030), mix(TEAL, bg, sub_alpha), 0.5)


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


_ = math  # kept for tools that import it from here

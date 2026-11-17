#!/usr/bin/env python3
"""
Two logo drafts for VoidOS, as vectors:

  warp  a V in the six lap colours that folds into itself, each V nested in
        the notch of the one before and turned a little, down to a white core
  root  "vOS" as a square root sign: the V's right stroke runs up into a long
        bar and OS sits under the bar (like the Vans wordmark)

    python3 tools/logo_concepts.py      # writes docs/logo/concepts/
"""
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import vgeom as g  # noqa: E402

BG = (8, 10, 18)
PANEL = (14, 19, 32)
WHITE = (255, 255, 255)
TEAL = (70, 216, 224)
CORAL = (240, 88, 110)
GREEN = (60, 200, 80)
YELLOW = (235, 200, 40)
PURPLE = (155, 108, 230)
ORANGE = (230, 140, 60)
MUTED = (138, 148, 168)
LAP = [CORAL, PURPLE, TEAL, GREEN, YELLOW, ORANGE]


# ------------------------------------------------------------- warp V

def v_shape(W=50.0, H=100.0, arm=0.36, foot=0.06):
    """A flat-topped V, top edge on y=0, point at (0, H) trimmed flat by foot."""
    tw = W * arm
    k = (W - tw) / W
    yf = H * (1 - foot)
    xf = W * foot  # half width at the trimmed foot
    return [(-W, 0), (-W + tw, 0), (0, k * H), (W - tw, 0), (W, 0), (xf, yf), (-xf, yf)]


def warp(levels=6, scale=0.64, twist=10.0, pivot=0.36, gap=0.10):
    W, H = 50.0, 100.0
    v = v_shape(W, H)
    about = (0.0, pivot * H)
    shapes = []
    for i in range(levels):
        s = scale ** i
        poly = [_sr(p, s, math.radians(twist * i), about) for p in v]
        if i:  # a thin cut of background round each inner V, so the layers read as stacked
            for q in g.stroke_polyline(poly, gap * s * W, closed=True):
                shapes.append((BG, [q]))
        shapes.append((LAP[i % 6], [poly]))
    # the void: a dark octagon and a white core at the pivot
    r = W * scale ** levels * 0.9
    cx, cy = about
    shapes.append((WHITE, [_oct(cx, cy, r * 0.55)]))
    return shapes


def _sr(p, s, a, about):
    x, y = p[0] - about[0], p[1] - about[1]
    ca, sa = math.cos(a), math.sin(a)
    return (about[0] + s * (x * ca - y * sa), about[1] + s * (x * sa + y * ca))


def _oct(cx, cy, r):
    k = r * 0.4142
    return [(cx - k, cy - r), (cx + k, cy - r), (cx + r, cy - k), (cx + r, cy + k),
            (cx + k, cy + r), (cx - k, cy + r), (cx - r, cy + k), (cx - r, cy - k)]


# ------------------------------------------------------------- root vOS

def clip(poly, a, b, c):
    """Keep the part of poly where a*x + b*y <= c (Sutherland-Hodgman)."""
    out = []
    n = len(poly)
    for i in range(n):
        p, q = poly[i], poly[(i + 1) % n]
        fp, fq = a * p[0] + b * p[1] - c, a * q[0] + b * q[1] - c
        if fp <= 0:
            out.append(p)
        if (fp < 0) != (fq < 0) and fp != fq:
            t = fp / (fp - fq)
            out.append((p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t))
    return out


def letter_O(x0, y0, x1, y1, t, c):
    k = t * (math.sqrt(2) - 1)
    return [g.octagon(x0, y0, x1, y1, c), g.octagon(x0 + t, y0 + t, x1 - t, y1 - t, max(0.0, c - k))]


def letter_S(x0, y0, x1, y1, t, c):
    h = t / 2
    a0, b0, a1, b1 = x0 + h, y0 + h, x1 - h, y1 - h
    ym = (y0 + y1) / 2
    c2 = c * 0.55
    pts = [(a1 + h, b0), (a0 + c, b0), (a0, b0 + c), (a0, ym - c2), (a0 + c2, ym), (a1 - c2, ym),
           (a1, ym + c2), (a1, b1 - c), (a1 - c, b1), (x0, b1)]
    return [g.stroke_outline(pts, t)]


def root(t=15.0, H=100.0, colours=None):
    """Square-root vOS. Bar on y=0 (centre line), V point at y=H."""
    colours = colours or {"down": CORAL, "up": PURPLE, "bar": [TEAL, GREEN, YELLOW, ORANGE], "os": WHITE}
    top = (60.0, 0.0)
    vertex = (34.0, H)
    start = (0.0, H * 0.40)
    bar_end = 216.0
    # extend the lead-in up its own line, then trim it flat
    dx, dy = start[0] - vertex[0], start[1] - vertex[1]
    L = math.hypot(dx, dy)
    ext = (start[0] + dx / L * t, start[1] + dy / L * t)
    nb = len(colours["bar"])
    bar_pts = [(top[0] + (bar_end - top[0]) * i / nb, 0.0) for i in range(1, nb + 1)]
    quads = g.stroke_polyline([ext, vertex, top] + bar_pts, t)
    foot = H + t * 0.45
    shapes = []
    q0 = clip(clip(quads[0], 0, -1, -start[1] + t * 0.5), 0, 1, foot)
    q1 = clip(quads[1], 0, 1, foot)
    shapes.append((colours["down"], [q0]))
    shapes.append((colours["up"], [q1]))
    for i, q in enumerate(quads[2:]):
        shapes.append((colours["bar"][i], [q]))
    # OS under the bar, right of the up stroke, bottoms level with the V's foot
    gap = t * 0.62
    ly0, ly1 = t / 2 + gap, foot
    lh = ly1 - ly0
    # right edge of the up stroke at the letters' mid height
    ux = vertex[0] + (top[0] - vertex[0]) * (1 - ((ly0 + ly1) / 2) / H) + t * 0.62
    ox0 = ux + gap * 0.9
    ow = lh * 0.86
    sw = lh * 0.74
    c = lh * 0.26
    shapes.append((colours["os"], letter_O(ox0, ly0, ox0 + ow, ly1, t, c)))
    sx0 = ox0 + ow + gap * 1.1
    shapes.append((colours["os"], letter_S(sx0, ly0, sx0 + sw, ly1, t, c * 0.85)))
    # bar ends just past the S
    return shapes, sx0 + sw


def root_shapes(**kw):
    shapes, right = root(**kw)
    return shapes


# ------------------------------------------------------------- output

def sheet(shapes, label, path, w=1200, h=900):
    """Preview sheet: big mark on the dark background, icon sizes, portrait."""
    from PIL import Image, ImageDraw, ImageFont
    img = Image.new("RGB", (w, h), BG)
    big = g.render(shapes, 760, 560, bg=BG, margin=0.10)
    img.paste(big, (40, 60))
    d = ImageDraw.Draw(img)
    try:
        f = ImageFont.truetype(str(Path(__file__).resolve().parent.parent / "app/src/main/res/font/kenney_future.ttf"), 26)
        fs = ImageFont.truetype(str(Path(__file__).resolve().parent.parent / "app/src/main/res/font/kenney_future.ttf"), 15)
    except OSError:
        f = fs = ImageFont.load_default()
    d.text((48, 24), label, font=f, fill=WHITE)
    # icon sizes on round launcher tiles
    x = 860
    y = 70
    for px in (192, 96, 48):
        tile = Image.new("RGB", (px, px), PANEL)
        m = Image.new("L", (px * 4, px * 4), 0)
        ImageDraw.Draw(m).ellipse([0, 0, px * 4 - 1, px * 4 - 1], fill=255)
        m = m.resize((px, px), Image.LANCZOS)
        mark = g.render(shapes, px, px, bg=None, margin=0.22)
        tile.paste(mark, (0, 0), mark)
        img.paste(tile, (x + (192 - px) // 2, y), m)
        d.text((x + 192 + 14, y + px // 2 - 8), f"{px}px", font=fs, fill=MUTED)
        y += px + 30
    # portrait 800x1280, shown at a quarter
    por = Image.new("RGB", (800, 1280), BG)
    pm = g.render(shapes, 800, 1280, bg=None, margin=0.0, box=(170, 420, 630, 760))
    por.paste(pm, (0, 0), pm)
    por = por.resize((200, 320), Image.LANCZOS)
    d.rectangle([w - 260 - 1, h - 340 - 1, w - 60, h - 20], outline=(42, 51, 68))
    img.paste(por, (w - 260, h - 340))
    d.text((w - 260, h - 360), "800x1280", font=fs, fill=MUTED)
    d.text((48, h - 50), "DRAFT", font=fs, fill=MUTED)
    img.save(path)


if __name__ == "__main__":
    out = Path(__file__).resolve().parent.parent / "docs/logo/concepts"
    out.mkdir(parents=True, exist_ok=True)
    for name, shapes, label in [("a-warp-v", warp(), "A  WARP V"), ("b-root-vos", root_shapes(), "B  ROOT vOS")]:
        (out / f"{name}.svg").write_text(g.svg(shapes, 1024, 1024, bg=BG, margin=0.12))
        g.render(shapes, 1024, 1024, bg=BG, margin=0.12).save(out / f"{name}.png")
        sheet(shapes, label, out / f"{name}-preview.png")
    print("wrote", ", ".join(sorted(p.name for p in out.iterdir())))

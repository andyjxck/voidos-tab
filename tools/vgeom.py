"""
Small vector toolkit for the VoidOS logo: shapes are lists of polygons
(an outer ring plus optional holes, filled even-odd) in one colour, so the
same geometry can be written as SVG, as Android vector pathData, as Kotlin
for Compose, and rasterised with Pillow.
"""
import math

from PIL import Image, ImageDraw


def stroke_polyline(pts, w, closed=False):
    """Mitred stroke of a polyline. Returns one quad per segment; neighbouring
    quads share their mitre edge exactly, so segments can take different
    colours with clean seams."""
    n = len(pts)
    h = w / 2

    def seg_dir(i):
        (x0, y0), (x1, y1) = pts[i], pts[(i + 1) % n]
        L = math.hypot(x1 - x0, y1 - y0)
        return ((x1 - x0) / L, (y1 - y0) / L)

    segs = n if closed else n - 1
    dirs = [seg_dir(i) for i in range(segs)]
    left, right = [], []
    for i in range(n):
        if not closed and i == 0:
            d0 = d1 = dirs[0]
        elif not closed and i == n - 1:
            d0 = d1 = dirs[-1]
        else:
            d0, d1 = dirs[(i - 1) % segs], dirs[i % segs]
        n0 = (-d0[1], d0[0])
        n1 = (-d1[1], d1[0])
        mx, my = n0[0] + n1[0], n0[1] + n1[1]
        ml = math.hypot(mx, my)
        mx, my = mx / ml, my / ml
        cos_half = mx * n1[0] + my * n1[1]
        k = h / cos_half
        x, y = pts[i]
        left.append((x + mx * k, y + my * k))
        right.append((x - mx * k, y - my * k))
    quads = []
    for i in range(segs):
        j = (i + 1) % n
        quads.append([left[i], left[j], right[j], right[i]])
    return quads


def stroke_outline(pts, w):
    """Outline of a whole open polyline stroke as one polygon."""
    q = stroke_polyline(pts, w)
    left = [q[0][0]] + [s[1] for s in q]
    right = [q[0][3]] + [s[2] for s in q]
    return left + right[::-1]


def octagon(x0, y0, x1, y1, c):
    """Axis-aligned octagon in a box, corners cut at 45 degrees by c."""
    return [(x0 + c, y0), (x1 - c, y0), (x1, y0 + c), (x1, y1 - c),
            (x1 - c, y1), (x0 + c, y1), (x0, y1 - c), (x0, y0 + c)]


def xform(rings, scale=1.0, dx=0.0, dy=0.0, rot=0.0, about=(0.0, 0.0)):
    ca, sa = math.cos(rot), math.sin(rot)
    ax, ay = about
    out = []
    for ring in rings:
        r2 = []
        for x, y in ring:
            x, y = x - ax, y - ay
            x, y = x * ca - y * sa, x * sa + y * ca
            r2.append((ax + x * scale + dx, ay + y * scale + dy))
        out.append(r2)
    return out


def bounds(shapes):
    xs = [x for _, rings in shapes for ring in rings for x, _ in ring]
    ys = [y for _, rings in shapes for ring in rings for _, y in ring]
    return min(xs), min(ys), max(xs), max(ys)


def fit(shapes, box, margin=0.0, align="center"):
    """Scale and move shapes to sit centred in box=(x0,y0,x1,y1)."""
    bx0, by0, bx1, by1 = bounds(shapes)
    x0, y0, x1, y1 = box
    W, H = (x1 - x0) * (1 - 2 * margin), (y1 - y0) * (1 - 2 * margin)
    s = min(W / (bx1 - bx0), H / (by1 - by0))
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    ox = cx - (bx0 + bx1) / 2 * s
    oy = cy - (by0 + by1) / 2 * s
    return [(col, [[(x * s + ox, y * s + oy) for x, y in ring] for ring in rings]) for col, rings in shapes]


def draw(img, shapes, colour_of=lambda c: c):
    """Fill shapes onto an RGB/RGBA Pillow image (even-odd per shape)."""
    for col, rings in shapes:
        mask = Image.new("L", img.size, 0)
        md = ImageDraw.Draw(mask)
        md.polygon(rings[0], fill=255)
        for hole in rings[1:]:
            md.polygon(hole, fill=0)
        c = colour_of(col)
        if img.mode == "RGBA" and len(c) == 3:
            c = tuple(c) + (255,)
        img.paste(c, (0, 0), mask)


def render(shapes, w, h, bg=None, ss=4, margin=0.08, box=None):
    """Shapes fitted into a w x h picture (transparent when bg is None)."""
    W, H = w * ss, h * ss
    img = Image.new("RGBA" if bg is None else "RGB", (W, H), (0, 0, 0, 0) if bg is None else bg)
    b = box or (0, 0, w, h)
    draw(img, fit(shapes, tuple(v * ss for v in b), margin))
    return img.resize((w, h), Image.LANCZOS)


def hexc(c):
    return "#%02x%02x%02x" % tuple(c[:3])


def path_d(rings, fmt="%.2f"):
    out = []
    for ring in rings:
        out.append("M" + " L".join((fmt + "," + fmt) % (x, y) for x, y in ring) + " Z")
    return " ".join(out)


def svg(shapes, w, h, bg=None, margin=0.08):
    sh = fit(shapes, (0, 0, w, h), margin)
    parts = []
    if bg is not None:
        parts.append(f'<rect width="{w}" height="{h}" fill="{hexc(bg)}"/>')
    for col, rings in sh:
        parts.append(f'<path fill="{hexc(col)}" fill-rule="evenodd" d="{path_d(rings)}"/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}">\n  '
            + "\n  ".join(parts) + "\n</svg>\n")

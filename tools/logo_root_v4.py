#!/usr/bin/env python3
"""
Square-root wordmark, round 4: E (line, dot, line) with the lettering
softened, following Andy's feedback that everything looked too squared.

  * o and d bowls are curved (superellipse rings) instead of octagons
  * every corner of the stroke is filleted: the hook, the V's point, the
    corner where the radical turns into the bar, and the bar's drop into
    the d's stem
  * stroke ends are round, the i's stem has round ends
  * the dot of the i is a wider pill, still in the bar line and still
    clear of the lines either side

The broken bar layout, the stroke weight and the six colours are kept.

    python3 tools/logo_root_v4.py      # writes docs/logo/concepts/f-*
"""
import math
import sys
from pathlib import Path

from shapely.geometry import LineString, Polygon, MultiPolygon, Point
from shapely.ops import unary_union
from shapely import affinity

sys.path.insert(0, str(Path(__file__).resolve().parent))
import vgeom as g  # noqa: E402
from logo_concepts import BG, WHITE, LAP  # noqa: E402
from logo_root_v2 import T, B, FLOOR, radical, boot, sheet  # noqa: E402

QS = 32  # arc segments per quarter circle


def fillet(pts, radii):
    """Replace each interior corner i of an open polyline with an arc of
    centre-line radius radii[i] (0 keeps the corner)."""
    out = [pts[0]]
    for i in range(1, len(pts) - 1):
        r = radii[i]
        P = pts[i]
        if r <= 0:
            out.append(P)
            continue
        ax, ay = pts[i - 1][0] - P[0], pts[i - 1][1] - P[1]
        bx, by = pts[i + 1][0] - P[0], pts[i + 1][1] - P[1]
        la, lb = math.hypot(ax, ay), math.hypot(bx, by)
        ax, ay, bx, by = ax / la, ay / la, bx / lb, by / lb
        alpha = math.acos(max(-1.0, min(1.0, ax * bx + ay * by)))
        d = r / math.tan(alpha / 2)
        t1 = (P[0] + ax * d, P[1] + ay * d)
        t2 = (P[0] + bx * d, P[1] + by * d)
        mx, my = ax + bx, ay + by
        ml = math.hypot(mx, my)
        hc = r / math.sin(alpha / 2)
        C = (P[0] + mx / ml * hc, P[1] + my / ml * hc)
        a1 = math.atan2(t1[1] - C[1], t1[0] - C[0])
        a2 = math.atan2(t2[1] - C[1], t2[0] - C[0])
        da = (a2 - a1 + math.pi) % (2 * math.pi) - math.pi
        n = max(4, int(abs(da) / (math.pi / 2) * QS))
        out += [(C[0] + r * math.cos(a1 + da * k / n), C[1] + r * math.sin(a1 + da * k / n)) for k in range(n + 1)]
    out.append(pts[-1])
    return out


def stroke(pts, radii=None):
    if radii:
        pts = fillet(pts, radii)
    return LineString(pts).buffer(T / 2, quad_segs=QS, cap_style="round", join_style="round")


def halfplane(P, t, before=True, R=1e4):
    """Half-plane through P perpendicular to t: the side t points away
    from (before=True) or towards."""
    tx, ty = t
    L = math.hypot(tx, ty)
    tx, ty = tx / L, ty / L
    nx, ny = -ty, tx
    s = -1 if before else 1
    a = (P[0] + nx * R, P[1] + ny * R)
    b = (P[0] - nx * R, P[1] - ny * R)
    return Polygon([a, b, (b[0] + s * tx * R, b[1] + s * ty * R), (a[0] + s * tx * R, a[1] + s * ty * R)])


def xband(x0, x1):
    return Polygon([(x0, -1e4), (x1, -1e4), (x1, 1e4), (x0, 1e4)])


def bisector(prev, P, nxt):
    ax, ay = P[0] - prev[0], P[1] - prev[1]
    bx, by = nxt[0] - P[0], nxt[1] - P[1]
    la, lb = math.hypot(ax, ay), math.hypot(bx, by)
    return (ax / la + bx / lb, ay / la + by / lb)


def bowl(x0, y0, x1, y1, n=2.3):
    """Superellipse ring of stroke T in a box."""
    cx, cy, rx, ry = (x0 + x1) / 2, (y0 + y1) / 2, (x1 - x0) / 2, (y1 - y0) / 2
    pts = []
    for k in range(480):
        a = 2 * math.pi * k / 480
        c, s = math.cos(a), math.sin(a)
        pts.append((cx + rx * math.copysign(abs(c) ** (2 / n), c), cy + ry * math.copysign(abs(s) ** (2 / n), s)))
    outer = Polygon(pts)
    return outer.difference(outer.buffer(-T, quad_segs=QS))


def pill(cx, cy, w, h):
    return LineString([(cx - (w - h) / 2, cy), (cx + (w - h) / 2, cy)]).buffer(h / 2, quad_segs=QS, cap_style="round")


def to_shapes(col, geom):
    geoms = geom.geoms if isinstance(geom, MultiPolygon) else [geom]
    out = []
    for p in geoms:
        if p.is_empty or p.area < 1e-6:
            continue
        rings = [list(p.exterior.coords)[:-1]] + [list(r.coords)[:-1] for r in p.interiors]
        out.append((col, rings))
    return out


def root_oid_soft(dot_w=1.8 * T, gap=13.0, o_clear=9.6):
    top_x = 62.0
    rad = radical(top_x)
    xh = 58.0
    ly0 = FLOOR - xh
    radii_rad = [0, 6.0, 4.0]          # hook, V point
    r_top = 9.0                        # radical into bar
    r_d = 9.0                          # bar into d stem

    # place the o: same clearance from the up stroke as round 3
    ow = 54.0
    up = stroke(rad, radii_rad)
    ox0 = 40.0
    for _ in range(40):
        o = bowl(ox0, ly0, ox0 + ow, FLOOR)
        dist = o.distance(up)
        ox0 += o_clear - dist
        if abs(o_clear - dist) < 0.01:
            break
    ox1 = ox0 + ow
    o = bowl(ox0, ly0, ox1, FLOOR)

    # i stem, round ends
    ix0 = ox1 + gap
    ic = ix0 + T / 2
    i_stem = stroke([(ic, ly0 + T / 2), (ic, FLOOR - T / 2)])
    ix1 = ix0 + T

    # d bowl; its stem is the second line dropping to the floor
    dx0 = ix1 + gap
    dw = 52.0
    d_bowl = bowl(dx0, ly0, dx0 + dw, FLOOR)
    stem_x = dx0 + dw - T / 2

    shapes = []
    # radical + line over the o, one filleted stroke, round end over the o's right edge
    pts1 = rad + [(ox1 - T / 2, 0.0)]
    s1 = stroke(pts1, radii_rad + [r_top])
    P = rad[-1]
    t = bisector(rad[-2], P, pts1[-1])
    white1 = s1.intersection(halfplane(P, t, before=True))
    bar1 = s1.difference(halfplane(P, t, before=True))
    xm = (top_x + ox1) / 2
    shapes += to_shapes(WHITE, white1)
    shapes += to_shapes(LAP[0], bar1.intersection(xband(-1e4, xm)))
    shapes += to_shapes(LAP[1], bar1.intersection(xband(xm, 1e4)))

    # the dot of the i: a wider pill in the bar line, centred over the stem
    shapes += to_shapes(LAP[2], pill(ic, 0.0, dot_w, T))

    # line over the d, filleted into the d's stem, round ends
    pts2 = [(dx0 + T / 2, 0.0), (stem_x, 0.0), (stem_x, FLOOR - T / 2)]
    s2 = stroke(pts2, [0, r_d])
    Q = pts2[1]
    t2 = bisector(pts2[0], Q, pts2[2])
    top2 = s2.intersection(halfplane(Q, t2, before=True))
    stem2 = s2.difference(halfplane(Q, t2, before=True))
    xa, xb = dx0 + (stem_x - dx0) / 3, dx0 + 2 * (stem_x - dx0) / 3
    shapes += to_shapes(LAP[3], top2.intersection(xband(-1e4, xa)))
    shapes += to_shapes(LAP[4], top2.intersection(xband(xa, xb)))
    shapes += to_shapes(LAP[5], top2.intersection(xband(xb, 1e4)))
    shapes += to_shapes(WHITE, stem2)

    for geom in (o, i_stem, d_bowl):
        shapes += to_shapes(WHITE, geom)

    # report the gaps either side of the dot (centre line of the bar)
    gl = (ic - dot_w / 2) - ox1
    gr = dx0 - (ic + dot_w / 2)
    print(f"dot {dot_w:.1f} x {T:.1f}; gap left {gl:.2f}, gap right {gr:.2f}; o clearance {o.distance(up):.2f}")
    return shapes


if __name__ == "__main__":
    out = Path(__file__).resolve().parent.parent / "docs/logo/concepts"
    f = root_oid_soft()
    (out / "f-root-oid-soft.svg").write_text(g.svg(f, 1024, 1024, bg=BG, margin=0.12))
    g.render(f, 1024, 1024, bg=BG, margin=0.12).save(out / "f-root-oid-soft.png")
    sheet(f, "F  ROOT OID, SOFTENED", out / "f-root-oid-soft-preview.png", boot(word=f), footer="DRAFT  ROUND 4")
    print("wrote f-root-oid-soft.svg/.png/-preview.png")

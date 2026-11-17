#!/usr/bin/env python3
"""
Square-root wordmark, round 5: round 4 with two spacing fixes from Andy.

  * the radical sat high: rounding the V's point lifted its bottom about
    7 units above the floor the o, i and d stand on. The V's point is now
    moved down until the outside of the rounded point touches the floor.
    The hook and the top corner stay put, so the radical still runs
    straight into the bar.
  * the V-to-o space looked tighter than the o-to-i space. The o is now
    placed so the open space between the up stroke and the o has the same
    mean width, across the x-height, as the space between the o and the i.

    python3 tools/logo_root_v5.py      # writes docs/logo/concepts/g-*
"""
import sys
from pathlib import Path

from shapely.geometry import LineString, box

sys.path.insert(0, str(Path(__file__).resolve().parent))
import vgeom as g  # noqa: E402
from logo_concepts import BG, WHITE, LAP  # noqa: E402
from logo_root_v2 import T, B, FLOOR, boot, sheet  # noqa: E402
from logo_root_v4 import stroke, halfplane, xband, bisector, bowl, pill, to_shapes  # noqa: E402


def sit_on_floor(top_x=62.0):
    """Radical centre line with the V's point lowered so the rounded point's
    outside edge lands on FLOOR."""
    vy = B
    for _ in range(60):
        pts = [(-6.0, 66.0), (10.0, 53.0), (34.0, vy), (top_x, 0.0)]
        err = FLOOR - stroke(pts, [0, 6.0, 4.0]).bounds[3]
        vy += err
        if abs(err) < 1e-5:
            break
    return pts


def mean_gap(left, right, y0, n=240):
    """Mean horizontal width of the space between two glyphs over y0..FLOOR."""
    tot, k = 0.0, 0
    for j in range(n):
        y = y0 + (FLOOR - y0) * (j + 0.5) / n
        L = LineString([(-1e3, y), (1e4, y)])
        a, b = L.intersection(left), L.intersection(right)
        if a.is_empty or b.is_empty:
            continue
        tot += b.bounds[0] - a.bounds[2]
        k += 1
    return tot / k


def root_oid_aligned(dot_w=1.8 * T, gap=13.0):
    top_x = 62.0
    rad = sit_on_floor(top_x)
    xh = 58.0
    ly0 = FLOOR - xh
    radii_rad = [0, 6.0, 4.0]          # hook, V point
    r_top = 9.0                        # radical into bar
    r_d = 9.0                          # bar into d stem

    # place the o so the space between the up stroke and the o has the same
    # mean width over the x-height as the space between the o and the i
    ow = 54.0
    up = stroke(rad, radii_rad)
    target = mean_gap(bowl(0, ly0, ow, FLOOR), box(ow + gap, ly0, ow + gap + T, FLOOR), ly0)
    lo, hi = 40.0, 80.0
    for _ in range(40):
        ox0 = (lo + hi) / 2
        if mean_gap(up, bowl(ox0, ly0, ox0 + ow, FLOOR), ly0) < target:
            lo = ox0
        else:
            hi = ox0
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
    print(f"dot {dot_w:.1f} x {T:.1f}; gap left {gl:.2f}, gap right {gr:.2f}; o clearance {o.distance(up):.2f}; mean gaps V-o {mean_gap(up, o, ly0):.2f}, o-i {target:.2f}; radical bottom {up.bounds[3]:.2f}, floor {FLOOR:.2f}")
    return shapes


if __name__ == "__main__":
    out = Path(__file__).resolve().parent.parent / "docs/logo/concepts"
    f = root_oid_aligned()
    (out / "g-root-oid-aligned.svg").write_text(g.svg(f, 1024, 1024, bg=BG, margin=0.12))
    g.render(f, 1024, 1024, bg=BG, margin=0.12).save(out / "g-root-oid-aligned.png")
    sheet(f, "G  ROOT OID, ALIGNED", out / "g-root-oid-aligned-preview.png", boot(word=f), footer="DRAFT  ROUND 5")
    print("wrote g-root-oid-aligned.svg/.png/-preview.png")

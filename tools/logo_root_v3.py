#!/usr/bin/env python3
"""
Square-root wordmark, round 3: D (root oid) with the bar broken into
line, dot, line, following Andy's sketch.

  * the radical's up stroke runs into a line over the o
  * the dot of the i sits in the bar itself, between the two lines
  * the second line runs over the d and drops into its stem
  * the gaps either side of the dot match the letter spacing, so the first
    line ends over the o's right edge and the second starts over the d's left

Everything else (stroke weight, radical, letters, colours) is D unchanged.

    python3 tools/logo_root_v3.py      # writes docs/logo/concepts/e-*
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import vgeom as g  # noqa: E402
from logo_concepts import BG, WHITE, LAP, clip  # noqa: E402
from logo_root_v2 import T, B, FLOOR, radical, up_edge_x, ring_oct, boot, sheet  # noqa: E402


def root_oid_dot():
    top_x = 62.0
    rad = radical(top_x)
    xh = 58.0
    ly0 = FLOOR - xh
    c = xh * 0.28
    gap = 12.0
    letters = []
    # o
    ox0 = up_edge_x(ly0 + c, top_x) + gap * 0.8
    ow = 54.0
    ox1 = ox0 + ow
    letters.append((WHITE, ring_oct(ox0, ly0, ox1, FLOOR, c)))
    # i stem; its dot is in the bar
    ix0 = ox1 + gap
    ix1 = ix0 + T
    letters.append((WHITE, [[(ix0, ly0), (ix1, ly0), (ix1, FLOOR), (ix0, FLOOR)]]))
    # d bowl; its stem is the second line dropping to the floor
    dx0 = ix1 + gap
    dw = 52.0
    letters.append((WHITE, ring_oct(dx0, ly0, dx0 + dw, FLOOR, c)))
    stem_x = dx0 + dw - T / 2

    shapes = []
    # radical + line over the o: one mitred stroke, flat end over the o's right edge
    line1 = [(top_x + (ox1 - top_x) * k / 2, 0.0) for k in (1, 2)]
    q1 = g.stroke_polyline(rad + line1, T)
    nr = len(rad) - 1
    rad_poly = [q1[0][0]] + [q[1] for q in q1[:nr]] + [q[2] for q in q1[:nr]][::-1] + [q1[0][3]]
    shapes.append((WHITE, [clip(rad_poly, 0, 1, FLOOR)]))
    shapes.append((LAP[0], [q1[nr]]))
    shapes.append((LAP[1], [q1[nr + 1]]))
    # the dot of the i, square, in the bar line
    h = T / 2
    shapes.append((LAP[2], [[(ix0, -h), (ix1, -h), (ix1, h), (ix0, h)]]))
    # line over the d, dropping into the d's stem
    xs = [dx0 + (stem_x - dx0) * k / 3 for k in range(4)]
    q2 = g.stroke_polyline([(x, 0.0) for x in xs] + [(stem_x, B)], T)
    for i in range(3):
        shapes.append((LAP[3 + i], [q2[i]]))
    shapes.append((WHITE, [clip(q2[3], 0, 1, FLOOR)]))
    return shapes + letters


if __name__ == "__main__":
    out = Path(__file__).resolve().parent.parent / "docs/logo/concepts"
    e = root_oid_dot()
    (out / "e-root-oid-dot.svg").write_text(g.svg(e, 1024, 1024, bg=BG, margin=0.12))
    g.render(e, 1024, 1024, bg=BG, margin=0.12).save(out / "e-root-oid-dot.png")
    sheet(e, "E  ROOT OID, LINE DOT LINE", out / "e-root-oid-dot-preview.png", boot(word=e), footer="DRAFT  ROUND 3")
    print("wrote e-root-oid-dot.svg/.png/-preview.png")

#!/usr/bin/env python3
"""
Square-root logo, second round (after round one read as the VO5 hair-care logo).

What changed from b-root-vos:
  * no "OS" letters inside the mark: an angular S under a flat bar reads as a 5
  * a real radical: a small hook on the left before the long down stroke, so the
    first stroke reads as a square-root sign, not as a plain V
  * monoline: every stroke one weight, sharp mitred corners, flat cut ends
  * the bar ends in a short drop (C) or runs straight into the d (D)

  C  root void   the radical over an empty octagon (the void; the same octagon as
                 the hub clock). Square, for the app icon and the boot screen.
  D  root oid    the radical is the V of "void": "oid" sits under the bar and the
                 bar drops into the stem of the d. Wide, for the wordmark.

    python3 tools/logo_root_v2.py      # writes docs/logo/concepts/
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import vgeom as g  # noqa: E402
from logo_concepts import BG, PANEL, WHITE, MUTED, LAP, clip  # noqa: E402

T = 11.0          # the one stroke weight
B = 100.0         # baseline: the radical's vertex centre and the letters' floor
FLOOR = B + T / 2


def radical(top_x=62.0):
    """Hook, down stroke, up stroke. Returns the centre-line points."""
    return [(-6.0, 66.0), (10.0, 53.0), (34.0, B), (top_x, 0.0)]


def up_edge_x(y, top_x=62.0):
    """Right edge of the up stroke at height y."""
    x = 34.0 + (top_x - 34.0) * (1 - y / B)
    return x + T / 2 * 1.04


def bar_segments(x0, x1, n=6):
    return [(x0 + (x1 - x0) * i / n, 0.0) for i in range(1, n + 1)]


def split_stroke(rad, bar_pts, tail=None):
    """One mitred stroke through radical, bar and optional tail; radical and
    tail white, the bar in the six VoidOS colours."""
    pts = rad + bar_pts + ([tail] if tail else [])
    quads = g.stroke_polyline(pts, T)
    nr = len(rad) - 1           # radical segments
    nb = len(bar_pts)           # bar segments
    shapes = []
    rad_poly = [quads[0][0]] + [q[1] for q in quads[:nr]] + [q[2] for q in quads[:nr]][::-1] + [quads[0][3]]
    shapes.append((WHITE, [clip(rad_poly, 0, 1, FLOOR)]))
    for i, q in enumerate(quads[nr:nr + nb]):
        shapes.append((LAP[i % 6], [q]))
    for q in quads[nr + nb:]:
        shapes.append((WHITE, [clip(q, 0, 1, FLOOR)]))
    return shapes


def ring_oct(x0, y0, x1, y1, c):
    k = T * 0.4142
    return [g.octagon(x0, y0, x1, y1, c), g.octagon(x0 + T, y0 + T, x1 - T, y1 - T, max(0.0, c - k))]


# ------------------------------------------------------------- C root void

def root_void():
    top_x = 62.0
    rad = radical(top_x)
    gap = 13.0
    # the void: a ring octagon, centred in the space under the bar
    side = 56.0
    y0 = (T / 2 + FLOOR) / 2 - side / 2 + 6
    y1 = y0 + side
    x0 = up_edge_x(y0 + side * 0.29, top_x) + gap
    x1 = x0 + side
    end = x1 + gap + T / 2
    tail = (end, 30.0)
    shapes = split_stroke(rad, bar_segments(top_x, end), tail)
    shapes.append((WHITE, ring_oct(x0, y0, x1, y1, side * 0.29)))
    return shapes


# ------------------------------------------------------------- D root oid

def root_oid():
    top_x = 62.0
    rad = radical(top_x)
    xh = 58.0                    # x-height
    ly0 = FLOOR - xh
    c = xh * 0.28
    gap = 12.0
    shapes = []
    # o
    ox0 = up_edge_x(ly0 + c, top_x) + gap * 0.8
    ow = 54.0
    shapes.append((WHITE, ring_oct(ox0, ly0, ox0 + ow, FLOOR, c)))
    # i: stem plus a diamond dot
    ix0 = ox0 + ow + gap
    shapes.append((WHITE, [[(ix0, ly0), (ix0 + T, ly0), (ix0 + T, FLOOR), (ix0, FLOOR)]]))
    dc, dy, r = ix0 + T / 2, (T / 2 + ly0) / 2, T * 0.62
    shapes.append((LAP[2], [[(dc, dy - r), (dc + r, dy), (dc, dy + r), (dc - r, dy)]]))
    # d: octagon bowl; its stem is the bar dropping to the floor
    dx0 = ix0 + T + gap
    dw = 52.0
    shapes.append((WHITE, ring_oct(dx0, ly0, dx0 + dw, FLOOR, c)))
    stem_x = dx0 + dw - T / 2
    shapes = split_stroke(rad, bar_segments(top_x, stem_x), (stem_x, B)) + shapes
    return shapes


# ------------------------------------------------------------- previews

def font(px):
    from PIL import ImageFont
    p = Path(__file__).resolve().parent.parent / "app/src/main/res/font/kenney_future.ttf"
    try:
        return ImageFont.truetype(str(p), px)
    except OSError:
        return ImageFont.load_default()


def boot(icon=None, word=None):
    """800x1280 portrait boot screen: the symbol with VOIDOS set under it, or
    the wordmark alone."""
    from PIL import Image, ImageDraw
    por = Image.new("RGB", (800, 1280), BG)
    if icon:
        m = g.render(icon, 800, 1280, bg=None, margin=0.0, box=(230, 440, 570, 760))
        por.paste(m, (0, 0), m)
        d = ImageDraw.Draw(por)
        f = font(34)
        txt = "V O I D O S"
        tw = d.textlength(txt, font=f)
        d.text(((800 - tw) / 2, 820), txt, font=f, fill=MUTED)
    if word:
        m = g.render(word, 800, 1280, bg=None, margin=0.0, box=(120, 520, 680, 760))
        por.paste(m, (0, 0), m)
    return por


def sheet(shapes, label, path, por, icon=None, w=1200, h=900, footer="DRAFT  ROUND 2"):
    from PIL import Image, ImageDraw
    img = Image.new("RGB", (w, h), BG)
    big = g.render(shapes, 760, 560, bg=BG, margin=0.12)
    img.paste(big, (40, 70))
    d = ImageDraw.Draw(img)
    f, fs = font(26), font(15)
    d.text((48, 24), label, font=f, fill=WHITE)
    x, y = 860, 70
    for px in (192, 96, 48):
        tile = Image.new("RGB", (px, px), PANEL)
        m = Image.new("L", (px * 4, px * 4), 0)
        ImageDraw.Draw(m).ellipse([0, 0, px * 4 - 1, px * 4 - 1], fill=255)
        m = m.resize((px, px), Image.LANCZOS)
        mark = g.render(icon or shapes, px, px, bg=None, margin=0.2)
        tile.paste(mark, (0, 0), mark)
        img.paste(tile, (x + (192 - px) // 2, y), m)
        d.text((x + 192 + 14, y + px // 2 - 8), f"{px}px", font=fs, fill=MUTED)
        y += px + 30
    if icon:
        d.text((x, y - 12), "icon: the C symbol", font=fs, fill=MUTED)
    small = por.resize((200, 320), Image.LANCZOS)
    d.rectangle([w - 261, h - 341, w - 60, h - 20], outline=(42, 51, 68))
    img.paste(small, (w - 260, h - 340))
    d.text((w - 260, h - 362), "800x1280 boot", font=fs, fill=MUTED)
    d.text((48, h - 50), footer, font=fs, fill=MUTED)
    img.save(path)


if __name__ == "__main__":
    out = Path(__file__).resolve().parent.parent / "docs/logo/concepts"
    out.mkdir(parents=True, exist_ok=True)
    c, dd = root_void(), root_oid()
    for name, shapes in [("c-root-void", c), ("d-root-oid", dd)]:
        (out / f"{name}.svg").write_text(g.svg(shapes, 1024, 1024, bg=BG, margin=0.12))
        g.render(shapes, 1024, 1024, bg=BG, margin=0.12).save(out / f"{name}.png")
    sheet(c, "C  ROOT VOID", out / "c-root-void-preview.png", boot(icon=c))
    sheet(dd, "D  ROOT OID", out / "d-root-oid-preview.png", boot(word=dd), icon=c)
    print("wrote", ", ".join(sorted(p.name for p in out.iterdir() if p.name[0] in "cd")))

#!/usr/bin/env python3
"""
Builds every copy of the VoidOS logo from the one approved drawing: the
square-root wordmark (tools/logo_root_v5.py, round 5 "aligned"). The square
root sign is the V of "void", "oid" sits under a broken bar in the six lap
colours, the i's dot is the teal pill in the bar, and the bar drops into the
d's stem.

Two forms:
  wordmark  the whole of "void", for the boot screens, the start animation
            and the lockup
  mark      the square root and the o under its line, cut from the wordmark,
            for places too small for the word: the launcher icon (and its
            monochrome themed version), the rail, the lock screen

Outputs (all generated, do not edit by hand):
  tools/voidmark_geometry.json                  read by voidmark.py (Pillow tools)
  app/.../ui/LogoData.kt                        read by ui/Logo.kt (Compose)
  app/src/main/res/drawable/ic_launcher_fg.xml  adaptive icon foreground
  app/src/main/res/drawable/ic_launcher_mono.xml themed (monochrome) icon
  docs/logo/*                                   SVG and PNG exports

Needs shapely (only this script; the tools that read the JSON don't).

    python3 tools/logo_build.py
"""
import contextlib
import io
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from shapely.geometry import Polygon  # noqa: E402
from shapely.ops import unary_union  # noqa: E402
import shapely  # noqa: E402

import vgeom as g  # noqa: E402
from logo_concepts import WHITE, LAP  # noqa: E402
import logo_root_v5 as v5  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
KT = ROOT / "app/src/main/java/com/andysocial/voidos/ui/LogoData.kt"
JSON = ROOT / "tools/voidmark_geometry.json"
DOCS = ROOT / "docs/logo"
TOL = 0.04  # simplification tolerance, in drawing units (the stroke is 11)
ICON_TOL = 0.12  # in launcher icon units (the icon is 108, the stroke about 4.5)


def colour_index(c):
    c = tuple(c[:3])
    if c == tuple(WHITE):
        return -1
    return [tuple(x) for x in LAP].index(c)


def approved():
    with contextlib.redirect_stdout(io.StringIO()):
        return v5.root_oid_aligned()


def simplify(rings):
    p = Polygon(rings[0], rings[1:]).simplify(TOL, preserve_topology=True)
    return [list(p.exterior.coords)[:-1]] + [list(h.coords)[:-1] for h in p.interiors]


def build_geometry():
    raw = approved()
    shapes = []
    for col, rings in raw:
        shapes.append({"c": colour_index(col), "rings": simplify(rings)})
    # normalise: top-left of the whole word at 0,0, rounded
    x0, y0, x1, y1 = g.bounds([(None, s["rings"]) for s in shapes])
    for s in shapes:
        s["rings"] = [[(round(x - x0, 2), round(y - y0, 2)) for x, y in r] for r in s["rings"]]
    W, H = round(x1 - x0, 2), round(y1 - y0, 2)
    # the o: the leftmost white shape with a hole; the mark is everything up to its right edge
    bowls = sorted((s for s in shapes if s["c"] == -1 and len(s["rings"]) > 1),
                   key=lambda s: min(x for x, _ in s["rings"][0]))
    o_right = max(x for x, _ in bowls[0]["rings"][0])
    for s in shapes:
        xs = [x for x, _ in s["rings"][0]]
        s["mark"] = max(xs) <= o_right + 0.5
        s["dot"] = s["c"] == 2
    mark = [s for s in shapes if s["mark"]]
    mx0, my0, mx1, my1 = g.bounds([(None, s["rings"]) for s in mark])
    # smallest circle round the mark, for the round launcher mask
    u = unary_union([Polygon(s["rings"][0]) for s in mark])
    circ = shapely.minimum_bounding_circle(u)
    cx, cy = circ.centroid.x, circ.centroid.y
    cr = max(((x - cx) ** 2 + (y - cy) ** 2) ** 0.5 for s in mark for x, y in s["rings"][0])
    dots = [s for s in shapes if s["dot"]]
    dx0, dy0, dx1, dy1 = g.bounds([(None, s["rings"]) for s in dots])
    # how far the dot travels in the loading animation: into the purple bar on
    # its left and the green bar on its right (as tools/logo_loading_anim.py)
    pur_r = max(x for s in shapes if s["c"] == 1 for x, _ in s["rings"][0])
    grn_l = min(x for s in shapes if s["c"] == 3 for x, _ in s["rings"][0])
    reach_l, reach_r = dx0 - pur_r + 1.5, grn_l - dx1 + 1.5
    return {
        "source": "tools/logo_root_v5.py root_oid_aligned (round 5)",
        "word": {"w": W, "h": H},
        "origin": [round(x0, 4), round(y0, 4)],  # where the word's 0,0 is in logo_root_v5 units
        "mark": {"x0": round(mx0, 2), "y0": round(my0, 2), "x1": round(mx1, 2), "y1": round(my1, 2),
                 "cx": round(cx, 2), "cy": round(cy, 2), "r": round(cr, 2)},
        "dot": {"cx": round((dx0 + dx1) / 2, 2), "cy": round((dy0 + dy1) / 2, 2),
                "reach_l": round(reach_l, 2), "reach_r": round(reach_r, 2)},
        "shapes": shapes,
    }


def rgb(i):
    return WHITE if i == -1 else LAP[i]


def as_vg(shapes, only_mark=False):
    return [(rgb(s["c"]), s["rings"]) for s in shapes if s["mark"] or not only_mark]


def path_d(rings, s=1.0, dx=0.0, dy=0.0, fmt="%.2f"):
    return " ".join("M" + " L".join((fmt + "," + fmt) % (x * s + dx, y * s + dy) for x, y in r) + " Z" for r in rings)


def write_kotlin(geo):
    w, m, d = geo["word"], geo["mark"], geo["dot"]
    rows = []
    for s in geo["shapes"]:
        flags = (1 if s["mark"] else 0) | (2 if s["dot"] else 0)
        rows.append(f'        Triple({s["c"]}, {flags}, "{path_d(s["rings"])}"),')
    KT.write_text(f"""package com.andysocial.voidos.ui

// GENERATED by tools/logo_build.py from the approved logo ({geo['source']}).
// Do not edit: change the drawing and run `python3 tools/logo_build.py`.

internal object LogoData {{
    const val WORD_W = {w['w']}f
    const val WORD_H = {w['h']}f
    const val MARK_X0 = {m['x0']}f
    const val MARK_Y0 = {m['y0']}f
    const val MARK_X1 = {m['x1']}f
    const val MARK_Y1 = {m['y1']}f
    const val DOT_CX = {d['cx']}f
    const val DOT_CY = {d['cy']}f
    const val DOT_REACH_L = {d['reach_l']}f
    const val DOT_REACH_R = {d['reach_r']}f

    /** colour (-1 white, 0..5 = V.Lap), flags (1 = part of the mark, 2 = the i's dot), even-odd path */
    val shapes = listOf(
{chr(10).join(rows)}
    )
}}
""")


def write_icons(geo):
    (RES / "mipmap-anydpi").mkdir(exist_ok=True)
    """Adaptive icon: 108 x 108 viewport, the mark inside the 66-unit safe circle."""
    m = geo["mark"]
    s = 30.0 / m["r"]
    dx, dy = 54 - m["cx"] * s, 54 - m["cy"] * s

    def icon_rings(rings):
        # coarser than the app's copy: at 108 units a tenth of a unit is plenty,
        # and short paths keep the launcher quick (lint VectorPath: under 800 chars)
        p = Polygon(rings[0], rings[1:]).simplify(ICON_TOL / s, preserve_topology=True)
        return [list(p.exterior.coords)[:-1]] + [list(h.coords)[:-1] for h in p.interiors]

    def vec(mono):
        out = []
        for sh in geo["shapes"]:
            if not sh["mark"]:
                continue
            col = "#FFFFFFFF" if mono else "#FF%02X%02X%02X" % tuple(rgb(sh["c"]))
            d = path_d(icon_rings(sh["rings"]), s, dx, dy, fmt="%.1f").replace(".0,", ",").replace(".0 ", " ")
            assert len(d) < 800, f"icon path {len(d)} chars"
            out.append(f'    <path android:fillColor="{col}" android:fillType="evenOdd"\n'
                       f'        android:pathData="{d}" />')
        what = "monochrome (themed) icon: one colour, Android tints it" if mono else "launcher icon foreground"
        return ('<?xml version="1.0" encoding="utf-8"?>\n'
                f'<!-- GENERATED by tools/logo_build.py: the VoidOS mark, {what}. -->\n'
                '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    android:width="108dp" android:height="108dp"\n'
                '    android:viewportWidth="108" android:viewportHeight="108">\n'
                + "\n".join(out) + "\n</vector>\n")

    (RES / "drawable/ic_launcher_fg.xml").write_text(vec(False))
    (RES / "drawable/ic_launcher_mono.xml").write_text(vec(True))
    (RES / "mipmap-anydpi/ic_launcher.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/void_bg" />\n'
        '    <foreground android:drawable="@drawable/ic_launcher_fg" />\n'
        '    <monochrome android:drawable="@drawable/ic_launcher_mono" />\n'
        '</adaptive-icon>\n')


def write_docs(geo):
    import voidmark as vm  # reads the JSON just written
    from PIL import Image, ImageDraw
    DOCS.mkdir(parents=True, exist_ok=True)
    word, mark = as_vg(geo["shapes"]), as_vg(geo["shapes"], only_mark=True)
    (DOCS / "voidos-wordmark.svg").write_text(g.svg(word, 1200, 560, margin=0.06))
    (DOCS / "voidos-mark.svg").write_text(g.svg(mark, 512, 512, margin=0.08))
    g.render(word, 2400, 1120, margin=0.06).save(DOCS / "voidos-wordmark-2400.png")
    g.render(mark, 512, 512, margin=0.08).save(DOCS / "voidos-mark-512.png")
    g.render(mark, 1024, 1024, margin=0.08).save(DOCS / "voidos-mark-1024.png")
    # lockup: the wordmark with TAB EDITION, on the void, like the boot screen
    ss = 2
    W = H = 1200 * ss
    img = Image.new("RGB", (W, H), vm.BG)
    d = ImageDraw.Draw(img)
    vm.draw_lockup(d, W, H)
    img.resize((1200, 1200), Image.LANCZOS).save(DOCS / "voidos-lockup.png")
    # launcher icon as Android shows it, round mask, for the README
    ic = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    tile = Image.new("RGB", (432, 432), vm.BG)
    m = geo["mark"]
    s = 30.0 / m["r"] * 4
    sh = [(c, [[(x * s + 216 - m["cx"] * s, y * s + 216 - m["cy"] * s) for x, y in r] for r in rings]) for c, rings in mark]
    big = Image.new("RGB", (432 * 4, 432 * 4), vm.BG)
    g.draw(big, [(c, [[(x * 4, y * 4) for x, y in r] for r in rings]) for c, rings in sh])
    tile = big.resize((432, 432), Image.LANCZOS)
    mask = Image.new("L", (432 * 4, 432 * 4), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, 432 * 4 - 1, 432 * 4 - 1], fill=255)
    ic.paste(tile, (0, 0), mask.resize((432, 432), Image.LANCZOS))
    ic.save(DOCS / "voidos-launcher-icon.png")


if __name__ == "__main__":
    geo = build_geometry()
    JSON.write_text(json.dumps(geo, separators=(",", ":")))
    write_kotlin(geo)
    write_icons(geo)
    write_docs(geo)
    n = len(geo["shapes"])
    pts = sum(len(r) for s in geo["shapes"] for r in s["rings"])
    print(f"{n} shapes, {pts} points; word {geo['word']['w']} x {geo['word']['h']}; mark {geo['mark']}")
    print("wrote", JSON.name, KT.name, "ic_launcher_fg.xml, ic_launcher_mono.xml, docs/logo/*")

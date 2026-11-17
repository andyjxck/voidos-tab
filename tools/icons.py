#!/usr/bin/env python3
"""VoidOS icon set.

Every icon is drawn on a 24 x 24 grid in the PineTime VoidOS style: 45-degree
chamfers instead of rounded corners, octagons instead of circles, one 1.8
stroke weight, and a translucent "second tone" fill (alpha 0.35) so a single
tint still gives two tones. Writes app/src/main/res/drawable/vi_*.xml.

Run: python3 tools/icons.py
"""
import math, os

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "drawable")
SW = 1.8


def f(v):
    s = ("%.2f" % v).rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def octagon(cx, cy, r, rot=22.5):
    pts = []
    for k in range(8):
        a = math.radians(rot + 45 * k)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return "M" + " L".join(f(x) + " " + f(y) for x, y in pts) + " Z"


def crect(x, y, w, h, c):
    """Rectangle with all four corners cut at 45 degrees."""
    return ("M%s %s H%s L%s %s V%s L%s %s H%s L%s %s V%s Z" % tuple(map(f, (
        x + c, y, x + w - c, x + w, y + c, y + h - c, x + w - c, y + h, x + c, x, y + h - c, y + c))))


CLOUD = "M6.5 18.5 H17.5 L20.5 15.5 V14 L18 11.5 H17 L14.5 8 H10.5 L8 11 H7 L3.5 14.5 V15.5 Z"
CLOUD_HI = "M6.5 15.5 H17.5 L20.5 12.5 V11 L18 8.5 H17 L14.5 5 H10.5 L8 8 H7 L3.5 11.5 V12.5 Z"

# each icon: list of (kind, path) where kind is s = stroke, f = solid fill, t = second-tone fill
ICONS = {
    "face": [("t", octagon(12, 12, 9.2)), ("s", octagon(12, 12, 9.2)), ("s", "M12 12 V6.8 M12 12 L15.6 14"), ("f", octagon(12, 12, 1.4))],
    "weather": [("t", octagon(8.5, 8.5, 4)), ("s", "M8.5 2 V3 M2 8.5 H3 M3.9 3.9 L4.6 4.6 M13.1 3.9 L12.4 4.6"),
                ("s", "M8 20 H17.5 L20.5 17 V15.5 L18 13 H17 L15 10 H11.5 L9.5 12.5 H8.5 L5 16 V17 Z")],
    "notes": [("s", "M6 3 H14.5 L19 7.5 V19.5 L17.5 21 H6.5 L5 19.5 V4 Z"), ("t", "M14.5 3 V7.5 H19 Z"), ("s", "M8.5 11.5 H15.5 M8.5 15 H15.5 M8.5 18 H12.5")],
    "media": [("f", crect(3.5, 10, 3, 10, 1)), ("t", crect(8, 4, 3, 16, 1)), ("f", crect(12.5, 7.5, 3, 12.5, 1)), ("t", crect(17, 12.5, 3, 7.5, 1))],
    "focus": [("s", "M6.5 3 H17.5 M6.5 21 H17.5 M8 3 V6.5 L12 12 L8 17.5 V21 M16 3 V6.5 L12 12 L16 17.5 V21"), ("t", "M9 21 V18.5 L12 15 L15 18.5 V21 Z")],
    "house": [("s", "M3.5 11 L12 3.8 L20.5 11 M5.5 9.5 V19.5 L7 21 H17 L18.5 19.5 V9.5"), ("t", crect(9.8, 14, 4.4, 7, 1))],
    "apps": [("s", octagon(7.5, 7.5, 3.6)), ("t", octagon(16.5, 7.5, 3.6)), ("s", octagon(16.5, 7.5, 3.6)), ("s", octagon(7.5, 16.5, 3.6)), ("f", octagon(16.5, 16.5, 3.6))],
    "system": [("s", octagon(12, 12, 7, 0)), ("t", octagon(12, 12, 3)), ("s", octagon(12, 12, 3)),
               ("s", "M12 2 V5 M12 19 V22 M2 12 H5 M19 12 H22 M4.9 4.9 L7 7 M17 17 L19.1 19.1 M4.9 19.1 L7 17 M17 7 L19.1 4.9")],
    "kitchen": [("s", "M3 10 H21 M5 10 V17 L8 20.5 H16 L19 17 V10 M10 7 H14"), ("t", "M5.9 13.5 H18.1 V16.6 L15.5 19.6 H8.5 L5.9 16.6 Z"), ("s", "M9 4.5 V3.5 M12 4.5 V2.5 M15 4.5 V3.5")],
    "timer": [("t", octagon(12, 13.5, 7.6)), ("s", octagon(12, 13.5, 7.6)), ("s", "M9.5 2.8 H14.5 M12 2.8 V5.9 M12 13.5 V9.5 M18.4 6.6 L19.8 5.2"), ("f", octagon(12, 13.5, 1.3))],
    "list": [("f", crect(3.5, 4, 4, 4, 1)), ("s", crect(3.5, 10, 4, 4, 1)), ("s", crect(3.5, 16, 4, 4, 1)), ("s", "M10.5 6 H20.5 M10.5 12 H20.5 M10.5 18 H17")],
    "convert": [("s", "M4 8 H19 M15.5 4.5 L19 8 L15.5 11.5 M20 16 H5 M8.5 12.5 L5 16 L8.5 19.5")],
    "wifi": [("s", "M2.5 9.5 L7 6 H17 L21.5 9.5 M6 13 L9 11 H15 L18 13 M9.5 16.5 L11 15.5 H13 L14.5 16.5"), ("f", octagon(12, 19.6, 1.5))],
    "bt": [("s", "M6.5 7.5 L17 16.5 L12 21 V3 L17 7.5 L6.5 16.5")],
    "sun": [("t", octagon(12, 12, 4.6)), ("s", octagon(12, 12, 4.6)),
             ("s", "M12 2 V4 M12 20 V22 M2 12 H4 M20 12 H22 M4.9 4.9 L6.3 6.3 M17.7 17.7 L19.1 19.1 M4.9 19.1 L6.3 17.7 M17.7 6.3 L19.1 4.9")],
    "moon": [("t", "M14.5 3.5 A8.5 8.5 0 1 0 20.5 15.5 A7 7 0 0 1 14.5 3.5 Z"), ("s", "M14.5 3.5 A8.5 8.5 0 1 0 20.5 15.5 A7 7 0 0 1 14.5 3.5 Z")],
    "cloud": [("t", CLOUD), ("s", CLOUD)],
    "rain": [("s", CLOUD_HI), ("s", "M8 18.5 L7 21 M12 18.5 L11 21 M16 18.5 L15 21")],
    "snow": [("s", CLOUD_HI), ("f", octagon(8, 19.5, 1.1)), ("f", octagon(12, 21, 1.1)), ("f", octagon(16, 19.5, 1.1))],
    "storm": [("s", CLOUD_HI), ("f", "M12.5 16 L9.5 20 H12 L11 23 L15 18.5 H12.5 L13.5 16 Z")],
    "fog": [("s", "M3 8 H17 M7 12 H21 M3 16 H17 M8 20 H16")],
    "play": [("f", "M7 4.5 L19.5 12 L7 19.5 Z")],
    "pause": [("f", crect(5.5, 4.5, 4.5, 15, 1.2)), ("f", crect(14, 4.5, 4.5, 15, 1.2))],
    "next": [("f", "M5 5 L15 12 L5 19 Z"), ("f", crect(16, 5, 3, 14, 1))],
    "prev": [("f", "M19 5 L9 12 L19 19 Z"), ("f", crect(5, 5, 3, 14, 1))],
    "plus": [("s", "M12 4.5 V19.5 M4.5 12 H19.5")],
    "minus": [("s", "M4.5 12 H19.5")],
    "back": [("s", "M15 4.5 L7.5 12 L15 19.5")],
    "chev": [("s", "M9 4.5 L16.5 12 L9 19.5")],
    "trash": [("s", "M3.5 6.5 H20.5 M9 6.5 V3.5 H15 V6.5 M5.5 6.5 L6.8 19.5 L8.3 21 H15.7 L17.2 19.5 L18.5 6.5"), ("s", "M10 10.5 V17 M14 10.5 V17")],
    "lock": [("s", "M7.5 10.5 V7 L10 4 H14 L16.5 7 V10.5"), ("t", crect(4.5, 10.5, 15, 10.5, 2)), ("s", crect(4.5, 10.5, 15, 10.5, 2)), ("s", "M12 14.5 V17")],
    "check": [("s", "M4.5 12.5 L9.5 17.5 L19.5 6.5")],
    "close": [("s", "M5.5 5.5 L18.5 18.5 M18.5 5.5 L5.5 18.5")],
    "search": [("t", octagon(10.5, 10.5, 6.5)), ("s", octagon(10.5, 10.5, 6.5)), ("s", "M15.5 15.5 L21 21")],
    "bell": [("s", "M4 17.5 H20 L18 15 V10 L15 6 H9 L6 10 V15 Z M10 20.5 H14 M12 3 V6"), ("t", "M7.2 15.5 H16.8 L16 14.4 V10.5 L14 7.8 H10 L8 10.5 V14.4 Z")],
    "volume": [("t", "M3.5 9 H7.5 L12.5 4.5 V19.5 L7.5 15 H3.5 Z"), ("s", "M3.5 9 H7.5 L12.5 4.5 V19.5 L7.5 15 H3.5 Z"), ("s", "M15.5 9 L16.5 10.5 V13.5 L15.5 15 M18.5 6 L20.5 9 V15 L18.5 18")],
    "battery": [("s", crect(2.5, 7, 17, 10, 1.5)), ("t", crect(4.5, 9, 9, 6, 0.8)), ("s", "M21.5 10.5 V13.5")],
    "display": [("s", crect(2.5, 4, 19, 13, 1.5)), ("t", crect(4.5, 6, 15, 9, 0.8)), ("s", "M12 17 V20.5 M7.5 20.5 H16.5")],
    "info": [("s", octagon(12, 12, 9.2)), ("s", "M12 11 V17"), ("f", octagon(12, 7.6, 1.2))],
    "rotate": [("s", "M19.5 12 V9 L15 4.5 H9 L6.5 7 M4.5 12 V15 L9 19.5 H15 L17.5 17"), ("s", "M3.5 4.5 L6.5 7 L9.5 4.5 M20.5 19.5 L17.5 17 L14.5 19.5")],
    "light": [("s", "M9 17 V15 L6 11.5 V7 L9 3.5 H15 L18 7 V11.5 L15 15 V17 Z M9.5 20.5 H14.5"), ("t", "M10.5 15.5 V14.3 L7.6 10.9 V7.6 L9.7 5.1 H14.3 L16.4 7.6 V10.9 L13.5 14.3 V15.5 Z")],
    "message": [("s", "M5 4 H19 L21 6 V14.5 L19 16.5 H11 L6.5 20.5 V16.5 H5 L3 14.5 V6 Z"), ("t", octagon(8, 10.3, 1.2)), ("t", octagon(12, 10.3, 1.2)), ("t", octagon(16, 10.3, 1.2))],
    "video": [("s", crect(2.5, 5, 19, 14, 2)), ("f", "M10 8.8 L15.5 12 L10 15.2 Z")],
    "power": [("s", "M12 3 V11 M7.5 6 L4.5 9.5 V14.5 L9 19.5 H15 L19.5 14.5 V9.5 L16.5 6")],
    "globe": [("s", octagon(12, 12, 9.2)), ("s", "M3 12 H21 M12 2.8 L8.5 7.5 V16.5 L12 21.2 M12 2.8 L15.5 7.5 V16.5 L12 21.2")],
    "camera": [("s", "M3 8.5 L4.5 7 H7.5 L9.5 4.5 H14.5 L16.5 7 H19.5 L21 8.5 V18 L19.5 19.5 H4.5 L3 18 Z"), ("t", octagon(12, 13, 3.6)), ("s", octagon(12, 13, 3.6))],
    "star": [("t", "M12 3 L14.6 9 L21 9.4 L16 13.6 L17.6 20 L12 16.5 L6.4 20 L8 13.6 L3 9.4 L9.4 9 Z"), ("s", "M12 3 L14.6 9 L21 9.4 L16 13.6 L17.6 20 L12 16.5 L6.4 20 L8 13.6 L3 9.4 L9.4 9 Z")],
    "user": [("s", octagon(12, 8, 4.2)), ("s", "M4 21 V18.5 L7.5 15 H16.5 L20 18.5 V21")],
    "shuffle": [("s", "M3 7 H7 L15 17 H20 M3 17 H7 L9.5 14 M13 10 L15 7 H20 M17.5 4.5 L20 7 L17.5 9.5 M17.5 14.5 L20 17 L17.5 19.5")],
    "phone": [("s", crect(6.5, 2.5, 11, 19, 2)), ("s", "M10.5 18.5 H13.5")],
    "star_off": [("s", "M12 3 L14.6 9 L21 9.4 L16 13.6 L17.6 20 L12 16.5 L6.4 20 L8 13.6 L3 9.4 L9.4 9 Z")],
    "cutlery": [("s", "M7 3 V9 L8.5 10.5 V21 M5 3 V8 L7 10 M10 3 V8 L8 10 M16 21 V3 L19 6.5 V12.5 L16 14")],
}


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, parts in ICONS.items():
        body = []
        for kind, d in parts:
            if kind == "s":
                body.append('    <path android:pathData="%s" android:strokeColor="#FFFFFFFF" android:strokeWidth="%s" '
                            'android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="#00000000"/>' % (d, SW))
            elif kind == "f":
                body.append('    <path android:pathData="%s" android:fillColor="#FFFFFFFF"/>' % d)
            else:
                body.append('    <path android:pathData="%s" android:fillColor="#59FFFFFF"/>' % d)
        xml = ('<?xml version="1.0" encoding="utf-8"?>\n<!-- VoidOS icon "%s". Generated by tools/icons.py, edit there. -->\n'
               '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" '
               'android:viewportWidth="24" android:viewportHeight="24">\n%s\n</vector>\n') % (name, "\n".join(body))
        with open(os.path.join(OUT, "vi_%s.xml" % name), "w") as fh:
            fh.write(xml)
    print("wrote %d icons" % len(ICONS))


if __name__ == "__main__":
    main()

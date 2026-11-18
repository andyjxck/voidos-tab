#!/usr/bin/env python3
"""Icons for the Notes drawing tools, from Google's Material Symbols (Rounded, weight 300).

Same source and style as tools/material_icons.py (it reuses that script's download and
conversion), written as res/drawable/vi_ink_*.xml so the drawing toolbar uses the same
icon set as the rest of VoidOS. Material Symbols are Apache 2.0 licensed (Google).

Run: python3 tools/notes_icons.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import material_icons as mi  # noqa: E402

# VoidOS name -> Material Symbol
ICONS = {
    "undo": "undo", "redo": "redo", "pen": "stylus", "highlighter": "ink_highlighter",
    "eraser": "ink_eraser", "brush": "tune", "clear": "delete_sweep", "free": "gesture",
    "line": "horizontal_rule", "arrow": "arrow_right_alt", "rect": "crop_square", "ellipse": "circle",
    "plain": "crop_portrait", "lined": "format_align_justify", "grid": "grid_on", "dots": "blur_on",
    "smooth": "draw", "eyedropper": "colorize", "thinner": "remove", "thicker": "add",
}


def main():
    bad = []
    for name, symbol in ICONS.items():
        try:
            xml = mi.vector(mi.svg(symbol, False))
        except Exception as e:
            bad.append("%s (%s): %s" % (name, symbol, e))
            continue
        with open(os.path.join(mi.OUT, "vi_ink_%s.xml" % name), "w") as fh:
            fh.write(xml)
    print("wrote %d icons" % (len(ICONS) - len(bad)))
    for b in bad:
        print("failed:", b)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())

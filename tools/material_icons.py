#!/usr/bin/env python3
"""VoidOS icon set from Google's Material Symbols (Rounded, weight 300).

Replaces the hand-drawn line icons that tools/icons.py made: every G entry in
ui/Kit.kt keeps its drawable name (res/drawable/vi_*.xml), so no screen had to
change. Downloads each symbol's SVG from Google Fonts, keeps a copy in
tools/material-symbols/ (so later builds work offline) and writes it as an
Android vector drawable.

Material Symbols are Apache 2.0 licensed (Google).

Run: python3 tools/material_icons.py
"""
import os
import re
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "res", "drawable")
CACHE = os.path.join(HERE, "material-symbols")
STYLE = "wght300"

# VoidOS name -> (Material Symbol, filled)
ICONS = {
    "face": ("home", False), "weather": ("partly_cloudy_day", False), "notes": ("sticky_note_2", False),
    "media": ("music_note", False), "focus": ("spa", False), "house": ("home", False), "apps": ("apps", False),
    "system": ("settings", False), "kitchen": ("handyman", False), "timer": ("hourglass_top", False),
    "list": ("checklist", False), "convert": ("sync_alt", False), "wifi": ("wifi", False), "bt": ("bluetooth", False),
    "sun": ("sunny", False), "moon": ("dark_mode", False), "cloud": ("cloud", False), "rain": ("rainy", False),
    "snow": ("weather_snowy", False), "storm": ("thunderstorm", False), "fog": ("foggy", False),
    "play": ("play_arrow", True), "pause": ("pause", True), "next": ("skip_next", True), "prev": ("skip_previous", True),
    "plus": ("add", False), "minus": ("remove", False), "back": ("arrow_back_ios_new", False), "chev": ("chevron_right", False),
    "trash": ("delete", False), "lock": ("lock", False), "check": ("check", False), "close": ("close", False),
    "search": ("search", False), "bell": ("notifications", False), "volume": ("volume_up", False),
    "battery": ("battery_full", False), "display": ("brightness_6", False), "info": ("info", False),
    "rotate": ("screen_rotation", False), "light": ("lightbulb", False), "message": ("chat", False),
    "video": ("smart_display", False), "power": ("power_settings_new", False), "globe": ("language", False),
    "camera": ("photo_camera", False), "star": ("star", True), "star_off": ("star", False), "user": ("person", False),
    "shuffle": ("shuffle", False), "phone": ("smartphone", False), "cutlery": ("restaurant", False),
    "pen": ("edit", False), "compose": ("edit_square", False), "folder": ("folder", False),
    "wifi_off": ("wifi_off", False), "plane": ("flight", False), "pin": ("location_on", False), "cast": ("cast", False),
    "qr": ("qr_code_scanner", False), "shield": ("vpn_lock", False), "send": ("send", False), "stopwatch": ("timer", False),
    "level": ("straighten", False), "mic": ("mic", False), "meter": ("graphic_eq", False),
    "torch": ("flashlight_on", False), "screenshot": ("screenshot", False), "record": ("screen_record", False),
    "leaf": ("eco", False), "contrast": ("contrast", False), "eye": ("visibility", False), "text": ("text_fields", False),
    "usb": ("usb", False), "sd": ("sd_card", False), "world": ("public", False), "radio": ("radio", False),
    "calendar": ("calendar_month", False), "assistant": ("assistant", False),
    "split": ("vertical_split", False), "scan": ("document_scanner", False), "mirror": ("face", False),
    "zoom": ("zoom_in", False), "doc": ("document_scanner", False), "wave": ("waves", False),
    "ring": ("trip_origin", False), "restart": ("restart_alt", False), "files": ("folder_open", False),
    "vibrate": ("vibration", False), "focus_mode": ("self_improvement", False), "work": ("work", False),
    "bed": ("bed", False), "book": ("menu_book", False), "game": ("sports_esports", False),
}


def svg(symbol, filled):
    style = STYLE + ("fill1" if filled else "")
    cached = os.path.join(CACHE, "%s_%s.svg" % (symbol, style))
    if os.path.exists(cached):
        return open(cached).read()
    url = "https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsrounded/%s/%s/24px.svg" % (symbol, style)
    data = urllib.request.urlopen(url, timeout=20).read().decode()
    if "<svg" not in data:
        raise RuntimeError("no symbol %s" % symbol)
    os.makedirs(CACHE, exist_ok=True)
    open(cached, "w").write(data)
    return data


def vector(data):
    paths = re.findall(r'<path[^>]*\sd="([^"]+)"', data)
    if not paths:
        raise RuntimeError("no path")
    body = "\n".join('        <path android:fillColor="#FFFFFFFF" android:pathData="%s"/>' % p for p in paths)
    # Material Symbols sit in a 960 box that starts at y = -960
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- Material Symbols Rounded (Google, Apache 2.0), written by tools/material_icons.py -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp" android:height="24dp"\n'
        '    android:viewportWidth="960" android:viewportHeight="960">\n'
        '    <group android:translateY="960">\n%s\n    </group>\n</vector>\n' % body
    )


def main():
    bad = []
    for name, (symbol, filled) in ICONS.items():
        try:
            xml = vector(svg(symbol, filled))
        except Exception as e:  # keep the old icon rather than break the build
            bad.append("%s (%s): %s" % (name, symbol, e))
            continue
        with open(os.path.join(OUT, "vi_%s.xml" % name), "w") as fh:
            fh.write(xml)
    print("wrote %d icons" % (len(ICONS) - len(bad)))
    for b in bad:
        print("kept old:", b)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())

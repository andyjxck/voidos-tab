#!/usr/bin/env python3
"""VoidOS notification sounds, made from scratch (no samples): soft glassy chimes
in the same family as the tap sound. Writes app/src/main/res/raw/notif_*.ogg with
a title tag, which VoidOS copies into the tablet's Notifications folder.

Run: python3 tools/void_sounds.py   (needs numpy and ffmpeg)
"""
import os
import subprocess
import tempfile
import wave

import numpy as np

SR = 44100
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "raw")


def bell(f, dur, decay, amp=1.0, partials=((1, 1.0), (2.01, 0.35), (3.02, 0.12), (4.2, 0.05))):
    t = np.arange(int(SR * dur)) / SR
    x = sum(a * np.sin(2 * np.pi * f * k * t) * np.exp(-t * decay * (1 + 0.6 * (k - 1))) for k, a in partials)
    att = np.minimum(1, t / 0.004)
    return amp * x * att


def place(total, parts):
    n = int(SR * total)
    y = np.zeros(n)
    for start, x in parts:
        i = int(SR * start)
        j = min(n, i + len(x))
        y[i:j] += x[: j - i]
    return y


def glass_pad(x, mix=0.25):
    # a little shimmer: short multi-tap echo, like light inside glass
    y = x.copy()
    for d, g in ((0.031, 0.5), (0.057, 0.35), (0.089, 0.22), (0.137, 0.14)):
        k = int(SR * d)
        y[k:] += x[:-k] * g * mix * 2
    return y


SOUNDS = {
    # name: (title, builder)
    "notif_void_drop": ("Void Drop", lambda: glass_pad(place(1.0, [(0, bell(988, 0.9, 7)), (0.09, bell(1480, 0.8, 8, 0.6))]))),
    "notif_void_bubble": ("Void Bubble", lambda: glass_pad(place(0.8, [
        (0, bell(660, 0.5, 14, 0.8)), (0.07, bell(880, 0.5, 14, 0.7)), (0.14, bell(1320, 0.6, 10, 0.6))]))),
    "notif_void_orbit": ("Void Orbit", lambda: glass_pad(place(1.4, [
        (0, bell(784, 1.2, 4.5)), (0.16, bell(1175, 1.1, 5, 0.55)), (0.32, bell(1568, 1.0, 5.5, 0.4))]))),
    "notif_void_glint": ("Void Glint", lambda: glass_pad(place(0.6, [(0, bell(2093, 0.5, 12, 0.7)), (0.05, bell(2637, 0.5, 13, 0.45))]))),
    "notif_void_pulse": ("Void Pulse", lambda: glass_pad(place(0.9, [(0, bell(523, 0.4, 16)), (0.18, bell(523, 0.6, 9, 0.85))]))),
    "notif_void_rise": ("Void Rise", lambda: glass_pad(place(1.2, [
        (0, bell(587, 0.5, 10, 0.7)), (0.11, bell(740, 0.5, 10, 0.7)), (0.22, bell(880, 0.5, 10, 0.7)), (0.33, bell(1175, 0.9, 5, 0.75))]))),
}


def main():
    for name, (title, build) in SOUNDS.items():
        x = build()
        x = x / np.abs(x).max() * 0.6
        n = len(x)
        x[-int(SR * 0.05):] *= np.linspace(1, 0, int(SR * 0.05))
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tf:
            w = wave.open(tf.name, "wb")
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
            w.writeframes((x * 32767).astype("<i2").tobytes()); w.close()
            out = os.path.join(OUT, name + ".ogg")
            subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", tf.name, "-c:a", "libvorbis", "-q:a", "5",
                            "-metadata", "title=" + title, out], check=True)
            os.unlink(tf.name)
        print(name, title, n / SR)


if __name__ == "__main__":
    main()

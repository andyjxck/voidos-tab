#!/usr/bin/env python3
"""
Loading animation preview for the square-root wordmark: the i's teal dot
shuttles between the purple line over the o and the green line over the d,
merging into each like a drop of liquid glass and pulling away with a neck
that snaps.

The wordmark is the newest drawn one (round 5 aligned if present, else round
4). Everything except the dot is the static logo. The dot and the two lines
it meets are drawn as a metaball field (smooth-min of capsule distances), so
they bridge and split organically. The dot carries a glass shading: a bright
upper rim, a specular highlight, a slightly darker lower rim and a faint glow.

    python3 tools/logo_loading_anim.py   # writes docs/logo/anim/*.mp4 + stills
"""
import contextlib
import io
import math
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import vgeom as g  # noqa: E402
from logo_concepts import BG, LAP  # noqa: E402
from logo_root_v2 import T  # noqa: E402

PURPLE, TEAL, GREEN = LAP[1], LAP[2], LAP[3]
FPS = 60
PERIOD = 2.4          # seconds for one round trip purple -> green -> purple
LOOPS = 3
K = 0.4 * T           # smooth-min radius: bridge forms when gap < K/2
OVERLAP = 5.5         # how far the dot pushes into a line at each end (units; 1.5 up to 1.13)
STRETCH = 0.08        # extra width at full speed
SQUASH = 0.10         # extra height at the turn
TINT = 0.22           # how much colour the dot carries from the line it touched


def wordmark():
    with contextlib.redirect_stdout(io.StringIO()):
        try:
            from logo_root_v5 import root_oid_aligned
            return root_oid_aligned(), "round 5 (aligned)"
        except Exception:
            from logo_root_v4 import root_oid_soft
            return root_oid_soft(), "round 4 (rounded)"


def xs(shape):
    return [x for ring in shape[1] for x, _ in ring]


def ys(shape):
    return [y for ring in shape[1] for _, y in ring]


def geometry(shapes):
    dot = [s for s in shapes if tuple(s[0]) == tuple(TEAL)]
    pur = [s for s in shapes if tuple(s[0]) == tuple(PURPLE)]
    grn = [s for s in shapes if tuple(s[0]) == tuple(GREEN)]
    assert len(dot) == 1 and len(pur) == 1 and len(grn) == 1, (len(dot), len(pur), len(grn))
    d, p, q = dot[0], pur[0], grn[0]
    geo = dict(
        xm=min(xs(p)), ox1=max(xs(p)),
        dx0=min(xs(q)), xa=max(xs(q)),
        ic=(min(xs(d)) + max(xs(d))) / 2, dot_w=max(xs(d)) - min(xs(d)),
        cy=(min(ys(d)) + max(ys(d))) / 2,
    )
    static = [s for s in shapes if s is not d]
    return geo, static


def transform(shapes, box):
    bx0, by0, bx1, by1 = g.bounds(shapes)
    x0, y0, x1, y1 = box
    s = min((x1 - x0) / (bx1 - bx0), (y1 - y0) / (by1 - by0))
    ox = (x0 + x1) / 2 - (bx0 + bx1) / 2 * s
    oy = (y0 + y1) / 2 - (by0 + by1) / 2 * s
    return s, ox, oy


def capsule(px, py, ax, bx, cy, r):
    """Distance to a horizontal capsule from (ax,cy) to (bx,cy), radius r."""
    qx = np.clip(px, ax, bx)
    return np.hypot(px - qx, py - cy) - r


def smin(a, b, k):
    h = np.clip(k - np.abs(a - b), 0, None) / k
    return np.minimum(a, b) - h * h * k / 4


def motion(geo):
    """Dot centre x, half width, half height and tint per frame (one loop)."""
    n = int(round(PERIOD * FPS))
    u = np.arange(n) / n
    s = np.sin(2 * math.pi * u)
    tri = 2 / math.pi * np.arcsin(s)              # even speed, sharp turn
    f = 0.75 * tri + 0.25 * s                      # a bounce with the turn softened
    half = geo["dot_w"] / 2
    a_left = geo["ic"] - half - geo["ox1"] + OVERLAP
    a_right = geo["dx0"] - (geo["ic"] + half) + OVERLAP
    # f in [-1,1]: -1 = into purple (left), +1 = into green (right)
    x = geo["ic"] + np.where(f < 0, f * a_left, f * a_right)
    v = (np.roll(x, -1) - np.roll(x, 1)) / 2
    acc = np.roll(x, -1) - 2 * x + np.roll(x, 1)
    vn = np.abs(v) / np.abs(v).max()
    an = np.abs(acc) / np.abs(acc).max()
    # jelly wobble after each turn, dying away in flight
    ts = ((u - 0.25) % 0.5) * PERIOD
    wob = 0.07 * np.sin(2 * math.pi * 5.0 * ts) * np.exp(-ts / 0.28)
    a = half * (1 + STRETCH * vn - 0.08 * an - 0.5 * wob)
    b = (T / 2) * (1 - 0.12 * vn + SQUASH * an + wob)
    # tint: the colour of the last line touched, fading in flight
    side = np.where(f < 0, 0.0, 1.0)
    tint_amt = TINT * np.abs(f) ** 3
    return x, a, b, side, tint_amt


def make_renderer(shapes, geo, static, W, H, box):
    """Returns (frames per loop, frame(i) -> uint8 HxWx3, window box in px)."""
    s, ox, oy = transform(shapes, box)
    base = np.asarray(g.render(static, W, H, bg=BG, margin=0.0, box=box), dtype=np.float32)
    # window in logo units, inside the purple and green pieces
    wx0, wx1 = geo["xm"] + 0.6, geo["xa"] - 0.6
    wy0, wy1 = geo["cy"] - 2.2 * T, geo["cy"] + 2.2 * T
    px0, px1 = int(math.ceil(wx0 * s + ox)), int(math.floor(wx1 * s + ox))
    py0, py1 = int(math.floor(wy0 * s + oy)), int(math.ceil(wy1 * s + oy))
    jj, ii = np.meshgrid(np.arange(px0, px1), np.arange(py0, py1))
    ux = ((jj + 0.5) - ox) / s           # pixel centres in logo units
    uy = ((ii + 0.5) - oy) / s
    cy, r = geo["cy"], T / 2
    dp = capsule(ux, uy, geo["xm"] - 200, geo["ox1"] - r, cy, r)
    dg = capsule(ux, uy, geo["dx0"] + r, geo["xa"] + 200, cy, r)
    bgc = np.array(BG, np.float32)
    pc, tc, gc = (np.array(c, np.float32) for c in (PURPLE, TEAL, GREEN))
    white = np.array([255, 255, 255], np.float32)
    X, A, Bh, side, tint = motion(geo)
    edge = np.minimum(ux - wx0, wx1 - ux)
    feather = np.clip((edge - 1.0) / 5.0, 0, 1)

    def frame(i):
        cx, a, b = X[i], A[i], Bh[i]
        dd = capsule(ux, uy, cx - (a - b), cx + (a - b), cy, b)
        field = smin(smin(dp, dd, K), dg, K)
        # colour weights
        wp, wd, wg = (np.exp(-np.clip(d, -T, 3 * T) / (0.16 * T)) for d in (dp, dd, dg))
        tot = wp + wd + wg
        wp, wd, wg = wp / tot, wd / tot, wg / tot
        dot_col = tc * (1 - tint[i]) + (pc if side[i] == 0 else gc) * tint[i]
        col = wp[..., None] * pc + wd[..., None] * dot_col + wg[..., None] * gc
        # glass shading, weighted by how much of the pixel belongs to the dot
        gy, gx = np.gradient(field)
        gl = np.hypot(gx, gy) + 1e-6
        ny = gy / gl
        depth = np.clip(-field, 0, None)
        rim = np.exp(-depth / (0.16 * T))
        top = np.clip(-ny, 0, 1)
        bot = np.clip(ny, 0, 1)
        spec = np.exp(-(((ux - (cx - 0.38 * a)) / (0.30 * a)) ** 2 + ((uy - (cy - 0.34 * b)) / (0.20 * b)) ** 2))
        wdx = wd[..., None]
        col = col * (1 - 0.22 * wdx * (rim * bot)[..., None])
        col = col + (white - col) * wdx * (0.42 * rim * top + 0.10 * rim + 0.60 * spec)[..., None]
        alpha = np.clip(0.5 - field * s, 0, 1)[..., None]
        glow = (0.16 * wd * np.exp(-np.clip(field, 0, None) / (0.30 * T)))[..., None] * (1 - alpha)
        out = bgc * (1 - alpha) + col * alpha + dot_col * glow
        # hand pixels the dot does not reach back to the static render exactly
        m = (np.clip(40 * wd, 0, 1) * feather)[..., None]
        fr = base.copy()
        fr[py0:py1, px0:px1] = base[py0:py1, px0:px1] * (1 - m) + out * m
        return np.clip(fr, 0, 255).astype(np.uint8)

    return len(X), frame, (px0, py0, px1, py1)


def render_video(shapes, geo, static, W, H, box, path, still_dir=None, tag=""):
    n1, frame, (px0, py0, px1, py1) = make_renderer(shapes, geo, static, W, H, box)
    ff = subprocess.Popen(
        [FFMPEG, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24",
         "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-", "-c:v", "libx264", "-preset", "slow",
         "-crf", "14", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(path)],
        stdin=subprocess.PIPE)
    frames = [frame(i) for i in range(n1)]
    for _ in range(LOOPS):
        for fr in frames:
            ff.stdin.write(fr.tobytes())
    ff.stdin.close()
    ff.wait()
    if still_dir is not None:
        crop = lambda fr: fr[max(py0 - 40, 0):py1 + 40, max(px0 - 260, 0):px1 + 260]
        strip = np.concatenate([crop(frames[i]) for i in STILL_AT(n1)], axis=0)
        Image.fromarray(strip).save(Path(still_dir) / f"frames{tag}.png")
    return ff.returncode


def STILL_AT(n):
    # into purple, pulling away, mid flight, merging green, into green
    # into purple, pulling away, mid flight, nearing green, into green
    return [int(n * f) for f in (0.75, 0.84, 0.88, 1.0 - 1e-9, 0.17, 0.25)]


if __name__ == "__main__":
    import imageio_ffmpeg
    FFMPEG = imageio_ffmpeg.get_ffmpeg_exe()
    shapes, which = wordmark()
    geo, static = geometry(shapes)
    print("wordmark:", which, {k: round(v, 2) for k, v in geo.items()})
    out = Path(__file__).resolve().parent.parent / "docs/logo/anim"
    out.mkdir(parents=True, exist_ok=True)
    rc1 = render_video(shapes, geo, static, 800, 1280, (120, 520, 680, 760),
                       out / "voidos-loading-portrait.mp4")
    rc2 = render_video(shapes, geo, static, 1920, 1080, (250, 270, 1670, 810),
                       out / "voidos-loading-closeup.mp4", still_dir=out, tag="-closeup")
    print("ffmpeg", rc1, rc2)

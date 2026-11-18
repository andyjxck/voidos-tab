#!/usr/bin/env python3
"""
The lava lamp behind the "void" wordmark in the boot animation: glossy
purple and green blobs of liquid glass that drift up and down, merge into one
another and pull apart again.

Everything here is a pure function of the loop time, so the loop closes on
itself exactly (every motion is a whole number of cycles per loop).

The blobs are a metaball field (compact kernels, so two blobs only join when
they really touch), lit like a glass dome: a soft body colour that blends
where purple meets green, a bright rim, a light gathered along the bottom, two
specular glints from the top left, and a coloured halo round the outside.

Used by tools/void_balloon.py.
"""
import math

import numpy as np
from PIL import Image, ImageFilter

PURPLE = np.array([150.0, 92.0, 250.0])
GREEN = np.array([48.0, 218.0, 128.0])
T = 0.22                     # field threshold: the surface

# (colour 0 green / 1 purple, R, x0, ax, kx, phx, y0, ay, ky, phy, kr, phr)
# lengths in word widths (560 px on the TB-X306F), k = cycles per loop
BLOBS = [
    (1, 0.30, -0.20, 0.05, 1, 0.3, -0.04, 0.16, 1, 0.00, 1, 0.2),
    (0, 0.27, 0.19, 0.06, 1, 2.1, 0.05, 0.17, 1, 3.30, 1, 1.9),
    (1, 0.21, 0.05, 0.10, 1, 4.0, -0.20, 0.10, 1, 1.60, 2, 0.7),
    (0, 0.20, -0.08, 0.09, 1, 1.2, 0.22, 0.09, 1, 4.70, 2, 2.8),
    (1, 0.17, 0.33, 0.05, 2, 0.9, -0.14, 0.12, 1, 5.60, 1, 4.1),
    (0, 0.17, -0.36, 0.05, 1, 5.2, 0.13, 0.12, 1, 2.40, 1, 5.3),
    (0, 0.13, 0.00, 0.17, 1, 3.1, 0.00, 0.21, 1, 0.80, 2, 1.1),
]


def smooth(t):
    t = min(1.0, max(0.0, t))
    return t * t * (3 - 2 * t)


def blobs(t, cx, cy, ww, emerge=1.0, gather=0.0):
    """Blob centres and radii at loop phase t (0..1, periodic), in pixels.
    emerge 0..1: the blobs grow out from behind the middle of the word.
    gather 0..1: they are drawn back together into the middle and shrink."""
    out = []
    e = emerge
    for col, r, x0, ax, kx, phx, y0, ay, ky, phy, kr, phr in BLOBS:
        x = x0 + ax * math.sin(2 * math.pi * kx * t + phx)
        y = y0 + ay * math.sin(2 * math.pi * ky * t + phy)
        rr = r * (1 + 0.10 * math.sin(2 * math.pi * kr * t + phr))
        k = (0.25 + 0.75 * e) * (1 - gather)
        x, y = x * k, y * k
        rr = rr * (e ** 0.8) * (1 - 0.55 * gather)
        if rr > 1e-4:
            out.append((cx + x * ww, cy + y * ww, rr * ww, col))
    return out


def render(W, H, bl, under, ss=2, alpha=1.0):
    """Draws the blobs bl (from blobs()) over `under` (H x W x 3 float
    array, changed in place). alpha fades the whole lamp."""
    if not bl or alpha <= 0:
        return under
    pad = 70
    x0 = int(max(0, min(b[0] - b[2] for b in bl) - pad))
    x1 = int(min(W, max(b[0] + b[2] for b in bl) + pad))
    y0 = int(max(0, min(b[1] - b[2] for b in bl) - pad))
    y1 = int(min(H, max(b[1] + b[2] for b in bl) + pad))
    if x1 <= x0 or y1 <= y0:
        return under
    w, h = x1 - x0, y1 - y0
    xs = x0 + (np.arange(w * ss) + 0.5) / ss
    ys = y0 + (np.arange(h * ss) + 0.5) / ss
    X, Y = np.meshgrid(xs, ys)
    F = np.zeros_like(X)
    P = np.zeros_like(X)
    for bx, by, R, col in bl:
        d2 = ((X - bx) ** 2 + (Y - by) ** 2) / (R * R)
        f = np.clip(1 - d2, 0, None) ** 3
        F += f
        if col:
            P += f
    # coverage, anti-aliased against the field's own slope
    gFy, gFx = np.gradient(F, 1.0 / ss)
    slope = np.hypot(gFx, gFy) + 1e-6
    cov = np.clip((F - T) / slope * ss + 0.5, 0, 1)
    # a dome: flat in the middle of a blob, standing up at its edge
    hgt = np.sqrt(np.clip((F - T) / (1 - T), 0, None))
    gy, gx = np.gradient(hgt, 1.0 / ss)
    k = 0.55 * float(np.mean([b[2] for b in bl]))
    nx, ny, nz = -gx * k, -gy * k, np.ones_like(gx)
    nl = np.sqrt(nx * nx + ny * ny + 1)
    nx, ny, nz = nx / nl, ny / nl, nz / nl
    wp = np.where(F > 1e-6, P / np.maximum(F, 1e-6), 0.5)[..., None]
    base = GREEN * (1 - wp) + PURPLE * wp
    L = np.array([-0.45, -0.65, 0.62])
    L = L / np.linalg.norm(L)
    Hv = L + np.array([0, 0, 1.0])
    Hv = Hv / np.linalg.norm(Hv)
    diff = np.clip(nx * L[0] + ny * L[1] + nz * L[2], 0, 1)[..., None]
    ndh = np.clip(nx * Hv[0] + ny * Hv[1] + nz * Hv[2], 0, 1)[..., None]
    rim = ((1 - nz) ** 2.2)[..., None]
    bottom = (np.clip(ny, 0, 1) ** 2 * (1 - nz))[..., None]
    light = base * 0.55 + 255 * 0.45
    col = base * (0.22 + 0.78 * diff)
    col = col + light * rim * 0.55 + light * bottom * 0.45
    col = col + 255 * (0.90 * ndh ** 70 + 0.22 * ndh ** 14)
    # the second, smaller glint low on the right, as on a glass bead
    Lb = np.array([0.5, 0.6, 0.62])
    Lb = (Lb / np.linalg.norm(Lb) + np.array([0, 0, 1.0]))
    Lb = Lb / np.linalg.norm(Lb)
    ndb = np.clip(nx * Lb[0] + ny * Lb[1] + nz * Lb[2], 0, 1)[..., None]
    col = col + 255 * 0.28 * ndb ** 90
    a = (cov * (0.80 + 0.20 * rim[..., 0]))[..., None]

    def down(arr):
        sh = arr.shape
        return arr.reshape(h, ss, w, ss, *sh[2:]).mean(axis=(1, 3))
    a1 = down(a)
    col1 = down(col * a) / np.maximum(a1, 1e-6)
    cov1 = down(cov[..., None])
    base1 = down(base * cov[..., None]) / np.maximum(cov1, 1e-6)
    # coloured halo round the outside
    halo = np.clip(base1 * cov1, 0, 255).astype(np.uint8)
    halo = np.asarray(Image.fromarray(halo).filter(ImageFilter.GaussianBlur(26))).astype(float)
    region = under[y0:y1, x0:x1]
    region += halo * 0.42 * alpha * (1 - region / 255)
    a1 = np.clip(a1 * alpha, 0, 1)
    region[:] = region * (1 - a1) + np.clip(col1, 0, 255) * a1
    return under

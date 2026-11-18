#!/usr/bin/env python3
"""
The VoidOS power-on and power-off pictures, drawn so that every stage hands
over to the next on the same frame:

  power on   still logo (logo partition, picture 0 and 38: tools/mtk_logo.py)
             -> boot animation part0 starts on that exact frame and the
                "void" wordmark balloons up (swells from the middle out,
                overshoots, settles)
             -> the lava lamp pours out from behind it: glossy purple and
                green blobs of liquid glass (tools/void_lava.py)
             -> part1 loops: the blobs drift, merge and split behind the
                word while Android starts (and while VoidOS waits for the
                tablet to unlock: ui/BootCover.kt keeps it on screen)
             -> part2 (played to the end once Android is ready): the blobs
                drain back in behind the word, it lets its air out and
                lands back on the still frame
             -> VoidOS (ui/Boot.kt) opens on that same still frame, waits for
                the boot animation to be gone, then balloons open into the hub
  power off  shutdown animation: a small last breath in, then the wordmark
             caves in on itself, swirling into the middle like it is being
             pulled into a void, ends as a teal point that blinks out, then
             black.

Landscape: Android 10's boot animation always draws in the panel's own
portrait and cannot read the sensor, so there are also versions for the
tablet turned either way (Display rotation 90 and 270), drawn landscape and
turned into the panel's portrait. VoidOS copies the matching pair into the
Magisk module when the tablet turns (ui/../BootOrient.kt). The power-on logo
is a still picture and always portrait, so the landscape versions start on
it and turn the word round to landscape while it balloons up.

Everything is drawn at the panel's native size (800x1280 portrait on the
TB-X306F: the stock logo and the stock boot animation are both stored that
way, and the stock boot animation's last frame is pixel for pixel the stock
logo, so the two share one coordinate system).

The background is VoidOS's own (ui/Theme.kt V.Bg = #0B0B0D) so the app's
first frame matches too.

    python3 tools/void_balloon.py            # writes root/bootanimation.zip,
                                             # root/shutdownanimation.zip, the
                                             # landscape ones in root/orient/,
                                             # and previews in root/preview/
"""
import argparse
import io
import math
import os
import subprocess
import sys
import tempfile
import zipfile
from multiprocessing import Pool
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

sys.path.insert(0, str(Path(__file__).resolve().parent))
import voidmark as vm  # noqa: E402
import void_lava as lava  # noqa: E402

FPS = 30
SS = 3                    # supersampling
BG = (11, 11, 13)         # ui/Theme.kt V.Bg 0xFF0B0B0D
SEG = 0.7                 # longest polygon edge before warping, in logo units

PANEL = (800, 1280)       # the TB-X306F's panel, portrait
ROTATIONS = (0, 90, 270)  # Display rotation the versions are drawn for

# boot timing (frames at 30 fps)
HOLD = 6                  # part0: hold on the still logo,
SWELL = 36                #        balloon up (and turn to landscape),
POUR = 24                 #        the lava lamp pours out from behind the word
INTRO = HOLD + SWELL + POUR
LOOP = 96                 # part1: the lava lamp, 3.2 s (its trimmed frames take less memory than the 72 full frames 1.3.0 looped without trouble)
DRAIN = 24                # part2: the blobs drain back in, the air goes out,
OUTRO = DRAIN + 2         #        and it holds on the still frame
# shutdown timing
SHUT_HOLD = 4
SHUT_BREATH = 9           # a small last breath in
SHUT_COLLAPSE = 33        # caving in
SHUT_SPARK = 12           # the point blinks out


def clamp(x, a=0.0, b=1.0):
    return max(a, min(b, x))


def ease_io(t):
    t = clamp(t)
    return t * t * (3 - 2 * t)


def ease_in(t):
    t = clamp(t)
    return t * t * t


def ease_out(t):
    t = clamp(t)
    return 1 - (1 - t) ** 3


# ------------------------------------------------------------------ geometry

_rings = None


def rings():
    """The wordmark's shapes with long edges cut into short ones, so a warp
    bends them instead of only moving their corners."""
    global _rings
    if _rings is None:
        geo = vm.geometry()
        out = []
        for sh in geo["shapes"]:
            rs = []
            for r in sh["rings"]:
                pts = []
                n = len(r)
                for i in range(n):
                    (x0, y0), (x1, y1) = r[i], r[(i + 1) % n]
                    k = max(1, int(math.ceil(math.hypot(x1 - x0, y1 - y0) / SEG)))
                    for j in range(k):
                        pts.append((x0 + (x1 - x0) * j / k, y0 + (y1 - y0) * j / k))
                rs.append(pts)
            col = vm.WHITE if sh["c"] == -1 else vm.LAP[sh["c"]]
            out.append((rs, col))
        _rings = out
    return _rings


def layout(W, H):
    """Same place and size as the app (tools/voidmark.py lockup_geometry,
    ui/Boot.kt): centre c, scale s px per unit, logo origin o."""
    unit, c, ww, wh, _ = vm.lockup_geometry(W, H)
    wW, wH = vm.word_size()
    s = ww / wW
    o = (c[0] - wW * s / 2, c[1] - wH * s / 2)
    return c, s, o, ww / 2, wh / 2


def balloon_warp(b, c, ax, ay):
    """b 0..1(+): how full of air. The middle swells most, like a balloon."""
    a = 0.20 * b
    sx = 1 + 0.025 * b
    sy = 1 + 0.06 * b

    def f(x, y):
        dx, dy = x - c[0], y - c[1]
        r2 = (dx / ax) ** 2 + (dy / ay) ** 2
        g = 1 + a / (1 + 1.6 * r2)
        return c[0] + dx * g * sx, c[1] + dy * g * sy
    return f


def collapse_warp(k, c, ax, ay):
    """k 0..1: caving in. The middle goes first and the ends follow, turning
    as they are pulled in, until it is all one point at c."""
    D = 0.45

    def f(x, y):
        dx, dy = x - c[0], y - c[1]
        u, v = dx / ax, dy / ay
        rn = min(1.0, math.hypot(u, v) / 1.15)
        e = clamp((k * (1 + D) - D * rn) / 1.0)
        g = 1 - ease_io(e) ** 0.85
        g = g * (1 - 0.35 * ease_in(k))          # and the whole thing shrinks
        th = 0.7 * ease_io(e) * (1 - 0.5 * rn)   # swirl, in the word's own proportions
        cu, su = math.cos(th), math.sin(th)
        u2, v2 = (u * cu - v * su) * g, (u * su + v * cu) * g
        return c[0] + u2 * ax, c[1] + v2 * ay
    return f


def draw_glow(img, W, H, glow, fade=1.0, post=None):
    """A soft teal light inside the balloon."""
    d = ImageDraw.Draw(img)
    c, s, o, ax, ay = layout(W, H)
    for i in range(6, 0, -1):
        rx, ry = ax * (0.35 + 0.13 * i), ay * (0.55 + 0.22 * i)
        col = vm.mix(vm.TEAL, BG, 0.022 * glow * (7 - i) / 6 * fade)
        if post is None:
            d.ellipse([c[0] - rx, c[1] - ry, c[0] + rx, c[1] + ry], fill=col)
        else:
            n = 180
            d.polygon([post(c[0] + rx * math.cos(2 * math.pi * j / n), c[1] + ry * math.sin(2 * math.pi * j / n))
                       for j in range(n)], fill=col)


def word_layer(W, H, warp=None, fade=1.0, post=None):
    """The wordmark on its own layer, with see-through holes. warp bends it
    (the balloon), post then places it (turning it round to landscape)."""
    c, s, o, ax, ay = layout(W, H)
    w = warp(c, ax, ay) if warp else None
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    for rs, col in rings():
        polys = []
        for r in rs:
            pts = [(o[0] + x * s, o[1] + y * s) for x, y in r]
            if w:
                pts = [w(x, y) for x, y in pts]
            if post:
                pts = [post(x, y) for x, y in pts]
            polys.append(pts)
        if len(polys[0]) >= 3:
            ld.polygon(polys[0], fill=tuple(vm.mix(col, BG, fade)) + (255,))
        for hole in polys[1:]:
            if len(hole) >= 3:
                ld.polygon(hole, fill=(0, 0, 0, 0))
    return layer


def draw_word(img, W, H, warp=None, glow=0.0, fade=1.0, post=None):
    if glow > 0:
        draw_glow(img, W, H, glow, fade, post)
    layer = word_layer(W, H, warp, fade, post)
    img.paste(layer, (0, 0), layer)
    return layout(W, H)[0]


def view_size(rot):
    """The picture as the person holding the tablet sees it."""
    return PANEL if rot in (0, 180) else (PANEL[1], PANEL[0])


def to_panel(img, rot):
    """Turns a picture drawn the way the person sees it into the panel's own
    portrait, the only way Android 10's boot animation can draw. Display
    rotation 90 is the tablet turned anticlockwise: the content is turned
    clockwise on the panel (android.view.Display.getRotation)."""
    if rot == 90:
        return img.transpose(Image.ROTATE_270)
    if rot == 270:
        return img.transpose(Image.ROTATE_90)
    return img


def from_panel(img, rot):
    """The other way round: the panel's portrait picture as the person
    holding the tablet at Display rotation rot sees it."""
    if rot == 90:
        return img.transpose(Image.ROTATE_90)
    if rot == 270:
        return img.transpose(Image.ROTATE_270)
    return img


def turn_post(W, H, rot, k):
    """Places the wordmark, drawn for the landscape view W x H (supersampled),
    k of the way from where the portrait power-on logo shows it (k = 0, on
    its side for someone holding the tablet in landscape) to its own
    landscape place (k = 1). None when there is nothing to turn."""
    if rot in (0, 180) or k >= 1:
        return None
    cL = layout(W, H)[0]
    cP = layout(H, W)[0]                      # the portrait logo, in panel pixels
    if rot == 90:
        m0 = (cP[1], H - cP[0])
        a0 = -math.pi / 2
    else:
        m0 = (W - cP[1], cP[0])
        a0 = math.pi / 2
    ke = k
    cx, cy = m0[0] + (cL[0] - m0[0]) * ke, m0[1] + (cL[1] - m0[1]) * ke
    a = a0 * (1 - ke)
    ca, sa = math.cos(a), math.sin(a)

    def f(x, y):
        dx, dy = x - cL[0], y - cL[1]
        return cx + ca * dx - sa * dy, cy + sa * dx + ca * dy
    return f


def render(w, h, warp=None, glow=0.0, fade=1.0, spark=None, turn=None, lava_state=None):
    """One frame, w x h as seen. turn = (rot, k) for turning the portrait
    logo round to landscape; lava_state = (blobs args) for the lava lamp."""
    W, H = w * SS, h * SS
    img = Image.new("RGB", (W, H), BG)
    post = turn_post(W, H, *turn) if turn else None
    if lava_state is None:
        if fade > 0:
            draw_word(img, W, H, warp, glow, fade, post)
        if spark is not None:
            c, _, _, ax, ay = layout(W, H)
            r, a = spark
            d = ImageDraw.Draw(img)
            for i in range(4, 0, -1):
                rr = r * (1 + 1.4 * i)
                d.ellipse([c[0] - rr, c[1] - rr, c[0] + rr, c[1] + rr], fill=vm.mix(vm.TEAL, BG, a * 0.07 * (5 - i)))
            if r > 0:
                d.ellipse([c[0] - r, c[1] - r, c[0] + r, c[1] + r], fill=vm.mix((235, 255, 255), BG, a))
        return img.resize((w, h), Image.LANCZOS)
    # the lava lamp goes between the glow and the wordmark: it is behind the word
    if glow > 0:
        draw_glow(img, W, H, glow, fade, post)
    base = np.asarray(img.resize((w, h), Image.LANCZOS)).astype(float)
    t, emerge, gather, alpha = lava_state
    c = layout(w, h)[0]
    ww = layout(w, h)[3] * 2
    lava.render(w, h, lava.blobs(t, c[0], c[1], ww, emerge, gather), base, alpha=alpha)
    out = Image.fromarray(np.clip(base + 0.5, 0, 255).astype(np.uint8))
    layer = word_layer(W, H, warp, fade, post).resize((w, h), Image.LANCZOS)
    # a soft dark shadow round the letters keeps the word clear of the glass
    sh = layer.getchannel("A").filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.GaussianBlur(7))
    sh = sh.point(lambda v, k=0.85 * alpha * min(1.0, emerge * 1.5): int(v * k))
    out.paste(Image.new("RGB", (w, h), BG), (0, 0), sh)
    out.paste(layer, (0, 0), layer)
    return out


# ------------------------------------------------------------------ timelines

def air_intro(f):
    """Air in the balloon at intro frame f: 0 on the still frame, up past 1
    with a little wobble, settling on 1 by the end of the swell."""
    if f < HOLD:
        return 0.0
    t = min(1.0, (f - HOLD) / SWELL)          # 0..1 over the swell
    # damped spring onto 1, starting from 0 with zero speed
    x = t * 2.6
    return 1 - math.exp(-2.0 * x) * (math.cos(3.6 * x) + (2.0 / 3.6) * math.sin(3.6 * x))


def turn_intro(f):
    """How far the word has turned round to landscape (0..1) at intro frame f:
    it turns while it balloons, done a little before the swell settles."""
    return ease_io((f - HOLD + 1) / (SWELL * 0.85))


def air_loop(f):
    """A small breath under the lava lamp, 1 -> 0.9 -> 1, seamless."""
    return 1 - 0.10 * (1 - math.cos(2 * math.pi * f / LOOP)) / 2


def air_outro(f):
    """1 -> 0 over the drain, ending exactly on the still frame."""
    return 1 - ease_io((f + 1) / DRAIN) if f < DRAIN else 0.0


def boot_spec(part, f):
    """What boot frame (part, f) shows: (air, turn k, lava_state or None)."""
    if part == "part0":
        if f < HOLD + SWELL:
            return air_intro(f), turn_intro(f), None
        u = (f - HOLD - SWELL + 1) / POUR
        return 1.0, 1.0, ((f - INTRO) / LOOP, ease_out(u), 0.0, 1.0)
    if part == "part1":
        return air_loop(f), 1.0, (f / LOOP, 1.0, 0.0, 1.0)
    # part2: the blobs drain back in behind the word as the air goes out
    if f >= DRAIN:
        return 0.0, 1.0, None
    u = (f + 1) / DRAIN
    return air_outro(f), 1.0, (f / LOOP, 1 - ease_in(u), ease_io(u), 1 - ease_io((u - 0.45) / 0.55))


BOOT_PARTS = (("part0", INTRO), ("part1", LOOP), ("part2", OUTRO))


def boot_frame(rot, part, f):
    w, h = view_size(rot)
    b, k, lv = boot_spec(part, f)
    glow = clamp(b) * (1 - 0.6 * (lv[1] if lv else 0) * (lv[3] if lv else 0))
    warp = (lambda c, ax, ay, b=b: balloon_warp(b, c, ax, ay)) if b > 0 else None
    turn = (rot, k) if rot not in (0, 180) and k < 1 else None
    if b <= 0 and lv is None and rot not in (0, 180) and k <= 0:
        # exactly the power-on logo (turned to how the person sees it), so
        # the hand-over from the still logo has no seam at all
        return from_panel(render(*PANEL), rot)
    return render(w, h, warp, glow=glow, turn=turn, lava_state=lv)


SHUT_PARTS = (("part0", SHUT_HOLD + SHUT_BREATH + SHUT_COLLAPSE + SHUT_SPARK), ("part1", 1))


def shutdown_frame(rot, part, f):
    w, h = view_size(rot)
    if part == "part1":
        return render(w, h, fade=0)
    if f < SHUT_HOLD:
        return render(w, h)
    f -= SHUT_HOLD
    if f < SHUT_BREATH:
        b = 0.45 * math.sin(math.pi * (f + 1) / (SHUT_BREATH + 1) * 0.5)  # 0 -> 0.45, still rising
        return render(w, h, lambda c, ax, ay, b=b: balloon_warp(b, c, ax, ay), glow=b)
    f -= SHUT_BREATH
    if f < SHUT_COLLAPSE:
        b0 = 0.45
        k = (f + 1) / SHUT_COLLAPSE
        b = b0 * (1 - ease_out(k / 0.35))  # the breath lets go as it starts caving in

        def warp(c, ax, ay, k=k, b=b):
            bw = balloon_warp(b, c, ax, ay)
            cw = collapse_warp(k, c, ax, ay)
            return lambda x, y: cw(*bw(x, y))
        sp = None
        if k > 0.75:  # the point lights up as the last of the word goes in
            sp = (3 * SS * ease_io((k - 0.75) / 0.25), ease_io((k - 0.75) / 0.25))
        return render(w, h, warp, glow=b, fade=1.0, spark=sp)
    f -= SHUT_COLLAPSE
    t = (f + 1) / SHUT_SPARK
    r = 3 * SS * (1 + 0.6 * math.sin(math.pi * min(1, t / 0.4))) * (1 - ease_in(t))
    return render(w, h, fade=0, spark=(r, 1 - ease_in(t)))


# ------------------------------------------------------------------ output

def png(img):
    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def trim(img):
    """Crops a panel frame to what differs from the background, for the
    part's trim.txt (Android 10's bootanimation clears the rest to the
    part's background colour). Returns (cropped image, 'WxH+X+Y')."""
    a = np.asarray(img)
    diff = np.any(a != np.array(BG, dtype=a.dtype), axis=2)
    ys, xs = np.nonzero(diff)
    if len(xs) == 0:
        x0, y0, x1, y1 = img.width // 2 - 1, img.height // 2 - 1, img.width // 2 + 1, img.height // 2 + 1
    else:
        x0, y0, x1, y1 = int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1
    return img.crop((x0, y0, x1, y1)), f"{x1 - x0}x{y1 - y0}+{x0}+{y0}"


def _job(args):
    kind, rot, part, f, keep = args
    seen = (boot_frame if kind == "boot" else shutdown_frame)(rot, part, f)
    crop, line = trim(to_panel(seen, rot))
    small = seen.resize((seen.width // 2, seen.height // 2), Image.LANCZOS) if keep else None
    return part, f, png(crop), line, small


def bg_hex():
    return "#%02x%02x%02x" % BG


def write_anim(path, kind, rot, pool, keep=False):
    """Renders one animation into a stored (uncompressed) zip with trimmed
    frames, as Android's bootanimation needs. Returns the frame counts and,
    with keep, half-size frames in play order (as seen) for the previews."""
    parts = BOOT_PARTS if kind == "boot" else SHUT_PARTS
    if kind == "boot":
        # c = play to the end even once Android is ready: part0 always plays
        # through to the lava lamp, part1 loops (count 0) and finishes its
        # loop once Android is ready, and part2 always drains back onto the
        # still frame, so the hand-over to VoidOS lands on the still frame
        desc = [f"c 1 0 part0 {bg_hex()}", f"c 0 0 part1 {bg_hex()}", f"c 1 0 part2 {bg_hex()}"]
    else:
        # same structure as the stock Lenovo shutdown animation: part0 once,
        # then part1 (the background) until the tablet is off
        desc = [f"p 1 0 part0 {bg_hex()}", f"p 0 0 part1 {bg_hex()}"]
    w, h = PANEL
    jobs = [(kind, rot, p, f, keep) for p, n in parts for f in range(n)]
    frames = {p: [] for p, _ in parts}
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as z:
        z.writestr(zipfile.ZipInfo("desc.txt"), f"{w} {h} {FPS}\n" + "".join(l + "\n" for l in desc))
        trims = {p: [] for p, _ in parts}
        for part, f, data, line, small in pool.imap(_job, jobs, chunksize=2):
            z.writestr(zipfile.ZipInfo(f"{part}/{f:05d}.png"), data)
            trims[part].append(line)
            if keep:
                frames[part].append(small)
        for p, lines in trims.items():
            z.writestr(zipfile.ZipInfo(f"{p}/trim.txt"), "".join(l + "\n" for l in lines))
    return {p: len(v) for p, v in trims.items()}, frames


def contact_sheet(frames, path, per_row=8):
    rows = []
    for p, v in frames.items():
        if len(v) > 1:
            idx = [round(i * (len(v) - 1) / (per_row - 1)) for i in range(per_row)]
            rows.append([v[i] for i in idx])
        elif v:
            rows.append(v[:1])
    tw, th = rows[0][0].width // 2, rows[0][0].height // 2
    sheet = Image.new("RGB", (tw * per_row, th * len(rows)), (40, 40, 40))
    for ri, row in enumerate(rows):
        for ci, im in enumerate(row):
            sheet.paste(im.resize((tw, th), Image.LANCZOS), (ci * tw, ri * th))
    sheet.save(path)


def video(seq, path, gif=None):
    """Half-size preview of frames in play order (MP4, and a smaller GIF)."""
    w, h = seq[0].size
    with tempfile.TemporaryDirectory() as d:
        for i, im in enumerate(seq):
            im.save(f"{d}/{i:05d}.png")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", str(FPS), "-i", f"{d}/%05d.png",
                        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", str(path)], check=True)
        if gif:
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", str(FPS), "-i", f"{d}/%05d.png",
                            "-vf", f"fps=15,scale={w // 2}:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128[p];[b][p]paletteuse=dither=bayer",
                            str(gif)], check=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(vm.ROOT / "root"))
    ap.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 2) - 1))
    a = ap.parse_args()
    w, h = PANEL
    out = Path(a.out)
    (out / "preview").mkdir(parents=True, exist_ok=True)

    still = render(w, h)
    still.save(out / f"preview/void_still_{w}x{h}.png")
    # the same still logo for BootCover.kt, in both shapes, so if it has to
    # draw before the hub is ready it shows exactly the boot animation's end
    res = vm.ROOT / "app/src/main/res/drawable-nodpi"
    res.mkdir(parents=True, exist_ok=True)
    still.save(res / "boot_still_port.png", optimize=True)
    render(h, w).save(res / "boot_still_land.png", optimize=True)

    with Pool(a.jobs) as pool:
        for rot in ROTATIONS:
            d = out if rot == 0 else out / f"orient/{rot}"
            keep = rot in (0, 90)
            counts, frames = write_anim(d / "bootanimation.zip", "boot", rot, pool, keep)
            print("boot", rot, counts, f"{(d / 'bootanimation.zip').stat().st_size / 1e6:.1f} MB")
            if keep:
                tag = "portrait" if rot == 0 else "landscape"
                contact_sheet(frames, out / f"preview/boot_sheet_{tag}.png")
                seq = frames["part0"] + frames["part1"] * 2 + frames["part2"]
                video(seq, out / f"preview/boot_{tag}.mp4", out / f"preview/boot_{tag}.gif")
            counts, frames = write_anim(d / "shutdownanimation.zip", "shutdown", rot, pool, keep=(rot == 0))
            print("shutdown", rot, counts, f"{(d / 'shutdownanimation.zip').stat().st_size / 1e6:.1f} MB")
            if rot == 0:
                contact_sheet(frames, out / "preview/shutdown_sheet.png")


if __name__ == "__main__":
    main()

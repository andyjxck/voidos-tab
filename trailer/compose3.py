#!/usr/bin/env python3
"""VoidOS trailer v3 compositor.

Every screen comes from rec/<fmt>_<scene>.rgba: frames of the real VoidRoot
(VoidOS 1.15.0, branch voidos-v2-hub-features @ 36e6567) filmed by
TrailerTest.kt at the TB-X306F's own 1280x800, density 1.0.
Nothing on the tablet is drawn here; this file only frames it (bezel in the
Lenovo Tab M10 HD 2nd Gen's proportions), adds titles, the finger dot,
and the original boot logo animation at the start and end.

Usage: compose3.py plan | still <fmt> <t>... | render <fmt> <a> <b>
"""
import json, math, subprocess, sys
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

sys.path.insert(0, '/home/sprite/trailer2')
import compose as C   # helpers only: fonts, easing, background, text, logo frames

ROOT = Path('/home/sprite/tr3')
REC = ROOT / 'rec'
FPS = 30
BG = C.BG
INTRO_END = 4.0
GAP0, OUTRO0, END = 38.5, 39.0, 47.0

# Lenovo Tab M10 HD 2nd Gen: 241.5 x 149.4 mm body, 10.1" 16:10 screen
# (216.6 x 135.4 mm) -> about 12.4 mm side bezels, 7 mm top/bottom.
PX_MM = 1280 / 216.6
BEZ_LONG = round(12.4 * PX_MM)   # bezel either side of the long screen edge
BEZ_SHORT = round(7.0 * PX_MM)
IRON_GREY = (46, 48, 52)

# (scene, src start frame, src end frame, kicker, title)
SCENES = [
    ('unlock', 18, 130, 'LOCK SCREEN', 'Swipe up. Your PIN. Home.'),
    ('home', 60, 229, 'HOME', 'Widgets you move, size and turn'),
    ('control', 0, 276, 'CONTROL CENTRE', 'Every control, one pull away'),
    ('tools', 0, 230, 'TIME', 'Timers, stopwatch, alarms'),
    ('notes', 20, 209, 'NOTES', 'Sketch it the second you think it'),
    ('weather', 0, 118, 'WEATHER', 'Any place, by name'),
]
DUR = [3.73, 5.0, 6.57, 5.7, 5.5, 4.5]      # seconds on screen for each scene
MONTAGE_T0 = 35.0
# quick beat cuts before the logo: (scene, src frame)
MONTAGE = [('control', 100), ('home', 200), ('tools', 228), ('notes', 205)]
BEAT = 0.875


def plan():
    out, t = [], INTRO_END
    for (name, a, b, k, ttl), d in zip(SCENES, DUR):
        out.append(dict(clip=name, t0=t, t1=t + d, a=a, b=b, kicker=k, title=ttl))
        t += d
    assert abs(t - MONTAGE_T0) < 0.02, t
    for i, (name, f) in enumerate(MONTAGE):
        out.append(dict(clip=name, t0=MONTAGE_T0 + i * BEAT, t1=MONTAGE_T0 + (i + 1) * BEAT, a=f - 10, b=f + 16, kicker=None, title=None))
    return out


PLAN = plan()


class Clip:
    def __init__(self, fmt, name):
        self.W, self.H = (1280, 800) if fmt == 'land' else (800, 1280)
        m = np.memmap(REC / f'{fmt}_{name}.rgba', dtype=np.uint8, mode='r')
        self.n = m.size // (self.W * self.H * 4)
        self.m = m[: self.n * self.W * self.H * 4].reshape(self.n, self.H, self.W, 4)
        self.touch = {}
        for line in (REC / f'{fmt}_{name}.touch').read_text().splitlines():
            parts = line.split()
            if len(parts) > 1:
                pts = []
                for p in ' '.join(parts[1:]).replace(';', ' ').split():
                    _, x, y = p.split(',')
                    pts.append((float(x), float(y)))
                self.touch[int(parts[0])] = pts

    def frame(self, f):
        f = int(min(max(f, 0), self.n - 1))
        return Image.fromarray(np.ascontiguousarray(self.m[f][:, :, :3]), 'RGB')


def src_frame(sc, t):
    u = (t - sc['t0']) / (sc['t1'] - sc['t0'])
    return sc['a'] + u * (sc['b'] - sc['a'])


def rounded_mask(w, h, r, ss=3):
    m = Image.new('L', (w * ss, h * ss), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, w * ss - 1, h * ss - 1), r * ss, fill=255)
    return m.resize((w, h), Image.LANCZOS)


class Tablet:
    """The device body around a screen of size (sw, sh)."""
    def __init__(self, sw, sh):
        land = sw > sh
        bx, by = (BEZ_LONG, BEZ_SHORT) if land else (BEZ_SHORT, BEZ_LONG)
        self.bx, self.by, self.sw, self.sh = bx, by, sw, sh
        self.tw, self.th = sw + 2 * bx, sh + 2 * by
        r = 54
        body = Image.new('RGBA', (self.tw, self.th), (0, 0, 0, 0))
        # metal rim, then black glass front
        rim = Image.new('RGBA', (self.tw, self.th), IRON_GREY + (255,))
        g = np.linspace(1.18, 0.82, self.th)[:, None, None]
        rim = Image.fromarray(np.clip(np.asarray(rim, np.float32) * np.concatenate([np.repeat(g, 3, 2), np.ones((self.th, 1, 1))], 2), 0, 255).astype(np.uint8), 'RGBA')
        body.paste(rim, (0, 0), rounded_mask(self.tw, self.th, r))
        glass = Image.new('RGBA', (self.tw - 6, self.th - 6), (9, 9, 11, 255))
        body.paste(glass, (3, 3), rounded_mask(self.tw - 6, self.th - 6, r - 3))
        # front camera, centred in the bezel above the screen in landscape
        d = ImageDraw.Draw(body)
        if land:
            cx, cy = self.tw // 2, by // 2
        else:
            cx, cy = bx // 2, self.th // 2
        d.ellipse((cx - 5, cy - 5, cx + 5, cy + 5), fill=(24, 26, 32, 255))
        d.ellipse((cx - 2, cy - 2, cx + 1, cy + 1), fill=(48, 56, 78, 255))
        self.body = body
        self.mask = rounded_mask(sw, sh, 4)
        sh_img = Image.new('L', (self.tw + 200, self.th + 200), 0)
        ImageDraw.Draw(sh_img).rounded_rectangle((100, 130, self.tw + 100, self.th + 110), r, fill=150)
        self.shadow = sh_img.filter(ImageFilter.GaussianBlur(40))

    def compose(self, screen, touches):
        img = self.body.copy()
        scr = screen.convert('RGBA')
        if touches:
            ov = Image.new('RGBA', scr.size, (0, 0, 0, 0))
            d = ImageDraw.Draw(ov)
            for x, y in touches:
                for rr, a in ((30, 40), (22, 70), (16, 110)):
                    d.ellipse((x - rr, y - rr, x + rr, y + rr), fill=(255, 255, 255, a))
            scr = Image.alpha_composite(scr, ov)
        img.paste(scr, (self.bx, self.by), self.mask)
        return img


class Renderer:
    def __init__(self, fmt):
        self.fmt = fmt
        self.W, self.H = (1920, 1080) if fmt == 'land' else (1080, 1920)
        sw, sh = (1280, 800) if fmt == 'land' else (800, 1280)
        self.tab = Tablet(sw, sh)
        self.bg = C.Background(self.W, self.H)
        self.clips = {}
        if fmt == 'land':
            self.scale = 0.95
            self.top = 190
        else:
            self.scale = 1.08
            self.top = 300
        self.kf = C.font(22 if fmt == 'land' else 26, 'SemiBold')
        self.tf = C.font(54 if fmt == 'land' else 60, 'SemiBold')
        self.cache = {}

    def clip(self, name):
        if name not in self.clips:
            self.clips[name] = Clip(self.fmt, name)
        return self.clips[name]

    def title_imgs(self, k, t):
        key = (k, t)
        if key not in self.cache:
            self.cache[key] = (C.gradient_text(k, self.kf, tracking=5), C.white_text(t, self.tf, align='center'))
        return self.cache[key]

    def scene_at(self, t):
        for sc in PLAN:
            if sc['t0'] <= t < sc['t1']:
                return sc
        return None

    def frame(self, i):
        t = i / FPS
        if t < INTRO_END:
            return self.logo(t, 0.0, push=1.0 + 0.012 * t)
        if t >= OUTRO0:
            return self.logo(t, t - OUTRO0 + 0.0, push=1.0)
        if t >= GAP0:
            return Image.new('RGB', (self.W, self.H), (0, 0, 0))
        sc = self.scene_at(t)
        u = (t - sc['t0']) / (sc['t1'] - sc['t0'])
        energy = 1.0
        canvas = self.bg.frame(t, energy).convert('RGBA')
        clip = self.clip(sc['clip'])
        f = src_frame(sc, t)
        fi = int(round(f))
        screen = clip.frame(fi)
        tab = self.tab.compose(screen, clip.touch.get(fi))
        # slow push in across each scene; a quick settle at each cut
        s = self.scale * (0.975 + 0.025 * C.ease(u))
        k_in = min(1, (t - sc['t0']) / 0.35)
        s *= 1 - 0.03 * (1 - C.ease_out(k_in))
        tw, th = int(self.tab.tw * s), int(self.tab.th * s)
        timg = tab.resize((tw, th), Image.LANCZOS)
        x = (self.W - tw) // 2
        free_h = self.H - self.top
        y = self.top + (free_h - th) // 2
        shadow = self.tab.shadow.resize((int(self.tab.shadow.width * s), int(self.tab.shadow.height * s)))
        blk = Image.new('RGBA', shadow.size, (0, 0, 0, 255)); blk.putalpha(shadow)
        canvas.alpha_composite(blk, (x - int(100 * s), y - int(100 * s)))
        canvas.alpha_composite(timg, (x, y))
        # fade in from each cut
        if sc['kicker']:
            kimg, ttl = self.title_imgs(sc['kicker'], sc['title'])
            a_in = C.ease_out(min(1, (t - sc['t0'] - 0.15) / 0.45)) if t - sc['t0'] > 0.15 else 0
            a_out = C.ease(max(0, (t - (sc['t1'] - 0.35)) / 0.35))
            a = a_in * (1 - a_out)
            dy = (1 - a_in) * 18
            ty = (self.top - kimg.height - 14 - ttl.height) // 2 + (0 if self.fmt == 'land' else 10)
            C.paste_alpha(canvas, kimg, ((self.W - kimg.width) / 2, ty + dy), a)
            C.paste_alpha(canvas, ttl, ((self.W - ttl.width) / 2, ty + kimg.height + 14 + dy), a)
        out = canvas.convert('RGB')
        # a quick flash-dip at each cut so the beat lands
        since = t - sc['t0']
        if since < 0.12:
            out = Image.blend(Image.new('RGB', out.size, (0, 0, 0)), out, 0.35 + 0.65 * since / 0.12)
        return out

    def logo(self, t, t_rel, push):
        lf = C.logo_frame(t_rel if t >= OUTRO0 else t, self.W, self.H)
        bg = self.bg.frame(t, 0.6)
        return C.logo_over(bg, lf, push)


def events():
    ev = []
    for sc in PLAN:
        ev.append(['cut', round(sc['t0'], 3)])
        if sc['kicker']:
            ev.append(['callout', round(sc['t0'] + 0.2, 3)])
            clip_touch = Clip('land', sc['clip']).touch
            prev = False
            n = int((sc['t1'] - sc['t0']) * FPS)
            for k in range(n):
                t = sc['t0'] + k / FPS
                on = int(round(src_frame(sc, t))) in clip_touch
                if on and not prev:
                    ev.append(['tap', round(t, 3)])
                if prev and not on:
                    ev.append(['lift', round(t, 3)])
                prev = on
    ev.append(['outro', OUTRO0])
    return ev


if __name__ == '__main__':
    cmd = sys.argv[1]
    if cmd == 'plan':
        for sc in PLAN:
            print(f"{sc['clip']:8s} {sc['t0']:6.2f}-{sc['t1']:6.2f} frames {sc['a']}-{sc['b']}  {sc['title']}")
        (ROOT / 'events.json').write_text(json.dumps(dict(events=events(), end=END, outro=OUTRO0, intro=INTRO_END)))
    elif cmd == 'still':
        r = Renderer(sys.argv[2])
        for t in sys.argv[3:]:
            r.frame(int(float(t) * FPS)).save(ROOT / f'still_{sys.argv[2]}_{t}.jpg', quality=88)
    elif cmd == 'render':
        fmt, a, b = sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
        r = Renderer(fmt)
        out = ROOT / 'chunks' / f'{fmt}_{a:05d}.mp4'
        out.parent.mkdir(exist_ok=True)
        p = subprocess.Popen(['ffmpeg', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{r.W}x{r.H}',
                              '-r', str(FPS), '-i', '-', '-c:v', 'libx264', '-preset', 'medium', '-crf', '16',
                              '-pix_fmt', 'yuv420p', str(out)], stdin=subprocess.PIPE)
        for i in range(a, b):
            p.stdin.write(r.frame(i).tobytes())
        p.stdin.close(); p.wait()
        print('done', out)

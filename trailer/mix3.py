#!/usr/bin/env python3
"""Sound mix v2: new music (music_v2.wav) + the same UI sound effects and
voiceover as mix.py, with a clean peak limiter and loudness normalisation.
Usage: mix2.py out.wav [vo|novo]"""
import json, sys, wave
import numpy as np
from scipy.signal import butter, sosfilt, resample_poly, fftconvolve

SR = 48000
ROOT = '/home/sprite/trailer2'
T3 = '/home/sprite/tr3'
rng = np.random.default_rng(3)
out_path, mode = sys.argv[1], sys.argv[2]
ev = json.load(open(f'{T3}/events.json'))
END = ev['end']
N = int(END * SR)


def read(p):
    with wave.open(p) as w:
        sr, ch = w.getframerate(), w.getnchannels()
        x = np.frombuffer(w.readframes(w.getnframes()), '<i2').astype(np.float64) / 32768
    x = x.reshape(-1, ch)
    if sr != SR:
        x = resample_poly(x, SR // 50, sr // 50 if sr % 50 == 0 else sr, axis=0) if sr % 50 == 0 else resample_poly(x, SR, sr, axis=0)
    return x


def t_(n):
    return np.arange(n) / SR


def bp(x, lo, hi):
    return sosfilt(butter(2, [lo, hi], 'band', fs=SR, output='sos'), x)


import soundfile as sf
import pyloudnorm as pyln
music, _sr = sf.read(f'{ROOT}/music_v2.wav')
assert _sr == SR
music = music[:N]
music = np.pad(music, ((0, N - len(music)), (0, 0)))
# match the old score's level in the main section so the effects and voice
# sit against the music exactly as before
_meter = pyln.Meter(SR)
_ref = sf.read(f'{ROOT}/music.wav')[0]
_g = _meter.integrated_loudness(_ref[int(4 * SR):int(38.5 * SR)]) - _meter.integrated_loudness(music[int(4 * SR):int(38.5 * SR)])
music *= 10 ** (_g / 20)
print('music gain dB', round(_g, 2))
sfx = np.zeros((N, 2))


def add(buf, sig, at, gain, pan=0.0):
    i = int(at * SR)
    if i < 0:
        sig = sig[-i:]; i = 0
    s = sig[: max(0, N - i)] * gain
    if s.ndim == 1:
        buf[i:i + len(s), 0] += s * np.sqrt(0.5 * (1 - pan))
        buf[i:i + len(s), 1] += s * np.sqrt(0.5 * (1 + pan))
    else:
        buf[i:i + len(s)] += s


def tap():
    n = int(0.06 * SR); tt = t_(n)
    s = (np.sin(2 * np.pi * 2300 * tt) * 0.6 + np.sin(2 * np.pi * 3450 * tt) * 0.4) * np.exp(-tt * 110)
    s += bp(rng.standard_normal(n), 2000, 7000) * np.exp(-tt * 400) * 0.5
    return s


def lift():
    n = int(0.04 * SR); tt = t_(n)
    return np.sin(2 * np.pi * 3100 * tt) * np.exp(-tt * 160) * 0.5


def whoosh(d=0.42):
    n = int(d * SR); tt = t_(n); k = tt / d
    nz = rng.standard_normal(n)
    out = np.zeros(n)
    blk = 1024
    for i in range(0, n, blk):
        f = 5000 * (1 - k[i]) ** 2 + 400
        out[i:i + blk] = bp(nz[i:i + blk], f * 0.6, min(f * 1.6, 20000))
    return out * np.sin(np.pi * k) ** 2


def shimmer():
    n = int(0.9 * SR); tt = t_(n)
    s = sum(np.sin(2 * np.pi * f * tt) * a for f, a in ((1318.5, 0.5), (1975.5, 0.35), (2637, 0.2)))
    return s * np.exp(-tt * 5) * np.minimum(1, tt / 0.01)


T, Lf, Wh, Sh = tap(), lift(), whoosh(), shimmer()
for kind, at in ev['events']:
    if kind == 'tap':
        add(sfx, T, at, 0.16, pan=0.1)
    elif kind == 'lift':
        add(sfx, Lf, at, 0.06, pan=0.1)
    elif kind == 'cut':
        add(sfx, Wh, at - 0.28, 0.10, pan=-0.2)
    elif kind == 'callout':
        add(sfx, Sh, at + 0.05, 0.035, pan=0.3)
add(sfx, whoosh(0.5), ev['intro'] - 0.35, 0.16)

# ---------------------------------------------------------- voiceover
vo = np.zeros((N, 2))
duck = np.ones(N)
LINES = [('l1', 1.55), ('l2', 4.45), ('l3', 8.0), ('l4', 13.15), ('l5', 19.75), ('l6', 25.45),
         ('l7', 31.0), ('l8', 35.3), ('l9', ev['outro'] + 2.7)]
if mode == 'vo':
    for name, at in LINES:
        # ElevenLabs delivers a clean studio read: only a low cut, no reverb or saturation
        x = read(f'{T3}/vo/{name}.wav')[:, 0]
        x = sosfilt(butter(2, 80, 'high', fs=SR, output='sos'), x)
        x = x / (np.abs(x).max() + 1e-9) * 0.9
        add(vo, np.stack([x, x], 1), at, 0.72)
        i0, i1 = int((at - 0.15) * SR), int((at + len(x) / SR + 0.15) * SR)
        duck[max(0, i0):min(N, i1)] = 0.45
    # smooth the ducking curve
    k = int(0.12 * SR)
    duck = np.convolve(duck, np.ones(k) / k, mode='same')

mix = music * duck[:, None] * 0.95 + sfx + vo
from scipy.ndimage import maximum_filter1d


def limit(x, ceil, look=0.005, rel=0.08):
    """Look-ahead peak limiter on 4x-oversampled peaks (true-peak style)."""
    up = resample_poly(x, 4, 1, axis=0)
    pk = np.abs(up).max(1).reshape(-1, 4).max(1)[: len(x)]
    pk = np.pad(pk, (0, len(x) - len(pk)))
    L = int(look * SR)
    need = np.minimum(1.0, ceil / np.maximum(pk, 1e-9))
    need = -maximum_filter1d(-need, size=2 * L + 1)  # hold the min across the window
    g = np.empty_like(need); a = np.exp(-1 / (rel * SR)); cur = 1.0
    for i, v in enumerate(need):  # instant attack (window already looks ahead), smooth release
        cur = v if v < cur else a * cur + (1 - a) * v
        g[i] = cur
    k = np.hanning(L * 2 + 1); k /= k.sum()
    g = np.minimum(g, np.convolve(np.pad(g, L, mode='edge'), k, mode='valid'))
    limit.acc = limit.acc * g if hasattr(limit, 'acc') else g
    return x * g[:, None], float(g.min())


TARGET = -13.3  # the delivered trailer's loudness, so the new one plays at the same volume
TP = 10 ** (-1.0 / 20)
for _ in range(4):
    mix *= 10 ** ((TARGET - _meter.integrated_loudness(mix)) / 20)
    mix, gmin = limit(mix, TP)
mix *= 10 ** ((TARGET - _meter.integrated_loudness(mix)) / 20)
mix, gmin = limit(mix, TP)
sf.write(out_path, mix, SR, subtype='PCM_24')
gdb=20*np.log10(limit.acc)
print('reduction: >1dB %.1f%% >3dB %.1f%% of time; worst at %.2fs' % ((gdb<-1).mean()*100,(gdb<-3).mean()*100,np.argmin(gdb)/SR))
for a in range(0,47,2): print(a, round(float(gdb[a*SR:(a+2)*SR].min()),1), end=' | ')
print()
print('wrote', out_path, END, 'LUFS', round(_meter.integrated_loudness(mix), 2), 'max limiter reduction dB', round(20 * np.log10(gmin), 2))

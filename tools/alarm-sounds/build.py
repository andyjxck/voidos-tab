"""
Builds VoidOS's bundled alarm sounds from the CC0 Freesound downloads in raw/
plus a few tones synthesised here. Output: mono Ogg Vorbis in out/, and
out/sounds.json with each sound's loop point and ingredients.
"""
import json, os, subprocess
import numpy as np

SR = 44100
os.makedirs('out', exist_ok=True)


def load(sid, start=0.0, end=None):
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', f'raw/{sid}.mp3', '-ac', '1', '-ar', str(SR), '-f', 's16le', '-'],
                         capture_output=True, check=True).stdout
    x = np.frombuffer(raw, np.int16).astype(np.float32) / 32768
    a = int(start * SR)
    b = len(x) if end is None else int(end * SR)
    return x[a:b].copy()


def fade(x, fin=0.02, fout=0.05):
    x = x.copy()
    n = min(int(fin * SR), len(x) // 2)
    if n: x[:n] *= np.linspace(0, 1, n) ** 2
    n = min(int(fout * SR), len(x) // 2)
    if n: x[-n:] *= np.linspace(1, 0, n) ** 2
    return x


def sil(sec):
    return np.zeros(int(sec * SR), np.float32)


def rms_db(x):
    # loudness of the parts that sound, not the gaps
    w = SR // 10
    frames = [np.sqrt(np.mean(x[i:i + w] ** 2)) for i in range(0, len(x) - w, w)]
    frames = [f for f in frames if f > 1e-4] or [1e-4]
    loud = np.percentile(frames, 75)
    return 20 * np.log10(loud)


def level(x, target_db):
    g = 10 ** ((target_db - rms_db(x)) / 20)
    return x * g


def limit(x, ceiling_db=-1.0):
    """Soft limiter: tanh above the knee, so loud sounds get loud without hard clipping."""
    c = 10 ** (ceiling_db / 20)
    return (np.tanh(x / c) * c).astype(np.float32)


def cat(*parts):
    return np.concatenate(parts).astype(np.float32)


def ramp(x, start_db, end_db):
    g = 10 ** (np.linspace(start_db, end_db, len(x)) / 20)
    return x * g


def mix(base, layer, at):
    a = int(at * SR)
    n = max(len(base), a + len(layer))
    out = np.zeros(n, np.float32)
    out[:len(base)] += base
    out[a:a + len(layer)] += layer
    return out


def tile(x, sec):
    reps = int(np.ceil(sec * SR / len(x)))
    return np.tile(x, reps)[:int(sec * SR)]


# ---------------------------------------------------------------- synth

def beep(freq, dur, amp=1.0):
    t = np.arange(int(dur * SR)) / SR
    # a square-ish tone (odd harmonics) like a clock-radio beeper
    y = sum(np.sin(2 * np.pi * freq * k * t) / k for k in (1, 3, 5, 7))
    return fade(y.astype(np.float32) * amp * 0.6, 0.004, 0.01)


def pulse():
    """Beeps that start soft and slow and get faster and louder; ends in the classic four-beep loop."""
    parts = []
    t = 0.0
    total = 48.0
    while t < total:
        p = t / total
        gap = 1.6 - 1.35 * p
        amp = 10 ** ((-30 + 28 * p) / 20)
        b = beep(880 + 220 * p, 0.12, amp)
        parts += [b, sil(gap)]
        t += 0.12 + gap
    head = cat(*parts)
    quad = cat(*[cat(beep(1100, 0.09), sil(0.06)) for _ in range(4)], sil(0.5))
    loop = tile(quad, 6.0)
    return head, loop


# ---------------------------------------------------------------- recipes

G, N, L, F = -20.0, -15.0, -9.0, -13.0  # loudness targets: gentle, normal, loud, funny

sounds = []


def add(key, name, cat_, data, sources, loop_from=0.0, gain=None, note=''):
    sounds.append(dict(key=key, name=name, category=cat_, data=data, sources=sources, loop_from=loop_from, note=note))


# gentle
add('dawn_chorus', 'Dawn chorus', 'gentle', fade(level(load(390563), G), 0.8, 0.8), [390563])
add('singing_bowl', 'Singing bowl', 'gentle', cat(fade(level(load(400819, 14.1, 40.0), G + 2), 0.005, 6.0), sil(0.6)), [400819])
add('music_box', 'Music box', 'gentle', fade(level(load(210799, 0.0, 28.6), G), 0.02, 0.3), [210799])
add('wind_chimes', 'Wind chimes', 'gentle', fade(level(load(437337, 7.0, 52.0), G), 2.0, 2.0), [437337])

# normal
add('twin_bell', 'Twin bell clock', 'normal', fade(level(load(743963, 0.45, 29.5), N), 0.01, 0.2), [743963])
add('digital', 'Digital clock', 'normal', fade(level(load(434055, 0.0, 12.0), N), 0.005, 0.01), [434055])
add('rotary_phone', 'Rotary phone', 'normal', fade(level(load(456433, 6.9, 30.9), N), 0.01, 0.3), [456433])
add('cuckoo', 'Cuckoo clock', 'normal', cat(fade(level(load(128177, 0.0, 10.7), N), 0.01, 0.4), sil(1.5)), [128177])

# loud
add('school_bell', 'School bell', 'loud', limit(cat(fade(level(load(378394, 0.0, 3.3), L), 0.005, 0.3), sil(0.7))), [378394])
add('fog_horn', 'Fog horn', 'loud', limit(cat(fade(level(load(437683, 0.0, 3.8), L + 2), 0.02, 1.2), sil(1.0))), [437683])
add('air_raid', 'Air raid siren', 'loud', limit(fade(level(load(412171, 0.5, 40.5), L), 0.3, 0.3)), [412171])
horn = fade(level(load(414208, 0.0, 1.45), L + 1), 0.005, 0.08)
add('air_horn', 'Air horn', 'loud', limit(cat(horn, sil(0.35), horn, sil(0.35), horn, sil(1.6))), [414208])

# funny
add('rooster', 'Rooster', 'funny', cat(fade(level(load(435508, 0.0, 2.0), F), 0.01, 0.15), sil(2.2)), [435508])
add('goat', 'Screaming goat', 'funny', cat(fade(level(load(750725, 15.8, 24.0), F), 0.2, 0.4), sil(0.8)), [750725],
    note='bleats from a longer farm recording')
quack = fade(level(load(418509), F), 0.005, 0.05)
toys = fade(level(load(348791, 0.0, 4.2), F - 1), 0.01, 0.3)
add('ducks', 'Duck parade', 'funny', cat(quack, sil(0.35), quack, sil(0.35), quack, sil(0.8), toys, sil(1.0)), [418509, 348791])
add('bugle', 'Bugle call', 'funny', cat(fade(level(load(438633, 0.3, 15.0), F), 0.01, 0.5), sil(1.5)), [438633])
add('slide_whistle', 'Slide whistle', 'funny', fade(level(load(202753, 0.0, 20.8), F), 0.01, 0.2), [202753])

# progressive: quiet at first, louder over a minute or so, then loop the loud end
# Sunrise: birds alone, then chimes, then the music box, then the twin bell clock
birds = load(390563)
birds = level(birds, G)
sun = ramp(tile(birds, 70.0), -24, 0)
sun = mix(sun, ramp(level(load(437337, 7.0, 47.0), G), -24, -4), 22.0)
sun = mix(sun, ramp(level(load(210799, 0.0, 28.6), G), -14, 0), 42.0)
sun = fade(sun, 1.5, 4.0)
bell = fade(level(load(743963, 0.45, 29.5), N), 0.01, 0.2)
sun = cat(sun[:int(68 * SR)], bell)
add('sunrise', 'Sunrise', 'progressive', limit(sun), [390563, 437337, 210799, 743963], loop_from=68.0,
    note='mix of four CC0 recordings: birds, then wind chimes, then the music box, then the twin bell clock')

# Escalation: a duck, a rooster, the bugle, then the air horn on repeat
esc = cat(
    ramp(cat(quack, sil(2.5), quack, sil(2.5)), -18, -12),
    ramp(cat(fade(level(load(435508, 0.0, 2.0), F), 0.01, 0.15), sil(2.0)), -10, -10),
    ramp(cat(fade(level(load(435508, 0.0, 2.0), F), 0.01, 0.15), sil(1.5)), -6, -6),
    ramp(cat(fade(level(load(438633, 0.3, 15.0), F), 0.01, 0.5), sil(1.0)), -4, 0),
)
esc_loop = cat(horn, sil(0.35), horn, sil(0.35), horn, sil(1.6))
add('escalation', 'Escalation', 'progressive', limit(cat(esc, esc_loop)), [418509, 435508, 438633, 414208], loop_from=len(esc) / SR,
    note='duck, rooster, bugle, then the air horn on repeat')

head, loop = pulse()
add('pulse', 'Pulse', 'progressive', limit(level(cat(head, loop), N) if False else cat(head, loop) * 1.5), [], loop_from=len(head) / SR,
    note='synthesised for VoidOS: soft slow beeps that speed up and get louder, then the classic four-beep pattern')

# ---------------------------------------------------------------- encode

meta = json.load(open('meta.json'))
index = []
total = 0
import pyloudnorm as pyln
meter = pyln.Meter(SR)
TARGET = {'gentle': -20.0, 'normal': -14.0, 'loud': -9.0, 'funny': -13.0, 'progressive': -11.0}


def fit(x, lufs, loop_from):
    # gain plus the soft limiter until the part that loops reaches the target loudness
    g = 1.0
    for _ in range(8):
        y = limit(x * g)
        seg = y[int(loop_from * SR):]
        if len(seg) < SR: seg = np.tile(seg, 3)
        cur = meter.integrated_loudness(seg.astype(np.float64))
        if abs(cur - lufs) < 0.3: break
        g *= 10 ** ((lufs - cur) / 20)
    return y, cur


for s in sounds:
    x, s['lufs'] = fit(s['data'], TARGET[s['category']], s['loop_from'])
    pcm = (np.clip(x, -1, 1) * 32767).astype(np.int16).tobytes()
    fn = f"out/alarm_{s['key']}.ogg"
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 's16le', '-ar', str(SR), '-ac', '1', '-i', '-',
                    '-c:a', 'libvorbis', '-q:a', '1', fn], input=pcm, check=True)
    size = os.path.getsize(fn)
    total += size
    index.append(dict(key=s['key'], name=s['name'], category=s['category'], seconds=round(len(x) / SR, 2),
                      loop_from=round(s['loop_from'], 3), bytes=size, lufs=round(s['lufs'], 1), note=s['note'],
                      sources=[dict(id=i, title=meta[str(i)]['title'].replace('Freesound - ', ''), url=meta[str(i)]['url'],
                                    licence='CC0 1.0 (public domain dedication)') for i in s['sources']]))
    print(f"{s['lufs']:6.1f} LUFS {s['key']:14s} {len(x) / SR:6.1f}s loop@{s['loop_from']:5.1f} {size // 1024:4d} KB  peak {20 * np.log10(np.max(np.abs(x)) + 1e-9):.1f} dB")
json.dump(index, open('out/sounds.json', 'w'), indent=1)
print('total', total // 1024, 'KB')

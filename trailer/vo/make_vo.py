#!/usr/bin/env python3
"""Voiceover lines for the VoidOS trailer, made with ElevenLabs (voice: George).
One request per line, with the lines around it as context so the read flows.
Writes vo/<name>.mp3 and vo/<name>.wav (48 kHz mono)."""
import json, subprocess, sys, urllib.request

KEY = open('/home/sprite/tr3/.xi_key').read().strip()
VOICE = sys.argv[1] if len(sys.argv) > 1 else 'JBFqnCBsd6RMkjVDRZzb'  # George
ONLY = sys.argv[2:] if len(sys.argv) > 2 else None
LINES = json.load(open('/home/sprite/tr3/vo/lines.json'))

for i, (name, text) in enumerate(LINES):
    if ONLY and name not in ONLY:
        continue
    body = {
        'text': text,
        'model_id': 'eleven_multilingual_v2',
        'voice_settings': {'stability': 0.42, 'similarity_boost': 0.8, 'style': 0.22, 'use_speaker_boost': True, 'speed': 0.97},
        'previous_text': ' '.join(t for _, t in LINES[:i]) or None,
        'next_text': ' '.join(t for _, t in LINES[i + 1:]) or None,
    }
    req = urllib.request.Request(
        f'https://api.elevenlabs.io/v1/text-to-speech/{VOICE}?output_format=mp3_44100_128',
        data=json.dumps(body).encode(), method='POST',
        headers={'xi-api-key': KEY, 'Content-Type': 'application/json', 'Accept': 'audio/mpeg'})
    with urllib.request.urlopen(req, timeout=120) as r:
        audio = r.read()
    mp3 = f'/home/sprite/tr3/vo/{name}.mp3'
    open(mp3, 'wb').write(audio)
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', mp3, '-ar', '48000', '-ac', '1', f'/home/sprite/tr3/vo/{name}.wav'], check=True)
    print(name, len(audio), 'bytes')

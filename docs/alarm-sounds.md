# Alarm sounds

VoidOS ships 20 alarm sounds in `app/src/main/res/raw/alarm_*.ogg` (mono Ogg Vorbis, about 3.3 MB in total).
Every recording comes from [Freesound](https://freesound.org) under **Creative Commons 0 (CC0 1.0)**: the authors dedicated them to the public domain, so they can be bundled, edited and shipped in an app with no attribution or fee. They are credited here anyway, with thanks.
Licence text: https://creativecommons.org/publicdomain/zero/1.0/

Each sound was trimmed, faded, levelled to a loudness for its group (gentle quietest, loud loudest) and soft-limited by `tools/alarm-sounds/build.py`, which rebuilds them all from the downloads (`fs_get.py <user>/<id> ...` fetches them; `meta.json` records each page and its licence as read on 18 November 2026). Pulse is synthesised by that script, not recorded.

Progressive sounds start almost silent and get louder on their own, then loop only their loud end. Any other sound can get gradual volume too: the alarm's Gradual volume setting fades it in over 30 seconds to 5 minutes.

## Gentle

- **Dawn chorus** (`alarm_dawn_chorus.ogg`, 36 s, 266 KB): [Morning-Birds.mp3](https://freesound.org/people/dadshb1489/sounds/390563/) by dadshb1489. Licence: CC0 1.0.
- **Singing bowl** (`alarm_singing_bowl.ogg`, 26 s, 161 KB): [Tibetan singing bowl](https://freesound.org/people/enhuber/sounds/400819/) by enhuber. Licence: CC0 1.0.
- **Music box** (`alarm_music_box.ogg`, 29 s, 220 KB): [gentle music box](https://freesound.org/people/Yuval/sounds/210799/) by Yuval. Licence: CC0 1.0.
- **Wind chimes** (`alarm_wind_chimes.ogg`, 45 s, 327 KB): [Wind chimes 1](https://freesound.org/people/giddster/sounds/437337/) by giddster. Licence: CC0 1.0.

## Classic

- **Twin bell clock** (`alarm_twin_bell.ogg`, 29 s, 320 KB): [Twin Bell Alarm Clock Continuous Ringing](https://freesound.org/people/cryptidcat/sounds/743963/) by cryptidcat. Licence: CC0 1.0.
- **Digital clock** (`alarm_digital.ogg`, 12 s, 90 KB): [Digital Alarm Clock Sound Effect](https://freesound.org/people/israra/sounds/434055/) by israra. Licence: CC0 1.0.
- **Rotary phone** (`alarm_rotary_phone.ogg`, 24 s, 179 KB): [Old Rotary Phone Ringing - Western Electric Bell 500 phone](https://freesound.org/people/mycompasstv/sounds/456433/) by mycompasstv. Licence: CC0 1.0.
- **Cuckoo clock** (`alarm_cuckoo.ogg`, 12 s, 73 KB): [Cuckoo Clock.wav](https://freesound.org/people/DiArchangeli/sounds/128177/) by DiArchangeli. Licence: CC0 1.0.

## Loud

- **School bell** (`alarm_school_bell.ogg`, 4 s, 25 KB): [School bell.wav](https://freesound.org/people/deleted_user_7020630/sounds/378394/) by deleted_user_7020630. Licence: CC0 1.0.
- **Fog horn** (`alarm_fog_horn.ogg`, 5 s, 26 KB): [Loud fog horn](https://freesound.org/people/SamsterBirdies/sounds/437683/) by SamsterBirdies. Licence: CC0 1.0.
- **Air raid siren** (`alarm_air_raid.ogg`, 40 s, 242 KB): [Air Raid Siren Alarm](https://freesound.org/people/Poligonstudio/sounds/412171/) by Poligonstudio. Licence: CC0 1.0.
- **Air horn** (`alarm_air_horn.ogg`, 7 s, 31 KB): [Airhorn](https://freesound.org/people/jacksonacademyashmore/sounds/414208/) by jacksonacademyashmore. Licence: CC0 1.0.

## Funny

- **Rooster** (`alarm_rooster.ogg`, 4 s, 17 KB): [Rooster Crow 1](https://freesound.org/people/BenjaminNelan/sounds/435508/) by BenjaminNelan. Licence: CC0 1.0.
- **Screaming goat** (`alarm_goat.ogg`, 9 s, 63 KB): [Goat in heat](https://freesound.org/people/Sadiquecat/sounds/750725/) by Sadiquecat. Licence: CC0 1.0. Bleats from a longer farm recording.
- **Duck parade** (`alarm_ducks.ogg`, 9 s, 52 KB): [single quack from a duck](https://freesound.org/people/Mikes-MultiMedia/sounds/418509/) by Mikes-MultiMedia, [Toy Ducks Quacking](https://freesound.org/people/nebyoolae/sounds/348791/) by nebyoolae. Licence: CC0 1.0.
- **Bugle call** (`alarm_bugle.ogg`, 16 s, 108 KB): [G39-16-Bugle Call.wav](https://freesound.org/people/craigsmith/sounds/438633/) by craigsmith. Licence: CC0 1.0.
- **Slide whistle** (`alarm_slide_whistle.ogg`, 21 s, 149 KB): [Slide Whistle](https://freesound.org/people/sheepfilms/sounds/202753/) by sheepfilms. Licence: CC0 1.0.

## Builds up (progressive)

- **Sunrise** (`alarm_sunrise.ogg`, 97 s, 809 KB): [Morning-Birds.mp3](https://freesound.org/people/dadshb1489/sounds/390563/) by dadshb1489, [Wind chimes 1](https://freesound.org/people/giddster/sounds/437337/) by giddster, [gentle music box](https://freesound.org/people/Yuval/sounds/210799/) by Yuval, [Twin Bell Alarm Clock Continuous Ringing](https://freesound.org/people/cryptidcat/sounds/743963/) by cryptidcat. Licence: CC0 1.0. Mix of four CC0 recordings: birds, then wind chimes, then the music box, then the twin bell clock.
- **Escalation** (`alarm_escalation.ogg`, 36 s, 174 KB): [single quack from a duck](https://freesound.org/people/Mikes-MultiMedia/sounds/418509/) by Mikes-MultiMedia, [Rooster Crow 1](https://freesound.org/people/BenjaminNelan/sounds/435508/) by BenjaminNelan, [G39-16-Bugle Call.wav](https://freesound.org/people/craigsmith/sounds/438633/) by craigsmith, [Airhorn](https://freesound.org/people/jacksonacademyashmore/sounds/414208/) by jacksonacademyashmore. Licence: CC0 1.0. Duck, rooster, bugle, then the air horn on repeat.
- **Pulse** (`alarm_pulse.ogg`, 54 s, 61 KB): synthesised in build.py (no recording). Licence: CC0 1.0. Synthesised for VoidOS: soft slow beeps that speed up and get louder, then the classic four-beep pattern.

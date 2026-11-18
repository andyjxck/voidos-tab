# VoidOS Tab Edition

VoidOS for the Lenovo Tab M10 HD 2nd Gen (TB-X306F, Helio P22T, Android 10).
The sister of [VoidOS for PineTime](https://github.com/andyjxck/VoidOS): same
octagon, same six lap colours, same Kenney Future type. The logo is "void"
written as a square root: the root sign is the V, "oid" sits under its bar.

It isn't a ROM. It's a Kotlin + Compose app that takes over the tablet as its
**device owner**: it becomes the home screen, replaces the whole UI, and uses
Android's device policy controls to hide extras, hold off updates, hide the
bars and pin itself in lock mode. No root needed.

## What's in it

| Screen | What it does |
| --- | --- |
| Home | The date and a large, thin clock, running timers as small pills underneath, and optional widgets (weather, music, the list, notes) you hold to drag anywhere |
| Tools | Timers that ring over everything (and keep going in the background), alarms (below), a unit converter (weight, volume, temperature incl. gas marks, length) and a list sorted by aisle |
| Weather | Open-Meteo weather (no key): now, 12-hour graph, six days; last known weather when offline |
| Notes | Like Notes on an iPad: list by day with search, the first line is the title, a floating pen capsule for drawing (7 colours, 4 sizes, solid/dotted/dashed, eraser, undo, clear), any note can go on Home as a widget |
| Sound | One now-playing card for whatever is playing, YouTube, Spotify or a file on the tablet (artwork, progress, controls, volume; apps need notification access), your music files in a sidebar behind Files, brown noise made on-device |
| Apps | A plain icon grid of only the apps you switch on in Manage that will actually open; everything else is hidden from VoidOS. Search, starred apps in a dock (long-press), quick actions |
| Settings | A grouped list (Connections, Screen, VoidOS, Tablet) with the page beside it, or over it when upright |

**Alarms** (Tools > Alarms): set the time on iPhone-style wheels, repeat on any days (or once), label, snooze length (or no snooze), volume, and gradual volume that fades any sound in over 30 s to 5 min. Skip the next ring of a repeating alarm. 20 bundled sounds in five groups: gentle, classic, loud, funny, and three that build up on their own (Sunrise, Escalation, Pulse). Alarms use Android's exact alarm-clock timer, wake the screen and ring full screen over the lock screen and the VoidOS lock, play on the alarm channel so Do Not Disturb lets them through, are put back after a reboot or a clock change, and stop after 15 minutes with a missed-alarm note. The next alarm within a day shows under the clock on Home. Sounds, sources and licences (all CC0): [`docs/alarm-sounds.md`](docs/alarm-sounds.md).

Getting around (no sidebar):

- **Swipe left or right** on a screen to move to the next one (Settings > Home & lock screen can turn this off).
- **Left edge: the tab wheel.** Swipe in from the left edge (or rest a finger on it) and a glass wheel of every screen opens under it. Slide down, up or out to pick; let go to open it. A tap on the edge leaves the wheel open so you can tap a screen instead.
- **Right edge: the app wheel.** The same for apps you've opened from VoidOS. Its centre opens Apps. It only works while VoidOS is on screen: once another app is in front, Android gives the edges to that app. Edge touches are decided in `MainActivity` before the page sees them (`ui/EdgeSwipe.kt`): the outer 34 dp always opens a wheel, and a quick inward flick that starts up to 64 dp in does too; a slow drag there is still a page swipe.
- **Top edge: the control centre.** Pull down from the status line, or tap it: brightness (and auto), volume, Wi-Fi, Bluetooth, do not disturb, rotation lock, stay awake, screen off, what's playing, Settings and Lock. There is no way out of VoidOS in it.

Also: the VoidOS logo (`docs/logo`, drawn in `ui/Logo.kt`; the square root
and its o on their own are the launcher icon, with a themed monochrome
version), an in-app start animation once per power-on, the VoidOS lock screen (every time the screen
goes off; optional PIN), a drifting ambient desk clock, desk mode (screen
stays on while charging), and every screen works in portrait as well as
landscape (Settings > Display & sound > Rotation, or Rotation lock in the control centre).

**Root/flash only:** the power-on logo (a VoidOS `logo.bin` built from your
tablet's own logo partition), the Android boot animation, and VoidOS as a
system app. With those, every stage from power button to home screen is
VoidOS: logo, boot animation, lock screen, home screen. Everything for it,
and the steps, is in [`root/README.md`](root/README.md).

## Escape hatch (you can't get locked out)

1. **On the tablet:** there is one hidden way out, with its own fixed code
   (not the lock screen PIN). It is deliberately not written down here and
   nothing on screen hints at it. From there: visit Android settings, turn lock
   mode off, or release VoidOS completely (removes device owner).
2. **From a computer (adb):**
   ```
   adb shell am broadcast -a com.andysocial.voidos.action.UNLOCK -n com.andysocial.voidos/.ReleaseReceiver
   adb shell dpm remove-active-admin com.andysocial.voidos/.VoidAdmin
   adb uninstall com.andysocial.voidos
   ```
   The APK is marked `testOnly`, which is what lets `dpm remove-active-admin`
   remove a device owner over adb. The UNLOCK/RELEASE broadcasts can only be
   sent from adb (they require the DUMP permission).
3. **Last resort:** factory reset from recovery (power + volume up).

## Install

Device owner can only be set when no account (Google or other) has been added
to the tablet. If one has, factory reset and skip every account during setup.

```
adb install -t root/voidos-tab-1.10.0.apk
adb shell dpm set-device-owner com.andysocial.voidos/.VoidAdmin
adb shell am start -n com.andysocial.voidos/.MainActivity
```

The edge wheels also work inside other apps (1.9.0+). That needs "draw over
other apps": VoidOS asks root for it once (tap Allow in Magisk), or run
`adb shell appops set com.andysocial.voidos SYSTEM_ALERT_WINDOW allow`.

Update later with `adb install -r -t <apk>` (same signing key on every build,
debug or release, so updates keep device owner). `root/` has the current
release APK with everything else for flashing day.

## Build and test

Every push to `main` builds on GitHub Actions and puts the APK on the `latest`
release. Locally (JDK 17 and the Android SDK, platform 35):

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk (the one to install)
./gradlew testDebugUnitTest    # unit tests + every screen in landscape and portrait
./gradlew lintDebug
```

The screen tests (`app/src/test/.../ScreensTest.kt`) render every screen on
the JVM with Robolectric at the tablet's size in landscape and portrait, and
at Android's next display size up, check what each shows, swipe and PIN the
lock screen, and audit the layout for cut-off, overlapping or off-screen
text. Pictures of each screen land in `docs/screens/` with the audit in
`docs/screens/audit.txt`.

`tools/` rebuilds the generated art: `logo_build.py` (the logo everywhere:
docs/logo, the launcher icons, `ui/LogoData.kt` and the geometry the other
tools draw from; needs shapely), `bootanim.py` (root/bootanimation.zip), `magisk_module.py` (both Magisk
modules) and `mtk_logo.py` (the power-on logo, from a dump of the tablet's
logo partition). `python3 tools/test_mtk_logo.py` and
`python3 tools/test_magisk_module.py` test the last two, and
`make_release.py` gathers the flashing-day kit in `root/`. The logo drafts
(`tools/logo_concepts.py`, `logo_root_v*.py`, `docs/logo/concepts/`) and the
loading animation (`tools/logo_loading_anim.py`, `docs/logo/anim/`) are kept
for reference.

`app/voidos-debug.keystore` is committed on purpose so every build has the same
signature. Don't use it for a store release.

Fonts: Kenney Future and Jupiteroid, both CC0, from the PineTime VoidOS repo.

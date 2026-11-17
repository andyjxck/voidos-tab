# VoidOS Tab Edition

VoidOS for the Lenovo Tab M10 HD 2nd Gen (TB-X306F, Helio P22T, Android 10).
The sister of [VoidOS for PineTime](https://github.com/andyjxck/VoidOS): same
octagon, same six lap colours, same Kenney Future type.

It isn't a ROM. It's a Kotlin + Compose app that takes over the tablet as its
**device owner**: it becomes the home screen, replaces the whole UI, and uses
Android's device policy controls to hide extras, hold off updates, hide the
bars and pin itself in lock mode. No root needed.

## What's in it

| Module | What it does |
| --- | --- |
| Face | Octagon clock (sixty ticks round an octagon fill in the minute's lap colour; AM/PM, next timer), sky, day progress, tiles for timers, the list and notes |
| Kitchen | Several named kitchen timers that ring over everything (and keep going in the background), a unit converter (weight, volume, oven incl. gas marks, length, cups to grams) and a shopping list sorted by aisle |
| Sky | Open-Meteo weather (no key): now, 12-hour graph, six days. Place is changeable |
| Notes | Notes saved on the tablet, autosave, six colours |
| Sound | Media controls for whatever is playing (Spotify, YouTube, BBC Sounds...: needs notification access), music files on the tablet, brown "void noise" made on-device |
| Focus | Octagon focus timer that keeps running in the background |
| House | Reserved slot for Void Home. Not connected yet |
| Apps | Android app drawer: only the apps you've allowed, with search, recents, and a Manage view. Icons in three styles: VoidOS glyphs, letters, or the apps' own |
| System | Wi-Fi, Bluetooth, screen + sound, hub + lock, do not disturb, date + time, battery, network, storage + apps, Android settings, boot + root, VoidOS rules, About |

Also: the VoidOS logo (`docs/logo`, drawn in `ui/Logo.kt`), an in-app start
animation once per power-on, the VoidOS lock screen (every time the screen
goes off; optional PIN), a drifting ambient desk clock, desk mode (screen
stays on while charging), and every screen works in portrait as well as
landscape (System > Screen + sound > Rotation).

**Root/flash only:** the real Android boot animation. Everything for it, and
the steps, is in [`root/README.md`](root/README.md).

## Escape hatch (you can't get locked out)

1. **On the tablet:** hold the VoidOS mark at the top of the left rail for
   3 seconds, enter your PIN. From there: visit Android settings, turn lock mode
   off, or release VoidOS completely (removes device owner).
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
adb install -t voidos-tab-debug.apk
adb shell dpm set-device-owner com.andysocial.voidos/.VoidAdmin
adb shell am start -n com.andysocial.voidos/.MainActivity
```

Update later with `adb install -r -t voidos-tab-debug.apk` (same signing key on
every build, so updates keep device owner).

## Build and test

Every push to `main` builds on GitHub Actions and puts the APK on the `latest`
release. Locally (JDK 17 and the Android SDK, platform 35):

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/voidos-tab-debug.apk
./gradlew testDebugUnitTest    # unit tests + every screen in landscape and portrait
./gradlew lintDebug
```

The screen tests (`app/src/test/.../ScreensTest.kt`) render every screen on
the JVM with Robolectric at the tablet's size in landscape and portrait, and
at Android's next display size up, check what each shows, swipe and PIN the
lock screen, and audit the layout for cut-off, overlapping or off-screen
text. Pictures of each screen land in `docs/screens/` with the audit in
`docs/screens/audit.txt`.

`tools/` rebuilds the generated art: `logo.py` (docs/logo),
`bootanim.py` (root/bootanimation.zip) and `magisk_module.py`.

`app/voidos-debug.keystore` is committed on purpose so every build has the same
signature. Don't use it for a store release.

Fonts: Kenney Future and Jupiteroid, both CC0, from the PineTime VoidOS repo.

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
| Face | Giant octagon lap-ring clock, sky, day progress, latest note, power |
| Sky | Open-Meteo weather (no key): now, 12-hour graph, six days. Place is changeable |
| Notes | Notes saved on the tablet, autosave, six colours |
| Sound | Music files on the tablet, media keys for Spotify etc., brown "void noise" made on-device |
| Focus | Octagon focus timer that keeps running in the background |
| House | Reserved slot for Void Home. Not connected yet |
| Apps | Drawer that only shows apps you've allowed |
| System | Wi-Fi, brightness, screen timeout, volumes, Bluetooth, VoidOS rules, About |

Also: an in-app start animation once per power-on, a drifting ambient desk clock,
and desk mode (screen stays on while charging).

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

## Build

Every push to `main` builds on GitHub Actions and puts the APK on the `latest`
release. Locally: `./gradlew assembleDebug`.

`app/voidos-debug.keystore` is committed on purpose so every build has the same
signature. Don't use it for a store release.

Fonts: Kenney Future and Jupiteroid, both CC0, from the PineTime VoidOS repo.

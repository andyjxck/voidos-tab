# ROOT: the parts of VoidOS that need root or flashing

## Flashing-day kit (everything is in this folder)

| File | What it is | Used in |
| --- | --- | --- |
| `voidos-tab-1.3.0.apk` | The VoidOS app, release build (same fixed key as every build, so it installs over any earlier one and keeps device owner) | main README, Install; after unlocking (section 1) |
| `voidos-boot-magisk.zip` | Magisk module: the Android boot animation | section 3 |
| `bootanimation.zip` | The boot animation on its own, for a hand copy or your own firmware | section 3 |
| `voidos-system-magisk.zip` | Magisk module: VoidOS as a system app (optional) | section 4 |
| `logo-tool/` | The power-on logo tool, runs on its own with Python 3 + Pillow | section 5 |
| `SHA256SUMS.txt` | Checksums of all of the above (`sha256sum -c SHA256SUMS.txt`) | before you start |
| `preview/` | What the boot animation and the power-on logo should look like | sections 3 and 5 |

The logo on all of them is the square-root "void" wordmark: the root sign is
the V, "oid" sits under its bar in the six lap colours, and the i's dot is the
teal pill. While Android boots, the dot slides between the purple and green
bars. Rebuild everything with `tools/make_release.py` (its header lists the
steps).

## What you see, from power button to home screen

| Stage | Drawn by | Made VoidOS by | Status |
| --- | --- | --- | --- |
| 1. Power-on logo (the still "Lenovo" splash) | MediaTek bootloader (LK), from the `logo` partition | `tools/mtk_logo.py` builds a VoidOS `logo.bin` from a dump of **your** partition; you flash it (section 5) | Tool built and tested; flashing is yours |
| 1b. "Orange state" warning (only once the bootloader is unlocked, if your firmware shows one) | LK itself, not the logo partition | Nothing here: it would mean patching LK (section 7) | Not done, on purpose |
| 2. Boot animation | Android's `bootanimation` service | `voidos-boot-magisk.zip` (section 3) | Ready |
| 3. Lock screen | VoidOS (Android's keyguard switched off) | No root needed: device owner + "VoidOS lock screen" | Ready |
| 4. Home screen | VoidOS (device owner, persistent Home) | No root needed | Ready |
| VoidOS as part of the system | Android package manager | `voidos-system-magisk.zip` (section 4, optional) | Built and checked; not yet run on the tablet |

VoidOS > System > Boot + root shows stages 2 to 4 live on the tablet ("What
the tablet shows"), plus the firmware, bootloader, Magisk and module status.
It can't see the logo partition, so it never claims stage 1.

### The lock screen: why it's VoidOS's, not Android's

Android's lock screen (the keyguard) is part of SystemUI, which Lenovo signs.
Replacing it outright means rebuilding SystemUI, i.e. building whole firmware.
VoidOS gets the same result without that: as device owner it switches the
keyguard off (System > VoidOS rules > "No lock screen") and shows its own lock
screen at power-on and every time the screen goes off, in front of whatever
app was open. So Android's lock screen never appears. Two limits:

* It only works while Android itself has **no** PIN, pattern or password. If
  one is set, Android's keyguard stays in force and Boot + root says so. Use
  VoidOS's own "PIN to unlock" instead.
* VoidOS's PIN keeps people out of the hub, not out of the data: anyone with
  USB debugging access or a recovery can still get in. That's the trade for
  not having Android's encrypted keyguard in front.

### Why it's still Android underneath

Everything you see can be VoidOS, but Android stays underneath as the engine
(drivers, Wi-Fi, Bluetooth, apps). A non-Android OS isn't realistic on this
tablet today: the only Linux port found for the TB-X306F is an early bring-up
where internal storage, touch, battery and Wi-Fi don't work yet. Treble GSIs
(generic Android builds) and unofficial LineageOS builds do exist for it, but
they are still Android with a different coat, and VoidOS runs the same on them
as on stock. Stock firmware plus Magisk is the least risky way to get every
stage above.

---

**These steps are for the Lenovo Tab M10 HD 2nd Gen (TB-X306F, MediaTek Helio
P22T, Android 10), the tablet this repo is built for. On any other tablet the
unlock and flash steps are different: look up your own model first.**

---

## Before you start

* **Unlocking the bootloader wipes the tablet.** That also removes VoidOS as
  device owner. Afterwards, set it up again exactly as in the main README
  (skip every account during setup, then `adb shell dpm set-device-owner ...`).
* Back up anything on the tablet.
* You need `adb` and `fastboot` (Android platform-tools) on the computer, a
  USB cable, and the battery over 50%.
* On Windows, fastboot often sits at `< waiting for device >` until the
  MediaTek USB drivers are installed. macOS and Linux need no drivers.
* Keep Lenovo Rescue and Smart Assistant (RSA) handy: its **Rescue** button
  re-flashes stock firmware if anything goes wrong (it re-locks the bootloader
  and wipes the tablet). Download the firmware in RSA **before** you change
  anything, while the tablet still works, so a rescue never depends on it.
* Recovery has limits. RSA and fastboot fix anything above the bootloader.
  A broken bootloader or preloader (only ever at risk from section 7, which
  this repo does not do) can need MediaTek's BROM mode and SP Flash Tool or
  mtkclient with a matching scatter file, and may not be recoverable at home.

## 1. Unlock the bootloader (wipes the tablet)

1. Settings > About tablet > tap **Build number** seven times.
2. Settings > System > Developer options: turn on **OEM unlocking** and
   **USB debugging**.
   VoidOS hides Settings: open it from the escape hatch (hold the VoidOS mark
   on the rail for 3 seconds, enter your PIN, "Open Android settings").
3. On the computer:
   ```
   adb reboot bootloader
   fastboot flashing unlock
   ```
   Confirm on the tablet with the volume keys, then `fastboot reboot`.

## 2. Root with Magisk

1. In RSA: Rescue > your tablet's serial number > download the firmware. RSA's
   download folder then has the stock `boot.img` and `vbmeta.img` for **the
   exact build on your tablet**. Check that build with
   `adb shell getprop ro.build.display.id` (also shown in VoidOS under
   System > Boot + root); a `boot.img` from another build can boot-loop.
2. Copy `boot.img` to the tablet, install the Magisk app, then Magisk >
   Install > **Select and patch a file** > `boot.img`. Copy the
   `magisk_patched-....img` it writes to Downloads back to the computer.
3. Flash it. The `vbmeta` line is needed on this tablet: without it a
   patched boot image boot-loops.
   ```
   adb reboot bootloader
   fastboot --disable-verity --disable-verification flash vbmeta vbmeta.img
   fastboot flash boot magisk_patched-XXXXX.img
   fastboot reboot
   ```
4. Open Magisk: it should show it's installed. VoidOS > System > Boot + root
   should now say Root (Magisk): Yes and Bootloader: Unlocked.

## 3. Install the boot animation

**With Magisk (recommended, systemless):**

```
adb push root/voidos-boot-magisk.zip /sdcard/Download/
```

Magisk > Modules > **Install from storage** > `voidos-boot-magisk.zip`,
then reboot. VoidOS > System > Boot + root shows "VoidOS boot module: 1.0"
once it's in. To undo, remove the module in Magisk and reboot.

The module overlays `/system/media/bootanimation.zip` and
`/product/media/bootanimation.zip`. If the stock animation still plays, the
tablet's firmware keeps its animation somewhere else; find it with
```
adb shell ls -l /system/media /product/media /oem/media /vendor/media 2>/dev/null | grep -i boot
```
`/oem` can't be overlaid by Magisk; then copy the file by hand as root
(back up the original first):
```
adb push root/bootanimation.zip /sdcard/
adb shell su -c 'mount -o rw,remount /oem && cp /oem/media/bootanimation.zip /sdcard/bootanimation.stock.zip && cp /sdcard/bootanimation.zip /oem/media/ && chmod 644 /oem/media/bootanimation.zip'
```

**Try it without rebooting** (root):
```
adb shell su -c 'setprop service.bootanim.exit 0; setprop ctl.start bootanim'
adb shell su -c 'setprop service.bootanim.exit 1; setprop ctl.stop bootanim'
```

**In a flashed system image** (building your own firmware or GSI): put
`bootanimation.zip` at `/product/media/bootanimation.zip` (or
`/system/media/`), mode 644, owner root.

### Rebuilding the animation

`bootanimation.zip` is 800 x 1280 (the TB-X306F panel in its natural
portrait orientation), 30 fps: `part0` plays the 2.4 s intro once (the
wordmark wipes in, the i's dot pops, TAB EDITION fades in), `part1` loops
until Android finishes booting (the dot slides into the green bar and back,
then the purple one, like liquid glass). The files are stored without
compression, which Android requires. For another screen:
```
adb shell wm size                                  # e.g. Physical size: 1200x1920
python3 tools/bootanim.py --size 1200x1920
python3 tools/magisk_module.py
```
(needs Python 3 and Pillow: `pip install pillow`). Previews of three frames
are in `root/preview/`.

## 4. VoidOS as a system app (optional)

`voidos-system-magisk.zip` puts VoidOS into `/system/priv-app/VoidOS`
(systemlessly, like the boot module), with the allowlist that grants it
`SET_TIME_ZONE` and `WRITE_SECURE_SETTINGS`. What you get: System > Date + time
can set the time zone on Android 10, and VoidOS is part of the system rather
than a downloaded app. Your settings, device owner and data stay, because the
APK is signed with the same key as the one already installed.

Risk: Android 9 and newer refuse to boot if a privileged app asks for a
privileged permission no allowlist grants. `tools/test_magisk_module.py`
checks the built module against the real Android 10 and 11 permission lists
(`tools/testdata/`), and the module refuses to install below Android 10. If
the tablet still sticks on the boot animation after installing it:

1. Wait two minutes (first boot after it re-optimises VoidOS).
2. `adb wait-for-device shell su -c 'magisk --remove-modules'` (removes all
   modules and reboots), or boot into Magisk's safe mode, then delete the
   module in Magisk.
3. Last resort: flash the stock `boot.img` from RSA with fastboot; that
   unroots and so drops every module.

Install the same way as the boot module: `adb push
root/voidos-system-magisk.zip /sdcard/Download/`, Magisk > Modules > Install
from storage, reboot. Boot + root then shows "VoidOS is a system app: Yes".
Remove the module and reboot to make VoidOS an ordinary app again.

After a VoidOS update (`adb install -r -t ...`) rebuild the module too
(`./gradlew assembleRelease && python3 tools/magisk_module.py system`) so the
system copy isn't older than the installed one. The module takes the release
APK when there is one, else the debug one.

## 5. Boot logo (the power-on splash)

The still logo LK shows before the boot animation lives in the MediaTek
`logo` partition: a 512-byte header, a table of offsets and a few dozen
zlib-packed pictures (boot logo, kernel logo, off-mode charging and battery
pictures). `tools/mtk_logo.py` reads a dump of **your** partition and swaps
only the full-screen boot pictures for the VoidOS logo, keeping the number,
order, pixel format and size of every picture and copying the rest byte for
byte. It checks its own output before it writes it, and refuses anything that
doesn't look like a MediaTek logo partition.

Tested here on synthetic partitions in every layout the tool handles
(`python3 tools/test_mtk_logo.py`) and on a real MediaTek logo dump from
another tablet (162 pictures, 26 MiB): unpacked and repacked byte-identical,
VoidOS picture written and read back pixel-identical. **Not** tested on a
TB-X306F dump, because there isn't one in this repo.

Needs root (section 2) and Python 3 with Pillow on the computer. The
commands below use `tools/mtk_logo.py` in the repo; `root/logo-tool/mtk_logo.py`
is the same tool packed to run on its own. To see the picture it will put in
without a dump: `python3 tools/mtk_logo.py preview bootlogo.png`.

1. **Find and dump the partition** (two copies, kept off the tablet):
   ```
   adb shell su -c 'ls -l /dev/block/by-name/ | grep -i logo'
   adb shell su -c 'blockdev --getsize64 /dev/block/by-name/logo'
   adb shell su -c 'dd if=/dev/block/by-name/logo of=/sdcard/logo.stock.bin'
   adb shell su -c 'sha256sum /dev/block/by-name/logo /sdcard/logo.stock.bin'
   adb pull /sdcard/logo.stock.bin
   ```
   If the first line shows no `logo`, stop: the partition is named or placed
   differently on your firmware. The two hashes must match, and the file must
   be exactly the size `blockdev` printed. Keep `logo.stock.bin` safe: it is
   your way back.
2. **Look inside it**:
   ```
   python3 tools/mtk_logo.py info logo.stock.bin
   python3 tools/mtk_logo.py unpack logo.stock.bin logo.d
   ```
   Open `logo.d/000.png`. It should be the Lenovo splash, upright. If it's
   upside down or sideways, note the angle (180, 90, 270). If its colours look
   swapped (blue for red), add `--swap-rb` below. `info` lists which pictures
   are full screen and which ones `build` will replace (the first and, when
   full screen, the last).
3. **Build and check the VoidOS logo**:
   ```
   python3 tools/mtk_logo.py build logo.stock.bin logo-voidos.bin --preview logo-voidos.png [--rotate 180]
   python3 tools/mtk_logo.py unpack logo-voidos.bin logo-voidos.d
   ```
   `logo-voidos.d/000.png` should look like `root/preview/bootlogo_800x1280.png`,
   turned the same way as the Lenovo picture was. `--all-fullscreen` also
   replaces the charging background pictures.
4. **Flash it and read it back**:
   ```
   adb push logo-voidos.bin /sdcard/
   adb shell su -c 'dd if=/sdcard/logo-voidos.bin of=/dev/block/by-name/logo && sync'
   adb shell su -c 'sha256sum /dev/block/by-name/logo'
   ```
   The hash must be the "sha256 new" line `build` printed. Then reboot.
   (With the bootloader unlocked, `fastboot flash logo logo-voidos.bin` should
   do the same; not tried on this tablet.)
5. **Undo**: the same `dd` with `logo.stock.bin`, or
   `fastboot flash logo logo.stock.bin`, or RSA Rescue (stock firmware, wipes
   the tablet).

If the splash comes out black, garbled or the wrong colours, that's the logo
only: Android still boots and you can flash the stock dump back from it. That
comes from how other MediaTek devices behave; it isn't verified on this
tablet's bootloader.

## 6. Order to do it in

1. Back up and download the RSA firmware (Before you start).
2. Unlock (section 1, wipes), set VoidOS up again as device owner (main README).
3. Root (section 2).
4. Boot animation module (section 3). Reboot, check Boot + root.
5. Boot logo (section 5).
6. Optional: system app module (section 4).

## 7. What this repo deliberately doesn't do

* **Patch the bootloader (LK) to remove the unlocked-bootloader warning.** That
  text is drawn by LK, not stored in the logo partition. Checked in the stock
  `lk-verified.img` for build S120637: the "Orange State" message and its
  5-second wait are strings and code inside LK itself, and LK is signed. People
  have patched it on other Lenovo MediaTek tablets, but nothing confirms the
  patch for the TB-X306F, and a bad LK can leave the tablet unable to reach
  fastboot at all. Relocking would hide it but wipe the tablet and refuse to
  boot the Magisk-patched boot image, so don't relock either.
* **Replace SystemUI's keyguard or build a whole ROM.** VoidOS's own lock screen
  with the keyguard off gets the same result at no risk (see the top).
* **Port a non-Android OS.** See "Why it's still Android underneath".

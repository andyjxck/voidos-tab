# ROOT: the parts of VoidOS that need root or flashing

Almost all of VoidOS runs as a normal app with **device owner** (see the main
README). Only these need more:

| Feature | Needs | What's here |
| --- | --- | --- |
| Boot animation (the one Android plays before VoidOS starts) | Root (Magisk) **or** a flashed system image | `bootanimation.zip`, `voidos-boot-magisk.zip` |
| Boot logo (the still "Lenovo" splash the bootloader shows first) | Unlocked bootloader + flashing the `logo` partition | Not provided (see below) |
| Setting the time zone from System > Date + time on Android 10 | VoidOS installed as a system app (root/flash) | Nothing to flash; it falls back to Android's own setting |

No root needed for the rest:

* **Lock screen.** VoidOS switches Android's own lock screen off as device owner
  (System > VoidOS rules > "No lock screen") and, with System > Hub + lock >
  "VoidOS lock screen" on, shows its own lock screen every time the screen
  goes off. This only works while Android itself
  has no PIN, pattern or password set; turn VoidOS's "PIN to unlock" on instead.
* **Start animation.** VoidOS plays its own start animation once per power-on,
  straight after Android's boot animation. `bootanimation.zip` is the same
  animation, so with it installed the two run into each other.

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
  and wipes the tablet).

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
portrait orientation), 30 fps: `part0` plays the 2.4 s intro once, `part1`
loops until Android finishes booting. The files are stored without
compression, which Android requires. For another screen:
```
adb shell wm size                                  # e.g. Physical size: 1200x1920
python3 tools/bootanim.py --size 1200x1920
python3 tools/magisk_module.py
```
(needs Python 3 and Pillow: `pip install pillow`). Previews of three frames
are in `root/preview/`.

## Boot logo: not included

The still logo the bootloader shows before the animation lives in the
MediaTek `logo` partition, in a packed format specific to the firmware. It
can only be changed by dumping that partition from your tablet, repacking it
with a MediaTek logo tool, and flashing it back with fastboot or SP Flash
Tool, and a bad image there can leave the tablet without a boot screen. This
repo doesn't ship a logo image for it. `docs/logo/` has the VoidOS mark as
PNG and SVG if you want to make one.

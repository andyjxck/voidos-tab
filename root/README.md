# ROOT: the parts of VoidOS that need root or flashing

## Flashing-day kit (everything is in this folder)

| File | What it is | Used in |
| --- | --- | --- |
| `voidos-tab-1.20.0.apk` | The VoidOS app, release build (same fixed key as every build, so it installs over any earlier one and keeps device owner) | main README, Install; after unlocking (section 1) |
| `voidos-boot-magisk.zip` | Magisk module 3.2: the boot animation and the shutdown animation, in portrait and both ways round in landscape | section 3 |
| `bootanimation.zip`, `shutdownanimation.zip` | The two animations on their own (portrait), for a hand copy or your own firmware; `orient/90` and `orient/270` hold the landscape ones | section 3 |
| `logo-voidos.bin` | The power-on logo for the TB-X306F, build TB-X306F_S120637_230301_BMP, ready to flash | section 5 |
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
| 1. Power-on logo (the still "Lenovo" splash) | MediaTek bootloader (LK), from the `logo` partition | `root/logo-voidos.bin`, built from the stock logo of build S120637_230301 (section 5) | Built and checked; not yet flashed |
| 1b. "Orange state" warning (only once the bootloader is unlocked, if your firmware shows one) | LK itself, not the logo partition | Nothing here: it would mean patching LK (section 7) | Not done, on purpose |
| 2. Boot animation | Android's `bootanimation` service | `voidos-boot-magisk.zip` (section 3) | Ready |
| 2b. Start animation | VoidOS, its first screen | Opens on the boot animation's last frame, balloons into the hub | In the app source; ships with the next app build |
| Power off | Android's `bootanimation` service, started by MediaTek's shutdown code | `voidos-boot-magisk.zip` replaces `/system/media/shutdownanimation.zip` (section 3) | Ready |
| 3. Lock screen | VoidOS (Android's keyguard switched off) | No root needed: device owner + "VoidOS lock screen" | Ready |
| 4. Home screen | VoidOS (device owner, persistent Home) | No root needed | Ready |
| VoidOS as part of the system | Android package manager | `voidos-system-magisk.zip` (section 4, optional) | Built and checked; not yet run on the tablet |

VoidOS > Settings > About this tablet has a plain "Startup" section: whether the
power-on logo is the VoidOS one (it reads the logo partition through root and
compares it with `logo-voidos.bin` and the stock logo), and whether the startup
and shutdown animations are VoidOS's (it reads the boot module's real version).
Firmware, bootloader and module details sit under "Technical details".

### The lock screen: why it's VoidOS's, not Android's

Android's lock screen (the keyguard) is part of SystemUI, which Lenovo signs.
Replacing it outright means rebuilding SystemUI, i.e. building whole firmware.
VoidOS gets the same result without that: as device owner it switches the
keyguard off (System > VoidOS rules > "No lock screen") and shows its own lock
screen at power-on and every time the screen goes off, in front of whatever
app was open. So Android's lock screen never appears. Two limits:

* It only works while Android itself has **no** PIN, pattern or password. If
  one is set, Android's keyguard stays in force. Use VoidOS's own PIN
  (Settings > Home & lock screen > Ask for PIN to unlock) instead.
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
   VoidOS hides Settings: open it from the hidden way out (enter its code,
   then "Open Android settings"), or release VoidOS from the Mac with the adb
   commands in the main README first.
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
   Settings > About this tablet); a `boot.img` from another build can boot-loop.
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
4. Open Magisk: it should show it's installed. VoidOS > Settings > About this tablet
   should now say Root (Magisk): Yes and Bootloader: Unlocked.

## 3. Install the boot and shutdown animations

### How the pictures hand over (no cuts)

`tools/void_balloon.py` draws every stage from the same picture: the still
"void" wordmark on VoidOS's background (#0B0B0D), at the place and size the
app draws it (`tools/voidmark.py lockup_geometry`). That picture is
`root/preview/void_still_800x1280.png`.

1. **Power-on logo** (section 5) shows that still picture.
2. **Boot animation** starts on that exact frame (checked pixel for pixel),
   holds it for 0.2 s, then the wordmark balloons up: it swells from the
   middle, overshoots a little and settles (1.2 s), then the dot of the i
   wakes up and starts to swing (1.2 s; together `part0`). While Android
   starts it loops the original loading animation from 1.2.0
   (`tools/logo_loading_anim.py`): the dot bounces between the purple line
   over the o and the green line over the d, merging into each like a drop
   of liquid glass and pulling away (`part1`, a 2.4 s seamless loop). Once
   VoidOS has drawn its first screen the loop finishes, the dot swings to a
   stop in its place and the word lets its air out, ending on the still
   picture again (1.9 s, `part2`). All parts are `c`, so they always
   play to the end and the loop never cuts mid-motion.
   **No "Android is starting" screen:** with the tablet's storage still
   locked, Android used to show Settings' FallbackHome (black, with a
   starting message) between the boot animation and VoidOS. VoidOS now has
   its own stand-in for it (`BootCover.kt`) that doesn't draw, and Android
   keeps the boot animation up until the home screen has drawn, so the
   bouncing dot carries on until VoidOS itself is ready. Limits: it draws the still
   logo after 20 s whatever happens (so any system error message can show;
   Android's own limit is 30 s), steps aside for Android's starting screen
   after 90 s still locked, and draws straight away if Android has its own
   PIN, so its unlock screen can show. The "Optimising apps" screen after a
   system update, recovery and fastboot come before any home screen, so
   they show as before.
3. **VoidOS** opens on that same still picture, waits until the boot
   animation has really gone (`init.svc.bootanim`), then balloons up and opens
   out into the hub, which is already drawn underneath (1.65 s, tap to skip).
4. **Power off** (module 3.2): the wordmark takes a slow last breath in, then
   folds in on itself, swirling into the middle, until it's one teal point
   (6 s, it was 1.9 s up to 3.1). The point then settles into a small ember
   that keeps breathing instead of a black frame, so the screen never sits
   lit and empty. At shutdown `orient.sh` also starts the module's
   `screenoff.sh`: it counts from when the shutdown animation started, waits
   for the fold plus 0.3 s, then turns the backlight down over a quarter of
   a second, so the ember goes out with the screen. If it can't find the
   animation or the backlight it does nothing, and the ember stays until
   Android turns the screen off. To move the moment, put a number of
   milliseconds (negative for earlier) in
   `/data/adb/modules/voidos_boot/screenoff.offset`. How long MediaTek's
   shutdown code (`MtkShutdownThread`) lets it run before the power goes
   needs checking on the tablet: before 3.2 the screen stayed lit about ten
   seconds after the old 1.9 s animation, so the 6 s fold should fit.

**Landscape.** Android 10's boot animation always draws in the panel's own
portrait and can't read the sensor, so the module also carries both
animations drawn for the tablet held landscape either way (`orient/90` and
`orient/270`, Display rotation). VoidOS (with root) runs the module's
`orient.sh` a couple of seconds after the tablet settles in a new way round,
and again at shutdown: it writes the matching version into the files Magisk
shows at `/system/media` and `/product/media` (into the same files, because
Magisk mounts each module file over the system one: the shutdown animation
changes straight away, the boot animation at the next boot). Upside-down
portrait gets the portrait version. The power-on logo is a still picture in
the logo partition and is always portrait, so the landscape boot animation
starts on it, on its side, and turns the word round to landscape while it
balloons up.

What can't be made seamless: the orange unlocked-bootloader warning before
the logo (LK, section 7), a possible blink between the logo and the boot
animation while Android takes over the screen, and the power-on logo itself
staying portrait. All of this needs checking on the tablet.

**With Magisk (recommended, systemless):**

```
adb push root/voidos-boot-magisk.zip /sdcard/Download/
```

Magisk > Modules > **Install from storage** > `voidos-boot-magisk.zip`,
then reboot. VoidOS > Settings > About this tablet shows "Startup animation: VoidOS animation"
once it's in. To undo, remove the module in Magisk and reboot. Installing 3.2
over an earlier one replaces it.

The module overlays `/system/media/bootanimation.zip`,
`/product/media/bootanimation.zip` (its install script copies the one file
there, which keeps the module smaller) and
`/system/media/shutdownanimation.zip`. On the TB-X306F's stock firmware
those are the only boot and shutdown animations (checked in the firmware's
`system` and `product` images). If the stock animation still plays, the
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

Both animations are 800 x 1280 (the TB-X306F panel in its natural portrait
orientation, the same as the stock ones), 30 fps, stored without compression
as Android requires. To rebuild them, the logo and the module:
```
python3 tools/void_balloon.py
python3 tools/mtk_logo.py build <stock logo.bin> root/logo-voidos.bin --indices 0,38 --max-size 13631488 --art root/preview/void_still_800x1280.png
python3 tools/magisk_module.py boot
python3 tools/test_void_balloon.py
```
(needs Python 3, Pillow, NumPy and ffmpeg; the boot animation takes a few
minutes). `root/preview/boot_sheet_portrait.png`, `boot_sheet_landscape.png`
and `shutdown_sheet.png` show frames from each part, and `boot_portrait.mp4`
/ `.gif` and `boot_landscape.mp4` / `.gif` play it at half size. (`tools/bootanim.py` made
the 1.0 animation and is kept for reference.)

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
from storage, reboot. the app then runs as part of the system.
Remove the module and reboot to make VoidOS an ordinary app again.

Module 1.3 (VoidOS 1.20.0): holding power opens VoidOS's power menu straight
away, and Android's white Power off / Restart dialog isn't drawn at all. VoidOS
hands a hold on power to itself (Android's "long press power" setting, set to
"assistant", with VoidOS as the assistant). A short press still sleeps and wakes
the screen. There's always a way out: if VoidOS's key service stops, Android
restarts it and the next hold works; if VoidOS or its service is ever missing,
the module puts Android's dialog back within about 20 seconds; and removing
the module puts it back too.

After a VoidOS update (`adb install -r -t ...`) rebuild the module too
(`./gradlew assembleRelease && python3 tools/magisk_module.py system`) so the
system copy isn't older than the installed one. The module takes the release
APK when there is one, else the debug one.

## 5. Boot logo (the power-on splash)

### TB-X306F, build TB-X306F_S120637_230301_BMP: ready-made

`root/logo-voidos.bin` was built from the `logo-verified.bin` in that build's
stock firmware. Checked here:

* the 512-byte header is the stock one apart from the size field, and the
  size field matches the data; 42 pictures as in stock; 40 of them
  (charging and battery) are byte-for-byte stock
* pictures 0 and 38 (the two full-screen splashes) decompress to the still
  VoidOS picture exactly, in the stock pixel format (800 x 1280 BGRA)
* 925,736 bytes, in a 13,631,488-byte partition
* the stock file ends in a MediaTek signature (cert1/cert2) that the new one
  can't carry. This tablet's LK has the policy `logo: sbc+unlock = 0`
  (no check) as for `boot` (read with lkpatcher `--analyze-policies`), so
  while the bootloader is unlocked it isn't checked. **Put the stock logo
  back before you ever relock**, or the tablet won't start (locked policy is 3).

Flash (from the bootloader):
```
./adb reboot bootloader
./fastboot flash logo logo-voidos.bin
./fastboot reboot
```
Undo: `./fastboot flash logo logo-verified.bin` (the stock one from the
firmware download).

### Any other MediaTek firmware

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
4. Boot and shutdown animation module (section 3). Reboot, check Settings > About this tablet.
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

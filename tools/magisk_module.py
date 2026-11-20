#!/usr/bin/env python3
"""
Builds the two VoidOS Magisk modules:

root/voidos-boot-magisk.zip (low risk): a Magisk module that puts the VoidOS boot
animation in place without touching the system partition (Magisk overlays it
at boot; remove the module in the Magisk app to get the stock animation back).

It puts the same bootanimation.zip at every place Android 10 looks for one
(/system/media and /product/media; /oem/media is a separate partition Magisk
can't overlay), plus /system/etc/voidos_boot.txt, which System > Boot + root
in VoidOS reads to show the module is installed.

root/voidos-system-magisk.zip (optional, more risk): installs VoidOS as a
privileged system app (/system/priv-app/VoidOS), systemlessly, with the
privileged-permission allowlist Android 9+ insists on (a privileged permission
missing from it stops the tablet booting, so the list is generated from the APK
and checked by tools/test_magisk_module.py). As a system app VoidOS gets
SET_TIME_ZONE and WRITE_SECURE_SETTINGS, so the time zone setting works, and it
stays on the tablet as part of the system rather than as a downloaded app.
Remove the module in Magisk and VoidOS goes back to being an ordinary app.

Run tools/bootanim.py and build the APK (./gradlew assembleRelease, or
assembleDebug) first; the system module takes the release APK when there is one.

    python3 tools/magisk_module.py            # both
    python3 tools/magisk_module.py boot       # just the boot animation
    python3 tools/magisk_module.py system     # just the system app
"""
import subprocess
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import voidmark as vm  # noqa: E402

VERSION = "3.3"
VERSION_CODE = 6

MODULE_PROP = f"""id=voidos_boot
name=VoidOS boot and shutdown animation
version={VERSION}
versionCode={VERSION_CODE}
author=andyjxck
description=VoidOS power-on and power-off animations: the "void" wordmark balloons up and the dot of the i bounces between the two lines at boot and slowly folds in on itself at shutdown, going out with the screen, the way round the tablet is held. Systemless: remove the module to restore the stock Lenovo animations.
"""

# The standard Magisk module installer stub (Magisk 20.4+ does the real work).
UPDATE_BINARY = """#!/sbin/sh

#################
# Initialization
#################

umask 022

# echo before loading util_functions
ui_print() { echo "$1"; }

require_new_magisk() {
  ui_print "*******************************"
  ui_print " Please install Magisk v20.4+! "
  ui_print "*******************************"
  exit 1
}

#########################
# Load util_functions.sh
#########################

OUTFD=$2
ZIPFILE=$3

mount /data 2>/dev/null

[ -f /data/adb/magisk/util_functions.sh ] || require_new_magisk
. /data/adb/magisk/util_functions.sh
[ $MAGISK_VER_CODE -lt 20400 ] && require_new_magisk

install_module
exit 0
"""

CUSTOMIZE = """# Runs inside Magisk's installer.
ui_print "- VoidOS boot and shutdown animation"
ui_print "- Reboot to see it. Remove this module in Magisk to undo."
# the boot animation is shipped once and copied here, so the module stays
# small: Android 10 plays /product/media's copy first when there is one, and
# the TB-X306F has one
mkdir -p $MODPATH/system/product/media
cp -f $MODPATH/system/media/bootanimation.zip $MODPATH/system/product/media/bootanimation.zip
# the portrait pair is also the one orient.sh puts back for rotation 0
mkdir -p $MODPATH/orient/0
cp -f $MODPATH/system/media/bootanimation.zip $MODPATH/orient/0/bootanimation.zip
cp -f $MODPATH/system/media/shutdownanimation.zip $MODPATH/orient/0/shutdownanimation.zip
echo 0 > $MODPATH/orient/boot.now
echo 0 > $MODPATH/orient/shutdown.now
set_perm_recursive $MODPATH/system 0 0 0755 0644
set_perm_recursive $MODPATH/orient 0 0 0755 0644
set_perm $MODPATH/orient.sh 0 0 0755
set_perm $MODPATH/screenoff.sh 0 0 0755
"""

# Run by VoidOS (BootOrient.kt) as root when the tablet turns, and at
# shutdown with "boot": puts the version of the animations for that Display
# rotation where Magisk shows them. It writes into the same files rather than
# replacing them, because Magisk mounts each module file over the system one:
# a new file would only show after the next boot, a write into the old one
# shows straight away, so the shutdown animation turns with the tablet too.
ORIENT_SH = """#!/system/bin/sh
# usage: orient.sh 0|90|270 [boot]
M=$(cd "$(dirname "$0")" && pwd)
R=$1
case "$R" in 0|90|270) ;; *) R=0 ;; esac
S=$M/orient/$R
[ -f "$S/bootanimation.zip" ] || exit 3
put() { # put <name> <version file> <target>...
  name=$1; mark=$2; shift 2
  [ "$(cat "$mark" 2>/dev/null)" = "$R" ] && return 0
  for t in "$@"; do
    [ -f "$t" ] || continue
    cat "$S/$name" > "$t" || return 1
  done
  sync
  echo "$R" > "$mark"
}
BOOT="$M/system/media/bootanimation.zip $M/system/product/media/bootanimation.zip"
if [ "$2" = boot ]; then
  # at shutdown: first start screenoff.sh on its own (so the picture goes
  # out with the screen), then only the boot animation, as the shutdown one
  # is playing
  if [ -f "$M/screenoff.sh" ]; then
    if command -v setsid >/dev/null 2>&1; then
      ( setsid sh "$M/screenoff.sh" </dev/null >/dev/null 2>&1 & )
    else
      ( sh "$M/screenoff.sh" </dev/null >/dev/null 2>&1 & )
    fi
  fi
  put bootanimation.zip $M/orient/boot.now $BOOT || exit 1
  exit 0
fi
# never under an animation that is playing (VoidOS tries again later)
[ "$(getprop init.svc.bootanim)" = running ] && exit 4
put bootanimation.zip $M/orient/boot.now $BOOT || exit 1
put shutdownanimation.zip $M/orient/shutdown.now $M/system/media/shutdownanimation.zip || exit 1
exit 0
"""


# Started by orient.sh at shutdown (VoidOS runs orient.sh from its
# ACTION_SHUTDOWN receiver). The shutdown animation is part0 (the fold, its
# length is filled in from tools/void_balloon.py) and then part1, a small
# teal ember that breathes until the tablet is off. On the TB-X306F the
# screen stayed lit (and black) for about ten seconds after the old 1.9 s
# animation. This waits until the fold has played, counted from when the
# bootanimation process started, then turns the backlight down over a
# quarter of a second, so the ember goes out with the screen. If anything is
# missing (no bootanimation or mtkbootanimation running, no backlight file) it does nothing and
# the ember simply stays until Android turns the screen off itself.
# screenoff.offset (milliseconds, may be negative) moves the moment.
SCREENOFF_SH = r"""#!/system/bin/sh
M=$(cd "$(dirname "$0")" && pwd)
FOLD_MS=@FOLD_MS@   # part0 of the shutdown animation
LOAD_MS=400         # bootanimation opening the zip before the first frame
HOLD_MS=300         # the ember sits for a moment before the screen goes
say() { log -t VoidOS "screenoff: $*" 2>/dev/null; }
command -v getprop >/dev/null 2>&1 || exit 0
[ -n "$(getprop sys.shutdown.requested)" ] || { say "no shutdown in progress"; exit 0; }
now_ms() { read -r up _ < /proc/uptime; s=${up%.*}; f=${up#*.}; f=${f#0}; echo $(( s * 1000 + ${f:-0} * 10 )); }
pid=""
i=0
while [ $i -lt 30 ]; do
  # AOSP's bootanimation, or MediaTek's mtkbootanimation (banim_shut*)
  pid=$(pidof bootanimation 2>/dev/null)
  [ -n "$pid" ] || pid=$(pidof mtkbootanimation 2>/dev/null)
  [ -n "$pid" ] && break
  sleep 0.1
  i=$((i + 1))
done
[ -n "$pid" ] || { say "no shutdown animation playing"; exit 0; }
set -- $pid; pid=$1
read -r st < /proc/$pid/stat || exit 0
set -- $st; shift 21; start_ms=$(( $1 * 10 ))   # starttime, in 1/100 s
off=$(cat "$M/screenoff.offset" 2>/dev/null); case "$off" in ''|*[!0-9-]*) off=0 ;; esac
wait_ms=$(( start_ms + LOAD_MS + FOLD_MS + HOLD_MS + off - $(now_ms) ))
[ $wait_ms -gt 20000 ] && wait_ms=20000
[ $wait_ms -gt 0 ] && sleep $(( wait_ms / 1000 )).$(printf %03d $(( wait_ms % 1000 )))
[ -d /proc/$pid ] || { say "animation already gone"; exit 0; }
bl=""
for f in $VOID_TEST_BL /sys/class/leds/lcd-backlight/brightness /sys/class/backlight/*/brightness; do
  [ -w "$f" ] && { bl=$f; break; }
done
[ -n "$bl" ] || { say "no backlight control found"; exit 0; }
cur=$(cat "$bl" 2>/dev/null); case "$cur" in ''|*[!0-9]*) cur=0 ;; esac
n=5
while [ $n -gt 0 ] && [ $cur -gt 0 ]; do
  n=$((n - 1))
  echo $(( cur * n / 6 )) > "$bl"
  sleep 0.05
done
echo 0 > "$bl"
say "backlight off after the fold ($bl)"
exit 0
"""


def build(out: Path):
    anim = vm.ROOT / "root/bootanimation.zip"
    if not anim.exists():
        sys.exit("root/bootanimation.zip is missing: run tools/bootanim.py first")
    data = anim.read_bytes()
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as z:
        z.writestr("META-INF/com/google/android/update-binary", UPDATE_BINARY)
        z.writestr("META-INF/com/google/android/updater-script", "#MAGISK\n")
        z.writestr("module.prop", MODULE_PROP)
        z.writestr("customize.sh", CUSTOMIZE)
        z.writestr("system/etc/voidos_boot.txt", VERSION + "\n")
        # bootanimation.zip itself must stay uncompressed inside (it's already stored)
        # (customize.sh copies it to system/product/media on the tablet)
        z.writestr(zipfile.ZipInfo("system/media/bootanimation.zip"), data, compress_type=zipfile.ZIP_STORED)
        # the shutdown animation: the TB-X306F's only one is
        # /system/media/shutdownanimation.zip (its bootanimation binary also
        # looks in /product/media and /oem/media, which have none), so only
        # that file is replaced
        shut = vm.ROOT / "root/shutdownanimation.zip"
        if shut.exists():
            z.writestr(zipfile.ZipInfo("system/media/shutdownanimation.zip"), shut.read_bytes(), compress_type=zipfile.ZIP_STORED)
        # the landscape versions (tools/void_balloon.py) and the script that
        # swaps them in; the portrait pair is copied to orient/0 on install
        z.writestr("orient.sh", ORIENT_SH)
        import void_balloon as vb
        z.writestr("screenoff.sh", SCREENOFF_SH.replace("@FOLD_MS@", str(round(vb.SHUT_SECONDS * 1000))))
        for rot in (90, 270):
            for name in ("bootanimation.zip", "shutdownanimation.zip"):
                f = vm.ROOT / f"root/orient/{rot}/{name}"
                if not f.exists():
                    sys.exit(f"{f} is missing: run tools/void_balloon.py first")
                z.writestr(zipfile.ZipInfo(f"orient/{rot}/{name}"), f.read_bytes(), compress_type=zipfile.ZIP_STORED)
    print(f"{out}: {out.stat().st_size / 1e6:.1f} MB")


# ---------------------------------------------------------------- system app

SYS_VERSION = "1.3"
SYS_VERSION_CODE = 4
PACKAGE = "com.andysocial.voidos"
APK_RELEASE = vm.ROOT / "app/build/outputs/apk/release/app-release.apk"
APK_DEBUG = vm.ROOT / "app/build/outputs/apk/debug/app-debug.apk"
APK = APK_RELEASE if APK_RELEASE.exists() else APK_DEBUG

# Permissions the platform ("android" package) marks privileged, as of Android
# 10 and 11 (AOSP core/res/AndroidManifest.xml, android10-release and
# android11-release): the full lists are in tools/testdata. VoidOS asks for the
# ones below; WRITE_SETTINGS is 'preinstalled', not privileged, and is listed
# anyway because a grant for a non-privileged permission is simply ignored.
PRIVILEGED_LISTS = [vm.ROOT / "tools/testdata/android10-privileged.txt",
                    vm.ROOT / "tools/testdata/android11-privileged.txt"]
ALWAYS_ALLOW = ["android.permission.WRITE_SETTINGS"]

SYS_PROP = f"""id=voidos_system
name=VoidOS system app
version={SYS_VERSION}
versionCode={SYS_VERSION_CODE}
author=andyjxck
description=Installs VoidOS Tab Edition as a privileged system app (time zone control, part of the system). Systemless: remove the module and VoidOS is an ordinary app again.
"""

SYS_CUSTOMIZE = """# Runs inside Magisk's installer.
ui_print "- VoidOS system app"
API=$(getprop ro.build.version.sdk)
MODEL=$(getprop ro.product.model)
ui_print "- Device: $MODEL, Android API $API"
if [ "$API" -lt 29 ]; then
  abort "! VoidOS needs Android 10 (API 29) or newer"
fi
case "$MODEL" in
  *X306F*) ;;
  *) ui_print "! Not a TB-X306F: VoidOS was built for that tablet. Continuing anyway." ;;
esac
ui_print "- privapp enforcement: $(getprop ro.control_privapp_permissions)"
# keep only the native library folder for this tablet's ABI
ABI=$(getprop ro.product.cpu.abi)
case "$ABI" in
  arm64*) rm -rf $MODPATH/system/priv-app/VoidOS/lib/arm ;;
  *) rm -rf $MODPATH/system/priv-app/VoidOS/lib/arm64 ;;
esac
ui_print "- Reboot. If VoidOS is already installed with the same key it stays,"
ui_print "  now as an updated system app. Remove this module in Magisk to undo."
set_perm_recursive $MODPATH/system 0 0 0755 0644
set_perm $MODPATH/service.sh 0 0 0755
set_perm $MODPATH/uninstall.sh 0 0 0755
ui_print "- Holding power opens VoidOS's power menu (Android's comes back if"
ui_print "  VoidOS is ever missing, or when this module is removed)."
ui_print "- No navigation bar from the next boot (hold volume down while booting"
ui_print "  for Magisk safe mode if you ever need Android's bar back)."
"""


# 1.1: no navigation bar from the first boot, whatever VoidOS's settings say. Three ways, so one
# is enough on any build: the emulator's "hardware keys" property, a static framework overlay
# that says this tablet has no navigation bar (how tablets with real keys are built), and at
# boot the immersive policy (only an edge swipe could ever bring a bar back, and there is none).
SYS_PROP_FILE = "qemu.hw.mainkeys=1\n"

SYS_SERVICE = """#!/system/bin/sh
# VoidOS system app: runs late in every boot.
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
sleep 4
P=com.andysocial.voidos
resetprop qemu.hw.mainkeys 1 2>/dev/null
settings put global policy_control 'immersive.full=*:immersive.preconfirms=*'
appops set $P SYSTEM_ALERT_WINDOW allow
pm grant $P android.permission.WRITE_SECURE_SETTINGS 2>/dev/null
# 1.19: VoidOS's own permissions granted up front, so Android's "Allow VoidOS to..." prompts never show
for perm in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION CAMERA RECORD_AUDIO READ_CALENDAR READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE; do
  pm grant $P android.permission.$perm 2>/dev/null
done
appops set $P WRITE_SETTINGS allow 2>/dev/null
appops set $P ACCESS_NOTIFICATIONS allow 2>/dev/null
cmd notification allow_listener $P/$P.VoidListener 2>/dev/null
cmd notification allow_dnd $P 2>/dev/null
# VoidOS's volume sliders, power menu and PIN pad (an accessibility service)
S=$P/$P.VoidKeysService
CUR=$(settings get secure enabled_accessibility_services)
case "$CUR" in
  *VoidKeysService*) ;;
  null|"") settings put secure enabled_accessibility_services $S ;;
  *) settings put secure enabled_accessibility_services "$CUR:$S" ;;
esac
settings put secure accessibility_enabled 1
# no "Viewing full screen" or accessibility shortcut dialogs
settings put secure accessibility_shortcut_dialog_shown 1
settings put secure immersive_mode_confirmations confirmed
# 1.3 (VoidOS 1.20.0): a hold on power goes straight to VoidOS's menu (PowerHold.kt), and
# Android's white dialog is never drawn. VoidOS switches it on itself; this watchdog switches
# Android's dialog back on whenever VoidOS or its service is missing, so there is always a
# power menu. A short press still sleeps and wakes the screen.
voidos_ok() {
  cmd package list packages -e $P 2>/dev/null | grep -qx "package:$P" || return 1
  case "$(settings get secure enabled_accessibility_services)" in *VoidKeysService*) ;; *) return 1 ;; esac
  return 0
}
(
  while true; do
    if ! voidos_ok && [ "$(settings get global power_button_long_press)" = "5" ]; then
      settings put global power_button_long_press 1
      settings delete secure assistant
    fi
    sleep 20
  done
) &
"""

# 1.3: removing the module in Magisk gives a hold on power back to Android's dialog
SYS_UNINSTALL = """#!/system/bin/sh
(
  until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
  settings put global power_button_long_press 1
  case "$(settings get secure assistant)" in *com.andysocial.voidos*) settings delete secure assistant ;; esac
) &
"""

OVERLAY_MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.andysocial.voidos.nobar" android:versionCode="2" android:versionName="1.1">
    <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="28" />
    <overlay android:targetPackage="android" android:isStatic="true" android:priority="999" />
    <application android:hasCode="false" />
</manifest>
"""

OVERLAY_VALUES = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <bool name="config_showNavigationBar">false</bool>
</resources>
"""


def _sdk():
    lp = vm.ROOT / "local.properties"
    for l in lp.read_text().splitlines():
        if l.startswith("sdk.dir="):
            return Path(l.split("=", 1)[1].strip())
    sys.exit("sdk.dir missing from local.properties")


def build_nobar_overlay() -> bytes:
    """The framework overlay APK (config_showNavigationBar=false), signed with VoidOS's fixed key."""
    import tempfile
    sdk = _sdk()
    bt = sorted(sdk.glob("build-tools/*"))[-1]
    jar = sorted(sdk.glob("platforms/android-*/android.jar"))[-1]
    with tempfile.TemporaryDirectory() as t:
        t = Path(t)
        (t / "res/values").mkdir(parents=True)
        (t / "res/values/bools.xml").write_text(OVERLAY_VALUES)
        (t / "AndroidManifest.xml").write_text(OVERLAY_MANIFEST)
        subprocess.run([str(bt / "aapt2"), "compile", "--dir", str(t / "res"), "-o", str(t / "res.zip")], check=True)
        subprocess.run([str(bt / "aapt2"), "link", "-I", str(jar), "--manifest", str(t / "AndroidManifest.xml"), "-o", str(t / "u.apk"), str(t / "res.zip")], check=True)
        subprocess.run([str(bt / "zipalign"), "-f", "4", str(t / "u.apk"), str(t / "a.apk")], check=True)
        subprocess.run([str(bt / "apksigner"), "sign", "--ks", str(vm.ROOT / "app/voidos-debug.keystore"), "--ks-pass", "pass:android",
                        "--ks-key-alias", "androiddebugkey", "--key-pass", "pass:android", "--out", str(t / "s.apk"), str(t / "a.apk")], check=True)
        return (t / "s.apk").read_bytes()


def requested_permissions(apk: Path):
    import os
    sdks = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    lp = vm.ROOT / "local.properties"
    if lp.exists():
        sdks += [l.split("=", 1)[1].strip() for l in lp.read_text().splitlines() if l.startswith("sdk.dir=")]
    sdks.append(str(vm.ROOT.parent / "tools/android-sdk"))
    cands = [b / "aapt2" for s in sdks if s for b in sorted(Path(s).glob("build-tools/*"), reverse=True)]
    aapt2 = next((c for c in cands if c.exists()), None)
    if aapt2 is None:
        sys.exit("aapt2 not found: set ANDROID_HOME or sdk.dir in local.properties")
    out = subprocess.run([str(aapt2), "dump", "permissions", str(apk)], check=True, capture_output=True, text=True).stdout
    pkg = next(l.split(":", 1)[1].strip() for l in out.splitlines() if l.startswith("package:"))
    perms = [l.split("'")[1] for l in out.splitlines() if l.startswith("uses-permission:")]
    return pkg, perms


def privileged_set():
    s = set()
    for p in PRIVILEGED_LISTS:
        s |= {l.strip() for l in p.read_text().splitlines() if l.strip()}
    return s


def allowlist(perms):
    priv = privileged_set()
    need = sorted({p for p in perms if p in priv} | set(ALWAYS_ALLOW))
    lines = "\n".join(f'        <permission name="{p}"/>' for p in need)
    xml = f"""<?xml version="1.0" encoding="utf-8"?>
<!-- VoidOS Tab Edition: privileged permissions for /system/priv-app/VoidOS.
     Android 9+ will not boot if a privileged app asks for a privileged
     permission that no allowlist on its partition grants. -->
<permissions>
    <privapp-permissions package="{PACKAGE}">
{lines}
    </privapp-permissions>
</permissions>
"""
    return xml, need


def build_system(out: Path, apk: Path = APK):
    if not apk.exists():
        sys.exit(f"{apk} is missing: run ./gradlew assembleRelease first")
    pkg, perms = requested_permissions(apk)
    if pkg != PACKAGE:
        sys.exit(f"{apk} is {pkg}, not {PACKAGE}")
    xml, need = allowlist(perms)
    with zipfile.ZipFile(apk) as a:
        libs = {n: a.read(n) for n in a.namelist() if n.startswith("lib/") and n.endswith(".so")}
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as z:
        z.writestr("META-INF/com/google/android/update-binary", UPDATE_BINARY)
        z.writestr("META-INF/com/google/android/updater-script", "#MAGISK\n")
        z.writestr("module.prop", SYS_PROP)
        z.writestr("customize.sh", SYS_CUSTOMIZE)
        z.writestr("system/etc/voidos_system.txt", SYS_VERSION + "\n")
        z.writestr(f"system/etc/permissions/privapp-permissions-{PACKAGE}.xml", xml)
        z.writestr("system/priv-app/VoidOS/VoidOS.apk", apk.read_bytes())
        z.writestr("system.prop", SYS_PROP_FILE)
        z.writestr("service.sh", SYS_SERVICE)
        z.writestr("uninstall.sh", SYS_UNINSTALL)
        z.writestr("system/vendor/overlay/VoidOSNoBar/VoidOSNoBar.apk", build_nobar_overlay())
        # a system app's native code is not unpacked from its APK: ship it beside it
        for name, data in libs.items():
            _, abi, so = name.split("/", 2)
            folder = {"arm64-v8a": "arm64", "armeabi-v7a": "arm"}.get(abi)
            if folder:
                z.writestr(f"system/priv-app/VoidOS/lib/{folder}/{so}", data)
    print(f"{out}: {out.stat().st_size / 1e6:.1f} MB, allowlist {need}, native libs {sorted(libs)}")


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    if which in ("all", "boot"):
        build(vm.ROOT / "root/voidos-boot-magisk.zip")
    if which in ("all", "system"):
        build_system(vm.ROOT / "root/voidos-system-magisk.zip")

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

VERSION = "1.0"
VERSION_CODE = 1

MODULE_PROP = f"""id=voidos_boot
name=VoidOS boot animation
version={VERSION}
versionCode={VERSION_CODE}
author=andyjxck
description=VoidOS Tab Edition boot animation (the square-root "void" wordmark, lap colours). Systemless: remove the module to restore the stock animation.
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
ui_print "- VoidOS boot animation"
ui_print "- Reboot to see it. Remove this module in Magisk to undo."
set_perm_recursive $MODPATH/system 0 0 0755 0644
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
        for path in ("system/media/bootanimation.zip", "system/product/media/bootanimation.zip"):
            z.writestr(zipfile.ZipInfo(path), data, compress_type=zipfile.ZIP_STORED)
    print(f"{out}: {out.stat().st_size / 1e6:.1f} MB")


# ---------------------------------------------------------------- system app

SYS_VERSION = "1.0"
SYS_VERSION_CODE = 1
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
"""


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

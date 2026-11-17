#!/usr/bin/env python3
"""
Builds root/voidos-boot-magisk.zip: a Magisk module that puts the VoidOS boot
animation in place without touching the system partition (Magisk overlays it
at boot; remove the module in the Magisk app to get the stock animation back).

It puts the same bootanimation.zip at every place Android 10 looks for one
(/system/media and /product/media; /oem/media is a separate partition Magisk
can't overlay), plus /system/etc/voidos_boot.txt, which System > Boot + root
in VoidOS reads to show the module is installed.

Run tools/bootanim.py first.

    python3 tools/magisk_module.py
"""
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
description=VoidOS Tab Edition boot animation (octagon mark, lap colours). Systemless: remove the module to restore the stock animation.
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


if __name__ == "__main__":
    build(vm.ROOT / "root/voidos-boot-magisk.zip")

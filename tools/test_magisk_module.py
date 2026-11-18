#!/usr/bin/env python3
"""Checks the built Magisk modules before they go anywhere near the tablet.
Run after tools/magisk_module.py:  python3 tools/test_magisk_module.py

The one that matters most: Android 9+ refuses to boot when a privileged app asks
for a privileged permission its allowlist doesn't grant, so every privileged
permission the APK in the system module requests (on Android 10 and on 11) must
be in the module's privapp-permissions XML."""
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import magisk_module as mm  # noqa: E402
import voidmark as vm  # noqa: E402

BOOT = vm.ROOT / "root/voidos-boot-magisk.zip"
SYSTEM = vm.ROOT / "root/voidos-system-magisk.zip"
XML = "system/etc/permissions/privapp-permissions-com.andysocial.voidos.xml"


def props(z):
    return dict(l.split("=", 1) for l in z.read("module.prop").decode().splitlines() if "=" in l)


class Common:
    path: Path

    def test_magisk_layout(self):
        with zipfile.ZipFile(self.path) as z:
            names = set(z.namelist())
            self.assertIn("META-INF/com/google/android/update-binary", names)
            self.assertEqual(z.read("META-INF/com/google/android/updater-script"), b"#MAGISK\n")
            p = props(z)
            for k in ("id", "name", "version", "versionCode", "author", "description"):
                self.assertIn(k, p)
            self.assertRegex(p["id"], r"^[a-zA-Z][a-zA-Z0-9._-]+$")
            self.assertIn("install_module", z.read("META-INF/com/google/android/update-binary").decode())
            self.assertTrue(all(n.startswith(("META-INF/", "system/", "orient/")) or n in ("module.prop", "customize.sh", "orient.sh") for n in names))
            self.assertIsNone(z.testzip())


class BootModule(Common, unittest.TestCase):
    path = BOOT

    def test_animation_stored(self):
        with zipfile.ZipFile(BOOT) as z:
            info = z.getinfo("system/media/bootanimation.zip")
            self.assertEqual(info.compress_type, zipfile.ZIP_STORED)
            self.assertEqual(props(z)["id"], "voidos_boot")
            # shutdown animation stored too, and the product copy made on install
            self.assertEqual(z.getinfo("system/media/shutdownanimation.zip").compress_type, zipfile.ZIP_STORED)
            c = z.read("customize.sh").decode()
            self.assertIn("cp -f $MODPATH/system/media/bootanimation.zip $MODPATH/system/product/media/bootanimation.zip", c)
            self.assertLess(c.index("cp -f"), c.index("set_perm_recursive"))
            # under GitHub's 100 MB file limit with room to spare
            self.assertLess(BOOT.stat().st_size, 90_000_000)
            # the landscape versions and the script that swaps them in
            for rot in (90, 270):
                for n in ("bootanimation.zip", "shutdownanimation.zip"):
                    self.assertEqual(z.getinfo(f"orient/{rot}/{n}").compress_type, zipfile.ZIP_STORED)
            self.assertIn("orient.sh", z.namelist())
            self.assertIn("cp -f $MODPATH/system/media/bootanimation.zip $MODPATH/orient/0/bootanimation.zip", c)


class SystemModule(Common, unittest.TestCase):
    path = SYSTEM

    def test_apk_matches_build_and_package(self):
        with zipfile.ZipFile(SYSTEM) as z:
            apk = z.read("system/priv-app/VoidOS/VoidOS.apk")
            self.assertEqual(props(z)["id"], "voidos_system")
        self.assertEqual(apk, mm.APK.read_bytes(), "module APK is stale: rerun tools/magisk_module.py")

    def test_every_privileged_permission_is_allowlisted(self):
        with zipfile.ZipFile(SYSTEM) as z:
            root = ET.fromstring(z.read(XML))
            apk = z.read("system/priv-app/VoidOS/VoidOS.apk")
        entries = root.findall("privapp-permissions")
        self.assertEqual([e.get("package") for e in entries], [mm.PACKAGE])
        granted = {p.get("name") for p in entries[0].findall("permission")}
        self.assertFalse(entries[0].findall("deny-permission"))
        with tempfile.NamedTemporaryFile(suffix=".apk") as f:
            f.write(apk); f.flush()
            pkg, requested = mm.requested_permissions(Path(f.name))
        self.assertEqual(pkg, mm.PACKAGE)
        for lst in mm.PRIVILEGED_LISTS:
            priv = {l.strip() for l in lst.read_text().splitlines() if l.strip()}
            self.assertGreater(len(priv), 200, lst)  # the real AOSP list, not an empty file
            missing = sorted(p for p in requested if p in priv and p not in granted)
            self.assertEqual(missing, [], f"privileged but not allowlisted ({lst.name}): device would not boot")
        self.assertIn("android.permission.SET_TIME_ZONE", granted)
        self.assertIn("android.permission.WRITE_SECURE_SETTINGS", granted)

    def test_allowlist_on_same_partition_as_app(self):
        with zipfile.ZipFile(SYSTEM) as z:
            names = z.namelist()
        # an allowlist only counts for priv-apps on its own partition: both under /system
        self.assertIn("system/priv-app/VoidOS/VoidOS.apk", names)
        self.assertIn(XML, names)

    def test_native_libs_beside_apk(self):
        with zipfile.ZipFile(mm.APK) as a:
            so = {n.split("/")[-1] for n in a.namelist() if n.startswith("lib/arm64-v8a/")}
        with zipfile.ZipFile(SYSTEM) as z:
            names = set(z.namelist())
        for s in so:
            self.assertIn(f"system/priv-app/VoidOS/lib/arm64/{s}", names)
            self.assertIn(f"system/priv-app/VoidOS/lib/arm/{s}", names)

    def test_installer_guards(self):
        with zipfile.ZipFile(SYSTEM) as z:
            c = z.read("customize.sh").decode()
        self.assertIn('abort "! VoidOS needs Android 10', c)
        self.assertIn("ro.product.cpu.abi", c)
        self.assertIn("set_perm_recursive $MODPATH/system 0 0 0755 0644", c)


if __name__ == "__main__":
    unittest.main(verbosity=2)

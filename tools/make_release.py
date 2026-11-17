#!/usr/bin/env python3
"""
Gathers everything for flashing day into root/, next to root/README.md:

  root/voidos-tab-<version>.apk   the app (release build, fixed key, testOnly)
  root/voidos-boot-magisk.zip     Magisk module: the Android boot animation
  root/voidos-system-magisk.zip   Magisk module: VoidOS as a system app (optional)
  root/bootanimation.zip          the boot animation on its own
  root/logo-tool/                 the power-on logo tool, runs on its own
                                  (Python 3 + Pillow): mtk_logo.py, voidmark.py,
                                  its logo geometry and font
  root/SHA256SUMS.txt             checksums of all of the above

Run after the build:
    ./gradlew clean assembleRelease
    python3 tools/bootanim.py && python3 tools/magisk_module.py
    python3 tools/make_release.py
"""
import hashlib
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "root"


def version():
    m = re.search(r'versionName\s*=\s*"([^"]+)"', (ROOT / "app/build.gradle.kts").read_text())
    return m.group(1)


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def main():
    v = version()
    apk = ROOT / "app/build/outputs/apk/release/app-release.apk"
    if not apk.exists():
        sys.exit("no release APK: run ./gradlew assembleRelease first")
    for need in ("bootanimation.zip", "voidos-boot-magisk.zip", "voidos-system-magisk.zip"):
        if not (OUT / need).exists():
            sys.exit(f"root/{need} is missing: run tools/bootanim.py and tools/magisk_module.py")
    for old in OUT.glob("voidos-tab-*.apk"):
        old.unlink()
    dst = OUT / f"voidos-tab-{v}.apk"
    shutil.copy2(apk, dst)

    tool = OUT / "logo-tool"
    if tool.exists():
        shutil.rmtree(tool)
    tool.mkdir()
    for f in ("tools/mtk_logo.py", "tools/voidmark.py", "tools/voidmark_geometry.json",
              "app/src/main/res/font/kenney_future.ttf"):
        shutil.copy2(ROOT / f, tool / Path(f).name)
    (tool / "README.txt").write_text(
        "VoidOS power-on logo tool. Needs Python 3 and Pillow (pip install pillow).\n"
        "Run it from this folder, e.g.\n"
        "    python3 mtk_logo.py info logo.stock.bin\n"
        "    python3 mtk_logo.py build logo.stock.bin logo-voidos.bin --preview logo-voidos.png\n"
        "Full steps, checks and how to undo: ../README.md, section 5.\n")

    files = [dst, OUT / "voidos-boot-magisk.zip", OUT / "voidos-system-magisk.zip", OUT / "bootanimation.zip"]
    files += sorted(p for p in tool.iterdir())
    lines = [f"{sha(p)}  {p.relative_to(OUT).as_posix()}" for p in files]
    (OUT / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n")
    for p, l in zip(files, lines):
        print(f"{p.stat().st_size / 1e6:7.2f} MB  {l}")


if __name__ == "__main__":
    main()

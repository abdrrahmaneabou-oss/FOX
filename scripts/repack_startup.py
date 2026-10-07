#!/usr/bin/env python3
"""Repair startup DEX, keep binary Shizuku manifest, and restore NP resource identity."""
import argparse
import copy
import os
import subprocess
import zipfile
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument("baseline", type=Path)
p.add_argument("rebuilt", type=Path)
p.add_argument("output", type=Path)
p.add_argument("--manifest-apk", type=Path, default=None,
               help="APK containing the separately compiled binary AndroidManifest.xml")
a = p.parse_args()
original = Path("work/project/input/FOX_ORIGINAL.apk")
with zipfile.ZipFile(original) as legacy, zipfile.ZipFile(original, metadata_encoding="utf-8") as correct:
    restored_names = {old.filename: new.filename for old, new in zip(legacy.infolist(), correct.infolist()) if old.filename != new.filename}

manifest_apk = a.manifest_apk
if manifest_apk is None:
    android_home = Path(os.environ["ANDROID_HOME"])
    build_tools = android_home / "build-tools/35.0.0"
    android_jar = android_home / "platforms/android-35/android.jar"
    manifest_apk = Path("work/manifest-only.apk")

    # Compile only the merged manifest. Use FOX itself as an include so aapt2 can
    # resolve the app's existing @mipmap/@style/etc references against the original
    # compiled resources table. This avoids rebuilding any FOX resource files.
    subprocess.run([
        str(build_tools / "aapt2"), "link",
        "-I", str(android_jar),
        "-I", str(a.baseline),
        "--manifest", "work/decoded/AndroidManifest.xml",
        "-o", str(manifest_apk),
    ], check=True)

manifest_zip = zipfile.ZipFile(manifest_apk)
try:
    binary_manifest = manifest_zip.read("AndroidManifest.xml")
    # Binary Android XML begins with RES_XML_TYPE (0x0003) as little-endian uint16.
    if len(binary_manifest) < 8 or binary_manifest[:2] != b"\x03\x00":
        raise RuntimeError("Compiled AndroidManifest.xml is not binary AXML")

    with zipfile.ZipFile(a.baseline) as before, zipfile.ZipFile(a.rebuilt) as rebuilt, zipfile.ZipFile(a.output, "w") as after:
        for entry in before.infolist():
            name = entry.filename
            if name.upper().startswith("META-INF/") and name.upper().endswith((".RSA", ".DSA", ".EC", ".SF", "MANIFEST.MF")):
                continue
            if name == "AndroidManifest.xml":
                data = binary_manifest
            elif name in ("classes.dex", "classes2.dex", "classes3.dex"):
                data = rebuilt.read(name)
            else:
                data = before.read(name)
            if name == "AndroidManifest.xml":
                old, new = "com.fox.awg12", "com.fox.onev8"
                assert len(old) == len(new)
                assert old.encode("utf-16le") in data or old.encode() in data
                for encoding in ("utf-8", "utf-16le"):
                    data = data.replace(old.encode(encoding), new.encode(encoding))
            target = copy.copy(entry)
            if name in restored_names:
                target.filename = restored_names[name]
                target.orig_filename = target.filename
            after.writestr(target, data)
finally:
    manifest_zip.close()

with zipfile.ZipFile(a.output) as check:
    assert check.testzip() is None

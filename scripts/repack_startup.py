#!/usr/bin/env python3
"""Repair startup DEX, preserve the Shizuku manifest, and restore FOX package identity."""
import argparse
import copy
import zipfile
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument("baseline", type=Path)
p.add_argument("rebuilt", type=Path)
p.add_argument("output", type=Path)
a = p.parse_args()
original = Path("work/project/input/FOX_ORIGINAL.apk")

with zipfile.ZipFile(original) as legacy, zipfile.ZipFile(original, metadata_encoding="utf-8") as correct:
    restored_names = {
        old.filename: new.filename
        for old, new in zip(legacy.infolist(), correct.infolist())
        if old.filename != new.filename
    }

with zipfile.ZipFile(a.baseline) as before, zipfile.ZipFile(a.rebuilt) as rebuilt, zipfile.ZipFile(a.output, "w") as after:
    for entry in before.infolist():
        name = entry.filename
        if name.upper().startswith("META-INF/") and name.upper().endswith((".RSA", ".DSA", ".EC", ".SF", "MANIFEST.MF")):
            continue

        # DEX and AndroidManifest.xml are the only rebuilt entries we intentionally keep.
        # The manifest is required because it contains the Shizuku provider/permission/meta-data.
        if name in ("classes.dex", "classes2.dex", "classes3.dex", "AndroidManifest.xml"):
            data = rebuilt.read(name)
        else:
            data = before.read(name)

        if name == "AndroidManifest.xml":
            old, new = "com.fox.awg12", "com.fox.onev8"
            assert len(old) == len(new)
            found = False
            for encoding in ("utf-8", "utf-16le"):
                encoded_old = old.encode(encoding)
                if encoded_old in data:
                    found = True
                    data = data.replace(encoded_old, new.encode(encoding))
            assert found, "Rebuilt manifest no longer contains expected FOX package identity"

        target = copy.copy(entry)
        if name in restored_names:
            target.filename = restored_names[name]
            target.orig_filename = target.filename
        after.writestr(target, data)

with zipfile.ZipFile(a.output) as check:
    assert check.testzip() is None

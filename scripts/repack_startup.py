#!/usr/bin/env python3
"""Replace only the two rebuilt DEX files, preserving all other APK bytes."""
import argparse
import zipfile
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument("baseline", type=Path)
p.add_argument("rebuilt", type=Path)
p.add_argument("output", type=Path)
a = p.parse_args()
with zipfile.ZipFile(a.baseline) as before, zipfile.ZipFile(a.rebuilt) as rebuilt, zipfile.ZipFile(a.output, "w") as after:
    for entry in before.infolist():
        name = entry.filename
        if name.upper().startswith("META-INF/") and name.upper().endswith((".RSA", ".DSA", ".EC", ".SF", "MANIFEST.MF")):
            continue
        data = rebuilt.read(name) if name in ("classes.dex", "classes2.dex") else before.read(name)
        after.writestr(entry, data)
with zipfile.ZipFile(a.output) as check:
    assert check.testzip() is None

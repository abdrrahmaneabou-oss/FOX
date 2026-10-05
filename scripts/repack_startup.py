#!/usr/bin/env python3
"""Repair startup DEX and restore the identity expected by NP resource loader."""
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
        if name == "AndroidManifest.xml":
            old, new = "com.fox.awg12", "com.fox.onev8"
            assert len(old) == len(new)
            assert old.encode("utf-16le") in data or old.encode() in data
            for encoding in ("utf-8", "utf-16le"):
                data = data.replace(old.encode(encoding), new.encode(encoding))
        after.writestr(entry, data)
with zipfile.ZipFile(a.output) as check:
    assert check.testzip() is None

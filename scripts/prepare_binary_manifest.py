#!/usr/bin/env python3
"""Prepare FOX's merged text manifest for standalone aapt2 compilation.

FOX's original resources.arsc must remain byte-for-byte intact.  Apktool can decode
its manifest, but rebuilding all decoded resources is not reliable for this APK.
This helper resolves application resource references in the manifest to the
original numeric IDs from public.xml so aapt2 can compile only AndroidManifest.xml
without needing to rebuild FOX resources.
"""
import argparse
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)

REF = re.compile(r"^@(?:\+)?(?:(?P<pkg>[A-Za-z0-9_.]+):)?(?P<type>[A-Za-z0-9_]+)/(?P<name>[A-Za-z0-9_.]+)$")
THEME = re.compile(r"^\?(?:(?P<pkg>[A-Za-z0-9_.]+):)?(?P<type>attr)/(?P<name>[A-Za-z0-9_.]+)$")


def load_public(path: Path):
    root = ET.parse(path).getroot()
    out = {}
    for node in root.findall("public"):
        typ = node.attrib.get("type")
        name = node.attrib.get("name")
        rid = node.attrib.get("id")
        if typ and name and rid:
            out[(typ, name)] = rid
    if not out:
        raise RuntimeError(f"No public resource IDs found in {path}")
    return out


def resolve(value: str, public):
    match = REF.match(value) or THEME.match(value)
    if not match:
        return value
    pkg = match.group("pkg")
    # Framework references are resolved by android.jar during aapt2 link.
    if pkg == "android":
        return value
    key = (match.group("type"), match.group("name"))
    rid = public.get(key)
    if rid is None:
        raise RuntimeError(f"Missing original resource ID for {value}")
    prefix = "?" if value.startswith("?") else "@"
    return prefix + rid


def prepare(manifest: Path, public_xml: Path, output: Path):
    public = load_public(public_xml)
    tree = ET.parse(manifest)
    root = tree.getroot()
    replacements = 0
    for node in root.iter():
        for attr, value in list(node.attrib.items()):
            new_value = resolve(value, public)
            if new_value != value:
                node.attrib[attr] = new_value
                replacements += 1
    output.parent.mkdir(parents=True, exist_ok=True)
    tree.write(output, encoding="utf-8", xml_declaration=True)
    print(f"Resolved {replacements} FOX resource references in manifest")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("manifest", type=Path)
    p.add_argument("public_xml", type=Path)
    p.add_argument("output", type=Path)
    a = p.parse_args()
    prepare(a.manifest, a.public_xml, a.output)

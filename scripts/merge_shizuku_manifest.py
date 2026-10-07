#!/usr/bin/env python3
"""Merge the Shizuku AAR manifest entries into FOX's decoded manifest.

This project deliberately builds without Gradle, so AAR manifest merging has to be
performed explicitly. Only Shizuku top-level permissions and application children
(provider/meta-data) are copied; FOX's existing components remain untouched.
"""
import argparse
import copy
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)
NAME = "{%s}name" % ANDROID


def key(node):
    return (node.tag.split('}')[-1], node.attrib.get(NAME, ''))


def substitute(node, application_id):
    out = copy.deepcopy(node)
    for element in out.iter():
        for attr, value in list(element.attrib.items()):
            element.attrib[attr] = value.replace("${applicationId}", application_id)
    return out


def merge(target_path, library_paths):
    tree = ET.parse(target_path)
    root = tree.getroot()
    application_id = root.attrib["package"]
    app = root.find("application")
    if app is None:
        raise RuntimeError("Target manifest has no <application>")

    top_keys = {key(n) for n in list(root)}
    app_keys = {key(n) for n in list(app)}

    for library_path in library_paths:
        lib_root = ET.parse(library_path).getroot()
        for child in list(lib_root):
            local = child.tag.split('}')[-1]
            if local == "application":
                for component in list(child):
                    if component.tag.split('}')[-1] not in ("provider", "meta-data"):
                        continue
                    merged = substitute(component, application_id)
                    if key(merged) not in app_keys:
                        app.append(merged)
                        app_keys.add(key(merged))
            elif local in ("uses-permission", "permission"):
                merged = substitute(child, application_id)
                if key(merged) not in top_keys:
                    # Permissions belong before <application> for predictable output.
                    index = list(root).index(app)
                    root.insert(index, merged)
                    top_keys.add(key(merged))

    providers = [
        n for n in list(app)
        if n.tag.split('}')[-1] == "provider"
        and n.attrib.get(NAME) == "rikka.shizuku.ShizukuProvider"
    ]
    if len(providers) != 1:
        raise RuntimeError("Expected exactly one ShizukuProvider after merge")

    authority = providers[0].attrib.get("{%s}authorities" % ANDROID, "")
    if application_id not in authority:
        raise RuntimeError("ShizukuProvider authority is not bound to FOX package")

    tree.write(target_path, encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("target", type=Path)
    parser.add_argument("libraries", nargs="+", type=Path)
    args = parser.parse_args()
    merge(args.target, args.libraries)

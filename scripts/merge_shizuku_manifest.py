#!/usr/bin/env python3
"""Merge Shizuku manifest metadata into FOX's decoded manifest.

This project deliberately builds without Gradle, so AAR manifest merging has to be
performed explicitly. Shizuku's provider AAR does not register ShizukuProvider in
its own manifest; the client application is expected to declare it. We therefore
merge the library permissions/meta-data and add the provider exactly as documented
by Shizuku.
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


def ensure_shizuku_provider(app, application_id):
    providers = [
        n for n in list(app)
        if n.tag.split('}')[-1] == "provider"
        and n.attrib.get(NAME) == "rikka.shizuku.ShizukuProvider"
    ]
    if len(providers) > 1:
        raise RuntimeError("Multiple ShizukuProvider declarations in target manifest")

    if not providers:
        provider = ET.Element("provider")
        provider.set(NAME, "rikka.shizuku.ShizukuProvider")
        provider.set("{%s}authorities" % ANDROID, application_id + ".shizuku")
        provider.set("{%s}enabled" % ANDROID, "true")
        provider.set("{%s}exported" % ANDROID, "true")
        provider.set("{%s}multiprocess" % ANDROID, "false")
        provider.set("{%s}permission" % ANDROID, "android.permission.INTERACT_ACROSS_USERS_FULL")
        app.append(provider)
        providers = [provider]

    provider = providers[0]
    authority = provider.attrib.get("{%s}authorities" % ANDROID, "")
    if authority != application_id + ".shizuku":
        raise RuntimeError("ShizukuProvider authority is not bound to FOX package")


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

    ensure_shizuku_provider(app, application_id)
    tree.write(target_path, encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("target", type=Path)
    parser.add_argument("libraries", nargs="+", type=Path)
    args = parser.parse_args()
    merge(args.target, args.libraries)

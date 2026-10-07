#!/usr/bin/env python3
"""Add only the Shizuku client declarations required by the fourth Freeze analog."""
import argparse
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "http://schemas.android.com/apk/res/android"
A = "{%s}" % ANDROID
ET.register_namespace("android", ANDROID)


def patch(path: Path):
    tree = ET.parse(path)
    root = tree.getroot()

    permission_name = "moe.shizuku.manager.permission.API_V23"
    if not any(node.get(A + "name") == permission_name for node in root.findall("uses-permission")):
        permission = ET.Element("uses-permission")
        permission.set(A + "name", permission_name)
        root.insert(0, permission)

    application = root.find("application")
    if application is None:
        raise RuntimeError("AndroidManifest.xml has no application node")

    meta_name = "moe.shizuku.client.V3_SUPPORT"
    if not any(node.get(A + "name") == meta_name for node in application.findall("meta-data")):
        meta = ET.Element("meta-data")
        meta.set(A + "name", meta_name)
        meta.set(A + "value", "true")
        application.append(meta)

    provider_name = "rikka.shizuku.ShizukuProvider"
    providers = [node for node in application.findall("provider") if node.get(A + "name") == provider_name]
    if providers:
        provider = providers[0]
    else:
        provider = ET.Element("provider")
        application.append(provider)
    provider.set(A + "name", provider_name)
    provider.set(A + "authorities", "com.fox.onev8.shizuku")
    provider.set(A + "enabled", "true")
    provider.set(A + "exported", "true")
    provider.set(A + "multiprocess", "false")
    provider.set(A + "permission", "android.permission.INTERACT_ACROSS_USERS_FULL")

    tree.write(path, encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    patch(args.manifest)

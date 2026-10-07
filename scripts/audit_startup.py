#!/usr/bin/env python3
"""Reject changes outside startup/presentation and verify the complete intent/input bridge."""
import argparse
import copy
import hashlib
import json
import zipfile
from pathlib import Path
from loguru import logger
logger.remove()
from androguard.core.dex import DEX
from androguard.core.axml import AXMLPrinter

ALLOWED = {
    ("Lcom/ponie/dayov12/LoginActivity;", "onCreate", "(Landroid/os/Bundle;)V"),
    ("Lcom/ponie/dayov12/MainActivity;", "onCreate", "(Landroid/os/Bundle;)V"),
}
GATE = ("Lcom/ponie/dayov12/MainActivity;", "foxOriginalOnCreate", "(Landroid/os/Bundle;)V")
UPDATE = ("Landroidx/work/impl/workers/ExpDialog$FetchUpdateConfigTask;", "onPostExecute", "(Lorg/json/JSONObject;)V")
MOTION = ("Lcom/ponie/dayov12/MainActivity;", "isReducedMotionEnabled", "()Z")

ANDROID = "{http://schemas.android.com/apk/res/android}"
A_NAME = ANDROID + "name"
A_VALUE = ANDROID + "value"
A_AUTHORITIES = ANDROID + "authorities"
A_ENABLED = ANDROID + "enabled"
A_EXPORTED = ANDROID + "exported"
A_MULTIPROCESS = ANDROID + "multiprocess"
A_PERMISSION = ANDROID + "permission"
SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"
SHIZUKU_META = "moe.shizuku.client.V3_SUPPORT"
SHIZUKU_PROVIDER = "rikka.shizuku.ShizukuProvider"


def presentation(key):
    return key[0].startswith((
        "Lcom/ponie/dayov12/FoxAwgUi",
        "Lcom/ponie/dayov12/ui/",
        "Lrikka/shizuku/",
        "Lrikka/sui/",
        "Lmoe/shizuku/",
    ))


def methods(archive):
    result = {}
    for name in ("classes.dex", "classes2.dex", "classes3.dex"):
        for cls in DEX(archive.read(name)).get_classes():
            for method in cls.get_methods():
                code = method.get_code()
                if code:
                    key = (cls.get_name(), method.get_name(), method.get_descriptor())
                    assert key not in result
                    result[key] = (
                        code.get_registers_size(),
                        [(i.get_name(), i.get_output()) for i in code.get_bc().get_instructions()],
                    )
    return result


def _local(tag):
    return tag.rsplit('}', 1)[-1]


def _normalize_value(value):
    if value is None:
        return value
    return value.replace("com.fox.onev8", "com.fox.awg12")


def _signature(node):
    attrs = tuple(sorted((key, _normalize_value(value)) for key, value in node.attrib.items()))
    children = tuple(_signature(child) for child in list(node))
    text = (node.text or "").strip()
    return (_local(node.tag), attrs, text, children)


def _remove_shizuku_nodes(root):
    root = copy.deepcopy(root)
    root.set("package", "com.fox.awg12")

    for child in list(root):
        if _local(child.tag) == "uses-permission" and child.get(A_NAME) == SHIZUKU_PERMISSION:
            root.remove(child)

    app = next(child for child in list(root) if _local(child.tag) == "application")
    for child in list(app):
        kind = _local(child.tag)
        name = child.get(A_NAME)
        if kind == "provider" and name == SHIZUKU_PROVIDER:
            app.remove(child)
        elif kind == "meta-data" and name == SHIZUKU_META:
            app.remove(child)
    return root


def verify_manifest(before_bytes, after_bytes):
    old_root = AXMLPrinter(before_bytes).get_xml_obj()
    new_root = AXMLPrinter(after_bytes).get_xml_obj()
    assert old_root.get("package") == "com.fox.awg12"
    assert new_root.get("package") == "com.fox.onev8"

    permissions = [
        node for node in list(new_root)
        if _local(node.tag) == "uses-permission" and node.get(A_NAME) == SHIZUKU_PERMISSION
    ]
    assert len(permissions) == 1, "Missing/duplicate Shizuku API permission"

    app = next(child for child in list(new_root) if _local(child.tag) == "application")
    providers = [
        node for node in list(app)
        if _local(node.tag) == "provider" and node.get(A_NAME) == SHIZUKU_PROVIDER
    ]
    assert len(providers) == 1, "Missing/duplicate Shizuku provider"
    provider = providers[0]
    assert provider.get(A_AUTHORITIES) == "com.fox.onev8.shizuku"
    assert provider.get(A_ENABLED) == "true"
    assert provider.get(A_EXPORTED) == "true"
    assert provider.get(A_MULTIPROCESS) == "false"
    assert provider.get(A_PERMISSION) == "android.permission.INTERACT_ACROSS_USERS_FULL"

    metadata = [
        node for node in list(app)
        if _local(node.tag) == "meta-data" and node.get(A_NAME) == SHIZUKU_META
    ]
    assert len(metadata) == 1, "Missing/duplicate Shizuku V3 metadata"
    assert metadata[0].get(A_VALUE) == "true"

    # After removing the three intentional Shizuku nodes and normalizing the package
    # identity, the entire manifest tree must still match the baseline semantically.
    old_clean = _remove_shizuku_nodes(old_root)
    new_clean = _remove_shizuku_nodes(new_root)
    assert _signature(old_clean) == _signature(new_clean), "Unexpected manifest change"


def audit(baseline, final):
    original = Path("work/project/input/FOX_ORIGINAL.apk")
    with zipfile.ZipFile(original) as legacy, zipfile.ZipFile(original, metadata_encoding="utf-8") as correct:
        restored_names = {
            old.filename: new.filename
            for old, new in zip(legacy.infolist(), correct.infolist())
            if old.filename != new.filename
        }
        original_resources = {new: correct.read(new) for new in restored_names.values()}

    with zipfile.ZipFile(baseline) as before, zipfile.ZipFile(final) as after:
        unchanged = []
        for name in before.namelist():
            if name in ("classes.dex", "classes2.dex", "classes3.dex") or name.upper().startswith("META-INF/"):
                continue
            expected = before.read(name)
            actual_name = restored_names.get(name, name)
            actual = after.read(actual_name)
            if name == "AndroidManifest.xml":
                verify_manifest(expected, actual)
            else:
                assert expected == actual, name
                if name in restored_names:
                    assert expected == original_resources[restored_names[name]], "Original resource bytes changed"
            unchanged.append(name)

        old, new = methods(before), methods(after)
        assert {k for k in old if not presentation(k)} == {k for k in new if not presentation(k)}, "Backend method inventory changed"
        changed = {key for key in old.keys() & new.keys() if not presentation(key) and old[key] != new[key]}
        assert changed == ALLOWED | {GATE, UPDATE, MOTION}, changed
        assert new[MOTION] == (2, [("const/4", "v0, 1"), ("return", "v0")]), "Unexpected reduced-motion implementation"

        protected_prefixes = (
            "Lcom/ponie/dayov12/MyVpnService",
            "Lcom/ponie/dayov12/FloatingService",
            "Lcom/ponie/dayov12/FoxTransport",
            "Lcom/ponie/dayov12/FoxNative",
            "Lcom/ponie/dayov12/FoxConfigStore",
            "Lcom/ponie/dayov12/FoxAwgConfig",
        )
        protected = [k for k in old if k[0].startswith(protected_prefixes)]
        assert all(old[k] == new[k] for k in protected), "Functional core changed"

        update_registers, update_instructions = old[UPDATE]
        update_indices = [
            i for i, (op, operand) in enumerate(update_instructions)
            if op == "invoke-static" and "ExpDialog;->-$$Nest$smshowStyledDialog" in operand
        ]
        assert len(update_indices) == 1
        expected_update = list(update_instructions)
        expected_update[update_indices[0]:update_indices[0] + 1] = [("nop", "")] * 3
        assert new[UPDATE] == (update_registers, expected_update), "Changes outside update dialog invocation"

        old_registers, old_instructions = old[GATE]
        gate_indices = [
            i for i, (_, operand) in enumerate(old_instructions)
            if "۟۟ۦۥۢ;->۟ۦ۟ۦۥ(Ljava/lang/Object;)" in operand
        ]
        assert len(gate_indices) == 1
        expected_instructions = list(old_instructions)
        expected_instructions[gate_indices[0]:gate_indices[0] + 1] = [("nop", "")] * 3
        assert new[GATE] == (old_registers, expected_instructions), "Changes outside access-key dialog call"

        for key in ALLOWED:
            registers, instructions = new[key]
            operands = "\n".join(operand for _, operand in instructions)
            assert registers >= 6
            assert "key_auth_check" in operands
            assert "Intent;->putExtra(Ljava/lang/String; J)" in operands or "Intent;->putExtra(Ljava/lang/String;J)" in operands
            assert "LoginActivity;->key" in operands
            assert any(op == "const-wide/16" and operand.endswith(", 2") for op, operand in instructions)
            if "MainActivity;" == key[0].split("/")[-1]:
                put = next(i for i, (_, operand) in enumerate(instructions) if "putExtra" in operand)
                create = next(i for i, (_, operand) in enumerate(instructions) if "foxOriginalOnCreate" in operand)
                assert put < create

        return {
            "changed_methods": [list(k) for k in sorted(changed)],
            "unchanged_method_count": sum(k in new and old[k] == new[k] for k in old),
            "protected_core_methods": len(protected),
            "presentation_methods": sum(presentation(k) for k in new),
            "deleted_legacy_ui_methods": sum(k not in new for k in old),
            "remote_update_dialog_suppressed": True,
            "verified_apk_entry_count": len(unchanged),
            "native_and_packet_logic_unchanged": True,
            "restored_package": "com.fox.onev8",
            "manifest_changes": "package identity plus Shizuku API permission/provider/V3 metadata only",
            "zip_resource_names_restored": len(restored_names),
            "sha256": hashlib.sha256(final.read_bytes()).hexdigest(),
            "device_tested": False,
        }


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("baseline", type=Path)
    p.add_argument("final", type=Path)
    p.add_argument("report", type=Path)
    a = p.parse_args()
    report = audit(a.baseline, a.final)
    a.report.write_text(json.dumps(report, indent=2))
    print("PASS: presentation/Shizuku bridge verified; native assets, packet logic, floating controls and AWG backend unchanged.")

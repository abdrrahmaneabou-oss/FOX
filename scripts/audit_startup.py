#!/usr/bin/env python3
"""Reject changes outside startup/presentation and verify the Shizuku manifest addition."""
import argparse
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


def presentation(key):
    return key[0].startswith((
        "Lcom/ponie/dayov12/FoxAwgUi",
        "Lcom/ponie/dayov12/ui/",
        "Lrikka/shizuku/",
        "Lrikka/sui/",
        "Lmoe/shizuku/api/",
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
                    result[key] = (code.get_registers_size(), [(i.get_name(), i.get_output()) for i in code.get_bc().get_instructions()])
    return result


def audit_manifest(before_bytes, after_bytes):
    before = AXMLPrinter(before_bytes).get_xml_obj()
    after = AXMLPrinter(after_bytes).get_xml_obj()
    assert after.get("package") == "com.fox.onev8"
    before_app = before.find("application")
    after_app = after.find("application")
    assert before_app is not None and after_app is not None

    def components(app):
        out = set()
        for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
            for node in app.findall(tag):
                out.add((tag, node.get(ANDROID + "name")))
        return out

    old_components = components(before_app)
    new_components = components(after_app)
    assert old_components <= new_components, "Existing manifest component removed"
    extras = new_components - old_components
    assert extras == {("provider", "rikka.shizuku.ShizukuProvider")}, extras

    providers = [
        node for node in after_app.findall("provider")
        if node.get(ANDROID + "name") == "rikka.shizuku.ShizukuProvider"
    ]
    assert len(providers) == 1
    assert providers[0].get(ANDROID + "authorities") == "com.fox.onev8.shizuku"

    old_permissions = {n.get(ANDROID + "name") for n in before.findall("uses-permission")}
    new_permissions = {n.get(ANDROID + "name") for n in after.findall("uses-permission")}
    assert old_permissions <= new_permissions, "Existing permission removed"
    return sorted(p for p in new_permissions - old_permissions if p)


def audit(baseline, final):
    original = Path("work/project/input/FOX_ORIGINAL.apk")
    with zipfile.ZipFile(original) as legacy, zipfile.ZipFile(original, metadata_encoding="utf-8") as correct:
        restored_names = {old.filename: new.filename for old, new in zip(legacy.infolist(), correct.infolist()) if old.filename != new.filename}
        original_resources = {new: correct.read(new) for new in restored_names.values()}

    added_permissions = []
    with zipfile.ZipFile(baseline) as before, zipfile.ZipFile(final) as after:
        unchanged = []
        for name in before.namelist():
            if name in ("classes.dex", "classes2.dex", "classes3.dex") or name.upper().startswith("META-INF/"):
                continue
            expected = before.read(name)
            if name == "AndroidManifest.xml":
                added_permissions = audit_manifest(expected, after.read(name))
                unchanged.append(name)
                continue
            assert expected == after.read(restored_names.get(name, name)), name
            if name in restored_names:
                assert expected == original_resources[restored_names[name]], "Original resource bytes changed"
            unchanged.append(name)

        old, new = methods(before), methods(after)
        assert {k for k in old if not presentation(k)} == {k for k in new if not presentation(k)}, "Backend method inventory changed"
        changed = {key for key in old.keys() & new.keys() if not presentation(key) and old[key] != new[key]}
        assert changed == ALLOWED | {GATE, UPDATE, MOTION}, changed
        assert new[MOTION] == (2, [("const/4", "v0, 1"), ("return", "v0")]), "Unexpected reduced-motion implementation"
        protected_prefixes = ("Lcom/ponie/dayov12/MyVpnService", "Lcom/ponie/dayov12/FloatingService", "Lcom/ponie/dayov12/FoxTransport", "Lcom/ponie/dayov12/FoxNative", "Lcom/ponie/dayov12/FoxConfigStore", "Lcom/ponie/dayov12/FoxAwgConfig")
        protected = [k for k in old if k[0].startswith(protected_prefixes)]
        assert all(old[k] == new[k] for k in protected), "Functional core changed"

        update_registers, update_instructions = old[UPDATE]
        update_indices = [i for i, (op, operand) in enumerate(update_instructions) if op == "invoke-static" and "ExpDialog;->-$$Nest$smshowStyledDialog" in operand]
        assert len(update_indices) == 1
        expected_update = list(update_instructions)
        expected_update[update_indices[0]:update_indices[0]+1] = [("nop", "")] * 3
        assert new[UPDATE] == (update_registers, expected_update), "Changes outside update dialog invocation"

        old_registers, old_instructions = old[GATE]
        gate_indices = [i for i, (_, operand) in enumerate(old_instructions) if "۟۟ۦۥۢ;->۟ۦ۟ۦۥ(Ljava/lang/Object;)" in operand]
        assert len(gate_indices) == 1
        expected_instructions = list(old_instructions)
        expected_instructions[gate_indices[0]:gate_indices[0]+1] = [("nop", "")] * 3
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
            "manifest_changes": "ShizukuProvider and Shizuku client permissions only",
            "shizuku_permissions_added": added_permissions,
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
    print("PASS: Shizuku presentation/input extension verified; protected FOX backend unchanged.")

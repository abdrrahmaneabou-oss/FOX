#!/usr/bin/env python3
"""Reject changes outside startup, and verify the complete intent handshake."""
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


def audit(baseline, final):
    original = Path("work/project/input/FOX_ORIGINAL.apk")
    with zipfile.ZipFile(original) as legacy, zipfile.ZipFile(original, metadata_encoding="utf-8") as correct:
        restored_names = {old.filename: new.filename for old, new in zip(legacy.infolist(), correct.infolist()) if old.filename != new.filename}
        original_resources = {new: correct.read(new) for new in restored_names.values()}
    with zipfile.ZipFile(baseline) as before, zipfile.ZipFile(final) as after:
        unchanged = []
        for name in before.namelist():
            if name in ("classes.dex", "classes2.dex") or name.upper().startswith("META-INF/"):
                continue
            expected = before.read(name)
            if name == "AndroidManifest.xml":
                for encoding in ("utf-8", "utf-16le"):
                    expected = expected.replace("com.fox.awg12".encode(encoding), "com.fox.onev8".encode(encoding))
                root = AXMLPrinter(after.read(name)).get_xml_obj()
                assert root.get("package") == "com.fox.onev8"
            assert expected == after.read(restored_names.get(name, name)), name
            if name in restored_names:
                assert expected == original_resources[restored_names[name]], "Original resource bytes changed"
            unchanged.append(name)
        old, new = methods(before), methods(after)
        assert old.keys() == new.keys(), "Method inventory changed"
        changed = {key for key in old if old[key] != new[key]}
        assert changed == ALLOWED, changed
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
        return {"changed_methods": [list(k) for k in sorted(changed)], "unchanged_method_count": len(old)-2,
                "verified_apk_entry_count": len(unchanged), "native_and_packet_logic_unchanged": True,
                "restored_package": "com.fox.onev8", "manifest_changes": "package and matching provider authority strings only",
                "zip_resource_names_restored": len(restored_names),
                "sha256": hashlib.sha256(final.read_bytes()).hexdigest(), "device_tested": False}


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("baseline", type=Path)
    p.add_argument("final", type=Path)
    p.add_argument("report", type=Path)
    a = p.parse_args()
    report = audit(a.baseline, a.final)
    a.report.write_text(json.dumps(report, indent=2))
    print("PASS: only two startup methods, package identity and original ZIP names changed; resource bytes and network logic preserved.")

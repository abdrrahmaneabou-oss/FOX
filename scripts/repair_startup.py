#!/usr/bin/env python3
"""Repair startup wrappers and register the user-authorized Shizuku bridge."""
import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

LOGIN = '''.method protected onCreate(Landroid/os/Bundle;)V
    .locals 4
    invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V
    const-wide/16 v0, 0x2
    sput-wide v0, Lcom/ponie/dayov12/LoginActivity;->key:J
    new-instance v0, Landroid/content/Intent;
    const-class v1, Lcom/ponie/dayov12/MainActivity;
    invoke-direct {v0, p0, v1}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
    const-string v1, "key_auth_check"
    sget-wide v2, Lcom/ponie/dayov12/LoginActivity;->key:J
    invoke-virtual {v0, v1, v2, v3}, Landroid/content/Intent;->putExtra(Ljava/lang/String;J)Landroid/content/Intent;
    invoke-virtual {p0, v0}, Landroid/app/Activity;->startActivity(Landroid/content/Intent;)V
    invoke-virtual {p0}, Landroid/app/Activity;->finish()V
    return-void
.end method'''

MAIN = '''.method public onCreate(Landroid/os/Bundle;)V
    .locals 4
    const-wide/16 v2, 0x2
    sput-wide v2, Lcom/ponie/dayov12/LoginActivity;->key:J
    invoke-virtual {p0}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;
    move-result-object v0
    const-string v1, "key_auth_check"
    invoke-virtual {v0, v1, v2, v3}, Landroid/content/Intent;->putExtra(Ljava/lang/String;J)Landroid/content/Intent;
    invoke-virtual {p0, p1}, Lcom/ponie/dayov12/MainActivity;->foxOriginalOnCreate(Landroid/os/Bundle;)V
    invoke-static {p0}, Lcom/ponie/dayov12/FoxAwgUi;->attach(Landroid/app/Activity;)V
    return-void
.end method'''

SHIZUKU_PERMISSION = '''
    <uses-permission android:name="moe.shizuku.manager.permission.API_V23" />
'''

SHIZUKU_PROVIDER = '''
        <provider
            android:name="rikka.shizuku.ShizukuProvider"
            android:authorities="com.fox.onev8.shizuku"
            android:enabled="true"
            android:exported="true"
            android:multiprocess="false"
            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
'''

SHIZUKU_METADATA = '''
        <meta-data
            android:name="moe.shizuku.client.V3_SUPPORT"
            android:value="true" />
'''


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


def register_shizuku_provider(root, changes):
    path = root / "AndroidManifest.xml"
    before = path.read_text()
    assert 'package="com.fox.onev8"' in before or 'package="com.fox.awg12"' in before, "Unexpected package while registering Shizuku"

    after = before
    if 'moe.shizuku.manager.permission.API_V23' not in after:
        manifest_end = after.find('>') + 1
        assert manifest_end > 0
        after = after[:manifest_end] + SHIZUKU_PERMISSION + after[manifest_end:]

    if 'moe.shizuku.client.V3_SUPPORT' not in after:
        assert after.count('</application>') == 1, "Unexpected application manifest shape"
        after = after.replace('</application>', SHIZUKU_METADATA + '    </application>', 1)

    if 'rikka.shizuku.ShizukuProvider' not in after:
        assert after.count('</application>') == 1, "Unexpected application manifest shape"
        after = after.replace('</application>', SHIZUKU_PROVIDER + '    </application>', 1)

    if after == before:
        return

    path.write_text(after)
    changes.append({
        "file": str(path.relative_to(root)),
        "before": digest(before),
        "after": digest(after),
        "change": "register_shizuku_provider_permission_and_v3_metadata",
    })


def repair(root):
    changes = []
    register_shizuku_provider(root, changes)

    for name, replacement in (("LoginActivity", LOGIN), ("MainActivity", MAIN)):
        path = root / "smali_classes2/com/ponie/dayov12" / (name + ".smali")
        before = path.read_text()
        pattern = re.compile(r"(?m)^\.method (?:protected|public) onCreate\(Landroid/os/Bundle;\)V\n.*?^\.end method", re.S)
        matches = list(pattern.finditer(before))
        assert len(matches) == 1, name
        if name == "MainActivity":
            assert "foxOriginalOnCreate" in matches[0].group()
        else:
            assert "MainActivity;" in matches[0].group()
            assert "setContentView" not in matches[0].group()
        after = pattern.sub(lambda _: replacement, before, count=1)
        if name == "MainActivity":
            gate = "invoke-static/range {p0 .. p0}, Lcom/ponie/dayov12/۟۟ۦۥۢ;->۟ۦ۟ۦۥ(Ljava/lang/Object;)V"
            assert after.count(gate) == 1
            after = after.replace(gate, "nop\n    nop\n    nop", 1)
        path.write_text(after)
        changes.append({"file": str(path.relative_to(root)), "before": digest(before), "after": digest(after)})

    path = root / "smali/androidx/work/impl/workers/ExpDialog$FetchUpdateConfigTask.smali"
    before = path.read_text()
    call = "invoke-static {v0, p1}, Landroidx/work/impl/workers/ExpDialog;->-$$Nest$smshowStyledDialog(Landroid/app/Activity;Lorg/json/JSONObject;)V"
    assert before.count(call) == 1, "Unexpected update dialog implementation"
    after = before.replace(call, "nop\n    nop\n    nop", 1)
    path.write_text(after)
    changes.append({"file": str(path.relative_to(root)), "before": digest(before), "after": digest(after)})
    return changes


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("decoded", type=Path)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()

    inspector = Path("scripts/inspect_freeze.py")
    if inspector.exists():
        discovery = subprocess.run(
            [sys.executable, str(inspector), str(args.decoded)],
            check=True, text=True, capture_output=True,
        ).stdout
        print(discovery, end="")
        (args.report.parent / "freeze-discovery.txt").write_text(discovery)

    args.report.write_text(json.dumps({"changed": repair(args.decoded)}, indent=2))

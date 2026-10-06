#!/usr/bin/env python3
"""Repair the two startup methods; keep all packet processing untouched."""
import argparse
import hashlib
import json
import re
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


def repair(root):
    changes = []
    for name, replacement in (("LoginActivity", LOGIN), ("MainActivity", MAIN)):
        path = root / "smali_classes2/com/ponie/dayov12" / (name + ".smali")
        before = path.read_text()
        pattern = re.compile(r"(?m)^\.method (?:protected|public) onCreate\(Landroid/os/Bundle;\)V\n.*?^\.end method", re.S)
        matches = list(pattern.finditer(before))
        assert len(matches) == 1, name
        # Only patch our known experimental wrappers, never an unrelated APK.
        if name == "MainActivity":
            assert "foxOriginalOnCreate" in matches[0].group()
        else:
            assert "MainActivity;" in matches[0].group()
            assert "setContentView" not in matches[0].group()
        after = pattern.sub(lambda _: replacement, before, count=1)
        if name == "MainActivity":
            # This wrapper only displays DevModz's separate access-key dialog.
            # It runs before Activity.onCreate and hides the initialized main UI.
            gate = "invoke-static/range {p0 .. p0}, Lcom/ponie/dayov12/۟۟ۦۥۢ;->۟ۦ۟ۦۥ(Ljava/lang/Object;)V"
            assert after.count(gate) == 1
            # Preserve the original three-code-unit width and all branch offsets.
            after = after.replace(gate, "nop\n    nop\n    nop", 1)
        path.write_text(after)
        changes.append({"file": str(path.relative_to(root)), "before": hashlib.sha256(before.encode()).hexdigest(), "after": hashlib.sha256(after.encode()).hexdigest()})
    # Suppress only the remote update dialog invocation; retain fetch and checks.
    path = root / "smali/androidx/work/impl/workers/ExpDialog$FetchUpdateConfigTask.smali"
    before = path.read_text()
    call = "invoke-static {v0, p1}, Landroidx/work/impl/workers/ExpDialog;->-$$Nest$smshowStyledDialog(Landroid/app/Activity;Lorg/json/JSONObject;)V"
    assert before.count(call) == 1, "Unexpected update dialog implementation"
    after = before.replace(call, "nop\n    nop\n    nop", 1)
    path.write_text(after)
    changes.append({"file": str(path.relative_to(root)), "before": hashlib.sha256(before.encode()).hexdigest(), "after": hashlib.sha256(after.encode()).hexdigest()})
    return changes


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("decoded", type=Path)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    args.report.write_text(json.dumps({"changed": repair(args.decoded)}, indent=2))

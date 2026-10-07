#!/usr/bin/env python3
"""Install the source-built UI while preserving every backend class from the baseline."""
import argparse
import re
import shutil
from pathlib import Path


def install(decoded, source):
    package = decoded / 'smali_classes3/com/ponie/dayov12'
    assert (package / 'FoxTransport.smali').exists()
    for path in package.glob('FoxAwgUi*.smali'):
        path.unlink()
    for path in source.rglob('*.smali'):
        relative = path.relative_to(source)
        name = relative.as_posix()
        assert name.startswith('com/ponie/dayov12/ui/') or name.startswith('com/ponie/dayov12/FoxAwgUi'), name
        target = decoded / 'smali_classes3' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
    # Honor V12's existing reduced-motion path, avoiding continuous decorative loops.
    main = decoded / 'smali_classes2/com/ponie/dayov12/MainActivity.smali'
    text = main.read_text()
    pattern = r'(?ms)^\.method private isReducedMotionEnabled\(\)Z\n.*?^\.end method'
    replacement = '.method private isReducedMotionEnabled()Z\n    .locals 1\n    const/4 v0, 0x1\n    return v0\n.end method'
    text, count = re.subn(pattern, lambda _: replacement, text)
    assert count == 1
    main.write_text(text)

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('decoded',type=Path)
    p.add_argument('source',type=Path)
    a=p.parse_args()
    install(a.decoded,a.source)

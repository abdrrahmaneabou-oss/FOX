#!/usr/bin/env bash
# Build presentation only. Backend classes are compile-only stubs, never packaged.
set -euo pipefail
fox_android_jar="$ANDROID_HOME/platforms/android-35/android.jar"
fox_tools="$ANDROID_HOME/build-tools/35.0.0"
mkdir -p work/presentation/classes work/presentation/dex
find src/presentation/java src/presentation/stubs -name '*.java' -print > work/presentation/sources.txt
javac --release 8 -cp "$fox_android_jar" -d work/presentation/classes @work/presentation/sources.txt
python3 - <<'PY'
from pathlib import Path
import zipfile
root=Path('work/presentation/classes')
with zipfile.ZipFile('work/presentation/ui.jar','w') as z:
    for p in root.rglob('*.class'):
        rel=p.relative_to(root).as_posix()
        if rel.startswith('com/ponie/dayov12/ui/') or rel.startswith('com/ponie/dayov12/FoxAwgUi'):
            z.write(p,rel)
PY
java -cp "$fox_tools/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 29 --lib "$fox_android_jar" --output work/presentation/dex work/presentation/ui.jar
python3 - <<'PY'
from pathlib import Path
import zipfile
with zipfile.ZipFile('FOX_AWG_Experimental.apk') as base, zipfile.ZipFile('work/presentation/ui.apk','w') as z:
    z.writestr('AndroidManifest.xml',base.read('AndroidManifest.xml'))
    z.write('work/presentation/dex/classes.dex','classes.dex')
PY
java -jar work/tools/apktool.jar d -r work/presentation/ui.apk -o work/presentation/decoded > work/presentation/decode.log 2>&1
python3 scripts/install_presentation.py work/decoded work/presentation/decoded/smali

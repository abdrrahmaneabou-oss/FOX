#!/usr/bin/env bash
# Build the presentation layer plus the small Shizuku client used by the fourth Freeze analog.
# Backend/network classes remain compile-only stubs and are never packaged from src/presentation/stubs.
set -euo pipefail

fox_android_jar="$ANDROID_HOME/platforms/android-35/android.jar"
fox_tools="$ANDROID_HOME/build-tools/35.0.0"
fox_shizuku_jars=(
  work/tools/shizuku-api.jar
  work/tools/shizuku-provider.jar
  work/tools/shizuku-aidl.jar
  work/tools/shizuku-shared.jar
)

for jar in "${fox_shizuku_jars[@]}" work/tools/androidx-annotation.jar; do
  test -s "$jar"
done

mkdir -p work/presentation/classes work/presentation/dex
find src/presentation/java src/presentation/stubs -name '*.java' -print > work/presentation/sources.txt
fox_cp="$fox_android_jar:work/tools/androidx-annotation.jar:$(IFS=:; echo "${fox_shizuku_jars[*]}")"
javac --release 8 -cp "$fox_cp" -d work/presentation/classes @work/presentation/sources.txt

python3 - <<'PY'
from pathlib import Path
import zipfile

root = Path('work/presentation/classes')
shizuku_jars = [
    Path('work/tools/shizuku-api.jar'),
    Path('work/tools/shizuku-provider.jar'),
    Path('work/tools/shizuku-aidl.jar'),
    Path('work/tools/shizuku-shared.jar'),
]

own_prefixes = ('com/ponie/dayov12/ui/', 'com/ponie/dayov12/FoxAwgUi')
runtime_prefixes = ('rikka/shizuku/', 'rikka/sui/', 'moe/shizuku/')
seen = set()
with zipfile.ZipFile('work/presentation/ui.jar', 'w', zipfile.ZIP_DEFLATED) as out:
    for path in root.rglob('*.class'):
        rel = path.relative_to(root).as_posix()
        # android.* files under src/presentation/stubs are compile-only hidden-API stubs.
        if rel.startswith(own_prefixes):
            out.write(path, rel)
            seen.add(rel)
    for jar_path in shizuku_jars:
        with zipfile.ZipFile(jar_path) as jar:
            for name in jar.namelist():
                if not name.endswith('.class') or not name.startswith(runtime_prefixes) or name in seen:
                    continue
                out.writestr(name, jar.read(name))
                seen.add(name)
PY

java -cp "$fox_tools/lib/d8.jar" com.android.tools.r8.D8 \
  --release --min-api 29 \
  --lib "$fox_android_jar" \
  --lib work/tools/androidx-annotation.jar \
  --output work/presentation/dex \
  work/presentation/ui.jar

python3 - <<'PY'
import zipfile
with zipfile.ZipFile('FOX_AWG_Experimental.apk') as base, zipfile.ZipFile('work/presentation/ui.apk','w') as out:
    out.writestr('AndroidManifest.xml', base.read('AndroidManifest.xml'))
    out.write('work/presentation/dex/classes.dex', 'classes.dex')
PY

java -jar work/tools/apktool.jar d -r work/presentation/ui.apk -o work/presentation/decoded > work/presentation/decode.log 2>&1
python3 scripts/install_presentation.py work/decoded work/presentation/decoded/smali

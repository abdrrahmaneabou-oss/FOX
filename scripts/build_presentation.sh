#!/usr/bin/env bash
# Build the presentation layer and the Shizuku client runtime without touching FOX backend code.
set -euo pipefail

fox_android_jar="$ANDROID_HOME/platforms/android-35/android.jar"
fox_tools="$ANDROID_HOME/build-tools/35.0.0"
shizuku_version="13.1.5"
shizuku_base="https://repo1.maven.org/maven2/dev/rikka/shizuku"

rm -rf work/presentation
mkdir -p \
  work/presentation/classes \
  work/presentation/dex \
  work/presentation/deps/api \
  work/presentation/deps/provider

curl --fail --location --retry 3 \
  --output work/presentation/deps/api.aar \
  "$shizuku_base/api/$shizuku_version/api-$shizuku_version.aar"
curl --fail --location --retry 3 \
  --output work/presentation/deps/provider.aar \
  "$shizuku_base/provider/$shizuku_version/provider-$shizuku_version.aar"

# Keep both bytecode and library manifests. This project intentionally avoids
# Gradle manifest merging, so Shizuku entries are merged explicitly below.
unzip -qo work/presentation/deps/api.aar classes.jar AndroidManifest.xml -d work/presentation/deps/api
unzip -qo work/presentation/deps/provider.aar classes.jar AndroidManifest.xml -d work/presentation/deps/provider

fox_cp="$fox_android_jar:work/presentation/deps/api/classes.jar:work/presentation/deps/provider/classes.jar"
find src/presentation/java src/presentation/stubs -name '*.java' -print > work/presentation/sources.txt
javac --release 8 -cp "$fox_cp" -d work/presentation/classes @work/presentation/sources.txt

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

# Shizuku must be a real runtime dependency. #46 only put these jars on javac's
# classpath, which produced a decorative client with no Shizuku runtime in the APK.
java -cp "$fox_tools/lib/d8.jar" com.android.tools.r8.D8 \
  --release --min-api 29 --lib "$fox_android_jar" \
  --output work/presentation/dex \
  work/presentation/ui.jar \
  work/presentation/deps/api/classes.jar \
  work/presentation/deps/provider/classes.jar

python3 - <<'PY'
import zipfile
with zipfile.ZipFile('FOX_AWG_Experimental.apk') as base, zipfile.ZipFile('work/presentation/ui.apk','w') as z:
    z.writestr('AndroidManifest.xml',base.read('AndroidManifest.xml'))
    z.write('work/presentation/dex/classes.dex','classes.dex')
PY

java -jar work/tools/apktool.jar d -r work/presentation/ui.apk -o work/presentation/decoded > work/presentation/decode.log 2>&1
python3 scripts/install_presentation.py work/decoded work/presentation/decoded/smali

# Provider + Shizuku permission metadata must live in FOX's final manifest, not
# merely in the downloaded AARs.
python3 scripts/merge_shizuku_manifest.py \
  work/decoded/AndroidManifest.xml \
  work/presentation/deps/api/AndroidManifest.xml \
  work/presentation/deps/provider/AndroidManifest.xml

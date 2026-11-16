#!/usr/bin/env bash
set -e
export PATH=/usr/lib/jvm/default-java/bin:/usr/lib/jvm/java-17-openjdk-arm64/bin:$PATH
ROOT=/root/workspace/iosduo
JAVA=/usr/lib/jvm/default-java/bin/java
JAVAC=/usr/lib/jvm/java-17-openjdk-arm64/bin/javac
JAR=/usr/lib/jvm/java-17-openjdk-arm64/bin/jar
KEYTOOL=/usr/lib/jvm/java-17-openjdk-arm64/bin/keytool
ANDROID_JAR=/root/workspace/android.jar
R8=/root/workspace/r8.jar
B=$ROOT/build
rm -rf "$B"; mkdir -p "$B/stubs" "$B/classes" "$B/dex"

echo "== 1. compile xposed stubs"
find "$ROOT/stubs" -name '*.java' > "$B/stubs.txt"
$JAVAC -source 8 -target 8 -nowarn -d "$B/stubs" @"$B/stubs.txt" 2>&1 | grep -v 'bootstrap class path' || true
$JAR cf "$B/stubs.jar" -C "$B/stubs" .

echo "== 2. aapt2 link base apk"
aapt2 compile --dir "$ROOT/res" -o "$B/res.zip"
aapt2 link -o "$B/base.apk" --manifest "$ROOT/AndroidManifest.xml" \
  -I "$ANDROID_JAR" -A "$ROOT/assets" "$B/res.zip" \
  --min-sdk-version 24 --target-sdk-version 35

echo "== 3. compile module"
find "$ROOT/src" -name '*.java' > "$B/src.txt"
$JAVAC -source 8 -target 8 -nowarn \
  -classpath "$ANDROID_JAR:$B/stubs.jar" -d "$B/classes" @"$B/src.txt" 2>&1 | grep -v -E 'bootstrap class path|uses unchecked|deprecat|warning' || true
$JAR cf "$B/classes.jar" -C "$B/classes" .

echo "== 4. dex"
$JAVA -cp "$R8" com.android.tools.r8.D8 --release --min-api 24 \
  --lib "$ANDROID_JAR" --output "$B/dex" "$B/classes.jar"

echo "== 5. package dex into apk"
cd "$B/dex" && python3 - "$B/base.apk" "$B/unsigned.apk" <<'PY'
import sys,zipfile,os
base,out=sys.argv[1],sys.argv[2]
zi=zipfile.ZipFile(base,'r')
zo=zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED)
for it in zi.infolist():
    zo.writestr(it, zi.read(it.filename))
zo.write('classes.dex','classes.dex')
zo.close(); zi.close()
print('packed', out)
PY
cd "$ROOT"

echo "== 6. zipalign + sign"
zipalign -f 4 "$B/unsigned.apk" "$B/aligned.apk"
KS="${IOSDUO_KS:-$ROOT/iosduo.jks}"
KS_PASS="${IOSDUO_KS_PASS:?set IOSDUO_KS_PASS}"
if [ ! -f "$KS" ]; then
  $KEYTOOL -genkeypair -keystore "$KS" -storepass iosduo -keypass iosduo \
    -alias iosduo -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=DSH, OU=DSH, O=DSH, L=NA, S=NA, C=CN" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$ROOT/IOSDuo-ThreeInOne.apk" "$B/aligned.apk"
apksigner verify "$ROOT/IOSDuo-ThreeInOne.apk" && echo "SIGNED OK"
ls -l "$ROOT/IOSDuo-ThreeInOne.apk"

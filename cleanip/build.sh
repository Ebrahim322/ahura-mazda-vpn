#!/usr/bin/env bash
# Builds the APK with a self contained toolchain (see tools/fetch-tools.sh).
#
#   ./build.sh              -> cleanip.apk
#   OUT=/tmp/cleanip.apk ./build.sh
set -euo pipefail
cd "$(dirname "$0")"

TOOLS="${TOOLS_DIR:-$HOME/.toolchain}"
if [ ! -f "$TOOLS/env.sh" ]; then
    bash tools/fetch-tools.sh
fi
# shellcheck disable=SC1090
source "$TOOLS/env.sh"

OUT="${OUT:-cleanip.apk}"
BUILD=".build"
KEY="cleanip.keystore"

echo "== 0/6 preparing =="
rm -rf "$BUILD"
mkdir -p "$BUILD/classes"
PYTHON="${TOOLS}/venv/bin/python"
[ -x "$PYTHON" ] || PYTHON=python3
$PYTHON tools/make_icon.py || echo "   (icons left as they are)"

echo "== 1/6 compiling java =="
find src -name '*.java' > "$BUILD/sources.txt"
java -cp "$ECJ" org.eclipse.jdt.internal.compiler.batch.Main \
    -8 -nowarn -proc:none -encoding UTF-8 \
    -bootclasspath "$ANDROID_JAR" -cp "$ANDROID_JAR" \
    -d "$BUILD/classes" "@$BUILD/sources.txt"

echo "== 2/6 dexing =="
(cd "$BUILD/classes" && zip -qr ../classes.jar .)
java -cp "$D8" com.android.tools.r8.D8 \
    --lib "$ANDROID_JAR" --release --min-api 24 \
    --output "$BUILD" "$BUILD/classes.jar"

echo "== 3/6 packaging resources =="
"$AAPT2" compile --dir res -o "$BUILD/res.zip"
"$AAPT2" link -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    --min-sdk-version 24 --target-sdk-version 34 \
    -o "$BUILD/unsigned.apk" "$BUILD/res.zip"
(cd "$BUILD" && zip -q unsigned.apk classes.dex)

echo "== 4/6 zipalign =="
$PYTHON tools/zipalign.py "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "== 5/6 signing =="
if [ ! -f "$KEY" ]; then
    keytool -genkeypair -keystore "$KEY" -alias cleanip -keyalg RSA -keysize 2048 \
        -validity 10950 -storepass android -keypass android \
        -dname "CN=Ahura Clean IP, O=AhuraMazda, C=IR" >/dev/null
fi
rm -f "$OUT"
java -jar "$APKSIGNER" sign \
    --ks "$KEY" --ks-key-alias cleanip --ks-pass pass:android --key-pass pass:android \
    --out "$OUT" "$BUILD/aligned.apk"

echo "== 6/6 verifying =="
java -jar "$APKSIGNER" verify --print-certs "$OUT" | head -6
"$AAPT2" dump badging "$OUT" | head -6
echo
echo "built: $(pwd)/$OUT  ($(du -h "$OUT" | cut -f1))"

#!/usr/bin/env bash
# Headless tests for the scanner core (no Android device needed).
#   ./test/run-tests.sh
set -euo pipefail
cd "$(dirname "$0")/.."

TOOLS="${TOOLS_DIR:-$HOME/.toolchain}"
if [ ! -f "$TOOLS/env.sh" ]; then
    bash tools/fetch-tools.sh
fi
# shellcheck disable=SC1090
source "$TOOLS/env.sh"

OUT=".build/test"
rm -rf "$OUT"
mkdir -p "$OUT"

echo "== self signed certificate for the local test server =="
keytool -genkeypair -alias cleanip-test -keyalg RSA -keysize 2048 -validity 30 \
    -storetype PKCS12 -keystore "$OUT/test.p12" -storepass changeit -keypass changeit \
    -dname "CN=speed.cloudflare.com, O=Ahura Test, C=IR" \
    -ext "SAN=dns:speed.cloudflare.com,ip:127.0.0.1" >/dev/null

echo "== compiling =="
java -cp "$ECJ" org.eclipse.jdt.internal.compiler.batch.Main -8 -nowarn -proc:none -encoding UTF-8 \
    -d "$OUT" src/com/ahuramazda/cleanip/core test/CoreTest.java

echo "== running =="
java -cp "$OUT" CoreTest "$OUT/test.p12"

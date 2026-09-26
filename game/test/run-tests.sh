#!/usr/bin/env bash
# Runs the headless simulation tests (no Android needed).
set -euo pipefail
cd "$(dirname "$0")/.."
TOOLS="${TOOLS_DIR:-$HOME/.toolchain}"
# shellcheck disable=SC1090
source "$TOOLS/env.sh"
OUT=".build/test"
rm -rf "$OUT"
mkdir -p "$OUT"
java -cp "$ECJ" org.eclipse.jdt.internal.compiler.batch.Main -8 -nowarn -proc:none -d "$OUT" \
    src/com/ahuramazda/war/sim test/SimTest.java
java -cp "$OUT" SimTest

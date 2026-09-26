#!/usr/bin/env bash
# Renders the game screens to .preview/*.png so the art can be checked without a device.
set -euo pipefail
cd "$(dirname "$0")/.."
TOOLS="${TOOLS_DIR:-$HOME/.toolchain}"
# shellcheck disable=SC1090
source "$TOOLS/env.sh"
OUT=".preview"
rm -rf "$OUT"
mkdir -p "$OUT"
java -cp "$ECJ" org.eclipse.jdt.internal.compiler.batch.Main -8 -nowarn -proc:none -d "$OUT" \
    test/shim test/Preview.java src/com/ahuramazda/war/sim \
    src/com/ahuramazda/war/Renderer.java src/com/ahuramazda/war/Ui.java \
    src/com/ahuramazda/war/Theme.java
java -Djava.awt.headless=true -cp "$OUT" Preview "$OUT"

#!/usr/bin/env bash
set -euo pipefail

APPS_DIR="$HOME/.local/share/applications"

rm -f "$APPS_DIR/jarvis.desktop" "$APPS_DIR/jarvis-launcher.desktop"

if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "$APPS_DIR" >/dev/null 2>&1 || true
fi

echo "Removed Jarvis desktop entries from: $APPS_DIR"

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JARVIS_HOME="$(cd "$SCRIPT_DIR/../.." && pwd)"

APPS_DIR="$HOME/.local/share/applications"
TEMPLATE="$JARVIS_HOME/assets/desktop/jarvis.desktop.template"
OUTPUT="$APPS_DIR/jarvis.desktop"

mkdir -p "$APPS_DIR"

if [ ! -f "$TEMPLATE" ]; then
  echo "Template not found: $TEMPLATE" >&2
  exit 1
fi

rm -f "$APPS_DIR/jarvis.desktop" "$APPS_DIR/jarvis-launcher.desktop"

SMART_ENTRY="$APPS_DIR/SmartJARVIS.desktop"
if [ -f "$SMART_ENTRY" ]; then
  if grep -Eq '^(Name|Comment)=.*[Jj]arvis|^Exec=.*jarvis' "$SMART_ENTRY"; then
    rm -f "$SMART_ENTRY"
    echo "Removed legacy Jarvis desktop entry: $SMART_ENTRY"
  else
    echo "Leaving $SMART_ENTRY untouched (not clearly Jarvis-related)."
  fi
fi

sed "s|%JARVIS_HOME%|$JARVIS_HOME|g" "$TEMPLATE" > "$OUTPUT"
chmod 644 "$OUTPUT"

ICON_PATH="$(sed -n 's/^Icon=//p' "$OUTPUT" | tail -n 1)"
if [ -n "$ICON_PATH" ] && [ ! -e "$ICON_PATH" ]; then
  echo "Warning: Icon missing: $ICON_PATH"
fi

if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "$APPS_DIR" >/dev/null 2>&1 || true
fi

echo "Installed desktop entry: $OUTPUT"
echo "Jarvis launcher logs directory: $HOME/.jarvis/logs"

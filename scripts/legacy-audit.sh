#!/usr/bin/env bash
# Legacy archive audit script.
# Usage: ./scripts/legacy-audit.sh [path/to/extracted/legacy]
# Runtime no longer depends on the legacy archive; this is for migration forensics only.

set -e

LEGACY_DIR="${1:-}"

if [[ -z "$LEGACY_DIR" ]]; then
    echo "Usage: $0 /path/to/extracted/legacy"
    echo "See docs/_archive/legacy-migration/EXTRACTION.md for extraction notes."
    exit 1
fi

if [[ ! -d "$LEGACY_DIR" ]]; then
    echo "ERROR: Directory not found: $LEGACY_DIR"
    echo "Pass an extracted legacy directory, or use the extraction notes in docs/_archive/legacy-migration/EXTRACTION.md"
    exit 1
fi

AUDIT_OUT="${LEGACY_DIR}.audit"
mkdir -p "$AUDIT_OUT"

echo "=== Legacy Audit: $LEGACY_DIR ==="
echo "Output: $AUDIT_OUT"
echo ""

# 1. WAV inventory
echo "--- WAV inventory ---"
find "$LEGACY_DIR" -name "*.wav" -type f 2>/dev/null | sort > "${AUDIT_OUT}/wav_list.txt"
WAV_COUNT=$(wc -l < "${AUDIT_OUT}/wav_list.txt")
echo "Found $WAV_COUNT .wav files"

# 2. CSV inventory
echo "--- CSV inventory ---"
find "$LEGACY_DIR" -name "*.csv" -type f 2>/dev/null | sort > "${AUDIT_OUT}/csv_list.txt"
CSV_COUNT=$(wc -l < "${AUDIT_OUT}/csv_list.txt")
echo "Found $CSV_COUNT CSV files"
if [[ -s "${AUDIT_OUT}/csv_list.txt" ]]; then
    while read -r f; do
        echo "  $f: $(wc -l < "$f") lines"
    done < "${AUDIT_OUT}/csv_list.txt"
fi

# 3. Top-level structure
echo "--- Structure (maxdepth 3) ---"
find "$LEGACY_DIR" -maxdepth 3 -type f 2>/dev/null | head -80 > "${AUDIT_OUT}/structure.txt"
echo "Saved to ${AUDIT_OUT}/structure.txt"

# 4. XML config files
echo "--- XML files ---"
find "$LEGACY_DIR" -name "*.xml" -type f 2>/dev/null | sort > "${AUDIT_OUT}/xml_list.txt"
echo "Found $(wc -l < "${AUDIT_OUT}/xml_list.txt") XML files"

# 5. Windows-specific (discard candidates)
echo "--- Windows-only candidates ---"
find "$LEGACY_DIR" \( -name "*.exe" -o -name "*.bat" -o -name "*.ps1" \) -type f 2>/dev/null | sort > "${AUDIT_OUT}/windows_only.txt"
echo "Found $(wc -l < "${AUDIT_OUT}/windows_only.txt") Windows artifacts"

echo ""
echo "=== Audit complete. Review ${AUDIT_OUT}/ ==="

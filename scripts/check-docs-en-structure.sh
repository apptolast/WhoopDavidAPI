#!/bin/bash
# Script: Verify that docs/en/ structure is consistent with docs/en/00-index.md
# Usage: ./scripts/check-docs-en-structure.sh
# Exit code: 0 if OK, 1 if problems found
# Exits gracefully (0) if docs/en/ does not exist yet

set -euo pipefail

DOCS_DIR="docs/en"
INDEX="docs/en/00-index.md"

# Graceful skip if English docs not generated yet
if [ ! -d "$DOCS_DIR" ]; then
    echo "docs/en/ not found, skipping English docs structure check"
    exit 0
fi

if [ ! -f "$INDEX" ]; then
    echo "docs/en/00-index.md not found, skipping English docs structure check"
    exit 0
fi

ERRORS=0
TOTAL=0

echo "=== English docs structure verification ==="
echo ""

# --- Check 1: Each .md in docs/en/ must be referenced in the index ---
echo "Check 1: Files in docs/en/ referenced in index..."
for doc in "$DOCS_DIR"/*.md; do
    filename=$(basename "$doc")
    # Skip the index itself
    if [ "$filename" = "00-index.md" ]; then
        continue
    fi

    TOTAL=$((TOTAL + 1))

    if ! grep -q "$filename" "$INDEX"; then
        echo "  ERROR: $filename exists in docs/en/ but is not in 00-index.md"
        ERRORS=$((ERRORS + 1))
    fi
done
echo "  $TOTAL files verified"
echo ""

# --- Check 2: Each index entry points to an existing .md ---
echo "Check 2: Index entries point to existing files..."
ENTRIES=0
# Extract markdown links from the index table: [text](XX-name.md)
index_links=$(grep -oP '\]\([0-9]+-[^)]+\.md\)' "$INDEX" | grep -oP '[0-9]+-[^)]+\.md' || true)

for link in $index_links; do
    ENTRIES=$((ENTRIES + 1))
    if [ ! -f "$DOCS_DIR/$link" ]; then
        echo "  ERROR: $INDEX references $link but it does not exist in docs/en/"
        ERRORS=$((ERRORS + 1))
    fi
done
echo "  $ENTRIES entries verified"
echo ""

# --- Check 3: .kt files from the structure tree exist in src/ ---
echo "Check 3: Source files from structure tree exist..."
KT_FILES=0
# Extract .kt filenames mentioned in the structure tree
kt_names=$(grep -oP '[A-Z][a-zA-Z]+\.kt' "$INDEX" || true)

for kt in $kt_names; do
    KT_FILES=$((KT_FILES + 1))
    # Search for the file in src/ (main and test)
    found=$(find src -name "$kt" 2>/dev/null | head -1 || true)
    if [ -z "$found" ]; then
        echo "  ERROR: $kt mentioned in structure tree but not found in src/"
        ERRORS=$((ERRORS + 1))
    fi
done
echo "  $KT_FILES .kt files verified"
echo ""

# --- Summary ---
TOTAL_CHECKS=$((TOTAL + ENTRIES + KT_FILES))
echo "=== Summary: $TOTAL_CHECKS checks, $ERRORS errors ==="

if [ $ERRORS -gt 0 ]; then
    echo ""
    echo "English documentation structure is inconsistent."
    echo "Re-run the translation script or update docs/en/00-index.md."
    exit 1
fi

echo "English documentation structure is consistent."
exit 0

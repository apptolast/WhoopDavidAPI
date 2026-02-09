#!/bin/bash
# Script: Verify that relative links in docs/en/ point to existing files
# Usage: ./scripts/check-docs-en-links.sh
# Exit code: 0 if all links valid, 1 if broken links found
# Exits gracefully (0) if docs/en/ does not exist yet

set -euo pipefail

DOCS_DIR="docs/en"

# Graceful skip if English docs not generated yet
if [ ! -d "$DOCS_DIR" ]; then
    echo "docs/en/ not found, skipping English docs link check"
    exit 0
fi

# Graceful skip if no .md files exist yet
md_count=$(find "$DOCS_DIR" -maxdepth 1 -name "*.md" 2>/dev/null | wc -l)
if [ "$md_count" -eq 0 ]; then
    echo "No .md files in docs/en/, skipping English docs link check"
    exit 0
fi

BROKEN=0
TOTAL=0
BROKEN_LIST=""

# Extract all relative links from markdown files
# Format: [text](../../path/to/file) — English docs are one level deeper
for doc in "$DOCS_DIR"/*.md; do
    # Extract relative paths (../../something) from markdown links
    links=$(grep -oP '\]\(\.\./\.\./[^)]+\)' "$doc" | grep -oP '\.\./\.\./[^)]+' || true)

    for link in $links; do
        TOTAL=$((TOTAL + 1))
        # Resolve relative path from docs/en/
        resolved="$DOCS_DIR/$link"
        # Normalize (resolve ../)
        normalized=$(realpath --relative-to=. "$resolved" 2>/dev/null || echo "$resolved")

        if [ ! -f "$normalized" ] && [ ! -d "$normalized" ]; then
            BROKEN=$((BROKEN + 1))
            BROKEN_LIST="${BROKEN_LIST}\n  - ${doc}: ${link} -> ${normalized}"
        fi
    done
done

echo "English docs link check: $TOTAL links verified, $BROKEN broken"

if [ $BROKEN -gt 0 ]; then
    echo -e "\nBroken links:${BROKEN_LIST}"
    echo ""
    echo "Source files referenced in docs/en/ have changed or do not exist."
    echo "Re-run the translation script to update English docs."
    exit 1
fi

echo "All links in docs/en/ are valid."
exit 0

#!/bin/bash
# Script: Verificar que la estructura de docs/ es consistente con 00-indice.md
# Uso: ./scripts/check-docs-structure.sh
# Exit code: 0 si todo OK, 1 si hay problemas

set -euo pipefail

INDEX="docs/00-indice.md"
DOCS_DIR="docs"
ERRORS=0
TOTAL=0

echo "=== Verificacion de estructura de documentacion ==="
echo ""

# --- Check 1: Cada .md en docs/ debe estar referenciado en el indice ---
echo "Check 1: Ficheros en docs/ referenciados en indice..."
for doc in "$DOCS_DIR"/*.md; do
    filename=$(basename "$doc")
    # Saltar el propio indice
    if [ "$filename" = "00-indice.md" ]; then
        continue
    fi

    TOTAL=$((TOTAL + 1))

    if ! grep -q "$filename" "$INDEX"; then
        echo "  ERROR: $filename existe en docs/ pero no esta en 00-indice.md"
        ERRORS=$((ERRORS + 1))
    fi
done
echo "  $TOTAL ficheros verificados"
echo ""

# --- Check 2: Cada entrada de la tabla del indice apunta a un .md que existe ---
echo "Check 2: Entradas del indice apuntan a ficheros existentes..."
ENTRIES=0
# Extraer links markdown de la tabla del indice: [texto](XX-nombre.md)
index_links=$(grep -oP '\]\([0-9]+-[^)]+\.md\)' "$INDEX" | grep -oP '[0-9]+-[^)]+\.md' || true)

for link in $index_links; do
    ENTRIES=$((ENTRIES + 1))
    if [ ! -f "$DOCS_DIR/$link" ]; then
        echo "  ERROR: $INDEX referencia $link pero no existe en docs/"
        ERRORS=$((ERRORS + 1))
    fi
done
echo "  $ENTRIES entradas verificadas"
echo ""

# --- Check 3: Ficheros .kt del arbol de estructura existen ---
echo "Check 3: Ficheros fuente del arbol de estructura existen..."
KT_FILES=0
# Extraer nombres de ficheros .kt mencionados en el arbol de estructura
# El arbol usa formato: ├── NombreFichero.kt
kt_names=$(grep -oP '[A-Z][a-zA-Z]+\.kt' "$INDEX" || true)

for kt in $kt_names; do
    KT_FILES=$((KT_FILES + 1))
    # Buscar el fichero en src/ (main y test)
    found=$(find src -name "$kt" 2>/dev/null | head -1 || true)
    if [ -z "$found" ]; then
        echo "  ERROR: $kt mencionado en arbol de estructura pero no encontrado en src/"
        ERRORS=$((ERRORS + 1))
    fi
done
echo "  $KT_FILES ficheros .kt verificados"
echo ""

# --- Resumen ---
TOTAL_CHECKS=$((TOTAL + ENTRIES + KT_FILES))
echo "=== Resumen: $TOTAL_CHECKS verificaciones, $ERRORS errores ==="

if [ $ERRORS -gt 0 ]; then
    echo ""
    echo "La estructura de la documentacion no es consistente."
    echo "Actualiza docs/00-indice.md para reflejar el estado actual del proyecto."
    exit 1
fi

echo "Estructura de documentacion consistente."
exit 0

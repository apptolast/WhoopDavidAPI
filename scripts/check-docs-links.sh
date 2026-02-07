#!/bin/bash
# Script: Verificar que los links relativos en docs/ apuntan a ficheros existentes
# Uso: ./scripts/check-docs-links.sh
# Exit code: 0 si todos los links son validos, 1 si hay links rotos

set -euo pipefail

DOCS_DIR="docs"
BROKEN=0
TOTAL=0
BROKEN_LIST=""

# Extraer todos los links relativos de los ficheros markdown
# Formato: [texto](../ruta/al/fichero)
for doc in "$DOCS_DIR"/*.md; do
    # Extraer rutas relativas (../algo) de links markdown
    links=$(grep -oP '\]\(\.\./[^)]+\)' "$doc" | grep -oP '\.\./[^)]+' || true)

    for link in $links; do
        TOTAL=$((TOTAL + 1))
        # Resolver ruta relativa desde docs/
        resolved="$DOCS_DIR/$link"
        # Normalizar (eliminar ../)
        normalized=$(realpath --relative-to=. "$resolved" 2>/dev/null || echo "$resolved")

        if [ ! -f "$normalized" ] && [ ! -d "$normalized" ]; then
            BROKEN=$((BROKEN + 1))
            BROKEN_LIST="${BROKEN_LIST}\n  - ${doc}: ${link} -> ${normalized}"
        fi
    done
done

echo "Docs link check: $TOTAL links verificados, $BROKEN rotos"

if [ $BROKEN -gt 0 ]; then
    echo -e "\nLinks rotos:${BROKEN_LIST}"
    echo ""
    echo "Los ficheros fuente referenciados en docs/ han cambiado o no existen."
    echo "Actualiza los docs para reflejar la estructura actual del proyecto."
    exit 1
fi

echo "Todos los links en docs/ son validos."
exit 0

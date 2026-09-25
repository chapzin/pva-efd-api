#!/bin/sh
# Valida um arquivo EFD e mostra o resultado formatado.
# Uso: ./validar.sh arquivo.txt
set -e
URL="${PVA_URL:-http://127.0.0.1:8095}"
curl -sS --fail-with-body --data-binary @"$1" -H 'Content-Type: text/plain' "$URL/validar" | python3 -m json.tool

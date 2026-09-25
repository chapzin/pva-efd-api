#!/bin/bash
# Confere o serviço ponta a ponta com os dois arquivos fictícios de exemplos/:
# o válido tem que sair GERADA_PARA_ENTREGA e o com erro tem que trazer MSG_VL_ICMS_ANALIT.
set -euo pipefail
URL="${URL:-http://127.0.0.1:8095}"
AQUI="$(cd "$(dirname "$0")/.." && pwd)"

echo "aguardando $URL/saude ..."
for _ in $(seq 120); do
  curl -sf "$URL/saude" >/dev/null 2>&1 && break
  sleep 5
done
curl -sf "$URL/saude"; echo

validar() { curl -sS --max-time 900 --data-binary @"$AQUI/exemplos/$1" -H 'Content-Type: text/plain' "$URL/validar"; }

r=$(validar efd-exemplo-valido.txt)
echo "$r" | grep -q '"estado":"GERADA_PARA_ENTREGA"' || { echo "FALHOU (válido): $r"; exit 1; }
echo "ok: arquivo válido aprovado"

r=$(validar efd-exemplo-com-erro.txt)
echo "$r" | grep -q '"valido":false' && echo "$r" | grep -q 'MSG_VL_ICMS_ANALIT' || { echo "FALHOU (com erro): $r"; exit 1; }
echo "ok: arquivo com erro reprovado com MSG_VL_ICMS_ANALIT"

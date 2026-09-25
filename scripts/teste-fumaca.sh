#!/bin/bash
# Confere o serviço ponta a ponta com os arquivos fictícios de exemplos/:
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

enviar() { curl -sS --max-time 900 --data-binary @"$2" "$URL/$1"; }

r=$(enviar analisar "$AQUI/exemplos/efd-exemplo-malha.txt")
echo "$r" | grep -q '"estado":"GERADA_PARA_ENTREGA"' && echo "$r" | grep -q 'CREDITO_USO_CONSUMO' \
  || { echo "FALHOU (analisar): $r"; exit 1; }
echo "ok: /analisar aponta crédito de uso e consumo"

ZIP="$(mktemp -d)/malha.zip"
(cd "$AQUI/exemplos" && zip -qj "$ZIP" efd-exemplo-malha.txt xml-malha/*.xml)
r=$(enviar cruzar "$ZIP")
rm -rf "$(dirname "$ZIP")"
for c in XML_NAO_ESCRITURADO CREDITO_SIMPLES_ACIMA_PERMITIDO; do
  echo "$r" | grep -q "$c" || { echo "FALHOU (cruzar, $c): $r"; exit 1; }
done
echo "ok: /cruzar aponta nota não escriturada e crédito do Simples acima do permitido"

curl -sf "$URL/mensagens/MSG_VL_ICMS_ANALIT" | grep -q 'VL_ICMS' || { echo "FALHOU (mensagens)"; exit 1; }
curl -sf "$URL/tabelas/CFOP?codigo=1556" | grep -q 'uso ou consumo' || { echo "FALHOU (tabelas)"; exit 1; }
echo "ok: /mensagens e /tabelas"

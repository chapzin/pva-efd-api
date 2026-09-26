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

assinado=$(mktemp)
{ cat "$AQUI/exemplos/efd-exemplo-valido.txt"; printf 'SBRCAAEPDR-assinatura-ficticia\r\n'; } > "$assinado"
r=$(curl -sS --max-time 900 --data-binary @"$assinado" -H 'Content-Type: text/plain' "$URL/validar"); rm -f "$assinado"
echo "$r" | grep -q '"estado":"GERADA_PARA_ENTREGA"' && echo "$r" | grep -q 'ASSINATURA_REMOVIDA' \
  || { echo "FALHOU (assinado): $r"; exit 1; }
echo "ok: arquivo assinado tem a assinatura cortada e é validado"

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

# Nota regerada pelo ERP: XML assinado com um Id, protocolo autorizando outra chave; a EFD usou o Id.
DIR="$(mktemp -d)"
cp "$AQUI/exemplos/efd-exemplo-malha.txt" "$AQUI"/exemplos/xml-malha/*.xml "$DIR"/
PROPRIA=23250111222333000181550010000001231123456781
sed -i.bak "s#<chNFe>$PROPRIA</chNFe>#<chNFe>23250111222333000181550010000001231999999990</chNFe>#" "$DIR/$PROPRIA.xml"
(cd "$DIR" && zip -qj malha.zip efd-exemplo-malha.txt ./*.xml)
r=$(enviar cruzar "$DIR/malha.zip")
rm -rf "$DIR"
echo "$r" | grep -q CHAVE_NAO_AUTORIZADA_ESCRITURADA || { echo "FALHOU (cruzar, chave do protocolo): $r"; exit 1; }
echo "ok: /cruzar casa pela chave do protocolo e aponta a EFD que usou o Id do XML regerado"

# Saída própria com ST no cadastro do ERP: a nota tributou 180,00 (CST 00), a EFD debitou zero (CST 060).
DIR="$(mktemp -d)"
cp "$AQUI"/exemplos/xml-malha/*.xml "$DIR"/
sed -e "s#|$PROPRIA|15012025|15012025|1000,00|0|0,00|0,00|1000,00|9|0,00|0,00|0,00|1000,00|180,00|#|$PROPRIA|15012025|15012025|1000,00|0|0,00|0,00|1000,00|9|0,00|0,00|0,00|0,00|0,00|#" \
  -e 's#^|C190|000|5102|18,00|1000,00|1000,00|180,00|#|C190|060|5102|0,00|1000,00|0,00|0,00|#' \
  "$AQUI/exemplos/efd-exemplo-malha.txt" > "$DIR/efd.txt"
(cd "$DIR" && zip -qj malha.zip efd.txt ./*.xml)
r=$(enviar cruzar "$DIR/malha.zip")
rm -rf "$DIR"
echo "$r" | grep -q DEBITO_MENOR_QUE_DESTACADO && echo "$r" | grep -q '"cstXml":"000","cstEfd":"060"' \
  || { echo "FALHOU (cruzar, débito menor por CFOP): $r"; exit 1; }
echo "ok: /cruzar aponta débito menor que o destacado e compara CFOP a CFOP com o C190"

# SAT em resumo diário: cupom tributado resumido como ST, cupom fora da faixa do C860, dia sem C860, cancelado ignorado.
ZIP="$(mktemp -d)/sat.zip"
(cd "$AQUI/exemplos" && zip -qj "$ZIP" efd-exemplo-sat.txt xml-sat/*.xml)
r=$(enviar cruzar "$ZIP")
rm -rf "$(dirname "$ZIP")"
for c in CFE_FORA_DO_RESUMO_SAT CFE_SEM_RESUMO_SAT DEBITO_SAT_MENOR_QUE_XML '"cuponsCancelados":1' '"cstXml":"000","cstEfd":"060"'; do
  echo "$r" | grep -q "$c" || { echo "FALHOU (cruzar SAT, $c): $r"; exit 1; }
done
echo "$r" | grep -q 'XML_NAO_ESCRITURADO' && { echo "FALHOU (cruzar SAT, cupom virou nota não escriturada): $r"; exit 1; }
echo "ok: /cruzar confere CF-e contra o resumo diário do SAT (C860/C890)"

# Sem o CFeCanc: o cupom que o C890 deixou de fora pelo valor exato vira provável cancelado, não débito menor.
DIR="$(mktemp -d)"
cp "$AQUI"/exemplos/efd-exemplo-sat.txt "$AQUI"/exemplos/xml-sat/CFe2*.xml "$DIR"/
(cd "$DIR" && zip -qj sat.zip ./*.txt ./*.xml)
r=$(enviar cruzar "$DIR/sat.zip")
rm -rf "$DIR"
echo "$r" | grep -q CFE_PROVAVEL_CANCELADO_SEM_XML && echo "$r" | grep -q '"provaveisCancelados":1' \
  && echo "$r" | grep -q '"destacado":18' || { echo "FALHOU (cruzar SAT, cancelado sem XML): $r"; exit 1; }
echo "ok: /cruzar reconhece cupom provavelmente cancelado sem o XML de cancelamento"

# Fretes: CT-e creditado sem ser o tomador, CT-e tomado fora do D100 e cancelado (com o evento) ignorado.
ZIP="$(mktemp -d)/frete.zip"
(cd "$AQUI/exemplos" && zip -qj "$ZIP" efd-exemplo-frete.txt xml-frete/*.xml xml-malha/23250111222333000181550010000001231123456781.xml)
r=$(enviar cruzar "$ZIP")
rm -rf "$(dirname "$ZIP")"
for c in CTE_SEM_SER_TOMADOR '"papel":"tomador"' '"eventosCancelamento":1' '"casadosComEscrituracao":3'; do
  echo "$r" | grep -q "$c" || { echo "FALHOU (cruzar frete, $c): $r"; exit 1; }
done
echo "$r" | grep -q 23250155666777000181570010000008041000008047 && { echo "FALHOU (cruzar frete, CT-e cancelado apontado): $r"; exit 1; }
echo "ok: /cruzar confere D100 contra o CT-e pelo tomador"

curl -sf "$URL/mensagens/MSG_VL_ICMS_ANALIT" | grep -q 'VL_ICMS' || { echo "FALHOU (mensagens)"; exit 1; }
curl -sf "$URL/tabelas/CFOP?codigo=1556" | grep -q 'uso ou consumo' || { echo "FALHOU (tabelas)"; exit 1; }
echo "ok: /mensagens e /tabelas"

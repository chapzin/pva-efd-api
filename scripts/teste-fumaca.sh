#!/bin/bash
# Confere o serviço ponta a ponta com os arquivos fictícios de exemplos/:
# o válido tem que sair GERADA_PARA_ENTREGA e o com erro tem que trazer MSG_VL_ICMS_ANALIT.
set -euo pipefail
URL="${URL:-${PVA_URL:-http://127.0.0.1:8095}}"
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

utf8=$(mktemp)
sed 's/EMPRESA FICTICIA/EMPRESA FICTÍCIA/' "$AQUI/exemplos/efd-exemplo-valido.txt" > "$utf8"
r=$(curl -sS --max-time 900 --data-binary @"$utf8" -H 'Content-Type: text/plain' "$URL/validar"); rm -f "$utf8"
echo "$r" | grep -q CODIFICACAO_UTF8 || { echo "FALHOU (aviso UTF-8): $r"; exit 1; }
echo "ok: arquivo em UTF-8 recebe o aviso CODIFICACAO_UTF8"

r=$(validar efd-exemplo-com-erro.txt)
echo "$r" | grep -q '"valido":false' && echo "$r" | grep -q 'MSG_VL_ICMS_ANALIT' || { echo "FALHOU (com erro): $r"; exit 1; }
echo "ok: arquivo com erro reprovado com MSG_VL_ICMS_ANALIT"

enviar() { curl -sS --max-time 900 --data-binary @"$2" "$URL/$1"; }

for rota in validar cruzar; do
  r=$(curl -sS -o /dev/null -w '%{http_code}' --data-binary @/dev/null "$URL/$rota")
  [ "$r" = 400 ] || { echo "FALHOU (corpo vazio em /$rota): HTTP $r"; exit 1; }
done
echo "ok: corpo vazio responde 400"

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
# O CT-e escriturado é leiaute 3.00 e lista a NF-e em infDoc/infNFe: tem que ser lido como CT-e.
ZIP="$(mktemp -d)/frete.zip"
(cd "$AQUI/exemplos" && zip -qj "$ZIP" efd-exemplo-frete.txt xml-frete/*.xml xml-malha/23250111222333000181550010000001231123456781.xml)
r=$(enviar cruzar "$ZIP")
rm -rf "$(dirname "$ZIP")"
for c in CTE_SEM_SER_TOMADOR '"papel":"tomador"' '"eventosCancelamento":1' '"casadosComEscrituracao":3' '"ignorados":[]'; do
  echo "$r" | grep -qF "$c" || { echo "FALHOU (cruzar frete, $c): $r"; exit 1; }
done
echo "$r" | grep -q 23250155666777000181570010000008041000008047 && { echo "FALHOU (cruzar frete, CT-e cancelado apontado): $r"; exit 1; }
echo "ok: /cruzar confere D100 contra o CT-e pelo tomador"

curl -sf "$URL/mensagens/MSG_VL_ICMS_ANALIT" | grep -q 'VL_ICMS' || { echo "FALHOU (mensagens)"; exit 1; }
curl -sf "$URL/tabelas/CFOP?codigo=1556" | grep -q 'uso ou consumo' || { echo "FALHOU (tabelas)"; exit 1; }
echo "ok: /mensagens e /tabelas"

# MCP: protocolo sempre; o fluxo com arquivo só se os exemplos estiverem montados (PVA_DADOS=./exemplos).
mcp() { curl -sS --max-time 900 -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' --data "$1" "$URL/mcp"; }
ferramenta() { mcp "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":$2}}"; }
r=$(mcp '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"teste","version":"1"}}}')
echo "$r" | grep -q '"serverInfo":{"name":"pva-efd-api"' || { echo "FALHOU (mcp initialize): $r"; exit 1; }
r=$(curl -sS -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' --data '{"jsonrpc":"2.0","method":"notifications/initialized"}' "$URL/mcp")
[ "$r" = 202 ] || { echo "FALHOU (mcp notificação): HTTP $r"; exit 1; }
r=$(mcp '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')
for t in efd_abrir efd_detalhes efd_consultar efd_livro efd_editar efd_gerar_arquivo efd_fechar efd_validar_pasta; do
  echo "$r" | grep -q "\"$t\"" || { echo "FALHOU (mcp tools/list, $t): $r"; exit 1; }
done
r=$(curl -sS -o /dev/null -w '%{http_code}' -H 'Origin: https://exemplo.com' --data '{}' "$URL/mcp")
[ "$r" = 403 ] || { echo "FALHOU (mcp origem externa): HTTP $r"; exit 1; }
r=$(ferramenta explicar_mensagem '{"codigo":"MSG_VL_ICMS_ANALIT"}')
echo "$r" | grep -q 'VL_ICMS' || { echo "FALHOU (mcp explicar_mensagem): $r"; exit 1; }
echo "ok: /mcp responde ao protocolo (initialize, notificação, tools/list, origem)"

if ferramenta arquivos_listar '{"padrao":"efd-exemplo-malha.txt"}' | grep -q 'efd-exemplo-malha.txt'; then
  r=$(ferramenta efd_abrir '{"caminho":"efd-exemplo-malha.txt","pasta_xml":"xml-malha"}')
  s=$(echo "$r" | grep -o 'sessao\\":\\"s[0-9a-f]*' | head -1 | grep -o 's[0-9a-f]*$')
  [ -n "$s" ] && echo "$r" | grep -q XML_NAO_ESCRITURADO || { echo "FALHOU (mcp efd_abrir): $r"; exit 1; }
  echo "$r" | grep -q '| alerta | XML_NAO_ESCRITURADO |' || { echo "FALHOU (mcp tabela do resumo): $r"; exit 1; }
  r=$(ferramenta efd_consultar "{\"sessao\":\"$s\",\"sql\":\"SELECT COUNT(*) N FROM reg_c100\"}")
  echo "$r" | grep -q 'N\\":\\"2' || { echo "FALHOU (mcp efd_consultar): $r"; exit 1; }
  r=$(ferramenta efd_detalhes "{\"sessao\":\"$s\",\"secao\":\"achados\",\"codigo\":\"XML_NAO_ESCRITURADO\"}")
  echo "$r" | grep -q 23250177888999000181550010000004571876543228 || { echo "FALHOU (mcp efd_detalhes): $r"; exit 1; }
  r=$(ferramenta efd_livro "{\"sessao\":\"$s\",\"livro\":\"apuracao_icms\"}")
  echo "$r" | grep -q 'APURAÇÃO DO ICMS' && echo "$r" | grep -q 'VALOR TOTAL DO ICMS A RECOLHER | 144,00' \
    || { echo "FALHOU (mcp efd_livro): $r"; exit 1; }
  ferramenta efd_fechar "{\"sessao\":\"$s\"}" | grep -q 'fechada' || { echo "FALHOU (mcp efd_fechar)"; exit 1; }
  echo "ok: /mcp abre a EFD com os XMLs, consulta o banco, pagina o achado, gera o livro de apuração e fecha a sessão"

  r=$(ferramenta efd_abrir '{"caminho":"efd-exemplo-valido.txt"}')
  s=$(echo "$r" | grep -o 'sessao\\":\\"s[0-9a-f]*' | head -1 | grep -o 's[0-9a-f]*$')
  r=$(ferramenta efd_editar "{\"sessao\":\"$s\",\"operacoes\":[{\"acao\":\"alterar\",\"registro\":\"C100\",\"id\":1,\"campos\":{\"NAOEXISTE\":\"1\"}}]}")
  echo "$r" | grep -q 'nada foi gravado' || { echo "FALHOU (mcp efd_editar recusa campo): $r"; exit 1; }
  r=$(ferramenta efd_editar "{\"sessao\":\"$s\",\"recalcular_apuracao\":true,\"operacoes\":[
    {\"acao\":\"alterar\",\"registro\":\"C100\",\"id\":1,\"campos\":{\"VL_ICMS\":\"120,00\"}},
    {\"acao\":\"alterar\",\"registro\":\"C190\",\"id\":1,\"campos\":{\"ALIQ_ICMS\":\"12,00\",\"VL_ICMS\":\"120,00\"}},
    {\"acao\":\"alterar\",\"registro\":\"E116\",\"id\":1,\"campos\":{\"VL_OR\":\"120,00\"}}]}")
  echo "$r" | grep -q 'edicoesNaoExportadas' || { echo "FALHOU (mcp efd_editar): $r"; exit 1; }
  r=$(ferramenta efd_gerar_arquivo "{\"sessao\":\"$s\"}")
  if echo "$r" | grep -q 'pasta de saída'; then
    echo "pulado: efd_gerar_arquivo (suba com PVA_SAIDA montada com escrita)"
  else
    echo "$r" | grep -q 'GERADA_PARA_ENTREGA' || { echo "FALHOU (mcp efd_gerar_arquivo): $r"; exit 1; }
    echo "$r" | grep -q '| E110 VL_ICMS_RECOLHER | 180,00 | 120,00 |' || { echo "FALHOU (mcp tabela antes × depois): $r"; exit 1; }
    r=$(ferramenta efd_consultar "{\"sessao\":\"$s\",\"sql\":\"SELECT VL_ICMS_RECOLHER R FROM reg_e110\"}")
    echo "$r" | grep -q 'R\\":\\"120.00' || { echo "FALHOU (mcp E110 após edição): $r"; exit 1; }
    echo "ok: /mcp edita a escrituração, recalcula a apuração, gera o TXT pelo PVA e o revalida sem erros (com tabela antes × depois)"

    r=$(ferramenta efd_abrir '{"caminho":"efd-exemplo-malha.txt"}')
    m=$(echo "$r" | grep -o 'sessao\\":\\"s[0-9a-f]*' | head -1 | grep -o 's[0-9a-f]*$')
    r=$(ferramenta efd_editar "{\"sessao\":\"$m\",\"recalcular_analiticos\":true,\"recalcular_apuracao\":true,\"operacoes\":[
      {\"acao\":\"alterar\",\"registro\":\"C100\",\"id\":2,\"campos\":{\"VL_FRT\":\"10,01\",\"VL_DOC\":\"310,01\",\"VL_MERC\":\"300,00\",\"VL_BC_ICMS\":\"260,00\",\"VL_ICMS\":\"43,20\"}},
      {\"acao\":\"incluir\",\"registro\":\"C170\",\"pai\":2,\"campos\":{\"NUM_ITEM\":\"2\",\"COD_ITEM\":\"MAT01\",\"QTD\":\"1\",\"UNID\":\"UN\",\"VL_ITEM\":\"100,00\",\"IND_MOV\":\"0\",\"CST_ICMS\":\"020\",\"CFOP\":\"1102\",\"VL_BC_ICMS\":\"60,00\",\"ALIQ_ICMS\":\"12,00\",\"VL_ICMS\":\"7,20\"}},
      {\"acao\":\"alterar\",\"registro\":\"E116\",\"id\":1,\"campos\":{\"VL_OR\":\"136,80\"}}]}")
    echo "$r" | grep -q 'c100ComDespesasRateadas' || { echo "FALHOU (mcp recalcular_analiticos): $r"; exit 1; }
    echo "$r" | grep -q '| 020 | 1102 | 12,00 | 103,34 | 3,34 | 43,34 |' || { echo "FALHOU (mcp tabela dos C190): $r"; exit 1; }
    r=$(ferramenta efd_gerar_arquivo "{\"sessao\":\"$m\"}")
    echo "$r" | grep -q 'GERADA_PARA_ENTREGA' || { echo "FALHOU (mcp analíticos gerados): $r"; exit 1; }
    r=$(ferramenta efd_consultar "{\"sessao\":\"$m\",\"sql\":\"SELECT GROUP_CONCAT(CONCAT(VL_OPR,'/',VL_RED_BC) ORDER BY CFOP) V FROM reg_c190 WHERE ID_PAI=(SELECT MAX(ID) FROM reg_c100)\"}")
    echo "$r" | grep -q '103.34/43.34,206.67/0.00' || { echo "FALHOU (mcp VL_OPR/VL_RED_BC do C190): $r"; exit 1; }
    ferramenta efd_fechar "{\"sessao\":\"$m\"}" >/dev/null
    echo "ok: /mcp refaz os C190 pelo gerador do PVA, rateia o frete no VL_OPR e calcula o VL_RED_BC do CST 020"

    r=$(ferramenta efd_abrir '{"caminho":"efd-exemplo-malha.txt"}')
    g=$(echo "$r" | grep -o 'sessao\\":\\"s[0-9a-f]*' | head -1 | grep -o 's[0-9a-f]*$')
    r=$(ferramenta efd_editar "{\"sessao\":\"$g\",\"recalcular_analiticos\":true,\"recalcular_apuracao\":true,\"operacoes\":[
      {\"acao\":\"alterar\",\"registro\":\"C170\",\"id\":1,\"campos\":{\"CST_ICMS\":\"090\",\"VL_BC_ICMS\":\"0,00\",\"ALIQ_ICMS\":\"0,00\",\"VL_ICMS\":\"0,00\"}},
      {\"acao\":\"alterar\",\"registro\":\"E116\",\"id\":1,\"campos\":{\"VL_OR\":\"180,00\"}}]}")
    echo "$r" | grep -q '| 2 | VL_ICMS | 36,00 | 0,00 |' || { echo "FALHOU (mcp C100 alinhado aos C190): $r"; exit 1; }
    r=$(ferramenta efd_gerar_arquivo "{\"sessao\":\"$g\"}")
    echo "$r" | grep -q '| Erros | 0 | 0 |' || { echo "FALHOU (mcp glosa de uso e consumo): $r"; exit 1; }
    ferramenta efd_fechar "{\"sessao\":\"$g\"}" >/dev/null
    echo "ok: /mcp glosa o crédito de uso e consumo no item e alinha os totais do C100 aos C190 refeitos"
  fi
  ferramenta efd_fechar "{\"sessao\":\"$s\"}" >/dev/null
else
  echo "pulado: fluxo de arquivos do /mcp (suba com PVA_DADOS=./exemplos para testar)"
fi

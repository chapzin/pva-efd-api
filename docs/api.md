# API

Base: `http://127.0.0.1:8095` (porta configurável por `PVA_PORTA`). Todas as respostas são JSON em UTF-8.

## `POST /validar`

Importa e valida um arquivo EFD ICMS/IPI. `/analisar`, `/cruzar` e `/consultar` usam o mesmo caminho e
acrescentam campos à mesma resposta.

- **Corpo:** o conteúdo do `.txt` **cru**, exatamente como o PVA leria do disco (normalmente ISO-8859-1).
  Não use `multipart/form-data`; mande os bytes direto (`curl --data-binary @arquivo.txt`).
- **Tempo:** de ~2 s (arquivo pequeno, x86_64 nativo) a vários minutos (dezenas de milhares de linhas, emulado).
  Configure o timeout do seu cliente com folga (o `validar_lote.py` usa 30 min).
- **Concorrência:** as validações (de todos os endpoints `POST`) são **serializadas**. Um segundo pedido espera o primeiro terminar.

### Resposta `200`

```json
{
  "versaoPva": "6.1.1",
  "avisos": [],
  "estado": "EM_EDICAO",
  "valido": false,
  "erros": [ { "...": "..." } ],
  "mensagens": [],
  "ms": 2226
}
```

| Campo | Tipo | Significado |
|---|---|---|
| `versaoPva` | texto | Versão do PVA que validou. |
| `avisos` | lista | Conferências feitas **antes** do PVA. Hoje: `LEIAUTE_DO_PERIODO`, quando o `COD_VER` do 0000 não é o leiaute vigente na data inicial (segundo a tabela `VERSOES_LEIAUTE` da Receita), com `informado` e `esperado`; `ASSINATURA_REMOVIDA`, quando o arquivo veio assinado e o serviço cortou a assinatura depois do `\|9999\|`; e `SEM_REGISTRO_0000`, quando a primeira linha não é o registro 0000 (arquivo que não é EFD ou com lixo antes do 0000; o PVA reprova em seguida com `falha`). |
| `estado` | texto ou `null` | Estado em que o PVA deixou a escrituração. `GERADA_PARA_ENTREGA` ou `VALIDADA` = aprovado; `EM_EDICAO` = reprovado; `null` = o arquivo nem chegou a ser integrado (erro de estrutura, veja abaixo). |
| `valido` | booleano | `true` só se `estado` for `GERADA_PARA_ENTREGA` ou `VALIDADA`. |
| `erros` | lista | Inconsistências apontadas pelo PVA (máximo de 500 por arquivo, na ordem das linhas). |
| `mensagens` | lista de textos | Mensagens que o PVA tentou mostrar em diálogos durante a importação. |
| `falha` | texto (opcional) | Presente quando algo deu errado **no servidor** (exceção do PVA, relatório indisponível). |
| `ms` | número | Tempo da validação em milissegundos. |

Cada item de `erros`:

| Campo | Exemplo | Significado |
|---|---|---|
| `tipo` | `"E"` | `E` = erro (impede a entrega); `A` = advertência. |
| `codigo` | `"MSG_VL_ICMS_ANALIT"` | Identificador interno da mensagem do PVA. Útil para agrupar e buscar. |
| `descricao` | `"Valor inválido. A soma do campo VL_ICMS..."` | Texto oficial da mensagem, lido do catálogo do próprio PVA. |
| `registro` | `"C100"` | Registro onde o problema foi achado. |
| `campo` | `"22 - VL_ICMS"` | Número e nome do campo, como no Guia Prático. |
| `linha` | `10` | Linha do arquivo (começando em 1). |
| `valor` | `"180,00"` | Valor que está no arquivo. |
| `esperado` | `"170,00"` | Valor que o PVA calculou (pode ser `null`). |
| `conteudo` | `"\|C100\|..."` | A linha inteira do arquivo. |

### Arquivo não integrado (`estado: null`)

Quando o arquivo tem problema de **estrutura** (contagem do bloco 9 errada, registro fora de ordem, leiaute
desconhecido), o PVA recusa na importação e só mostraria um relatório de tela. O servidor reimporta pela
fachada interna para recuperar esses erros e os devolve no mesmo formato em `erros`.

### Outros códigos

| HTTP | Quando |
|---|---|
| `405` | Método diferente de `POST`. |
| `400` | Parâmetro inválido (`/consultar`, `/cruzar`, `/tabelas`). |
| `400` | Corpo vazio. |
| `413` | Corpo maior que `PVA_LIMITE_MB`. |
| `500` | Erro inesperado no servidor; o motivo vem em `erro`. |

## `GET /saude`

```json
{"ok":true,"display":true,"versaoPva":"6.1.1","tabelas":189,"tabelasAtualizadasEm":"2026-09-25T20:42:53Z"}
```

| Campo | Significado |
|---|---|
| `ok` | O serviço consegue validar agora. |
| `display` | A tela falsa (Xvfb) está respondendo. Sem ela, toda validação falharia. |
| `tabelas` | Quantos arquivos de tabelas externas estão em `recursos/TabelasExternas`. |
| `tabelasAtualizadasEm` | Última atualização bem-sucedida das tabelas pela Receita (`null` até a primeira). |

Responde `200` quando `ok` é `true` e `503` quando não é. O `HEALTHCHECK` da imagem usa este endpoint.
Enquanto o PVA está ligando (15 s a 2 min), a porta ainda não abre.

## `POST /analisar`

Mesmo corpo e mesma resposta de `/validar`, mais dois blocos calculados sobre o banco que o PVA montou ao
importar o arquivo (roda mesmo quando o arquivo é reprovado, desde que tenha sido integrado):

```json
{
  "resumo": {
    "contribuinte": {"nome": "...", "cnpj": "11222333000181", "uf": "CE", "ie": "...", "perfil": "A"},
    "periodo": {"inicio": "2025-01-01", "fim": "2025-01-31", "leiaute": "019", "finalidade": "original"},
    "apuracaoIcms": {"VL_TOT_DEBITOS": 180.00, "VL_TOT_CREDITOS": 36.00, "VL_ICMS_RECOLHER": 144.00, "...": 0},
    "totaisPorCfop": [{"cfop": "1556", "valorOperacao": 200.00, "baseIcms": 200.00, "icms": 36.00}],
    "quantidades": {"C100": 2, "D100": 0, "PARTICIPANTES": 2, "ITENS": 1, "INVENTARIO": 0}
  },
  "verificacoes": [
    {
      "codigo": "CREDITO_USO_CONSUMO",
      "nivel": "alerta",
      "titulo": "Crédito de ICMS em material de uso e consumo",
      "explicacao": "...",
      "fundamento": "LC 87/1996, art. 33, I",
      "quantidade": 1,
      "valorTotal": 36.00,
      "ocorrencias": [{"registro": "C190", "linha": 14, "documento": "456", "chave": "...", "cfop": "1556", "valor": 36.00}]
    }
  ]
}
```

`nivel`: `alerta` (provável autuação), `atencao` (conferir), `info`. Cada verificação traz no máximo 200
ocorrências; `quantidade` e `valorTotal` contam todas. A lista das verificações e o porquê de cada uma estão em
[verificacoes.md](verificacoes.md). Se uma verificação falhar no servidor, o motivo vem em `falhaEtapa`.

## `POST /cruzar`

Cruza a EFD com os XMLs das notas. **Corpo:** um arquivo `.zip` com **um** `.txt` (a EFD) e quantos `.xml`
quiser: NF-e/NFC-e (`nfeProc` ou `NFe`), CT-e (`cteProc` ou `CTe`), CF-e SAT (`CFe`, cancelamento `CFeCanc`) e eventos
de cancelamento (`procEventoNFe`, tipo 110111). Subpastas dentro do ZIP são aceitas. O limite total descompactado é 4× `PVA_LIMITE_MB`.

```bash
zip -j lote.zip efd.txt xmls/*.xml
curl --data-binary @lote.zip http://127.0.0.1:8095/cruzar
```

Resposta: tudo o que `/analisar` devolve, mais:

```json
"cruzamento": {
  "estatistica": {"xmlsLidos": 3, "documentos": 3, "eventosCancelamento": 0, "casadosComEscrituracao": 2, "ignorados": []},
  "achados": [
    {"codigo": "XML_NAO_ESCRITURADO", "nivel": "alerta", "quantidade": 1, "valorTotal": 350.00,
     "ocorrencias": [{"tipo": "NF-e", "chave": "...", "emissao": "2025-01-20", "papel": "destinatário", "valor": 350.00}]}
  ]
}
```

Os achados usam o mesmo formato das `verificacoes`. `ignorados` lista arquivos do ZIP que não são NF-e, NFC-e,
CT-e, CF-e nem evento, ou que não deu para ler, cada um com o motivo entre parênteses (até 50). CT-e é lido nos
leiautes 3.00 e 4.00. Erro no ZIP (sem `.txt`, dois `.txt`, grande demais) responde `400`.

## `POST /consultar?sql=SELECT ...`

Roda uma consulta **somente leitura** no banco que o PVA montou com o arquivo do corpo (o `.txt` da EFD). Serve
para montar relatórios próprios sem escrever um leitor de EFD.

```bash
curl --data-binary @efd.txt --url-query "sql=SELECT CFOP, SUM(VL_ICMS) ICMS FROM reg_c190 GROUP BY CFOP" \
  http://127.0.0.1:8095/consultar
```

- Tabelas `reg_XXXX` (ex.: `reg_c100`, `reg_c190`, `reg_0200`) com as colunas do leiaute oficial, mais `ID`,
  `ID_PAI` (liga filho ao pai: `reg_c190.ID_PAI = reg_c100.ID`) e `LINHA` (linha do arquivo).
- Só um `SELECT`; `;`, `INTO`, `OUTFILE`, `DUMPFILE`, `LOAD_FILE`, `SLEEP` e `BENCHMARK` são recusados (`400`).
- `limite` (padrão 1000, máximo 10000) corta o número de linhas; `truncado: true` avisa que havia mais.
- Resposta: o resultado de `/validar` mais `linhas` (lista de objetos coluna → texto).
- `--url-query` precisa do curl 7.87 ou mais novo; em versões antigas, codifique o SQL na URL à mão.

## `GET /mensagens` e `GET /mensagens/{codigo}`

O catálogo oficial de mensagens do validador (cerca de 900), lido do próprio PVA.

```bash
curl http://127.0.0.1:8095/mensagens/MSG_VL_ICMS_ANALIT
```

```json
{"codigo":"MSG_VL_ICMS_ANALIT","descricao":"Valor inválido. A soma do campo VL_ICMS dos registros analíticos ..."}
```

`404` quando o código não existe.

## `GET /tabelas` e `GET /tabelas/{nome}`

As tabelas externas que o PVA baixa da Receita (CFOP, códigos de ajuste de cada UF, CST, municípios, leiautes...).

- `GET /tabelas`: lista `pacote`, `tabela` e `versao` de cada arquivo.
- `GET /tabelas/{nome}?uf=CE&codigo=CE02&data=2025-01-15`: linhas da tabela. Todos os filtros são opcionais:
  `uf` descarta os pacotes de outras UFs, `codigo` filtra pelo começo do código (primeira coluna) e `data`
  (aaaa-mm-dd) deixa só o que estava vigente no dia. Até 2000 linhas; `total` conta todas.

```bash
curl "http://127.0.0.1:8095/tabelas/AJ_APUR_DED?uf=CE&codigo=CE02&data=2025-01-15"   # ajustes de crédito do CE
curl "http://127.0.0.1:8095/tabelas/CFOP?codigo=1556"
curl "http://127.0.0.1:8095/tabelas/VERSOES_LEIAUTE?data=2027-01-01"                  # qual COD_VER usar
```

`404` quando nenhuma tabela tem esse nome.

## `POST /tabelas/atualizar`

Baixa agora as tabelas externas publicadas pela Receita (as mesmas do menu *Atualizar tabelas* do PVA) e as
recarrega na memória. Espera a validação em curso terminar antes de começar.

```json
{"ok":true,"tabelas":189,"baixadas":["SPEDFISCAL$TABELA_X$..."],"falhas":[],"ms":7600}
```

`200` se tudo baixou; `502` se alguma tabela falhou (a lista vem em `falhas`).

## Variáveis de ambiente

| Variável | Padrão | Efeito |
|---|---|---|
| `PVA_PORTA` | `8095` | Porta HTTP dentro do contêiner. |
| `PVA_ATUALIZAR_TABELAS_HORAS` | `24` | Atualiza as tabelas no boot e a cada N horas. `0` = só pelo endpoint. |
| `PVA_LIMITE_MB` | `512` | Tamanho máximo do arquivo aceito. |
| `PVA_UF_DIFAL_NA_ENTRADA` | `CE` | UFs (separadas por vírgula) em que `DIFAL_SEM_AJUSTE` sai como `info`, porque o DIFAL é cobrado na entrada por guia própria. |
| `JAVA_OPTS` | vazio | Opções extras para a JVM (ex.: `-Xmx1536m`). |

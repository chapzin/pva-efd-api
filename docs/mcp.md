# MCP: o PVA como ferramenta do Claude

O contêiner expõe um servidor [MCP](https://modelcontextprotocol.io) em `POST /mcp` (transporte *Streamable HTTP*,
respostas em JSON). Com ele, o Claude Code (ou outro cliente MCP) usa o PVA oficial direto: abre uma EFD, lê os
erros e os achados de malha por páginas, consulta o banco que o PVA montou com SQL e cruza com os XMLs, sem
reimportar o arquivo a cada pergunta. Também edita a escrituração pelo editor do PVA e exporta o TXT corrigido.

## Ligar

1. Suba o contêiner apontando `PVA_DADOS` para a pasta onde ficam as EFD e os XMLs (**caminho absoluto**). Ela é
   montada só leitura em `/dados`:

   ```bash
   PVA_DADOS=$HOME/auditorias docker compose up -d --build
   ```

2. Registre no Claude Code:

   ```bash
   claude mcp add --transport http pva http://127.0.0.1:8095/mcp
   ```

   Confira com `claude mcp list` (tem que aparecer `✔ Connected`).

O contêiner não enxerga o resto do disco: o MCP só lê o que estiver em `PVA_DADOS`. Os caminhos podem vir como o
Claude os conhece no host (`$HOME/auditorias/cliente/efd-2025-01.txt`, traduzido por `PVA_DADOS_HOST`) ou relativos à
pasta montada (`cliente/efd-2025-01.txt`). Qualquer caminho fora dela é recusado.

## Ferramentas

| Ferramenta | O que faz |
|---|---|
| `pva_saude` | Versão do PVA, tabelas externas, pasta montada e sessões abertas. |
| `arquivos_listar` | Lista a pasta montada (`pasta`, `padrao` glob, `recursivo`), paginado. |
| `efd_abrir` | Importa a EFD (`caminho`), valida, roda as verificações de malha e, com `pasta_xml`, o cruzamento com os XMLs da pasta (subpastas incluídas). Devolve um **resumo compacto** e o id da **sessão**. |
| `efd_detalhes` | Pagina uma seção da sessão: `erros`, `verificacoes`, `achados`, `resumo`, `avisos`, `mensagens`, `estatistica`. `codigo` filtra os erros por mensagem ou abre as ocorrências de um achado. |
| `efd_consultar` | `SELECT`, `SHOW TABLES` ou `DESCRIBE <tabela>` no banco da sessão (tabelas `reg_0000`, `reg_c100`, `reg_c190`, `reg_e110`...). Somente leitura, até 2000 linhas. |
| `efd_livro` | Livros oficiais que o PVA gera da escrituração (menu Relatórios): `apuracao_icms`, `apuracao_st`, `difal`, `apuracao_ipi`, `inventario`, `ciap`, `entradas`, `saidas`, `producao_estoque`, `creditos_fiscais`. Sem `livro` lista os livros e períodos que a escrituração tem. `formato=texto` devolve as páginas em linhas (paginado); `formato=pdf` grava o PDF em `PVA_SAIDA`. `detalhar=true` lista entradas e saídas nota a nota. |
| `efd_editar` | Altera campos, inclui ou exclui registros da escrituração da sessão pelo editor do PVA (IDs do `efd_consultar`). Opcionalmente refaz os analíticos (`recalcular_analiticos`) e a apuração do bloco E (`recalcular_apuracao`) com o gerador do PVA. |
| `efd_propor_nfe` | Monta, do XML da `pasta_xml` da sessão, as operações do `efd_editar` que escrituram uma NF-e ausente da EFD (achado `XML_NAO_ESCRITURADO`). Não grava. |
| `efd_propor_correcao` | Transforma os achados da sessão em operações do `efd_editar` (crédito sem direito ou acima do XML, nota cancelada/denegada, chave regerada), com pendências para o resto. Não grava. |
| `efd_gerar_arquivo` | Exporta pelo PVA a escrituração editada para um TXT em `PVA_SAIDA` e o revalida na mesma sessão. |
| `efd_fechar` | Fecha a sessão e apaga a escrituração do banco do PVA. |
| `efd_validar_pasta` | Valida em lote as EFD de uma pasta, sem sessão: estado, total de erros e as 3 mensagens mais frequentes por arquivo. Até 50 por chamada; continue com `a_partir_de`. |
| `tabela_sped` | Tabelas externas da Receita: sem `nome` lista as tabelas; com `nome` filtra por `uf`, prefixo de `codigo` e `data` de vigência. |
| `explicar_mensagem` | Texto oficial de uma mensagem do validador. |

### Tabelas para mostrar ao usuário

As respostas de análise e de correção trazem `tabela`, em Markdown, pronta para o Claude mostrar como resultado. As
instruções do servidor pedem que ele exiba a tabela como está a cada achado, resultado e correção, sem refazer as
contas.

| Ferramenta | Tabela |
|---|---|
| `efd_abrir` | Resultado (estado, válido, erros, ICMS a recolher, saldo credor), erros do PVA por mensagem, verificações de malha e achados do cruzamento. |
| `efd_detalhes` | Erros linha a linha (linha, registro, campo, valor, esperado), lista de achados ou as ocorrências de um achado. |
| `efd_consultar` | As linhas do SELECT. |
| `efd_editar` | Correções gravadas (campo, antes, depois), os C190 refeitos (VL_OPR, despesas rateadas, VL_RED_BC) e os totais do C100 alinhados. |
| `efd_propor_nfe` | Proposta de escrituração (registro, pai, CFOP, CST, valor, BC, ICMS, regra aplicada) e pendências antes de gravar. |
| `efd_propor_correcao` | Achados cobertos (ocorrências × com proposta), correções propostas com o valor de antes, o achado e a regra, e pendências. |
| `efd_gerar_arquivo` | Arquivo original × arquivo gerado: estado, erros e totais do E110, cada mensagem do PVA e cada achado de malha/cruzamento como resolvido, novo, menor, maior ou igual. |
| `efd_validar_pasta` | Um arquivo por linha: estado, válido, erros e principais mensagens. |

Até 50 linhas por tabela (o resto vem avisado para paginar); células com mais de 90 caracteres são cortadas. Valores
monetários da apuração saem em pt-BR (`1.234,56`); os do `efd_consultar` saem como o banco guarda (`1234.56`). Se a
resposta passar do limite, a tabela sai primeiro (`tabelaOmitida`).

### Resumo de `efd_abrir`

Traz o que cabe numa primeira leitura: estado e veredito, contribuinte, período, apuração do E110, quantidades,
avisos, erros **agrupados por mensagem** (as 15 mais frequentes, contadas sobre todas as inconsistências, não só as
500 do `/validar`), a lista de verificações e de achados do cruzamento sem as ocorrências, e a estatística dos XMLs.
O detalhe vem depois, por `efd_detalhes` e `efd_consultar`.

## Livros

`efd_livro` usa os mesmos controladores e modelos (JasperReports) que o PVA usa na tela, então o conteúdo e o layout
são os do PVA: o livro de inventário de 3 mil itens sai em ~600 páginas em segundos. Os modelos pedem Arial; a imagem
traz a Liberation (mesma métrica) registrada com esse nome. Os valores saem em pt-BR (`1.000,00`).

O PDF vai para a pasta `PVA_SAIDA` (padrão `./saida`), montada com escrita:

```bash
PVA_DADOS=$HOME/auditorias PVA_SAIDA=$HOME/auditorias/livros docker compose up -d
```

Sem ela, só o formato texto funciona.

## Editar e gerar o arquivo

O fluxo de uma correção:

1. `efd_abrir` e `efd_consultar` para achar os IDs (`SELECT ID, ID_PAI, ... FROM reg_c190`).
2. `efd_editar` com a lista de operações:

   ```json
   {"sessao": "s1a2b3c", "recalcular_apuracao": true, "operacoes": [
     {"acao": "alterar", "registro": "C190", "id": 1, "campos": {"ALIQ_ICMS": "12,00", "VL_ICMS": "120,00"}},
     {"acao": "incluir", "registro": "C170", "pai": 2, "campos": {"NUM_ITEM": "2", "VL_ITEM": "100,00", "CFOP": "1556"}},
     {"acao": "excluir", "registro": "C100", "id": 3}
   ]}
   ```

   - Valores no formato do arquivo: `1000,00`, datas `ddmmaaaa`. O campo `REG` não se altera.
   - A lista inteira é conferida antes de gravar (registro, ID, pai, campos, e nenhum ID que uma exclusão anterior da
     lista já apaga). Se uma operação é inválida, nada é gravado. Um erro do próprio PVA no meio da gravação deixa as
     operações anteriores gravadas; a resposta diz quantas.
   - `excluir` leva os filhos: um C100 apaga os C170 e C190 dele.
   - No `incluir`, `"pai": "@N"` aponta para o registro incluído pela operação N (a partir de 0) da mesma lista: um
     C100 novo e os C170 dele vão num lote só. O `@N` tem de ser um `incluir` anterior do registro pai certo.
   - `recalcular_analiticos` refaz C190, C590, D190... a partir dos itens. O gerador do PVA soma só BC, ICMS, ST e
     IPI; o servidor completa o C190 (resumo em `vlOprC190`):
     - `VL_OPR` = VL_ITEM − VL_DESC + VL_ICMS_ST + VL_IPI dos C170 do grupo + frete, seguro e outras despesas do
       C100 rateados pelo VL_ITEM de cada grupo (a sobra dos centavos vai para o grupo de maior peso, então a soma
       fecha com o documento). Os C100 rateados vêm em `c100ComDespesasRateadas`.
     - `VL_RED_BC` = VL_ITEM − VL_DESC + despesas rateadas − VL_BC_ICMS nos CST x20 e x70; zero nos demais. Os
       calculados vêm em `c190ComVlRedBcCalculado` para conferir contra a NF-e.
     - Campos de valor que o gerador deixou vazios (itens sem ST ou IPI informados) viram `0,00`.
     - C190 de documento sem C170 fica como estava.
     - Os totais do C100 (VL_BC_ICMS, VL_ICMS, VL_BC_ICMS_ST, VL_ICMS_ST, VL_IPI) viram a soma dos C190 refeitos,
       só nos documentos cujos C170/C190 a edição mexeu (em `c100AlinhadosAosC190`, campo a campo). Divergência
       antiga em outro documento não é tocada: é achado. O E116 (guia a recolher) continua com você.
   - Falha do gerador não desfaz a edição: vem em `falhaRecalculo`.
3. `efd_gerar_arquivo` exporta pelo PVA (0990/9900/9999 recontados) para `PVA_SAIDA` e revalida o arquivo na mesma
   sessão: o resumo volta com os erros do arquivo novo. O nome padrão é o do original com `-pva.txt`; um arquivo com
   o mesmo nome é substituído.

### NF-e fora da EFD

`efd_propor_nfe` (sessão aberta com `pasta_xml`) lê o XML da chave e devolve `operacoes` prontas para o
`efd_editar`, `pendencias` e `pronto`:

- 0150 do participante quando o CNPJ não está no arquivo; C100 com totais pela soma dos itens.
- Entrada de terceiro: C170 por item, pai `@N` no C100. O `COD_ITEM` vem só do `de_para` (`{"cProd": "COD_ITEM"}`,
  código da empresa no 0200); sem ele, ou fora do 0200, é pendência que bloqueia. Nunca usa o cProd do fornecedor.
- CFOP de entrada por regra (5→1, 6→2, x404/x405→x403). Pelo `TIPO_ITEM` do 0200: 07 vira x556 e 08 vira x551,
  sem crédito no item. Simples com `vCredICMSSN` vira CST x90 com o crédito do XML; sem crédito, x90 (ou x60 no
  CSOSN 500) zerado.
- Emissão própria: C190 agrupado por CST/CFOP/alíquota, sem C170; chame o `efd_editar` só com
  `recalcular_apuracao`.
- `DT_E_S` padrão é a emissão (pendência na entrada); passe `dt_e_s` com a data da entrada.

Depois de gravar, o E116 continua com você, e o `efd_gerar_arquivo` mostra o achado como `resolvido`.

### Correção dos achados

`efd_propor_correcao` (opcional `codigo` para um achado só) usa os achados da última validação da sessão e o banco
do PVA para localizar os registros pela linha do arquivo:

| Achado | Operações propostas |
|---|---|
| `CREDITO_USO_CONSUMO`, `CREDITO_CST_SEM_DIREITO` | C170 do CFOP/CST do C190 com BC, alíquota e ICMS zerados (CST mantido) e `recalcular_analiticos`; sem C170, o C190. |
| `CREDITO_SIMPLES_ACIMA_PERMITIDO`, `CREDITO_MAIOR_QUE_DESTACADO` | C170 com BC/alíquota/ICMS do item de mesmo `nItem` no XML (`vCredICMSSN`/`pCredSN`, ou `vBC`/`pICMS`/`vICMS`); MEI zera. |
| `CANCELADA_ESCRITURADA`, `DENEGADA_ESCRITURADA` | Exclui o C100/D100 com os filhos; na emissão própria inclui de novo só com os campos de identificação e COD_SIT 02/04. O 0150 que só esse documento usava sai junto. |
| `CHAVE_NAO_AUTORIZADA_ESCRITURADA` | `CHV_NFE`/`CHV_CTE` trocada pela chave do protocolo. |

- Dois achados no mesmo item viram uma operação só, com o menor crédito (uso e consumo zera mesmo que o Simples
  permitisse algum).
- Os outros achados voltam em `pendencias` com o que fazer; os que pedem decisão (casamento item a item, documento
  sem C170) bloqueiam o `pronto`.
- Com edição não exportada, os achados estão velhos: gere o arquivo e proponha de novo.
- Os achados guardam até 200 ocorrências cada; acima disso, corrija, gere e proponha de novo.

Depois do `efd_editar`, ajuste o E116 ao `VL_ICMS_RECOLHER` refeito e rode o `efd_gerar_arquivo`: a tabela mostra
cada achado como `resolvido`.

O TXT exportado segue o formato do PVA: zeros decimais à direita somem (`1000` em vez de `1000,00`) e o `COD_PAIS`
do 0150 perde o zero à esquerda (`1058`). O próprio PVA aceita o arquivo assim. Ele sai sem assinatura; a entrega à
Receita é com o contribuinte.

Enquanto houver edição não exportada, o resumo da sessão traz `edicoesNaoExportadas`.

## Sessões

- A escrituração aberta por `efd_abrir` **fica no banco do PVA** até `efd_fechar`, até ficar ociosa por
  `PVA_MCP_TTL_MIN` minutos ou até passar do limite de `PVA_MCP_SESSOES` (fecha a mais antiga).
- O PVA guarda **uma escrituração por CNPJ e período**. Importar de novo o mesmo CNPJ e período, pelo MCP ou por
  qualquer endpoint (`/validar`, `/cruzar`...), substitui a do banco: a sessão antiga é fechada e o id dela aparece em
  `sessoesFechadas`. Para comparar original e retificadora, abra uma, anote o que precisa e abra a outra.
- Arquivo que o PVA não integra (erro de estrutura, `estado: null`) abre sessão só com os erros de importação;
  `efd_consultar` não funciona nela.
- O PVA atende um pedido por vez: uma chamada longa (arquivo grande) segura as outras na fila, inclusive as HTTP.

## Limites de resposta

Cada resposta de ferramenta tem no máximo 100 mil caracteres. Acima disso a ferramenta devolve erro pedindo
`por_pagina` menor, filtro por `codigo` ou `limite` menor. As ocorrências de cada achado são as primeiras 200; o
total está em `quantidade`.

## Protocolo

- `initialize`, `ping`, `tools/list`, `tools/call`. Versões aceitas: `2025-06-18`, `2025-03-26`, `2024-11-05`.
- Notificações e respostas do cliente recebem `202` sem corpo; `GET` recebe `405` (não há fluxo SSE).
- Lote JSON-RPC (lista de mensagens) é aceito.
- Pedido com cabeçalho `Origin` que não seja `localhost`/`127.0.0.1` recebe `403` (proteção contra DNS rebinding).
  Como o resto da API, não há autenticação: mantenha a porta em `127.0.0.1`.

## Variáveis de ambiente

| Variável | Padrão | Efeito |
|---|---|---|
| `PVA_DADOS` (compose) | `./dados` | Pasta do host montada só leitura em `/dados`. |
| `PVA_DADOS_HOST` | vazio | Caminho do host equivalente a `/dados`, para aceitar caminhos do host. O compose preenche com `PVA_DADOS`. |
| `PVA_SAIDA` (compose) | `./saida` | Pasta do host montada com escrita em `/saida`, para os PDFs de `efd_livro` e o TXT de `efd_gerar_arquivo`. |
| `PVA_SAIDA_HOST` | vazio | Caminho do host equivalente a `/saida`, para a resposta trazer o caminho que o Claude enxerga. |
| `PVA_MCP_SESSOES` | `4` | Sessões abertas ao mesmo tempo. |
| `PVA_MCP_TTL_MIN` | `60` | Minutos de ociosidade até a sessão ser fechada. |

# MCP: o PVA como ferramenta do Claude

O contêiner expõe um servidor [MCP](https://modelcontextprotocol.io) em `POST /mcp` (transporte *Streamable HTTP*,
respostas em JSON). Com ele, o Claude Code (ou outro cliente MCP) usa o PVA oficial direto: abre uma EFD, lê os
erros e os achados de malha por páginas, consulta o banco que o PVA montou com SQL e cruza com os XMLs, sem
reimportar o arquivo a cada pergunta.

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
| `efd_fechar` | Fecha a sessão e apaga a escrituração do banco do PVA. |
| `efd_validar_pasta` | Valida em lote as EFD de uma pasta, sem sessão: estado, total de erros e as 3 mensagens mais frequentes por arquivo. Até 50 por chamada; continue com `a_partir_de`. |
| `tabela_sped` | Tabelas externas da Receita: sem `nome` lista as tabelas; com `nome` filtra por `uf`, prefixo de `codigo` e `data` de vigência. |
| `explicar_mensagem` | Texto oficial de uma mensagem do validador. |

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
| `PVA_SAIDA` (compose) | `./saida` | Pasta do host montada com escrita em `/saida`, para os PDFs de `efd_livro`. |
| `PVA_SAIDA_HOST` | vazio | Caminho do host equivalente a `/saida`, para a resposta trazer o caminho que o Claude enxerga. |
| `PVA_MCP_SESSOES` | `4` | Sessões abertas ao mesmo tempo. |
| `PVA_MCP_TTL_MIN` | `60` | Minutos de ociosidade até a sessão ser fechada. |

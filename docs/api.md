# API

Base: `http://127.0.0.1:8095` (porta configurável por `PVA_PORTA`). Todas as respostas são JSON em UTF-8.

## `POST /validar`

Importa e valida um arquivo EFD ICMS/IPI.

- **Corpo:** o conteúdo do `.txt` **cru**, exatamente como o PVA leria do disco (normalmente ISO-8859-1).
  Não use `multipart/form-data`; mande os bytes direto (`curl --data-binary @arquivo.txt`).
- **Tempo:** de ~2 s (arquivo pequeno, x86_64 nativo) a vários minutos (dezenas de milhares de linhas, emulado).
  Configure o timeout do seu cliente com folga (o `validar_lote.py` usa 30 min).
- **Concorrência:** as validações são **serializadas**. Um segundo pedido espera o primeiro terminar.

### Resposta `200`

```json
{
  "versaoPva": "6.1.1",
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
| `413` | Corpo vazio ou maior que `PVA_LIMITE_MB`. |

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
| `JAVA_OPTS` | vazio | Opções extras para a JVM (ex.: `-Xmx1536m`). |

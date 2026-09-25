# Comportamentos do PVA observados na prática

Anotações de quem usou este serviço para validar lotes grandes de EFD ICMS/IPI com o **PVA 6.1.1**. Não é
documentação oficial: é o que o PVA de fato aceitou ou recusou. Sempre confira no
[Guia Prático da EFD ICMS/IPI](http://sped.rfb.gov.br/) e nas notas técnicas vigentes.

Contribuições são bem-vindas (veja o final).

## Estrutura do arquivo

- **Bloco vazio** que passa a ter documento: o indicador do `x001` precisa ir para `0` (com movimento), senão
  sai `MSG_BLOCO_SEM_MOVIMENTO`.
- Qualquer inclusão ou exclusão de linha exige recontar `x990`, os `9900` e o `9999`. Contagem errada = arquivo
  **não integrado** (`estado: null`).
- **Arquivo assinado** (vindo do ReceitanetBX) abre só para visualização: corte tudo depois do `|9999|`.

## Documentos (C100/C170/C190)

- **Base e ICMS:** o PVA recusa `C190` diferente da soma dos `C170` e `C100` diferente da soma dos `C190`
  (tolerância observada de ~R$ 1). `VL_OPR` e `VL_DOC` divergentes passam.
- **Data fora do período:** documento com data posterior a `DT_FIN` reprova e **deixa de contar** no `E110`
  (o PVA espera o débito sem ele).
- **Ao mudar `DT_FIN`** (ex.: retificadora de outro período), mude também `E100`/`E200`.
- **`C170` com `QTD` 0** só passa com `COD_SIT 06` (complementar). NF-e complementar de IPI vai como `06`
  mantendo o `C170`; tirar o `C170` reprova.
- **`COD_SIT 04` (denegada):** reprova (`MSG_EXISTE_COD_SIT`) em períodos a partir de 2023; até 2022 passa.
- **`MSG_CST_ICMS_RED_BC`** é só advertência.

## Participantes (0150)

- **Regra 12001** (`MSG_COMPARA_CNPJ_NFE_PARTICIPANTE_12001`): o CNPJ do participante tem que bater com o CNPJ
  da chave da NF-e. Não se aplica a `COD_SIT 08`.
- **NF-e avulsa emitida pela SEFAZ** (séries 890 a 899): a chave traz o CNPJ **da SEFAZ**, não do vendedor.
  Com `COD_SIT 00`, o participante no `0150` tem que ser a própria SEFAZ, com endereço (o `0150` exige `END`,
  e o XML da avulsa vem sem endereço do fisco).
- **`MSG_EXISTE_COD_MUN`** no `0175` pega `CONT_ANT` com código de município IBGE de dígito inválido.

## Itens (0200/0220)

- **`MSG_OBRIGATORIO_NCM`** só dispara com `IND_ATIV = 0` (industrial) e `TIPO_ITEM` fora de 07/08/09/10/99.
- **Unidade divergente** (`MSG_VALIDA_UNID_CONVERSAO`: `UNID` do `C170` ≠ `UNID_INV` do `0200` sem `0220`):
  só a partir do leiaute 016, ignora maiúsculas/minúsculas e só para `TIPO_ITEM 00`.
- **`0220`:** no leiaute 016 é cobrado para todo `TIPO_ITEM`; do 017 em diante o 07 (uso e consumo) fica
  dispensado. O `0220` ganhou `COD_BARRA` no leiaute 016 (`|0220|UNI|1||`); sem o campo, a importação quebra
  com "erro ao conectar ao banco".

## Apuração (E110/E116/E316)

- **`E316 COD_REC`:** validado contra a tabela da UF do `E300` e, quando a UF do `E300` é diferente da UF do
  `0000`, também contra a `SPEDFISCAL_GENERICA` (códigos GNRE 1001xx). UF sem tabela publicada = texto livre.

## Inventário (Bloco H)

- **`H010` sem `COD_CTA`** reprova (`MSG_OBRIGATORIO_COD_CTA`).

## Como descobrir o porquê de uma regra

Muitas regras do PVA são consultas SQL. Veja a seção final de [arquitetura.md](arquitetura.md): o texto das
consultas fica em `queries.xml`, dentro de `fiscalpva-dominio.jar`.

## Contribuir

Achou outro comportamento? Abra uma *issue* ou *pull request* com:

1. a versão do PVA e o leiaute (`COD_VER`);
2. o `codigo` da mensagem e o registro;
3. um trecho **anonimizado** do arquivo que reproduz o caso (nunca mande dados reais de contribuinte).

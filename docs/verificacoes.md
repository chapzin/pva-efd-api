# Verificações além do PVA

O PVA confere se o arquivo está **bem montado**: leiaute, somas, contagens, códigos que existem nas tabelas.
Ele não confere se o arquivo está **certo**: um crédito indevido, somado direitinho, passa no PVA e cai na malha
da SEFAZ meses depois.

`/analisar` e `/cruzar` rodam, depois do PVA, as conferências que as malhas fiscais estaduais fazem com mais
frequência e que aparecem como as maiores dores de escritórios contábeis em fóruns, cursos e comunicados das
SEFAZ. Tudo é calculado sobre o banco que o **próprio PVA** montou ao ler o arquivo: não existe um segundo leitor
de EFD que possa discordar do oficial.

> Os achados são **indícios para conferência**, não autuação. Cada um traz a explicação e o fundamento legal
> para o contador decidir. Regras estaduais (benefícios, regimes especiais) podem justificar o que parece erro.

## Níveis

| Nível | Significado |
|---|---|
| `alerta` | Situação que o fisco costuma autuar. Confira antes de entregar. |
| `atencao` | Pode estar certo, mas é um erro comum. Vale olhar. |
| `info` | Informação para completar a coleta (ex.: XML que falta). |

## `/analisar`: só com a EFD

| Código | Nível | O que procura | Por que importa |
|---|---|---|---|
| `CREDITO_USO_CONSUMO` | alerta | C190 de CFOP 1556, 2556, 3556, 1407, 2407 com ICMS | Crédito de material de uso e consumo está adiado por lei (LC 87/1996, art. 33, I). O ICMS nesse C190 entra direto como crédito no E110. |
| `CREDITO_ATIVO_DIRETO` | atencao | C190 de CFOP 1551, 2551, 3551, 1406, 2406 com ICMS | Crédito de ativo imobilizado é 1/48 por mês pelo CIAP (Bloco G), não integral na nota. |
| `CREDITO_CST_SEM_DIREITO` | alerta | Entrada com CST final 40, 41, 50 ou 60 e ICMS | Isenta, não tributada, suspensa ou com ST já retida: não há ICMS próprio para creditar. |
| `DEBITO_EM_SAIDA_ST` | atencao | Saída CFOP 5405/6404 com ICMS (nota, C890 do SAT, C850 da NFC-e, C490 do ECF) e nenhum estorno de débito no E111 | Mercadoria recebida com ST não costuma ter ICMS próprio na venda; débito aqui é erro de cadastro e imposto pago a mais. |
| `ESTORNO_DIFERE_DEBITO_ST` | alerta / atencao | Há estorno de débito no E111 (código `UF03xxxx`) e ele difere, em mais de R$ 1,00, do ICMS destacado nas vendas com CFOP 5403/5405/6403/6404 | Algumas UFs mandam destacar o ICMS na venda com ST e estornar na apuração (ex.: CE, Decreto 35.395/2023, código CE030007). Estorno **maior** que o débito reduz o imposto de outras operações (`alerta`); **menor** deixa débito de venda com ST na apuração (`atencao`). |
| `INVENTARIO_AUSENTE_FEVEREIRO` | alerta | EFD de fevereiro sem H005 com data 31/12 do ano anterior | O inventário de fim de ano vai na EFD de fevereiro. Omissão muito cruzada pelas SEFAZ. |
| `INVENTARIO_ZERADO` | alerta | H005 de 31/12 com valor zero ou sem nenhum H010, em estabelecimento com compras ou vendas | Estoque zerado no fim do ano quase nunca é real: as vendas do ano seguinte ficam sem estoque de origem (omissão de entrada). O PVA aceita o arquivo assim. |
| `DIFAL_SEM_AJUSTE` | atencao / info | Compras interestaduais de uso/consumo ou ativo e nenhum ajuste de débito no E111; lista as notas | Em regra o DIFAL dessas entradas entra como ajuste de débito. Nas UFs de `PVA_UF_DIFAL_NA_ENTRADA` (padrão `CE`), onde o DIFAL costuma ser cobrado na entrada por guia própria, o nível cai para `info`. Conferência heurística: o código de ajuste varia por UF. |

Além disso, `/analisar` devolve o `resumo` (contribuinte, período, apuração do E110 e totais por CFOP), que
serve para montar o painel da carteira sem abrir o arquivo.

## `/cruzar`: EFD × XML

Recebe um ZIP com a EFD e os XMLs de NF-e, NFC-e, CT-e e eventos de cancelamento do período. Casa cada
documento pela **chave de acesso** (C100.CHV_NFE, D100.CHV_CTE) e compara com o que o XML diz.

| Código | Nível | O que procura | Por que importa |
|---|---|---|---|
| `XML_NAO_ESCRITURADO` | alerta | XML autorizado, emitido no período, em que a empresa é emitente, destinatária ou tomadora, sem C100/D100 | É o cruzamento nº 1 das malhas. Nota de entrada pode ter sido lançada em outro mês: confira antes. |
| `CANCELADA_ESCRITURADA` | alerta | XML com cancelamento (evento 110111 ou protocolo 101) e EFD com COD_SIT diferente de 02/03 | Débito ou crédito de nota que não existe. |
| `DENEGADA_ESCRITURADA` | alerta | Protocolo de denegação e COD_SIT diferente de 04 | Nota denegada vai com COD_SIT 04 e sem valores. |
| `CREDITO_MAIOR_QUE_DESTACADO` | alerta | Entrada com ICMS escriturado maior que o destacado no XML | O crédito é limitado ao imposto destacado (LC 87/1996, art. 23). |
| `CREDITO_SIMPLES_ACIMA_PERMITIDO` | alerta | Fornecedor do Simples (CRT 1) com crédito acima do `vCredICMSSN`, ou MEI (CRT 4) com qualquer crédito | Só o crédito informado na nota pode ser aproveitado (LC 123/2006, art. 23). |
| `DEBITO_MENOR_QUE_DESTACADO` | atencao | Saída própria com ICMS escriturado menor que o destacado | O fisco cobra a diferença pelo valor do XML. |
| `VALOR_DIVERGENTE_DO_XML` | atencao | VL_DOC diferente de vNF / vTPrest | Digitação ou importação errada. |
| `OPERACAO_INVERTIDA` | alerta | Nota própria com IND_OPER diferente do tpNF do XML | Entrada lançada como saída (ou o contrário) inverte débito e crédito. |
| `CHAVE_DE_TERCEIRO` | alerta | NF-e escriturada em que o CNPJ da empresa não é emitente nem destinatário | Crédito de nota de outra empresa é glosado. |
| `CTE_SEM_SER_TOMADOR` | alerta | CT-e de entrada com crédito, em que a empresa não é a tomadora do serviço | Só o tomador do frete se credita do ICMS do CT-e. |
| `ESCRITURADO_SEM_XML` | info | C100/D100 com chave e sem XML no ZIP | Mostra o que falta coletar. |

A tolerância de valor é R$ 0,01. Documentos cancelados ou denegados na EFD não entram nas comparações de valor.

## Painel da carteira

`exemplos/validar_lote.py --analisar` roda `/analisar` numa pasta inteira e grava, além do `resumo.csv`:

- `erros-por-mensagem.csv`: cada mensagem do PVA, com o texto oficial, em quantos arquivos e quantas vezes
  aparece. Mostra o erro que se repete na carteira toda (quase sempre uma regra de cadastro no ERP).
- `verificacoes.csv`: os achados acima, por arquivo, com quantidade e valor.
- `saldo-credor.csv`: meses em que o **saldo credor a transportar** (E110) não é igual ao **saldo credor
  anterior** do mês seguinte do mesmo CNPJ/IE. Um mês reenviado sem ajustar os seguintes quebra a cadeia, e a
  SEFAZ enxerga o crédito aparecendo do nada. A coluna `tipo` separa `quebra` (R$ 1,00 ou mais) de
  `arredondamento` (centavos, comum em ERP).

```bash
python3 exemplos/validar_lote.py 'carteira/**/*.txt' resultados --analisar
```

## Fontes da seleção

A lista foi montada a partir de comunicados de autorregularização das SEFAZ (RS, CE, SP, MG, PR), do Guia
Prático da EFD ICMS/IPI, de perguntas frequentes do SPED e de discussões recorrentes de contadores sobre malha
fiscal, retrabalho de retificadoras e divergência EFD × XML. Sugestões de novas verificações são bem-vindas
como *issue*.

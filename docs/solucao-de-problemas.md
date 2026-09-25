# Solução de problemas

## `/saude` não responde

O PVA ainda está ligando. No primeiro start ele extrai o MySQL embutido e baixa as tabelas externas: pode levar
até 2 minutos (mais no Mac ARM). Acompanhe:

```bash
docker compose logs -f pva
```

A linha `PVA 6.1.1 pronto em NNNNms` marca o fim do boot.

## `/saude` responde `503` com `"display":false`

A tela falsa (Xvfb) caiu. O `entrypoint.sh` encerra o contêiner quando isso acontece e a política
`restart: unless-stopped` o recria. Se estiver rodando com outra política:

```bash
docker compose restart pva
```

## Toda validação volta `AWTError` ou `NoClassDefFoundError ... GraphicsEnvironment`

Mesma causa (display morto), em versões antigas sem a checagem. Apague o lock e reinicie:

```bash
docker compose exec pva rm -f /tmp/.X99-lock /tmp/.X11-unix/X99
docker compose restart pva
```

## O build falha em `sha256sum -c`

A Receita trocou o arquivo publicado com o mesmo nome, ou o download veio incompleto. Rode o build de novo; se
continuar, veja [nova-versao-do-pva.md](nova-versao-do-pva.md).

## O build falha no `curl` do instalador

`curl: (35) Recv failure: Connection reset by peer` quase sempre é o site da Receita recusando uma conexão de
**fora do Brasil** (VPS no exterior, GitHub Actions, VPN). Baixe o instalador de uma máquina no Brasil e coloque
em [`instalador/`](../instalador/LEIA-ME.md); o build usa o arquivo local.

Se estiver no Brasil, o site pode estar fora do ar: o build já tenta 5 vezes, e vale tentar mais tarde. Em rede
corporativa com proxy, passe `--build-arg HTTPS_PROXY=...`.

## `mysqld` não sobe (erro de biblioteca ou "Fatal error: Please read Security section")

- Faltam bibliotecas i386: confira se a imagem foi construída com o `Dockerfile` deste repositório.
- Rodando como root: o MySQL 5.0 se recusa. A imagem usa o usuário `pva`; não force `--user root`.

## Validação demora muito ou dá timeout no cliente

- Mac Apple Silicon: é emulação. Ative o Rosetta (veja o README) ou use uma máquina x86_64.
- Arquivo grande (dezenas de milhares de linhas): pode levar minutos. Aumente o timeout do cliente.
- Pedidos simultâneos entram em fila. Um arquivo enorme segura todos os outros.

## `"estado": null` com erros de estrutura

O arquivo não chegou a ser integrado: contagem do bloco 9 errada (`9900`, `9990`, `9999`), registro fora de
ordem, `x990` errado, leiaute (`COD_VER`) que o PVA não conhece para o período. A lista vem em `erros`.

## Arquivo assinado abre "só para visualização"

O PVA não valida um arquivo já assinado. Remova tudo o que vem depois da linha `|9999|...|` (é o bloco da
assinatura digital) e mande de novo.

## `"falha": "erro ao conectar ao banco"`

No PVA isso quase sempre é erro de importação disfarçado, não problema de banco. Casos vistos:

- registro com quantidade de campos diferente do leiaute do período (ex.: `0220` sem o campo `COD_BARRA` no
  leiaute 016 em diante);
- arquivo com codificação diferente de ISO-8859-1 e caracteres acentuados quebrados.

Confira o leiaute do registro indicado no Guia Prático da EFD.

## O contêiner usa muita memória

O padrão é `mem_limit: 2g`. Arquivos muito grandes podem precisar de mais: aumente `mem_limit` e passe
`JAVA_OPTS=-Xmx...`.

# Atualizar para uma nova versão do PVA

A Receita publica versões novas do PVA EFD ICMS/IPI algumas vezes por ano (novo leiaute, novas regras). A
página oficial de download fica em `sped.rfb.gov.br`, na área da EFD ICMS/IPI.

## 1. Pegar o endereço e a impressão digital

O instalador Linux segue o padrão:

```
https://servicos.receita.fazenda.gov.br/publico/programas/Sped/SpedFiscal/SpedEFD_linux_x86_64-<VERSAO>.sh
```

Baixe e calcule o SHA-256:

```bash
curl -fLO https://servicos.receita.fazenda.gov.br/publico/programas/Sped/SpedFiscal/SpedEFD_linux_x86_64-6.1.2.sh
shasum -a 256 SpedEFD_linux_x86_64-6.1.2.sh
```

## 2. Construir com a versão nova

Sem editar nada, por argumento de build:

```bash
docker build \
  --build-arg PVA_VERSAO=6.1.2 \
  --build-arg PVA_SHA256=<sha256 calculado> \
  -t pva-efd-api:6.1.2 .
```

Se o nome do arquivo mudar de padrão, passe também `--build-arg PVA_URL=<endereço completo>`.

## 3. Testar

```bash
docker run -d --name pva-novo -p 127.0.0.1:8097:8095 pva-efd-api:6.1.2
URL=http://127.0.0.1:8097 ./scripts/teste-fumaca.sh
```

Se o `javac` falhar no build, alguma classe interna mudou de nome ou assinatura. Veja
[arquitetura.md](arquitetura.md) ("Como investigar a API interna") para encontrar o novo ponto de entrada.

## 4. Fixar no repositório

Atualize `PVA_VERSAO` e `PVA_SHA256` no `Dockerfile`, a `image:` no `docker-compose.yml`, e rode o teste de
fumaça de novo. Abra um *pull request* com o resultado.

# Instalador local (opcional)

Normalmente o build baixa o PVA sozinho do site da Receita. Use esta pasta quando isso não for possível:
máquina fora do Brasil (o site da Receita costuma recusar essas conexões), rede com proxy ou build sem internet.

1. Baixe, de uma máquina no Brasil, o instalador Linux da versão que está no `Dockerfile`:
   `https://servicos.receita.fazenda.gov.br/publico/programas/Sped/SpedFiscal/SpedEFD_linux_x86_64-6.1.1.sh`
2. Coloque o arquivo aqui, com o nome original.
3. Rode o build normalmente. O SHA-256 é conferido do mesmo jeito.

Arquivos `.sh` desta pasta são ignorados pelo git: o instalador do PVA não pode ser redistribuído.

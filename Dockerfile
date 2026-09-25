# check=skip=FromPlatformFlagConstDisallowed
# PVA EFD ICMS/IPI oficial (Receita Federal) como serviço HTTP de validação.
# O instalador é baixado do site da Receita durante o build e conferido por SHA-256;
# este repositório não contém nem redistribui nenhum arquivo do PVA.
# Só existe para linux x86_64: o MySQL 5.0 embutido é i386 e o JRE é amd64.

# 1) Baixa e instala o PVA em modo silencioso (install4j: -q).
FROM --platform=linux/amd64 debian:bookworm-slim AS instalador
ARG PVA_VERSAO=6.1.1
ARG PVA_SHA256=ace6c9bf1b344a65cd4dd5a8706647ef17c1d737354edbae98171690cd50db5c
ARG PVA_URL=https://servicos.receita.fazenda.gov.br/publico/programas/Sped/SpedFiscal/SpedEFD_linux_x86_64-${PVA_VERSAO}.sh
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends ca-certificates curl && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL -o /tmp/pva.sh "$PVA_URL" && \
    echo "${PVA_SHA256}  /tmp/pva.sh" | sha256sum -c - && \
    cd /tmp && sh /tmp/pva.sh -q -dir /opt/pva -overwrite && rm /tmp/pva.sh
# O diálogo "atualizar tabelas externas?" é modal e trava a validação sem operador;
# o servidor atualiza as tabelas por conta própria (PVA_ATUALIZAR_TABELAS_HORAS).
RUN sed -i -e 's/^nuncaVerificarAtualizacoes=.*/nuncaVerificarAtualizacoes=true/' \
           -e 's/^sempreVerificarAtualizacoes=.*/sempreVerificarAtualizacoes=false/' \
           -e 's/^confirmarVerificarAtualizacoes=.*/confirmarVerificarAtualizacoes=false/' \
           /opt/pva/configuracoes/spedfiscal.properties

# 2) Compila o servidor HTTP contra o fiscalpva.jar do PVA recém-instalado.
FROM --platform=linux/amd64 eclipse-temurin:21-jdk AS compilador
COPY --from=instalador /opt/pva /opt/pva
COPY src/PvaServer.java /src/
RUN javac -d /opt/pva-server -cp /opt/pva/fiscalpva.jar /src/PvaServer.java

# 3) Imagem final: PVA + servidor + bibliotecas i386 do MySQL embutido + Xvfb.
FROM --platform=linux/amd64 debian:bookworm-slim
RUN dpkg --add-architecture i386 && apt-get update -qq && \
    apt-get install -y -qq --no-install-recommends \
      libc6:i386 libstdc++6:i386 zlib1g:i386 libncurses5:i386 libcrypt1:i386 \
      libfreetype6 fontconfig xvfb xauth libxrender1 libxtst6 libxi6 && \
    rm -rf /var/lib/apt/lists/*
COPY --from=instalador /opt/pva /opt/pva
COPY --from=compilador /opt/pva-server /opt/pva-server
COPY docker/entrypoint.sh /usr/local/bin/entrypoint.sh
# O mysqld embutido resolve caminhos relativos contra o prefixo compilado /usr/local/mysql
# e se recusa a rodar como root.
RUN mkdir -p /usr/local/mysql/share && \
    ln -s /opt/pva/mysql/share /usr/local/mysql/share/mysql && \
    ln -s /opt/pva/mysql/data /usr/local/mysql/data && \
    useradd -r -u 10001 -d /opt/pva pva && chown -R pva:pva /opt/pva
USER pva
WORKDIR /opt/pva
# Parte do núcleo ainda instancia Swing: sem display dá HeadlessException.
ENV HOME=/opt/pva PVA_PORTA=8095 DISPLAY=:99
EXPOSE 8095
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8095 && printf "GET /saude HTTP/1.0\r\n\r\n" >&3 && grep -q "\"ok\":true" <&3'
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]

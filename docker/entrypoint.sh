#!/bin/bash
# Sobe a "tela falsa" (Xvfb) e o servidor Java lado a lado. Se qualquer um dos
# dois morrer, o contêiner termina e a política de restart do Docker o recria
# limpo — um PVA sem display responde /saude mas falha em toda validação.
set -u
NUM="${DISPLAY#:}"; NUM="${NUM%%.*}"

# Lock de um Xvfb anterior (contêiner reiniciado) impede o novo de subir.
rm -f "/tmp/.X${NUM}-lock" "/tmp/.X11-unix/X${NUM}"

# xvfb-run não serve: espera um SIGUSR1 que não chega sob emulação amd64 (Apple Silicon).
Xvfb "$DISPLAY" -screen 0 1280x1024x24 -nolisten tcp &
XVFB=$!
for _ in $(seq 50); do [ -S "/tmp/.X11-unix/X${NUM}" ] && break; sleep 0.2; done

cd /opt/pva
./jre/bin/java -Dfile.encoding=ISO-8859-1 -XX:+UseParallelGC ${JAVA_OPTS:-} \
  --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.base/java.text=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens=java.base/java.security=ALL-UNNAMED \
  -cp /opt/pva/fiscalpva.jar:/opt/pva-server PvaServer &
JAVA=$!

trap 'kill -TERM $JAVA $XVFB 2>/dev/null; wait' TERM INT
wait -n $XVFB $JAVA
STATUS=$?
echo "entrypoint: Xvfb ou Java terminou (status $STATUS); encerrando o contêiner" >&2
kill -TERM $JAVA $XVFB 2>/dev/null
wait
# Nunca sai com 0: qualquer parada aqui é inesperada e deve contar como falha.
exit $(( STATUS == 0 ? 1 : STATUS ))

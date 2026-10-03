#!/bin/bash
# armacraft guest dev container main process (run under `docker run --init`).
# Keeps the dedicated server running and restarts it whenever it exits, so a rebuild only
# has to swap the binary and kill the server process; the container, its published ports
# and the named volume stay up (the host's TCP reconnect then lands within ~0.5 s).
#
# Server stdin is a FIFO (/run/arma/console) held open read-write here, so
# `ctl.sh console LINE` can type console commands without ever sending EOF.
# Server output goes to the container's stdout (`docker logs`).

BIN=/work/prefix/bin/armagetronad-dedicated
RUN=/run/arma
FIFO=$RUN/console

mkdir -p "$RUN" /var/armagetronad/resource
chown -R nobody:nogroup /var/armagetronad
[ -p "$FIFO" ] || mkfifo -m 600 "$FIFO"
exec 3<>"$FIFO"

pid=
stop() {
    echo "[supervise] container stopping"
    [ -n "$pid" ] && kill -TERM "$pid" 2>/dev/null && wait "$pid"
    exit 0
}
trap stop TERM INT

waited=0
while true; do
    if [ -e "$RUN/hold" ] || [ ! -x "$BIN" ]; then
        if [ $waited = 0 ]; then
            echo "[supervise] waiting for $BIN (run: dev/guest.ps1 rebuild)"; waited=1
        fi
        sleep 0.2 & wait $!
        continue
    fi
    waited=0
    echo "[supervise] starting server $(date +%T.%3N) build $(cat /work/prefix/.build-id 2>/dev/null)"
    setpriv --reuid=nobody --regid=nogroup --clear-groups -- \
        "$BIN" \
        --vardir /var/armagetronad \
        --userconfigdir /etc/armacraft \
        --autoresourcedir /var/armagetronad/resource \
        <&3 &
    pid=$!
    echo "$pid" > "$RUN/server.pid"
    wait "$pid"
    rc=$?
    rm -f "$RUN/server.pid"
    pid=
    echo "[supervise] server exited rc=$rc $(date +%T.%3N)"
    sleep 0.1 & wait $!
done

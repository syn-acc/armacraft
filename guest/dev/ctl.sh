#!/bin/bash
# armacraft guest dev control. Runs inside armacraft-guest-dev (via dev/guest.ps1).
#
#   ctl.sh restart            kill the server; supervise.sh starts the current binary again
#   ctl.sh console LINE...    type one console line into the running server (stdin FIFO)
#   ctl.sh cfg [FILE...]      re-exec cfg files from /etc/armacraft (default settings_custom.cfg)
#   ctl.sh hold | release     stop the server and keep it stopped / let supervise.sh start it again
#   ctl.sh status             pid, uptime, build id
set -euo pipefail
RUN=/run/arma

server_pid() { cat "$RUN/server.pid" 2>/dev/null || true; }

kill_server() {
    local pid
    pid=$(server_pid)
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null || return 0
    kill -TERM "$pid" 2>/dev/null || true
    for _ in $(seq 20); do
        kill -0 "$pid" 2>/dev/null || return 0
        sleep 0.05
    done
    kill -KILL "$pid" 2>/dev/null || true
}

cmd=${1:-status}
shift || true
case "$cmd" in
    restart)
        kill_server ;;
    console)
        [ $# -gt 0 ] || { echo "usage: ctl.sh console LINE" >&2; exit 2; }
        printf '%s\n' "$*" > "$RUN/console" ;;
    cfg)
        [ $# -gt 0 ] || set -- settings_custom.cfg
        for f in "$@"; do
            f=$(basename "$f")
            [ -f "/etc/armacraft/$f" ] || { echo "no such cfg: /etc/armacraft/$f" >&2; exit 1; }
            # INCLUDE resolves against --userconfigdir (/etc/armacraft, bind-mounted guest/server)
            printf 'INCLUDE %s\n' "$f" > "$RUN/console"
            echo "re-exec'd $f"
        done ;;
    hold)
        touch "$RUN/hold"; kill_server ;;
    release)
        rm -f "$RUN/hold" ;;
    status)
        pid=$(server_pid)
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            echo "server pid $pid up $(ps -o etimes= -p "$pid" | tr -d ' ')s build $(cat /work/prefix/.build-id 2>/dev/null)"
        else
            echo "server not running$([ -e "$RUN/hold" ] && echo ' (hold)')"
        fi ;;
    *)
        echo "ctl.sh: unknown command $cmd" >&2; exit 2 ;;
esac

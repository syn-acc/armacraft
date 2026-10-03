#!/bin/sh
# armacraft guest entrypoint: run the patched dedicated server with our config dir.
set -e
mkdir -p /var/armagetronad/resource
exec /usr/local/bin/armagetronad-dedicated \
    --vardir /var/armagetronad \
    --userconfigdir /etc/armacraft \
    --autoresourcedir /var/armagetronad/resource \
    "$@"

# armacraft guest: Armagetron Advanced dedicated server + JSON bridge (TCP 4535)
#
# Upstream is cloned *inside* the build at a pinned tag + commit so the build is
# reproducible. Our changes: guest/patches/*.patch (minimal hooks into upstream files)
# plus new files from guest/src/ copied into src/tron/.
#
#   docker build -t armacraft-guest:dev guest
#   (or guest/run.ps1, which also runs it)

ARG BASE=ubuntu:24.04

########################################
FROM ${BASE} AS deps
RUN apt-get -y update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      ca-certificates git autoconf automake patch bash bison bzip2 g++ make libtool \
      libxml2-dev libzthread-dev pkg-config python3 \
 && rm -rf /var/lib/apt/lists/*

########################################
FROM deps AS src
# Fallback if the tag ever stops building: ARMA_REF=release_0.2.9 and ARMA_COMMIT=<sha you tested>
ARG ARMA_REPO=https://gitlab.com/armagetronad/armagetronad.git
ARG ARMA_REF=v0.2.9.3.0
ARG ARMA_COMMIT=036daaf34f055b1efbcee6a4afc4d90a325ce569
RUN git clone --depth 1 --branch "${ARMA_REF}" "${ARMA_REPO}" /work/arma \
 && cd /work/arma \
 && test "$(git rev-parse HEAD)" = "${ARMA_COMMIT}" \
 || { echo "upstream ${ARMA_REF} is not at pinned commit ${ARMA_COMMIT}" >&2; exit 1; }

########################################
FROM src AS build
ARG ARMA_REF=v0.2.9.3.0
# batch/make/version reports CI_COMMIT_TAG on a protected ref -> version "0.2.9.3.0"
ENV CI_COMMIT_REF_PROTECTED=true CI_COMMIT_TAG=${ARMA_REF}
COPY patches/ /work/patches/
COPY src/ /work/bridge-src/
WORKDIR /work/arma
RUN set -e; \
    for p in /work/patches/*.patch; do \
      [ -e "$p" ] || continue; echo "applying $p"; git apply --whitespace=nowarn "$p"; \
    done; \
    cp -v /work/bridge-src/*.cpp /work/bridge-src/*.h src/tron/
RUN ./bootstrap.sh
RUN mkdir -p /work/build && cd /work/build \
 && /work/arma/configure --prefix=/usr/local --disable-glout --disable-sysinstall --disable-useradd \
      --disable-master --disable-uninstall --disable-desktop \
 && make -j"$(nproc)" \
 && DESTDIR=/work/destdir make install

########################################
FROM ${BASE} AS runtime
RUN apt-get -y update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      libxml2 libzthread-2.3-2t64 \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /work/destdir/ /
# settings_custom.cfg + bridge.cfg (run.ps1 also bind-mounts guest/server over this)
COPY server/ /etc/armacraft/
COPY entrypoint.sh /usr/local/bin/armacraft-entrypoint.sh
RUN chmod 755 /usr/local/bin/armacraft-entrypoint.sh \
 && mkdir -p /var/armagetronad/resource && chown -R nobody:nogroup /var/armagetronad
USER nobody
# 4534/udp: Armagetron clients. 4535/tcp: bridge (publish on 127.0.0.1 only; v1 has no auth)
EXPOSE 4534/udp 4535/tcp
ENTRYPOINT ["/usr/local/bin/armacraft-entrypoint.sh"]

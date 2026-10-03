#!/bin/bash
# armacraft guest dev build. Runs inside armacraft-guest-dev (dev/guest.ps1 rebuild).
#
#   build.sh             sync guest/src (+ re-apply guest/patches if they changed), incremental
#                        make of the server binary, swap it into /work/prefix
#   build.sh --full      also re-run bootstrap.sh + configure (configure.ac / Makefile.am edits)
#   build.sh --restart   restart the server process after a build that changed the binary
#   build.sh --force-restart   restart even if the binary is byte-identical
#   build.sh --quick     compile changed files at -O0 (~1.7 s instead of ~3.8 s for gBridge.cpp).
#                        Switching back to a normal build recompiles those files at -O2 (ccache).
#
# Volume layout (/work, named volume):
#   arma/    upstream tree (copied from /opt/arma-pristine) + patches + guest/src files
#   build/   out-of-tree build dir (configure output, objects)
#   prefix/  install prefix; supervise.sh runs prefix/bin/armagetronad-dedicated
#   ccache/  compiler cache (helps re-patch / --full; a single edited .cpp is always a miss)
#
# Env at configure time: DEV_CXXFLAGS (default upstream's), DEV_LINKER (default mold; "bfd" = GNU ld)
set -euo pipefail

W=/work; A=$W/arma; B=$W/build; P=$W/prefix
BIN_NAME=armagetronad-dedicated   # installed name
MAKE_TARGET=armagetronad_main      # name in build/src (renamed by make install)
full=0; restart=0; force=0; mode=O2
for a in "$@"; do
    case "$a" in
        --full) full=1 ;;
        --quick) mode=O0 ;;
        --restart) restart=1 ;;
        --force-restart) restart=1; force=1 ;;
        *) echo "build.sh: unknown arg $a" >&2; exit 2 ;;
    esac
done

ms() { local t=${EPOCHREALTIME/./}; echo $(( t / 1000 )); }
T0=$(ms)
step() { printf '[build] %-34s +%5d ms\n' "$1" $(( $(ms) - T0 )); }

# ---------------------------------------------------------------- upstream tree + patches
if [ ! -d "$A" ]; then
    cp -a /opt/arma-pristine "$A"
    step "copied pristine upstream"
fi
want=$(cat /guest/patches/*.patch 2>/dev/null | sha256sum | cut -c1-16)
have=$(cat "$W/.patches" 2>/dev/null || true)
if [ "$want" != "$have" ]; then
    cd "$A"
    # restore files touched by the previously applied patch set; checkout and apply both set
    # mtime=now, so make rebuilds everything that depends on a file whose content changed
    git diff --name-only | xargs -r git checkout --
    for p in /guest/patches/*.patch; do
        [ -e "$p" ] || continue
        git apply --whitespace=nowarn "$p"
        echo "[build] applied $(basename "$p")"
    done
    echo "$want" > "$W/.patches"
    step "patches applied ($want)"
fi

# ---------------------------------------------------------------- guest/src -> src/tron
for f in /guest/src/*.cpp /guest/src/*.h; do
    [ -e "$f" ] || continue
    d="$A/src/tron/$(basename "$f")"
    if ! cmp -s "$f" "$d"; then
        cp "$f" "$d"
        echo "[build] synced $(basename "$f")"
    fi
done

# ---------------------------------------------------------------- bootstrap + configure
fresh=0
if [ $full = 1 ] || [ ! -x "$A/configure" ]; then
    (cd "$A" && ./bootstrap.sh >/dev/null)
    step "bootstrap.sh"
fi
if [ $full = 1 ] || [ ! -f "$B/Makefile" ]; then
    mkdir -p "$B"
    cd "$B"
    linker=${DEV_LINKER:-mold}
    ldflags=""
    [ "$linker" != "bfd" ] && ldflags="-fuse-ld=$linker"
    cfg_env=(CC="ccache gcc" CXX="ccache g++" LDFLAGS="$ldflags")
    [ -n "${DEV_CXXFLAGS:-}" ] && cfg_env+=(CXXFLAGS="$DEV_CXXFLAGS" CFLAGS="$DEV_CXXFLAGS")
    "$A/configure" --prefix="$P" --disable-glout --disable-sysinstall --disable-useradd \
        --disable-master --disable-uninstall --disable-desktop "${cfg_env[@]}" >/dev/null
    fresh=1
    step "configure (linker=$linker)"
fi

# ---------------------------------------------------------------- make
J=$(nproc)
if [ $fresh = 1 ] || [ ! -x "$P/bin/$BIN_NAME" ]; then
    make -C "$B" -j"$J" -s >/dev/null
    step "make (all)"
    make -C "$B" -s install >/dev/null
    step "make install"
    echo O2 > "$W/.mode"
    changed=1
else
    # objects don't record their flags: on an -O0 <-> -O2 switch, touch every file that differs
    # from pristine upstream (the only ones an incremental build recompiles) so they get rebuilt
    last=$(cat "$W/.mode" 2>/dev/null || echo O2)
    if [ "$mode" != "$last" ]; then
        (cd "$A" && { git diff --name-only; git ls-files --others --exclude-standard src/tron; } | grep -E '\.(c|cc|cpp|h)$' | xargs -r touch)
        echo "[build] optimisation $last -> $mode: recompiling patched + bridge files"
    fi
    echo "$mode" > "$W/.mode"
    extra=()
    if [ "$mode" = O0 ]; then
        base=$(sed -n 's/^CXXFLAGS = //p' "$B/src/Makefile")
        extra=(CXXFLAGS="${base//-O2/-O0}")
    fi
    make -C "$B/src" -j"$J" -s "$MAKE_TARGET" "${extra[@]}"
    step "make $MAKE_TARGET (-$mode)"
    changed=0
    cmp -s "$B/src/$MAKE_TARGET" "$P/bin/$BIN_NAME" || changed=1
    if [ $changed = 1 ]; then
        # copy + rename: the running server keeps its (unlinked) inode, no "text file busy"
        cp "$B/src/$MAKE_TARGET" "$P/bin/.$BIN_NAME.new"
        mv -f "$P/bin/.$BIN_NAME.new" "$P/bin/$BIN_NAME"
        step "installed binary"
    fi
fi

if [ $changed = 1 ]; then
    echo "$(date +%H%M%S)-$(sha256sum "$P/bin/$BIN_NAME" | cut -c1-8)" > "$P/.build-id"
fi
echo "[build] build-id $(cat "$P/.build-id" 2>/dev/null) ($([ $changed = 1 ] && echo new binary || echo binary unchanged))"

if [ $restart = 1 ]; then
    if [ $changed = 1 ] || [ $force = 1 ]; then
        /bin/bash /guest/dev/ctl.sh restart
        step "server restarted"
    else
        echo "[build] binary unchanged, server left running (use --force-restart)"
    fi
fi

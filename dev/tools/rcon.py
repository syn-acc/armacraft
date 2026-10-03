#!/usr/bin/env python3
"""Minimal Minecraft RCON client for the armacraft dev server. Stdlib only.

The port and password are read from server.properties (--props), so the password never
appears on a command line or in logs.

  python dev/tools/rcon.py --props dev/mcserver/server.properties "arma status"
  python dev/tools/rcon.py --props ... --wait 30 "list"     # retry until RCON is up
Exit code 0 = command delivered, 2 = could not connect / auth failed.
"""

import argparse
import socket
import struct
import sys
import time


def read_props(path):
    props = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    return props


def _pkt(req_id, ptype, body):
    data = struct.pack("<ii", req_id, ptype) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(data)) + data


def _recv_exact(s, n):
    buf = b""
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("rcon connection closed")
        buf += chunk
    return buf


def _recv_pkt(s):
    (length,) = struct.unpack("<i", _recv_exact(s, 4))
    data = _recv_exact(s, length)
    req_id, ptype = struct.unpack("<ii", data[:8])
    return req_id, ptype, data[8:-2].decode("utf-8", "replace")


def rcon(host, port, password, command, timeout=5.0):
    with socket.create_connection((host, port), timeout=timeout) as s:
        s.sendall(_pkt(1, 3, password))
        req_id, _, _ = _recv_pkt(s)
        if req_id == -1:
            raise PermissionError("rcon auth failed")
        s.sendall(_pkt(2, 2, command))
        _, _, body = _recv_pkt(s)
        return body


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--props", required=True, help="server.properties with enable-rcon/rcon.port/rcon.password")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--wait", type=float, default=0.0, help="seconds to retry connecting")
    ap.add_argument("command", nargs="+")
    a = ap.parse_args()

    p = read_props(a.props)
    port = int(p.get("rcon.port", "25575"))
    password = p.get("rcon.password", "")
    if p.get("enable-rcon", "false") != "true" or not password:
        print("rcon not enabled in " + a.props, file=sys.stderr)
        return 2

    deadline = time.monotonic() + a.wait
    while True:
        try:
            out = rcon(a.host, port, password, " ".join(a.command))
            # strip MC formatting codes
            clean = []
            skip = False
            for ch in out:
                if skip:
                    skip = False
                    continue
                if ord(ch) == 0xA7:  # section sign = MC formatting code
                    skip = True
                    continue
                clean.append(ch)
            print("".join(clean))
            return 0
        except PermissionError as e:
            print(str(e), file=sys.stderr)
            return 2
        except (OSError, ConnectionError) as e:
            if time.monotonic() >= deadline:
                print(f"rcon {a.host}:{port} unavailable: {type(e).__name__}: {e}", file=sys.stderr)
                return 2
            time.sleep(0.1)


if __name__ == "__main__":
    sys.exit(main())

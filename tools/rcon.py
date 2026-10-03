"""Minimal Minecraft RCON client for the local dev server.

Usage: python tools/rcon.py [--props host/run/server.properties] <command...>
Reads rcon.port / rcon.password from server.properties; never prints the password.
"""
import argparse
import socket
import struct
from pathlib import Path


def read_props(path):
    props = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    return props


def packet(req_id, kind, body):
    data = struct.pack("<ii", req_id, kind) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(data)) + data


def recv_packet(sock):
    def read(n):
        buf = b""
        while len(buf) < n:
            chunk = sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("rcon connection closed")
            buf += chunk
        return buf

    (length,) = struct.unpack("<i", read(4))
    data = read(length)
    req_id, kind = struct.unpack("<ii", data[:8])
    return req_id, kind, data[8:-2].decode("utf-8", "replace")


def main():
    here = Path(__file__).resolve().parent.parent
    ap = argparse.ArgumentParser()
    ap.add_argument("--props", default=str(here / "host" / "run" / "server.properties"))
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("command", nargs="+")
    args = ap.parse_args()

    props = read_props(args.props)
    with socket.create_connection((args.host, int(props.get("rcon.port", 25575))), timeout=10) as s:
        s.sendall(packet(1, 3, props["rcon.password"]))
        req_id, _, _ = recv_packet(s)
        if req_id == -1:
            raise SystemExit("rcon auth failed")
        s.sendall(packet(2, 2, " ".join(args.command)))
        _, _, body = recv_packet(s)
        print(body)


if __name__ == "__main__":
    main()

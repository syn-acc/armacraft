#!/usr/bin/env python3
"""armacraft bridge probe: connect to a guest bridge port, wait for `hello`, optionally
count `state` snapshots for a while, print one JSON line and exit.

Used by dev/*.ps1 for readiness checks and timing. Stdlib only.

  python dev/tools/bridge_probe.py --port 4545                    # wait up to 60 s for hello
  python dev/tools/bridge_probe.py --port 4545 --rate 2           # + count state msgs for 2 s
  python dev/tools/bridge_probe.py --port 4545 --wait 0 --rate 2  # single attempt (status)
  python dev/tools/bridge_probe.py --port 4545 --cmd "CYCLE_SPEED 25"   # console line via bridge

Note: while connected the probe counts as a bridge host (guest stops napping). It never joins.
Output: {"ok":true,"connect_s":..,"hello":{..},"state_hz":..,"cycles":..,"round":..}
"""

import argparse
import json
import socket
import sys
import time


def attempt(host, port, timeout):
    s = socket.create_connection((host, port), timeout=timeout)
    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    return s


def read_lines(sock, buf):
    data = sock.recv(65536)
    if not data:
        raise ConnectionError("guest closed connection")
    buf += data
    lines = buf.split(b"\n")
    return lines[:-1], lines[-1]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=4545)
    ap.add_argument("--wait", type=float, default=60.0, help="seconds to keep retrying for hello (0 = one try)")
    ap.add_argument("--rate", type=float, default=0.0, help="seconds to count state snapshots after hello")
    ap.add_argument("--cmd", action="append", default=[], help="console line to send as {type:cmd} (repeatable)")
    ap.add_argument("--retry", type=float, default=0.1, help="seconds between connect attempts")
    a = ap.parse_args()

    t0 = time.monotonic()
    deadline = t0 + a.wait
    hello = None
    sock = None
    buf = b""
    last_err = None
    while True:
        try:
            sock = attempt(a.host, a.port, 2.0)
            sock.settimeout(2.0)
            # docker's port proxy accepts while nothing listens inside, then closes: treat as retry
            while hello is None:
                lines, buf = read_lines(sock, buf)
                for ln in lines:
                    try:
                        m = json.loads(ln)
                    except ValueError:
                        continue
                    if m.get("type") == "hello":
                        hello = m
                        break
            break
        except (OSError, ConnectionError) as e:
            last_err = f"{type(e).__name__}: {e}"
            if sock:
                sock.close()
                sock = None
            buf = b""
            if time.monotonic() >= deadline:
                print(json.dumps({"ok": False, "error": last_err, "waited_s": round(time.monotonic() - t0, 3)}))
                return 1
            time.sleep(a.retry)

    out = {"ok": True, "connect_s": round(time.monotonic() - t0, 3), "hello": hello}

    for line in a.cmd:
        sock.sendall((json.dumps({"type": "cmd", "line": line}) + "\n").encode())
    if a.cmd:
        out["sent_cmds"] = len(a.cmd)

    if a.rate > 0:
        states = 0
        first = last = None
        cycles = None
        rnd = None
        errors = []
        end = time.monotonic() + a.rate
        sock.settimeout(0.25)
        while time.monotonic() < end:
            try:
                lines, buf = read_lines(sock, buf)
            except socket.timeout:
                continue
            except (OSError, ConnectionError) as e:
                out["error"] = f"{type(e).__name__}: {e}"
                break
            now = time.monotonic()
            for ln in lines:
                try:
                    m = json.loads(ln)
                except ValueError:
                    continue
                t = m.get("type")
                if t == "state":
                    states += 1
                    first = first or now
                    last = now
                    cycles = len(m.get("cycles") or [])
                elif t == "round":
                    rnd = m.get("round")
                elif t == "error":
                    errors.append(m.get("msg"))
        out["state_msgs"] = states
        # rate between first and last snapshot: the guest may still be waking from its idle
        # nap (up to 1 s) when we connect, which would otherwise drag the average down
        out["state_hz"] = round((states - 1) / (last - first), 1) if states > 1 and last > first else 0.0
        out["cycles"] = cycles
        out["round"] = rnd
        if errors:
            out["errors"] = errors[:5]
    elif a.cmd:
        # give the guest a frame to process the commands and report errors
        sock.settimeout(0.3)
        try:
            lines, buf = read_lines(sock, buf)
            errs = [json.loads(l).get("msg") for l in lines if b'"error"' in l]
            if errs:
                out["errors"] = errs
        except (OSError, ConnectionError, ValueError):
            pass

    sock.close()
    print(json.dumps(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())

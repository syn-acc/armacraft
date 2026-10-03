#!/usr/bin/env python3
"""armacraft fake host: a stand-in for the Minecraft mod, for testing the guest bridge.

Connects to the guest's bridge port (PROTOCOL.md v1), prints hello/round/events/replies
and a one-line state summary once per second.

Examples:
  python tools/fake_host.py
  python tools/fake_host.py --join Syn            # join, then random turns every ~0.7 s
  python tools/fake_host.py --explode 0 0 20
  python tools/fake_host.py --cmd "CYCLE_SPEED 25"
  python tools/fake_host.py --join Syn --duration 60 --leave-after 40

Stdlib only.
"""

import argparse
import json
import os
import random
import select
import socket
import sys
import time
import uuid


def fmt(v, nd=1):
    try:
        return f"{float(v):.{nd}f}"
    except (TypeError, ValueError):
        return "?"


def heading(dx, dy):
    try:
        dx, dy = float(dx), float(dy)
    except (TypeError, ValueError):
        return "?"
    if abs(dx) >= abs(dy):
        return "E" if dx > 0 else "W"
    return "N" if dy > 0 else "S"


class FakeHost:
    def __init__(self, args):
        self.args = args
        self.sock = None
        self.buf = b""
        self.key = f"mc:{uuid.uuid4().hex[:12]}" if args.join else None
        self.joined = False
        self.last_state = None
        self.last_summary = 0.0
        self.next_turn = 0.0
        self.counts = {}
        self.start = time.monotonic()

    # ------------------------------------------------------------------ io
    def connect(self):
        deadline = time.monotonic() + self.args.connect_timeout
        while True:
            try:
                self.sock = socket.create_connection((self.args.host, self.args.port), timeout=3)
                self.sock.setblocking(False)
                self.log(f"connected to {self.args.host}:{self.args.port}")
                return
            except OSError as e:
                if time.monotonic() > deadline:
                    raise SystemExit(f"cannot connect to {self.args.host}:{self.args.port}: {e}")
                time.sleep(0.5)

    def send(self, obj):
        data = (json.dumps(obj, separators=(",", ":")) + "\n").encode("utf-8")
        self.sock.setblocking(True)
        try:
            self.sock.sendall(data)
        finally:
            self.sock.setblocking(False)
        if obj.get("type") != "input" or self.args.verbose:
            self.log(f">> {json.dumps(obj)}")

    def log(self, msg):
        t = time.monotonic() - self.start
        try:
            print(f"[{t:7.2f}] {msg}", flush=True)
        except OSError as e:
            # reader went away (e.g. `| head`); Windows reports that as EINVAL
            if isinstance(e, BrokenPipeError) or e.errno == 22:
                os.dup2(os.open(os.devnull, os.O_WRONLY), sys.stdout.fileno())
                raise SystemExit(0)
            raise

    # ------------------------------------------------------------ handling
    def handle(self, msg):
        typ = msg.get("type")
        self.counts[typ] = self.counts.get(typ, 0) + 1
        if typ == "state":
            self.last_state = msg
            return
        if typ == "hello":
            self.log(f"<< hello {json.dumps(msg)}")
            self.on_hello()
        elif typ == "round":
            static = msg.get("static", [])
            self.log(f"<< round #{msg.get('round')} t={fmt(msg.get('t'))} static={len(static)} segs "
                     f"bounds={msg.get('bounds')} first={static[:2]}")
        elif typ == "event":
            ev = msg.get("ev")
            if ev == "turn" and not self.args.verbose and not self.is_mine(msg.get("id")):
                return
            self.log(f"<< event {json.dumps(msg)}")
        elif typ == "joined":
            self.joined = True
            self.log(f"<< joined {json.dumps(msg)}")
        else:
            self.log(f"<< {typ} {json.dumps(msg)}")

    def is_mine(self, cid):
        if not self.last_state or not self.key:
            return False
        for c in self.last_state.get("cycles", []):
            if c.get("id") == cid and c.get("bridge_key") == self.key:
                return True
        return False

    def on_hello(self):
        a = self.args
        if a.cmd:
            self.send({"type": "cmd", "line": a.cmd})
        if a.join:
            color = [random.random() * 0.5 + 0.5, random.random() * 0.5, 1.0]
            self.send({"type": "join", "key": self.key, "name": a.join, "color": color})
        if a.chat:
            self.send({"type": "chat", "name": a.join or "fake_host", "text": a.chat})
        if a.explode:
            self.pending_explode = time.monotonic() + a.explode_delay
        else:
            self.pending_explode = None

    def summary(self):
        s = self.last_state
        if not s:
            self.log(f"(no state yet) msgs={self.counts}")
            return
        cycles = s.get("cycles", [])
        walls = s.get("walls", [])
        gaps = sum(len(w.get("gaps", [])) for w in walls)
        parts = []
        for c in cycles:
            mine = "*" if c.get("bridge_key") else ""
            parts.append(
                f"{c.get('id')}{mine}:{c.get('name')}@({fmt(c.get('x'))},{fmt(c.get('y'))})"
                f"{heading(c.get('dx'), c.get('dy'))}{'' if c.get('alive') else ' DEAD'}"
            )
        self.log(f"state t={fmt(s.get('t'), 2)} cycles={len(cycles)} walls={len(walls)} gaps={gaps} | "
                 + " | ".join(parts))
        if self.args.raw:
            raw = json.dumps(s, separators=(",", ":"))
            self.log(f"raw state ({len(raw)} bytes): {raw[:self.args.raw]}")
        if self.args.show_gaps:
            for w in walls:
                if w.get("gaps"):
                    self.log(f"  wall {w.get('id')} of cycle {w.get('cycle')}: "
                             f"({fmt(w.get('x1'))},{fmt(w.get('y1'))})->({fmt(w.get('x2'))},{fmt(w.get('y2'))}) "
                             f"gaps={w.get('gaps')}")

    # --------------------------------------------------------------- loop
    def run(self):
        self.connect()
        self.pending_explode = None
        a = self.args
        left_sent = False
        while True:
            now = time.monotonic()
            if a.duration and now - self.start > a.duration:
                self.log(f"duration reached; message counts: {self.counts}")
                return
            if a.leave_after and self.joined and not left_sent and now - self.start > a.leave_after:
                self.send({"type": "leave", "key": self.key})
                left_sent = True
            if a.disconnect_after and now - self.start > a.disconnect_after:
                self.log(f"disconnecting; message counts: {self.counts}")
                self.sock.close()
                return

            r, _, _ = select.select([self.sock], [], [], 0.05)
            if r:
                try:
                    data = self.sock.recv(65536)
                except BlockingIOError:
                    data = None
                except OSError as e:
                    self.log(f"connection error: {e}")
                    return
                if data == b"":
                    self.log("connection closed by guest")
                    return
                if data:
                    self.buf += data
                    while b"\n" in self.buf:
                        line, self.buf = self.buf.split(b"\n", 1)
                        if not line.strip():
                            continue
                        try:
                            msg = json.loads(line.decode("utf-8"))
                        except (UnicodeDecodeError, json.JSONDecodeError) as e:
                            self.log(f"!! bad line ({e}): {line[:200]!r}")
                            continue
                        self.handle(msg)

            now = time.monotonic()
            if self.pending_explode and now >= self.pending_explode:
                x, y, rad = a.explode
                self.send({"type": "explode", "x": x, "y": y, "r": rad})
                self.pending_explode = None

            if a.join and self.joined and not left_sent and not a.no_turns and now >= self.next_turn:
                self.send({"type": "input", "key": self.key, "turn": random.choice(["left", "right"])})
                self.next_turn = now + random.uniform(0.5, 0.9)

            if now - self.last_summary >= 1.0:
                self.last_summary = now
                self.summary()


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=4535)
    p.add_argument("--join", metavar="NAME", help="join as a bridge player, then turn randomly every ~0.7 s")
    p.add_argument("--no-turns", action="store_true", help="with --join: don't send random turns")
    p.add_argument("--explode", nargs=3, type=float, metavar=("X", "Y", "R"))
    p.add_argument("--explode-delay", type=float, default=3.0, help="seconds after hello before exploding")
    p.add_argument("--cmd", metavar="LINE", help="run a console command on the guest")
    p.add_argument("--chat", metavar="TEXT", help="send a chat line after hello")
    p.add_argument("--duration", type=float, default=0, help="exit after this many seconds")
    p.add_argument("--leave-after", type=float, default=0, help="send leave after this many seconds")
    p.add_argument("--disconnect-after", type=float, default=0, help="drop the connection after N seconds")
    p.add_argument("--connect-timeout", type=float, default=10)
    p.add_argument("-v", "--verbose", action="store_true", help="also print every turn event and input")
    p.add_argument("--raw", type=int, default=0, metavar="N",
                   help="with each summary, print the first N chars of the raw state JSON")
    p.add_argument("--show-gaps", action="store_true", help="with each summary, list walls that have gaps")
    args = p.parse_args()
    # names/chat can contain any Unicode; don't die on a cp1252 console
    try:
        sys.stdout.reconfigure(errors="replace")
    except (AttributeError, ValueError):
        pass
    try:
        FakeHost(args).run()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()

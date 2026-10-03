#!/usr/bin/env python3
"""Fake Armagetron guest for armacraft bridge protocol v1 (see ../PROTOCOL.md).

Stdlib only. Listens on 127.0.0.1:4535 (newline-delimited JSON over TCP), the way the patched
Armagetron dedicated server will, so the Minecraft host mod can be exercised without Armagetron:

  * sends `hello`, then `round` (square rim, default 200x200, centred on 0,0)
  * runs 3 AI cycles at speed 20 with random 90-degree turns and growing walls
  * `join` spawns a bridge cycle (bridge_key = the join key) steered by `input` turn/brake
  * collisions kill cycles (death event), walls of the dead fade after 2 s, respawn after 3 s
  * `explode` punches gaps into walls and kills cycles inside the radius
  * logs every received message

Usage:
  python tools/fake_guest.py                 # serve on 127.0.0.1:4535
  python tools/fake_guest.py --port 4600 --rate 30 --size 200 --ai 3 --seed 1
  python tools/fake_guest.py --self-test     # in-process protocol self-test, exit code 0/1
"""

import argparse
import json
import math
import random
import socket
import sys
import threading
import time

# Armagetron directions: index 0..3 = north, east, south, west. Left turn = counter-clockwise.
DIRS = [(0.0, 1.0), (1.0, 0.0), (0.0, -1.0), (-1.0, 0.0)]
AI_COLORS = [(1.0, 0.2, 0.1), (0.2, 1.0, 0.3), (1.0, 0.9, 0.1), (0.8, 0.2, 1.0), (1.0, 0.5, 0.0)]
EPS = 1e-6


def log(*args):
    print("[guest %s]" % time.strftime("%H:%M:%S"), *args, flush=True)


class Wall:
    def __init__(self, wid, cycle, x, y):
        self.id = wid
        self.cycle = cycle
        self.x1 = self.x2 = x
        self.y1 = self.y2 = y
        self.gaps = []
        self.expire_at = None

    def length(self):
        return math.hypot(self.x2 - self.x1, self.y2 - self.y1)

    def to_json(self):
        o = {"id": self.id, "cycle": self.cycle, "x1": r3(self.x1), "y1": r3(self.y1), "x2": r3(self.x2), "y2": r3(self.y2)}
        if self.gaps:
            o["gaps"] = [[r3(a), r3(b)] for a, b in self.gaps]
        return o


class Cycle:
    def __init__(self, cid, name, color, speed, bridge_key=None):
        self.id = cid
        self.name = name
        self.color = color
        self.base_speed = speed
        self.speed = speed
        self.bridge_key = bridge_key
        self.x = self.y = 0.0
        self.d = 0
        self.alive = False
        self.brake = False
        self.wall = None
        self.next_turn = 0.0
        self.respawn_at = None

    def to_json(self):
        dx, dy = DIRS[self.d]
        o = {"id": self.id, "name": self.name, "color": [r3(c) for c in self.color],
             "x": r3(self.x), "y": r3(self.y), "dx": dx, "dy": dy, "speed": r3(self.speed),
             "alive": self.alive, "brake": self.brake, "rubber": 0.0}
        if self.bridge_key:
            o["bridge_key"] = self.bridge_key
        return o


def r3(v):
    return round(float(v), 3)


def seg_intersect(p, p2, a, b):
    """Parameter t in (EPS, 1] along p->p2 where it crosses segment a-b, else None."""
    rx, ry = p2[0] - p[0], p2[1] - p[1]
    sx, sy = b[0] - a[0], b[1] - a[1]
    den = rx * sy - ry * sx
    if abs(den) < 1e-12:
        return None
    qpx, qpy = a[0] - p[0], a[1] - p[1]
    t = (qpx * sy - qpy * sx) / den
    u = (qpx * ry - qpy * rx) / den
    if EPS < t <= 1.0 and -EPS <= u <= 1.0 + EPS:
        return t
    return None


class Sim:
    def __init__(self, size=200.0, speed=20.0, ai=3, seed=None, collisions=True, round_seconds=0.0):
        self.rng = random.Random(seed)
        self.round_seconds = round_seconds
        self.round_started = 0.0
        self.pending_round = False
        self.half = size / 2.0
        self.speed = speed
        self.collisions = collisions
        self.lock = threading.RLock()
        self.cycles = {}
        self.walls = {}
        self.next_id = 100
        self.next_wall = 1000
        self.t = 0.0
        self.round_no = 1
        self.out_events = []  # pending events to broadcast
        for i in range(ai):
            c = Cycle(self._nid(), "AI-%d" % (i + 1), AI_COLORS[i % len(AI_COLORS)], speed)
            self.cycles[c.id] = c
            self.spawn(c, announce=False)

    def _nid(self):
        self.next_id += 1
        return self.next_id

    def _nwall(self):
        self.next_wall += 1
        return self.next_wall

    def bounds(self):
        h = self.half
        return [-h, -h, h, h]

    def round_msg(self):
        h = self.half
        rim = [[-h, -h, h, -h], [h, -h, h, h], [h, h, -h, h], [-h, h, -h, -h]]
        return {"type": "round", "round": self.round_no, "t": r3(self.t), "static": rim, "bounds": self.bounds()}

    def state_msg(self):
        return {"type": "state", "t": r3(self.t),
                "cycles": [c.to_json() for c in self.cycles.values()],
                "walls": [w.to_json() for w in self.walls.values() if w.length() > 0]}

    def event(self, ev, **kw):
        e = {"type": "event", "ev": ev, "t": r3(self.t)}
        e.update(kw)
        self.out_events.append(e)

    # --- lifecycle ---

    def spawn(self, c, announce=True):
        m = self.half * 0.7
        for _ in range(50):
            x = self.rng.uniform(-m, m)
            y = self.rng.uniform(-m, m)
            if self._clear_point(x, y, 6.0):
                break
        c.x, c.y = round(x), round(y)
        c.d = self.rng.randrange(4)
        c.alive = True
        c.brake = False
        c.speed = c.base_speed
        c.respawn_at = None
        c.next_turn = self.t + self.rng.uniform(0.8, 2.5)
        self._new_wall(c)
        if announce:
            self.event("spawn", id=c.id, x=r3(c.x), y=r3(c.y))

    def _clear_point(self, x, y, r):
        for w in self.walls.values():
            if point_seg_dist(x, y, w) < r:
                return False
        return True

    def _new_wall(self, c):
        w = Wall(self._nwall(), c.id, c.x, c.y)
        self.walls[w.id] = w
        c.wall = w

    def turn(self, c, direction):
        if not c.alive:
            return
        c.d = (c.d + (-1 if direction == "left" else 1)) % 4
        self._new_wall(c)
        self.event("turn", id=c.id, x=r3(c.x), y=r3(c.y), dx=DIRS[c.d][0], dy=DIRS[c.d][1])

    def kill(self, c, killer=None):
        if not c.alive:
            return
        c.alive = False
        c.speed = 0.0
        c.respawn_at = self.t + 3.0
        for w in self.walls.values():
            if w.cycle == c.id:
                w.expire_at = self.t + 2.0
        ev = {"id": c.id, "x": r3(c.x), "y": r3(c.y)}
        if killer is not None:
            ev["killer"] = killer
        self.event("death", **ev)

    def remove_cycle(self, cid):
        self.cycles.pop(cid, None)
        for wid in [w.id for w in self.walls.values() if w.cycle == cid]:
            del self.walls[wid]

    # --- simulation ---

    def _ray_hit(self, c, d, dist):
        dx, dy = DIRS[d]
        p, p2 = (c.x, c.y), (c.x + dx * dist, c.y + dy * dist)
        if abs(p2[0]) >= self.half or abs(p2[1]) >= self.half:
            return True
        for w in self.walls.values():
            if w is c.wall or w.length() == 0:
                continue
            if self._hits_wall(p, p2, w) is not None:
                return True
        return False

    def _hits_wall(self, p, p2, w):
        for a, b in solid_segments(w):
            t = seg_intersect(p, p2, a, b)
            if t is not None:
                return t
        return None

    def end_round(self):
        alive = [c for c in self.cycles.values() if c.alive]
        winner = max(alive, key=lambda c: c.wall.length() if c.wall else 0).name if alive else ""
        self.event("round_end", winner=winner)
        self.walls.clear()
        self.round_no += 1
        self.round_started = self.t
        self.pending_round = True
        for c in self.cycles.values():
            self.spawn(c)

    def step(self, dt):
        with self.lock:
            self.t += dt
            if self.round_seconds > 0 and self.t - self.round_started >= self.round_seconds:
                self.end_round()
            for c in list(self.cycles.values()):
                if not c.alive:
                    if c.respawn_at is not None and self.t >= c.respawn_at:
                        for wid in [w.id for w in self.walls.values() if w.cycle == c.id]:
                            del self.walls[wid]
                        self.spawn(c)
                    continue
                if c.bridge_key is None:
                    look = max(6.0, c.base_speed * 0.4)
                    if self._ray_hit(c, c.d, look) or self.t >= c.next_turn:
                        options = ["left", "right"]
                        self.rng.shuffle(options)
                        for o in options:
                            nd = (c.d + (-1 if o == "left" else 1)) % 4
                            if not self._ray_hit(c, nd, look):
                                self.turn(c, o)
                                break
                        c.next_turn = self.t + self.rng.uniform(0.8, 2.5)
                c.speed = c.base_speed * (0.5 if c.brake else 1.0)
                dx, dy = DIRS[c.d]
                nx, ny = c.x + dx * c.speed * dt, c.y + dy * c.speed * dt
                hit = None
                if self.collisions:
                    if abs(nx) >= self.half or abs(ny) >= self.half:
                        hit = ("rim", None)
                    else:
                        for w in self.walls.values():
                            if w is c.wall or w.length() == 0:
                                continue
                            if self._hits_wall((c.x, c.y), (nx, ny), w) is not None:
                                hit = ("wall", w.cycle)
                                break
                if hit:
                    killer = hit[1] if hit[1] is not None and hit[1] != c.id else None
                    self.kill(c, killer)
                    continue
                c.x, c.y = nx, ny
                c.wall.x2, c.wall.y2 = c.x, c.y
            for wid in [w.id for w in self.walls.values() if w.expire_at is not None and self.t >= w.expire_at]:
                del self.walls[wid]

    # --- protocol ---

    def handle(self, conn, msg):
        """Apply one host message. Returns direct replies for this connection."""
        typ = msg.get("type")
        replies = []
        with self.lock:
            if typ == "join":
                key = str(msg.get("key", ""))
                name = str(msg.get("name", "player"))[:32] or "player"
                color = msg.get("color") or [1, 1, 1]
                existing = self.by_key(key)
                if existing is None:
                    c = Cycle(self._nid(), name, tuple(float(v) for v in color[:3]), self.speed, bridge_key=key)
                    self.cycles[c.id] = c
                    self.spawn(c)
                conn.keys.add(key)
                replies.append({"type": "joined", "key": key, "name": name})
            elif typ == "leave":
                key = str(msg.get("key", ""))
                c = self.by_key(key)
                if c:
                    self.remove_cycle(c.id)
                conn.keys.discard(key)
                replies.append({"type": "left", "key": key})
            elif typ == "input":
                c = self.by_key(str(msg.get("key", "")))
                if c is None:
                    replies.append({"type": "error", "msg": "input for unknown key"})
                else:
                    if msg.get("turn") in ("left", "right"):
                        self.turn(c, msg["turn"])
                    if "brake" in msg:
                        c.brake = bool(msg["brake"])
            elif typ == "explode":
                self.explode(float(msg.get("x", 0)), float(msg.get("y", 0)), float(msg.get("r", 4)))
            elif typ == "cmd":
                line = str(msg.get("line", ""))
                self.event("chat", name="console", text="(fake) ran: " + line)
            elif typ == "chat":
                self.event("chat", name=str(msg.get("name", "?")), text=str(msg.get("text", "")))
            else:
                pass  # unknown types are ignored (forward compatibility)
        return replies

    def by_key(self, key):
        for c in self.cycles.values():
            if c.bridge_key == key:
                return c
        return None

    def drop_connection_players(self, conn):
        with self.lock:
            for key in list(conn.keys):
                c = self.by_key(key)
                if c:
                    self.remove_cycle(c.id)
            conn.keys.clear()

    def explode(self, x, y, r):
        for c in self.cycles.values():
            if c.alive and math.hypot(c.x - x, c.y - y) <= r:
                self.kill(c)
        for w in self.walls.values():
            L = w.length()
            if L <= 0:
                continue
            ux, uy = (w.x2 - w.x1) / L, (w.y2 - w.y1) / L
            along = (x - w.x1) * ux + (y - w.y1) * uy
            perp = abs((x - w.x1) * -uy + (y - w.y1) * ux)
            if perp >= r:
                continue
            half = math.sqrt(r * r - perp * perp)
            a, b = (along - half) / L, (along + half) / L
            a, b = max(0.0, a), min(1.0, b)
            if b > a:
                w.gaps.append((a, b))


def solid_segments(w):
    L = w.length()
    if not w.gaps or L == 0:
        return [((w.x1, w.y1), (w.x2, w.y2))]
    holes = sorted(w.gaps)
    out, cur = [], 0.0
    for a, b in holes:
        if a > cur:
            out.append((cur, a))
        cur = max(cur, b)
    if cur < 1.0:
        out.append((cur, 1.0))
    dx, dy = w.x2 - w.x1, w.y2 - w.y1
    return [((w.x1 + dx * a, w.y1 + dy * a), (w.x1 + dx * b, w.y1 + dy * b)) for a, b in out]


def point_seg_dist(x, y, w):
    dx, dy = w.x2 - w.x1, w.y2 - w.y1
    L2 = dx * dx + dy * dy
    if L2 == 0:
        return math.hypot(x - w.x1, y - w.y1)
    t = max(0.0, min(1.0, ((x - w.x1) * dx + (y - w.y1) * dy) / L2))
    return math.hypot(x - (w.x1 + t * dx), y - (w.y1 + t * dy))


class Conn:
    def __init__(self, sock, addr):
        self.sock = sock
        self.addr = addr
        self.keys = set()
        self.wlock = threading.Lock()
        self.alive = True

    def send(self, obj):
        data = (json.dumps(obj, separators=(",", ":")) + "\n").encode("utf-8")
        try:
            with self.wlock:
                self.sock.sendall(data)
            return True
        except OSError:
            self.alive = False
            return False


class Server:
    def __init__(self, sim, host="127.0.0.1", port=4535, rate=30.0, quiet_state=True):
        self.sim = sim
        self.rate = rate
        self.conns = []
        self.clock = threading.Lock()
        self.srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind((host, port))
        self.srv.listen(4)
        self.port = self.srv.getsockname()[1]
        self.running = True
        self.received = 0

    def serve_forever(self):
        threading.Thread(target=self._accept_loop, daemon=True).start()
        dt = 1.0 / self.rate
        nxt = time.monotonic()
        last_summary = time.monotonic()
        while self.running:
            self.sim.step(dt)
            with self.sim.lock:
                events = self.sim.out_events
                self.sim.out_events = []
                state = self.sim.state_msg()
                new_round = self.sim.round_msg() if self.sim.pending_round else None
                self.sim.pending_round = False
            for e in events:
                if e["ev"] != "turn":
                    log("event", json.dumps(e))
                self.broadcast(e)
            if new_round:
                log("new round", new_round["round"])
                self.broadcast(new_round)
            self.broadcast(state)
            now = time.monotonic()
            if now - last_summary > 10:
                last_summary = now
                with self.sim.lock:
                    alive = sum(1 for c in self.sim.cycles.values() if c.alive)
                    log("t=%.1f cycles=%d alive=%d walls=%d hosts=%d" % (self.sim.t, len(self.sim.cycles), alive, len(self.sim.walls), len(self.conns)))
            nxt += dt
            sleep = nxt - time.monotonic()
            if sleep > 0:
                time.sleep(sleep)
            else:
                nxt = time.monotonic()

    def stop(self):
        self.running = False
        try:
            self.srv.close()
        except OSError:
            pass
        with self.clock:
            for c in self.conns:
                try:
                    c.sock.close()
                except OSError:
                    pass

    def broadcast(self, obj):
        with self.clock:
            conns = list(self.conns)
        for c in conns:
            c.send(obj)

    def _accept_loop(self):
        while self.running:
            try:
                sock, addr = self.srv.accept()
            except OSError:
                return
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            conn = Conn(sock, addr)
            log("host connected from %s:%d" % addr)
            conn.send({"type": "hello", "proto": 1, "game": "armagetronad", "version": "fake-guest", "rate": self.rate})
            with self.sim.lock:
                conn.send(self.sim.round_msg())
            with self.clock:
                self.conns.append(conn)
            threading.Thread(target=self._read_loop, args=(conn,), daemon=True).start()

    def _read_loop(self, conn):
        buf = b""
        try:
            while self.running:
                chunk = conn.sock.recv(65536)
                if not chunk:
                    break
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    text = line.decode("utf-8", "replace").strip()
                    if not text:
                        continue
                    self.received += 1
                    log("<-", text)
                    try:
                        msg = json.loads(text)
                    except ValueError:
                        conn.send({"type": "error", "msg": "bad json"})
                        continue
                    if not isinstance(msg, dict):
                        continue
                    for reply in self.sim.handle(conn, msg):
                        conn.send(reply)
        except OSError:
            pass
        finally:
            log("host %s:%d disconnected, removing its bridge players: %s" % (conn.addr[0], conn.addr[1], sorted(conn.keys)))
            self.sim.drop_connection_players(conn)
            with self.clock:
                if conn in self.conns:
                    self.conns.remove(conn)
            try:
                conn.sock.close()
            except OSError:
                pass


# ---------------------------------------------------------------- self-test

class TestClient:
    def __init__(self, port):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=5)
        self.buf = b""
        self.events = []
        self.replies = []
        self.last_state = None
        self.states = 0
        self.hello = None
        self.round = None

    def send(self, obj):
        self.sock.sendall((json.dumps(obj) + "\n").encode())

    def pump(self, until, timeout=10.0, what="condition"):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if until():
                return True
            try:
                self.sock.settimeout(max(0.01, deadline - time.monotonic()))
                chunk = self.sock.recv(65536)
            except socket.timeout:
                continue
            if not chunk:
                raise AssertionError("guest closed the connection")
            self.buf += chunk
            while b"\n" in self.buf:
                line, self.buf = self.buf.split(b"\n", 1)
                m = json.loads(line)
                t = m.get("type")
                if t == "state":
                    self.last_state = m
                    self.states += 1
                elif t == "hello":
                    self.hello = m
                elif t == "round":
                    self.round = m
                elif t == "event":
                    self.events.append(m)
                else:
                    self.replies.append(m)
        if until():
            return True
        raise AssertionError("timed out waiting for " + what)

    def cycle(self, key):
        for c in (self.last_state or {}).get("cycles", []):
            if c.get("bridge_key") == key:
                return c
        return None


def self_test():
    sim = Sim(size=200, speed=20, ai=3, seed=7)
    srv = Server(sim, port=0, rate=30)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    ok = True
    results = []

    def check(name, fn):
        nonlocal ok
        try:
            fn()
            results.append(("PASS", name))
        except Exception as e:  # noqa: BLE001
            ok = False
            results.append(("FAIL", "%s: %s" % (name, e)))

    c = TestClient(srv.port)
    key = "mc:00000000-0000-0000-0000-000000000001"

    def hello_round_state():
        c.pump(lambda: c.hello and c.round and c.states >= 5, what="hello/round/state")
        assert c.hello["proto"] == 1
        assert c.round["bounds"] == [-100.0, -100.0, 100.0, 100.0], c.round["bounds"]
        assert len(c.round["static"]) == 4
        ai = [x for x in c.last_state["cycles"] if not x.get("bridge_key")]
        assert len(ai) == 3, ai
        assert all(x["speed"] in (20.0, 0.0) for x in ai)

    def ai_moves_and_walls_grow():
        first = {x["id"]: (x["x"], x["y"]) for x in c.last_state["cycles"] if x["alive"]}
        c.pump(lambda: c.states >= 40, what="more states")
        moved = [i for i, p in first.items() for x in c.last_state["cycles"] if x["id"] == i and (x["x"], x["y"]) != p]
        assert moved, "no AI cycle moved"
        assert c.last_state["walls"], "no walls"

    def join():
        c.send({"type": "join", "key": key, "name": "Tester", "color": [0.1, 0.6, 1.0]})
        c.pump(lambda: any(r.get("type") == "joined" and r.get("key") == key for r in c.replies), what="joined")
        c.pump(lambda: c.cycle(key) is not None and c.cycle(key)["alive"], what="bridge cycle")

    def turn_left():
        before = c.cycle(key)
        bd = (before["dx"], before["dy"])
        c.send({"type": "input", "key": key, "turn": "left"})
        idx = DIRS.index(bd)
        want = DIRS[(idx - 1) % 4]
        c.pump(lambda: c.cycle(key) and (c.cycle(key)["dx"], c.cycle(key)["dy"]) == want, timeout=3, what="left turn")

    def brake():
        c.send({"type": "input", "key": key, "brake": True})
        c.pump(lambda: c.cycle(key) and (c.cycle(key)["brake"] or not c.cycle(key)["alive"]), timeout=3, what="brake on")
        c.send({"type": "input", "key": key, "brake": False})
        c.pump(lambda: c.cycle(key) and not c.cycle(key)["brake"], timeout=3, what="brake off")

    def explode_gaps():
        # pick the longest wall and blast its middle
        walls = sorted(c.last_state["walls"], key=lambda w: math.hypot(w["x2"] - w["x1"], w["y2"] - w["y1"]))
        w = walls[-1]
        mx, my = (w["x1"] + w["x2"]) / 2, (w["y1"] + w["y2"]) / 2
        c.send({"type": "explode", "x": mx, "y": my, "r": 2.0})
        c.pump(lambda: any(x["id"] == w["id"] and x.get("gaps") for x in c.last_state["walls"]) or
               not any(x["id"] == w["id"] for x in c.last_state["walls"]), timeout=3, what="gaps")

    def cmd_chat_unknown():
        c.send({"type": "cmd", "line": "CYCLE_SPEED 20"})
        c.send({"type": "chat", "name": "Jamie", "text": "hi"})
        c.send({"type": "from_the_future", "x": 1})
        c.pump(lambda: any(e["ev"] == "chat" and e.get("text") == "hi" for e in c.events), timeout=3, what="chat echo")

    def death_and_respawn():
        cid = c.cycle(key)["id"]
        # go straight: the rim (or a wall) is at most ~10 s away at speed 20
        c.pump(lambda: any(e["ev"] == "death" and e["id"] == cid for e in c.events), timeout=15, what="bridge cycle death")
        died = max(e["t"] for e in c.events if e["ev"] == "death" and e["id"] == cid)
        c.pump(lambda: any(e["ev"] == "spawn" and e["id"] == cid and e["t"] > died for e in c.events), timeout=6, what="respawn")
        c.pump(lambda: c.cycle(key) is not None and c.cycle(key)["alive"], timeout=2, what="alive again")

    def leave():
        c.send({"type": "leave", "key": key})
        c.pump(lambda: any(r.get("type") == "left" for r in c.replies), what="left")
        c.pump(lambda: c.cycle(key) is None, what="bridge cycle removed")

    def drop_removes_players():
        c2 = TestClient(srv.port)
        k2 = "mc:00000000-0000-0000-0000-000000000002"
        c2.send({"type": "join", "key": k2, "name": "Dropper", "color": [1, 0, 0]})
        c2.pump(lambda: c2.cycle(k2) is not None, what="second join")
        c2.sock.close()
        c.pump(lambda: c.cycle(k2) is None and c.states > 0, timeout=3, what="dropped host's player removed")

    def round_restart():
        s2 = Sim(size=100, speed=20, ai=2, seed=1, round_seconds=0.5)
        for _ in range(12):
            s2.step(1 / 30)
        assert s2.walls, "walls before round end"
        old = set(s2.walls)
        for _ in range(6):
            s2.step(1 / 30)
        assert s2.round_no == 2 and s2.pending_round, (s2.round_no, s2.pending_round)
        assert any(e["ev"] == "round_end" for e in s2.out_events)
        assert not (old & set(s2.walls)), "old walls must be cleared at round start"
        assert s2.round_msg()["round"] == 2

    for name, fn in [("round restart (unit)", round_restart), ("hello/round/state", hello_round_state), ("AI moves, walls grow", ai_moves_and_walls_grow),
                     ("join -> joined + bridge cycle", join), ("input turn left", turn_left), ("input brake", brake),
                     ("explode -> gaps", explode_gaps), ("cmd/chat/unknown type", cmd_chat_unknown),
                     ("collision death + respawn", death_and_respawn), ("leave -> left", leave),
                     ("dropped host removes its players", drop_removes_players)]:
        check(name, fn)
    srv.stop()
    for status, name in results:
        print("%s  %s" % (status, name))
    print("self-test %s (%d checks, guest received %d lines)" % ("PASSED" if ok else "FAILED", len(results), srv.received))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=4535)
    ap.add_argument("--rate", type=float, default=30.0, help="state messages per second")
    ap.add_argument("--size", type=float, default=200.0, help="arena edge length (grid units)")
    ap.add_argument("--speed", type=float, default=20.0)
    ap.add_argument("--ai", type=int, default=3, help="number of AI cycles")
    ap.add_argument("--seed", type=int, default=None)
    ap.add_argument("--no-collisions", action="store_true")
    ap.add_argument("--round-seconds", type=float, default=90.0, help="end the round and restart after N seconds (0 = never)")
    ap.add_argument("--self-test", action="store_true", help="run the in-process protocol self-test and exit")
    a = ap.parse_args()
    if a.self_test:
        sys.exit(self_test())
    if a.host not in ("127.0.0.1", "localhost", "::1"):
        log("WARNING: protocol v1 has no auth; binding to %s exposes it" % a.host)
    sim = Sim(size=a.size, speed=a.speed, ai=a.ai, seed=a.seed, collisions=not a.no_collisions, round_seconds=a.round_seconds)
    srv = Server(sim, host=a.host, port=a.port, rate=a.rate)
    log("listening on %s:%d (rate %.0f Hz, arena %.0fx%.0f, %d AI)" % (a.host, srv.port, a.rate, a.size, a.size, a.ai))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        log("bye")
        srv.stop()


if __name__ == "__main__":
    main()

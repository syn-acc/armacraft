#!/usr/bin/env python3
"""ride_diff: record the real Armagetron engine (the armacraft guest bridge) driving scripted inputs, for the
differential test of armacraft's ride-mode physics port (host/src/main/java/gl/syn/armacraft/ride/core).

Joins the guest as a bridge player, sets the physics with console commands (localhost cmd), and per scenario:
waits for a fresh cycle, sends timed bridge inputs (turn / brake) relative to the moment the cycle starts moving,
and records every state snapshot of its cycle plus its turn / death events. Each scenario ends with a straight run
into the rim (rubber dies there), which also starts the next round. Writes one JSON file with all scenarios.

  python tools/ride_diff.py --port 4575 --preset armagetron --out build/ride-diff.json

Stdlib only. The replay against the Java core is RideDiffTest (gradlew test -Parmacraft.ridediff=<json>).
"""

import argparse
import json
import socket
import sys
import time
import uuid

# Armagetron 0.2.9.3.0 config/settings.cfg values for everything the port uses, plus the dedicated-server-only
# CYCLE_DELAY_BONUS (gCycleMovement.cpp:202-208, .95 on dedicated servers) forced to 1 like a client.
STOCK = {
    "CYCLE_SPEED": 30, "CYCLE_SPEED_MIN": .25, "CYCLE_SPEED_MAX": 0, "CYCLE_SPEED_DECAY_BELOW": 5, "CYCLE_SPEED_DECAY_ABOVE": .1,
    "CYCLE_START_SPEED": 20, "CYCLE_ACCEL": 10, "CYCLE_ACCEL_SELF": 1, "CYCLE_ACCEL_TEAM": 1, "CYCLE_ACCEL_ENEMY": 1,
    "CYCLE_ACCEL_RIM": 0, "CYCLE_ACCEL_SLINGSHOT": 1, "CYCLE_ACCEL_TUNNEL": 1, "CYCLE_ACCEL_OFFSET": 2, "CYCLE_WALL_NEAR": 6,
    "CYCLE_BRAKE": 30, "CYCLE_BRAKE_REFILL": .1, "CYCLE_BRAKE_DEPLETE": 1, "CYCLE_DELAY": .1, "CYCLE_DELAY_TIMEBASED": 1,
    "CYCLE_DELAY_DOUBLEBIND_BONUS": 1, "CYCLE_DELAY_BONUS": 1, "CYCLE_TURN_SPEED_FACTOR": .95, "CYCLE_TURN_MEMORY": 3,
    "CYCLE_RUBBER": 1, "CYCLE_RUBBER_SPEED": 40, "CYCLE_RUBBER_TIME": 10, "CYCLE_RUBBER_TIMEBASED": 0,
    "CYCLE_RUBBER_MINDISTANCE": .001, "CYCLE_RUBBER_MINDISTANCE_RATIO": .0001, "CYCLE_RUBBER_MINDISTANCE_RESERVOIR": .005,
    "CYCLE_RUBBER_MINDISTANCE_UNPREPARED": .005, "CYCLE_RUBBER_MINDISTANCE_PREPARATION": .2, "CYCLE_RUBBER_MINADJUST": .05,
    "CYCLE_RUBBER_DELAY": 0, "CYCLE_RUBBER_DELAY_BONUS": .5, "CYCLE_RUBBER_MALUS_TURN": 0, "CYCLE_PING_RUBBER": 0,
    "CYCLE_RUBBER_LEGACY": 0, "CYCLE_WIDTH": 0, "CYCLE_WIDTH_SIDE": 0,
}
# RCL rcl-sumobar.cfg physics lines over the stock values.
RCL = dict(STOCK, CYCLE_ACCEL=20, CYCLE_RUBBER=5)

# scenario: list of (seconds after the cycle starts moving, action); every scenario then drives into the rim.
SCENARIOS = {
    # brake held 1.5 s: the reservoir (CYCLE_BRAKE_DEPLETE 1/s) runs dry after 1 s, then decay pulls speed back up
    "brake": [(0.6, "brake on"), (2.1, "brake off"), (3.0, "end")],
    # two presses at once: the second is queued for CYCLE_DELAY (a U-turn)
    "double_tap": [(0.5, "right"), (0.5, "right"), (1.5, "end")],
    # six alternating presses at once: one now, CYCLE_TURN_MEMORY queue, the sixth cancels the fifth
    "turn_spam": [(0.4, "left"), (0.4, "right"), (0.4, "left"), (0.4, "right"), (0.4, "left"), (0.4, "right"), (1.5, "end")],
    # three lefts at once: a spiral into the own first leg (death by own wall)
    "spiral": [(0.4, "left"), (0.4, "left"), (0.4, "left"), (2.0, "end")],
    # straight into the rim: rubber runs out there
    "rim": [(0.0, "end")],
    # --- tight maneuvers next to the own wall (the cycle is a point, walls are lines) ---
    # double-bind 180 the other way, then back along the own first wall
    "uturn_left": [(0.5, "left"), (0.5, "left"), (1.5, "end")],
    # 180, back along the first wall, then an "adjust" towards it (right-left at once): the leg is about as long as
    # the gap, so the cycle ends up riding a hair beside its old wall (or grinds it for a moment)
    "adjust": [(0.5, "right"), (0.5, "right"), (0.9, "right"), (0.9, "left"), (2.5, "end")],
    # 180, then an adjust away from the old wall and one back towards it
    "adjust_out_in": [(0.5, "right"), (0.5, "right"), (0.8, "left"), (0.8, "right"), (1.1, "right"), (1.1, "left"), (2.5, "end")],
    # four lefts at once: the tightest box. The fourth leg runs a fraction of a unit beside the first wall and
    # into the second: death inside the box once the rubber is gone
    "box": [(0.4, "left"), (0.4, "left"), (0.4, "left"), (0.4, "left"), (3.0, "end")],
    # a box spiral inward with shrinking legs, ending on the third wall
    "spiral_in": [(0.4, "left"), (0.75, "left"), (1.0, "left"), (1.2, "left"), (1.35, "left"), (4.0, "end")],
}
# no AIs, no team filling: only our cycle in the arena
ENV = ["TEAMS_MIN 1", "SP_TEAMS_MIN 1", "TEAM_BALANCE_WITH_AIS 0", "SP_TEAM_BALANCE_WITH_AIS 0", "NUM_AIS 0", "SP_NUM_AIS 0",
       "AUTO_AIS 0", "SP_AUTO_AIS 0", "MIN_PLAYERS 0", "SP_MIN_PLAYERS 0", "WIN_ZONE_MIN_ROUND_TIME 999", "SP_WIN_ZONE_MIN_ROUND_TIME 999"]


class Bridge:
    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=10)
        self.sock.settimeout(0.05)
        self.buf = b""

    def send(self, obj):
        self.sock.sendall((json.dumps(obj) + "\n").encode())

    def recv(self):
        out = []
        try:
            chunk = self.sock.recv(1 << 20)
            if not chunk:
                raise ConnectionError("guest closed the bridge")
            self.buf += chunk
        except socket.timeout:
            pass
        while b"\n" in self.buf:
            line, self.buf = self.buf.split(b"\n", 1)
            if line.strip():
                out.append(json.loads(line))
        return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=4575)
    ap.add_argument("--preset", choices=["armagetron", "rcl"], default="armagetron")
    ap.add_argument("--out", default="ride-diff.json")
    ap.add_argument("--scenarios", default=",".join(SCENARIOS))
    args = ap.parse_args()

    b = Bridge(args.host, args.port)
    settings = STOCK if args.preset == "armagetron" else RCL
    for line in ENV:
        b.send({"type": "cmd", "line": line})
    for k, v in settings.items():
        b.send({"type": "cmd", "line": f"{k} {v}"})
    key = f"mc:ridediff-{uuid.uuid4().hex[:8]}"
    b.send({"type": "join", "key": key, "name": "ridediff", "color": [1, .5, 0]})
    results = {"preset": args.preset, "settings": settings, "scenarios": []}

    def pump(on_msg, until, timeout):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            for m in b.recv():
                if on_msg(m):
                    return True
            if until():
                return True
        return False

    bounds = [None]
    names = ["warmup"] + args.scenarios.split(",")
    SCENARIOS["warmup"] = [(0.0, "end")]
    for name in names:
        script = SCENARIOS[name]
        rec = {"name": name, "states": [], "events": [], "inputs": []}
        cyc = {"id": None, "start_t": None, "last_t": None, "dead": False}

        def on_msg(m):
            if m.get("type") == "round":
                bounds[0] = m.get("bounds")
            if m.get("type") in ("state", "delta"):
                others = [c for c in m.get("cycles", []) if c.get("bridge_key") != key and c.get("alive", True)]
                if others and cyc["id"] is not None:
                    rec["others_seen"] = max(rec.get("others_seen", 0), len(others))
                for c in m.get("cycles", []):
                    if c.get("bridge_key") == key:
                        if cyc["id"] != c["id"]:
                            if not c.get("alive", True):
                                continue
                            cyc["id"] = c["id"]
                            rec["states"].clear()
                            rec["events"].clear()
                            cyc["start_t"] = None
                        cyc["last_t"] = m["t"]
                        rec["states"].append({"t": m["t"], "x": c["x"], "y": c["y"], "dx": c["dx"], "dy": c["dy"], "speed": c["speed"],
                                              "alive": c.get("alive", True), "brake": c.get("brake", False), "rubber": c.get("rubber", 0)})
                        if cyc["start_t"] is None and m["t"] >= 0 and c.get("alive", True) and len(rec["states"]) > 1:
                            p = rec["states"][-2]
                            if abs(c["x"] - p["x"]) + abs(c["y"] - p["y"]) > 1e-6:
                                cyc["start_t"] = p["t"]
            if m.get("type") == "event" and cyc["id"] is not None and m.get("id") == cyc["id"]:
                rec["events"].append(m)
                if m.get("ev") == "death":
                    cyc["dead"] = True
            return False

        # wait for a fresh moving cycle
        if not pump(on_msg, lambda: cyc["start_t"] is not None, 60):
            print(f"{name}: no moving cycle within 60 s", file=sys.stderr)
            sys.exit(2)
        t0 = cyc["start_t"]
        rec["start_t"] = t0
        rec["bounds"] = bounds[0]
        wall0 = time.monotonic()
        lt0 = cyc["last_t"]
        for (at, action) in script:
            if action == "end":
                break
            # wait until guest time reaches t0 + at (guest clock runs in real time)
            target = t0 + at
            pump(on_msg, lambda: cyc["last_t"] is not None and cyc["last_t"] + (time.monotonic() - wall0) * 0 >= target - 1 / 60, 30)
            sent_t = cyc["last_t"]
            if action in ("left", "right"):
                b.send({"type": "input", "key": key, "turn": action})
            elif action == "brake on":
                b.send({"type": "input", "key": key, "brake": True})
            elif action == "brake off":
                b.send({"type": "input", "key": key, "brake": False})
            rec["inputs"].append({"at": at, "action": action, "sent_after_state_t": sent_t})
        # let it run into the rim and die
        pump(on_msg, lambda: cyc["dead"], 40)
        pump(on_msg, lambda: False, 0.3)
        print(f"{name}: {len(rec['states'])} states, {len(rec['events'])} events, start t {t0:.3f}, dead {cyc['dead']}, "
              f"other cycles seen {rec.get('others_seen', 0)}")
        if name != "warmup":
            results["scenarios"].append(rec)

    b.send({"type": "leave", "key": key})
    with open(args.out, "w") as f:
        json.dump(results, f, indent=1)
    print("wrote", args.out)


if __name__ == "__main__":
    main()

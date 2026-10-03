# armacraft performance: guest output, Minecraft cost, latency

Measured 2026-10-01/02 on the dev box. State of each claim is marked **[measured]**, **[code]** (read
from the 26.3 / guest / host source, not run) or **[estimate]**. Nothing here is deployed; the patches
in `docs/perf-patches/` are unapplied.

## TL;DR

| what | number | state |
|---|---|---|
| walls per snapshot, 32 AIs, 500x500 arena (mean / max over 120 s) | 141 / 262 | measured |
| walls per snapshot, 32 AIs, 1414x1414 arena (mean / max over 180 s) | 198 / 328 | measured |
| snapshot size | 80-85 B per wall + ~190 B per cycle; 14.6 KB p50 / 27 KB max (32 AIs) | measured |
| bridge traffic, guest -> host | 30 snapshots/s, 86 KB/s (3 AIs) .. 434 KB/s (32 AIs) .. 592 KB/s (32 AIs, big arena) | measured |
| guest CPU (whole server, % of one core) | 1.6 % (3 AIs) .. 4.7 % (32 AIs) mean, 9 % p95 | measured |
| guest CPU spent on snapshots | 0.17 ms (3 AIs) .. 0.63 ms (32 AIs) per snapshot = 0.5-1.9 % of a core | measured (A/B) |
| Python `json.loads`, 32-AI snapshot (27 KB / 31 KB) | 0.47 ms / 0.79 ms (~17-18 us/KB) | measured |
| host parse (`Protocol.parse` = Gson tree + records) on the bridge thread, 27 KB / 31 KB | 0.64 ms / 0.82 ms (Gson tree alone 0.35 / 0.42 ms) | measured (microbench) |
| host server-thread render work per tick (without Minecraft's own entity work) | 0.64 ms (32 AIs, 261 walls) .. 1.0 ms (328 walls); 6.4 ms at 3 000 walls; 27 ms at 10 000 | measured (mirror bench, no MC) |
| Minecraft display entities (scale 1) | 480 mean / 790 max (32 AIs, small arena); 1280 / 1980 (big arena) | estimate from measured walls |
| MC packets per client per tick if everything is in range | 46 mean / 102 max, 0.8-1.7 KB/tick (32 AIs) | estimate ([code] packet sizes) |
| bridge input -> guest turn executed (incl. Docker forward both ways) | 9.6 ms mean, 16 ms max at the default 40 FPS (the guest's "~13 ms" is the worst-phase case); 3.6 ms at `DEDICATED_FPS 120` | measured |
| bridge input -> first snapshot showing the turn | 20 ms mean, 40 ms max (snapshot cadence, unchanged at 120 FPS) | measured |
| key press -> cycle starts turning on a LAN client | ~158 ms mean (~108 with patch 0001), settled +100 ms (+50 with `interp_ticks 2`) | estimate (guest hops measured, MC hops from code) |
| delta protocol (patch 0003): bytes vs v1 | 24-26 % of v1 on identical frames; folded deltas == v1 walls on 3 567/3 567 frames (final patch) + 7 142/7 142 (previous revision); guest snapshot CPU 0.90 -> 0.36 ms | measured |

Is JSON the bottleneck? **No.** At the largest measured load the guest spends < 2 % of a core building
snapshots, the host parses them off the server thread in < 1 ms each (0.6-0.8 ms at 27-31 KB, 2.5 %
of a core at 30 Hz), and the Minecraft-side traffic is ~1 KB/tick per client. Binary encoding would
save CPU nobody is short of. Full snapshots do get expensive at thousands of walls (14 ms to parse
10 000), but that is a "send everything every frame" problem, which deltas fix while staying JSON.
The real costs are (1) latency, dominated by tick quantisation, one avoidable extra tick and
client-side interpolation, and (2) work that scales with *all* walls every tick instead of the
handful that changed.

Top 3:
1. **Render at START_SERVER_TICK** (patch 0001, one line): every visual update currently waits one
   extra server tick. -50 ms on everything. [code]
2. **Lower interpolation lag**: `interp_ticks` 3 -> 2 (config) cuts the steady-state visual lag of
   cycles and head walls from 100 ms to 50 ms; the 30 Hz -> 20 Hz jitter is already absorbed by
   `Extrapolate`. Consider `camera.interp_ticks` 5 -> 3. [code]
3. **Make per-tick host work O(changed)**: render memo + generation sweep (patch 0002, 6-7x less
   server-thread time and allocation per tick on 32-AI snapshots), then the opt-in `delta` protocol
   feature (patches 0003 + 0004: -71..76 % bytes, -60 % guest snapshot CPU, host parse proportional to
   changed walls). [measured]

## Setup and method

- Box: i9-10900K (10C/20T), Windows 11, Docker Desktop (WSL2, cgroup v1). **Shared**: during the runs
  OneDrive (~2 cores), another agent's Minecraft JVM (~1.5 cores) and the WSL VM kept host CPU load
  around 50-60 %. CPU numbers below are cgroup CPU *time* of the guest container, which contention
  barely moves; timings (latency, parse) are more exposed to it, so treat p95s as pessimistic.
- Guest image `armacraft-guest:dev` sha256:a5c03a25 (built 23:28 BST from `guest/`; `gBridge.cpp` last
  changed 23:26), re-tagged `armacraft-guest:bench` so a rebuild could not change it mid-run. Own
  container `armacraft-guest-bench` on 127.0.0.1:4554/udp + 4555/tcp, removed after each config. The
  dev container `armacraft-guest` was not touched (the tool refuses its name and ports).
- Config (generated, `tools/bench_guest.py`): N AI-only teams of one (`TEAMS_MIN N`, `TEAMS_MAX 64`,
  `TEAM_MAX_PLAYERS 1`), `AI_IQ 60`, `FINISH_TYPE 3`, win zone off, round/time limits off,
  `SIZE_FACTOR 0` (arena 500 x 500) and `3` (1414 x 1414), defaults otherwise (`CYCLE_SPEED 30`,
  `DEDICATED_FPS 40`, `WALLS_STAY_UP_DELAY 8`, `BRIDGE_RATE 30`).
- Getting N AIs: the guest adds at most ~10 AIs per round (`gAITeam::BalanceWithAIs` /
  `gAIPlayer::SetNumberOfAIs` run once per round), and the stock `aiplayers.cfg` has 22 characters,
  each usable once (`BestIQ` skips characters in use), so **22 AIs is a hard cap** with stock files. The
  bench clones character rows (`Emacs2`, ...) and ends rounds early with an `explode` until a round
  starts with N cycles; measurement starts at that round's start.
- CPU: the container's `cpuacct.usage` read through one persistent `docker exec` shell (builtins only,
  no fork per sample) every second; `docker stats --no-stream` recorded per A/B window as a cross-check
  (they agree within noise). 100 % = one core; the dedicated server is single-threaded.
- "Bridge on/off": with no host connected the dedicated server naps and plays no rounds, so "off" means
  connected but `BRIDGE_RATE 0.001` (via a `cmd` message): no snapshots, everything else (hello,
  events, per-frame cycle tracking) still runs. 5 s windows alternate; each ON window is compared with
  the mean of its neighbouring OFF windows, which controls for walls growing during the round.

## 1. Guest output under load [measured]

Stock guest, one config per row, 120 s from the start of a round (180 s for the big arena):

| AIs | arena | walls mean / max | snapshot bytes p50 / max | msgs/s | KB/s | turns/s | guest CPU mean (p95 1 s) | snapshot cost (A/B) |
|---|---|---|---|---|---|---|---|---|
| 3 | 500 | 32 / 49 | 3 256 / 4 238 | 30.5 | 86 | 0.47 | 1.59 % (2.0) | 0.51 % = 0.17 ms/snapshot |
| 8 | 500 | 64 / 113 | 6 274 / 10 009 | 32.6 | 173 | 2.65 | 2.27 % (3.0) | 1.02 % = 0.34 ms |
| 16 | 500 | 98 / 238 | 9 300 / 21 146 | 34.5 | 276 | 4.39 | 2.84 % (4.4) | 1.36 % = 0.45 ms |
| 32 | 500 | 141 / 262 | 14 598 / 27 051 | 36.0 | 434 | 5.64 | 4.71 % (9.1) | 1.90 % = 0.63 ms |
| 32 | 1414 | 198 / 328 | 21 318 / 30 813 | 33.5 | 592 | 3.32 | 5.34 % (9.5) | 1.45 % = 0.48 ms * |

\* measured late in that round with 2-4 cycles alive and 60-160 walls.

Walls over round time (max per 10 s bucket of game time; alive cycles in brackets):

| t (s) | 0 | 10 | 20 | 30 | 40 | 60 | 80 | 100 | 120 | 150 | 180 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 32 AIs, 500 | 115 (30) | 262 (14) | 262 (9) | 195 (5) | 188 (4) | 129 (4) | 186 (3) | 151 (1) | round over | | |
| 32 AIs, 1414 | 44 (29) | 133 (27) | 235 (24) | 267 (19) | 289 (15) | 227 (15) | 316 (13) | 314 (10) | 271 (5) | 157 (4) | 126 (4) |
| 3 AIs, 500 | 9 (3) | 19 | 28 | 37 (2) | 39 | 36 | 40 | 44 | 49 (2) | | |

Walls rise with turns and fall 8 s after each death (`WALLS_STAY_UP_DELAY`), so AI rounds plateau at
a few hundred walls. Humans turn more often than IQ-60 AIs, so expect more walls per cycle with real
players; the per-wall costs below scale linearly.

What the data says:
- **Size model**: snapshot = ~80-85 B per wall + ~190 B per cycle (+ ~50 B header). Cycles are 15-23 %
  of the bytes. Wall count, not cycle count, drives size.
- **Cadence is 25 ms / 40 ms, not 33 ms.** `BRIDGE_RATE 30` on a 40 FPS server main loop gives
  alternating 25/40 ms gaps (p50 38 ms, mean 33). Gaps over 60 ms: < 0.1 %; the worst, 316 ms, showed
  up once in each run that contained a round end. The host's `Extrapolate` (cap 50 ms) already hides the age jitter
  when Minecraft samples at 20 Hz.
- **Collinear merging buys nothing.** Over all 654 sampled frames there are **0** pairs of same-cycle
  walls where one continues the other in the same direction: Armagetron starts a new wall only on a
  turn. (The host's own 32-block cell split is collinear on purpose, for entity tracking range; keep it.)
- **Head walls have `id: 0`.** Every id-0 wall in the samples is the current wall of an alive cycle,
  at most one per cycle (~11 per frame at 32 AIs); a head wall only occasionally already carries its
  real id. That is what `logic/WallKeys` (added by the host agent while this was measured) handles; the
  delta patch keys them as `-cycle`.
- **Bridge share of guest CPU is small.** Snapshots cost ~0.13 ms + ~2 us per wall each (A/B diff /
  30). The rest of the guest's 1.6-4.7 % is the simulation and AI, which grows with cycles.

Parse cost per snapshot (single thread, median of 7 batches after JIT warm-up; shared box):

| snapshot | Python `json.loads` | Gson `JsonParser` tree | host `Protocol.parse` (tree + records) | same frame as a delta, `Protocol.parse` |
|---|---|---|---|---|
| 3 AIs, 3.3 KB (36 walls) | 55 us | 40 us | 60 us | 21 us (0.6 KB) |
| 32 AIs, ~14.5 KB (151 walls) | 246 us | 213 us | 333 us | 50 us (2.3 KB) |
| 32 AIs, 27 KB (261 walls) | 469 us | 347 us | 635 us | 108 us (5.7 KB) |
| 32 AIs big arena, 31 KB (328 walls) | 792 us | 415 us | 817 us | 88 us (5.2 KB) |
| synthetic, 84 KB (1 000 walls) | 1.4 ms | 0.86 ms | 1.5 ms | 0.13 ms (7.4 KB) |
| synthetic, 795 KB (10 000 walls) | 15.5 ms | 8.3 ms | 14.0 ms | 0.14 ms (7.4 KB) |

Python is ~17-18 us/KB, Gson's tree ~13 us/KB, the host's `Protocol.parse` (tree + lenient record
extraction) ~20-26 us/KB, plus ~0.5-0.6 KB of garbage per KB parsed. The last column is the same frame
as a `delta` would carry it (all cycles, head walls only), parsed by the same code.

The host parses on its bridge thread, not the server thread; at 30 Hz the worst measured real frame is
2.5 % of one core. Full snapshots only start to hurt at thousands of walls (10 000 walls: 14 ms per
snapshot, ~42 % of a core at 30 Hz), which is exactly where deltas stay flat (~0.14 ms). Every
snapshot is parsed although Minecraft only samples 20 of the 30 per second, so a third of that work
is thrown away (harmless at this size).

## 2. Minecraft-side cost model

### What vanilla 26.3 does with a display entity [code]

From the decompiled 26.3 server (`ServerEntity`, `ChunkMap.TrackedEntity`, `SynchedEntityData`,
`Display`, `EntityTypes`):

- `BLOCK_DISPLAY`/`TEXT_DISPLAY`: `clientTrackingRange(10)` = 160 blocks (x
  `entity-broadcast-range-percentage` on a dedicated server; fixed on an integrated one; capped at view
  distance x 16), **horizontal distance only**, `updateInterval(1)`. `ChunkMap.tick()`
  visits every tracked entity every tick and calls `ServerEntity.sendChanges()` when its chunk is
  entity-ticking.
- Position: a `MoveEntity.Pos` (13 B incl. framing) only when the position changed, plus a forced
  zero-delta one every 60 ticks and a full `EntityPositionSync` (40 B) about every 400 ticks, **for
  every entity, static or not**.
- Entity data: `SynchedEntityData.set` marks an item dirty only if the value changed (except forced
  sets). `Display.setTransformation` sets 4 items, but a growing head wall only changes `scale`:
  `SetEntityData` = scale (14 B) + `setTransformationInterpolationDelay(0)` (forced, 3 B) = **~24 B per
  head-wall update**. `Specs` already skips unchanged setters, so steady walls send no data.
- New entity: one bundle per tracking player, `AddEntity` (~52 B) + `SetEntityData` with all non-default
  items (~88 B) = **~146 B**. Removal: one `RemoveEntities` packet *per entity* per player (round end
  sends hundreds at once).
- Ordering: `ChunkMap.tick()` runs inside `ServerLevel.tick` (before entity ticking). Fabric's
  `START_SERVER_TICK` is injected right before `tickChildren`, `END_SERVER_TICK` at the tail of
  `tickServer`. Packets are queued with flushing suspended and flushed once at the end of
  `tickChildren`. **armacraft renders at END, so its changes are sent one tick later than they could be.**

### Entities and packets per client [estimate from measured walls]

Entities at scale 1 (wall pieces at the default 32-block `max_piece_blocks`, incl. gaps) + rim pieces
+ one cycle + one name per alive cycle. Per tracking client per tick, if everything is in range:
`bytes = A x (24 + 13 + 13) + E x (13/60 + 40/401)`, `packets = 3A + E/60 + E/401`
(A = alive cycles, E = armacraft entities):

| config | walls mean / max | entities mean / max | alive mean | bytes/tick/client mean / max | packets/tick mean / max |
|---|---|---|---|---|---|
| 3 AIs, 500 | 32 / 49 | 215 / 332 | 2.3 | 184 / 213 | 11 / 13 |
| 8 AIs, 500 | 64 / 113 | 239 / 340 | 5.1 | 332 / 486 | 20 / 29 |
| 16 AIs, 500 | 99 / 238 | 359 / 634 | 9.1 | 571 / 922 | 34 / 55 |
| 32 AIs, 500 | 141 / 261 | 482 / 794 | 12.2 | 765 / 1 707 | 46 / 102 |
| 32 AIs, 1414 | 198 / 328 | 1 277 / 1 982 | 13.5 | 1 081 / 1 768 | 65 / 106 |

Plus creation: ~146 B per new piece per client, at (turns/s + A x speed / 32 blocks) new pieces per
second = ~17/s = ~2.5 KB/s at 32 AIs. So **~20-40 KB/s per client**, worst case. Tracking range trims
this: a client sees a 160-block radius, about a third of the 500-block arena and ~4 % of the 1414-block
one. Bandwidth is not the constraint.

Side effect worth knowing: because tracking is horizontal-distance based, the overview camera
(spectator high above the arena centre) **does not receive walls more than 160 blocks away**, which at
scale 1 is the outer ring of a 500-block arena and most of a 1414-block one. Raise
`entity-broadcast-range-percentage` (server.properties, up to 1000) or lower `scale` if the overview
should show the whole arena.

### Server tick cost

- armacraft's own per-tick work (measured, `tools/bench/HostTickBench`, a line-by-line mirror of
  `ArmaController.tick` -> `Extrapolate.forward` -> `ArenaRenderer.render` -> 4x `EntitySet.sync`
  with Minecraft types replaced by stand-ins; Display setters and entity creation excluded, they are
  O(changed)):

| snapshot | walls | entities (pieces) | alive | now: us / KB allocated per tick | 0002 memo: us / KB | 0002 + deltas: us | share of a 50 ms tick, now -> 0002 |
|---|---|---|---|---|---|---|---|
| 3 AIs, median frame | 36 | 142 | 2 | 91 / 106 | 14 / 9 | 15 | 0.2 % -> 0.03 % |
| 16 AIs, max frame | 238 | 549 | 11 | 600 / 395 | 87 / 64 | 71 | 1.2 % -> 0.2 % |
| 32 AIs, max frame | 261 | 704 | 13 | 638 / 475 | 90 / 76 | 107 | 1.3 % -> 0.2 % |
| 32 AIs big arena, max frame | 328 | 1 725 | 13 | 1 005 / 938 | 159 / 102 | 152 | 2.0 % -> 0.3 % |
| synthetic, 32 cycles | 1 000 | 1 465 | 32 | 2 129 / 997 | 342 / 149 | 353 | 4.3 % -> 0.7 % |
| synthetic, 32 cycles | 3 000 | 4 441 | 32 | 6 433 / 2 914 | 1 031 / 360 | 1 169 | 13 % -> 2.1 % |
| synthetic, 32 cycles | 10 000 | 14 808 | 32 | 27 457 / 9 719 | 5 590 / 1 140 | 4 398 | 55 % -> 11 % |

  Captured frames are the median- and max-walls frame of each run (`bench_guest.py pick`); synthetic
  frames come from `bench_guest.py synth` (head walls with id 0, finished walls 2-60 units). Both
  pipelines end each tick with the same number of tracked entities (checked by the bench). "now" is
  the host as of 00:08 (with `WallKeys`). Updates per tick (entities whose spec changed) were ~3 x
  alive cycles in every case: cycle, name tag, head wall. So at today's sizes the render path is 1-2 % of the tick and
  ~0.5-1 MB of garbage per tick; it becomes a real cost at thousands of walls (13 % at 3k, 55 % at
  10k), where the memo keeps it 5-6x lower. The memo pipeline is still O(pieces) (key strings in
  `WallKeys`, one map lookup per piece); deltas alone barely change the server-thread number because
  `Wall.equals` was already cheap.

- Vanilla per entity [estimate, not measured]: `ChunkMap.tick` + `sendChanges` (a few allocations, no
  packet most ticks) plus `ServerLevel` entity ticking (`Display.tick` is trivial server-side):
  roughly 0.5-1.5 us per entity per tick, so ~0.5-1.5 ms/tick at 1 000 entities, 1-3 % of the 50 ms
  budget. To pin it down, run the game-test soak with spark or a tick-time probe; this box could not
  host a Minecraft server for this work.

### O(walls) per tick that could be O(changed)

| where | what scales with all walls | fix |
|---|---|---|
| host `ArenaRenderer.render` | rebuilds every wall's pieces: `WallLayout.pieces` (lists + key strings), `WallLayout.transform` (atan2/hypot), a new `WallSpec`, and `DyePalette.closest` (17 HSV conversions with cos/sin) **per wall per tick** | memo per wall key + colour cache (0002) |
| host `EntitySet.sync` x4 | `Reconcile.diff` copies both key sets into LinkedHashSets and builds add/keep/remove sets every tick | generation sweep + identity check (0002) |
| host `rimSpecs` | rim specs rebuilt every tick | build once per round (0002) |
| host `WallKeys.assign` | one key string + HashSet insert per wall per tick | small; could ride the memo (not patched) |
| host `EntitySet.rekeyPrefix` | copies the whole tracked key set per rekey (per turn) | small; index keys by prefix (not patched) |
| host `Extrapolate.forward` | copies the wall list per tick | small (us); fine |
| host `BridgeClient` | Gson-parses every full snapshot at 30 Hz, 1/3 never used | deltas (0003+0004) |
| guest `sg_BuildStateJson` | `snprintf("%.4f")` for every wall, 30x/s | deltas: format changed walls only (0003) |
| guest `sg_CollectCycles` | `std::find` over cycles for every wall, every frame (O(walls x cycles)) | tiny at these sizes; a set if cycles grow |
| vanilla tracking | `sendChanges` per entity per tick + forced resync every 60 / 400 ticks | inherent; only fewer entities help (bigger `max_piece_blocks` where range allows, LOD) |

## 3. Latency and smoothness budget

### Measured guest hops [measured]

`tools/bench_guest.py latency`: a bench host joins one bridge player on the bench container (2 teams:
the player + 1 AI, 500 arena) and sends 60 turns at random phase relative to the guest's frames (2 ms
polling). It times input sent -> `turn` event received, `turn` event -> first `state` whose direction
changed, and an idempotent re-join -> `joined` round trip ("echo": Docker port forward both ways +
waiting for the guest's next main-loop iteration, no simulation). Turns sent during a round's
countdown get no `turn` event within 1 s and are dropped from the stats (3 per round).

| hop (ms) | `DEDICATED_FPS 40` (default) mean / p50 / p95 / max | `DEDICATED_FPS 120` mean / p50 / p95 / max |
|---|---|---|
| input -> guest turns the cycle (`turn` event back at the host) | 9.6 / 9.9 / 13.7 / 15.8 | 3.6 / 4.1 / 5.1 / 5.4 |
| `turn` event -> first snapshot showing it | 10.5 / 12.8 / 25.6 / 26.0 | 16.9 / 17.5 / 30.9 / 33.2 |
| input -> first snapshot showing the turn | 20.1 / 19.6 / 38.4 / 39.8 | 20.4 / 20.6 / 35.1 / 37.9 |
| echo (re-join round trip), p50 / p95 / min | 8.4 / 13.7 / 1.1 | 4.1 / 5.4 / 0.9 |

How to read it: the guest only looks at bridge input once per main-loop iteration, and an idle
dedicated server iterates `DEDICATED_FPS x DEDICATED_FPS_IDLE_FACTOR` = 80 times a second, so input
waits 0-12.5 ms (plus ~1 ms of Docker port forwarding); the turn itself executes in that same
iteration. The "~13 ms" the guest reports is that wait's worst case: an earlier, phase-locked run
(inputs sent right after a snapshot arrived) gave 12.6 ms every time, and 4.2 ms at 120 FPS. Then the
snapshot cadence (25/40 ms) adds 0-26 ms before the turn is visible to the host. At 120 FPS the pickup
drops to ~4 ms but input -> snapshot stays at ~20 ms: with `BRIDGE_RATE 30` the wait just moves to
the next snapshot. Raising the rate too (e.g. 60) would be needed to cash it in (not measured).

### End-to-end budget, key press to pixels (LAN, one client) [code + estimate, guest hops measured]

Means, with the worst case of each hop in brackets (summing all worst cases is pessimistic):

| # | hop | now | with 0001 + `interp_ticks 2` | basis |
|---|---|---|---|---|
| 1 | key press -> input packet leaves the client | 25 (50) | 25 (50) | [code] key state sampled once per client tick |
| 2 | client -> Minecraft server, LAN | ~0.5 | ~0.5 | [estimate] |
| 3 | netty thread -> server thread (`handlePlayerInput` re-queues itself) | ~1 (a few ms if mid-tick) | same | [code] + [estimate] |
| 4 | bridge writer -> guest picks up the input and turns | 9.6 (16) | 9.6 (16) | [measured] incl. Docker forward both ways |
| 5 | turn -> next snapshot leaves the guest | 10.5 (26) | 10.5 (26) | [measured] |
| 6 | snapshot -> parsed on the host's bridge thread | ~1 | ~1 | [measured] parse, [estimate] transport |
| 7 | wait for the next Minecraft tick to sample `latestState` | 25 (50) | 25 (50) | [code] |
| 8 | tick -> display packets flushed | ~51 (55) | ~1 (5) | [code] END vs START tick |
| 9 | server -> client, applied on the client main thread (next frame at 60 FPS) | ~9 (17) | ~9 (17) | [estimate] |
| 10 | first interpolation step at the next client tick | 25 (50) | 25 (50) | [code] |
| | **key press -> cycle starts turning on screen** | **~158 (~270)** | **~108 (~220)** | |
| 11 | ... -> cycle settled on the new heading | +100 | +50 | [code] (N-1) ticks of interpolation |

The guest and bridge (hops 4-6) are ~21 ms of ~158. Minecraft's 20 Hz tick shows up three times
(client input sampling, server sampling, client interpolation step: ~75 ms of the mean) plus the
avoidable extra tick (hop 8). Only hop 8 and the interpolation length are cheap to change; the
three tick quantisations go away only with a higher tick rate (`/tick rate`), which also changes the
client's tick rate and doubles world cost.

### Smoothness notes [code]

- Interpolation lag. `LinearInterpolationHandler` (cycle/name position) and the display transformation
  interpolation (head-wall growth) both restart on every update and cover `1/remaining` of the gap
  per tick. With one update per tick and N interpolation ticks the visual settles **(N-1) ticks behind
  the target**: 100 ms at the default `interp_ticks 3`, 50 ms at 2. Cycles and head walls lag by the
  same amount, so they stay attached. The chase cam (`camera.interp_ticks 5`) trails 200 ms, i.e. it
  sits about 6 blocks further back than configured at 30 blocks/s, and swings late after turns.
- Updating head walls (and cycles) every N ticks with interpolation N gives exactly linear motion and
  1/N of the packets, at a lag of N ticks instead of N-1. Packet count is not a problem at these sizes
  (section 2), so this only makes sense for WAN clients; not recommended now.
- Turns: the client cuts the corner, because the cycle lerps from its lagging position straight to the
  post-turn target. And the new head wall spawns at full length (entity creation is never
  interpolated) while the cycle visual is still ~2 ticks short of the corner, so the new wall pokes out
  ahead of the cycle for ~100 ms. Fix idea (not patched): spawn new head-wall pieces at zero length and
  let the next update grow them (one extra 24 B packet per turn).
- Quick taps can be lost: the vanilla client samples key state once per client tick
  (`LocalPlayer.sendChanges`), so a tap shorter than ~50 ms between two ticks never reaches the server.
  Nothing server-side can recover it; worth a line in the player help.
- Snapshot age at sampling: 25/40 ms cadence vs 50 ms ticks; already compensated by `Extrapolate`.
  `BRIDGE_RATE 40` would make it uniform (25 ms) at +33 % bytes; not worth it on its own.

## 4. Recommendations, ranked by impact / effort

| # | change | impact | effort | evidence | patch |
|---|---|---|---|---|---|
| 1 | Render at `START_SERVER_TICK` | -50 ms on every visual update, for every player | 1 line | [code] tick ordering, Fabric injection points verified in the jars | 0001 |
| 2 | `interp_ticks` 3 -> 2 (and `camera.interp_ticks` 5 -> 3) | -50 ms steady-state lag on cycles/walls (camera -100 ms) | config | [code] interpolation math; [measured] snapshot cadence shows extrapolation still covers the jitter | none (`interp_ticks` / `camera.interp_ticks` in `config/armacraft.json`; no command sets them, so restart) |
| 3 | Render memo + generation sweep | server-thread time 6-7x and garbage 6-9x lower per tick (0.64 -> 0.09 ms at 32 AIs; 6.4 -> 1.0 ms at 3 000 walls) | small, contained in 2 files | [measured] mirror bench | 0002 |
| 4 | `delta` protocol feature (opt-in, v1 untouched) | -71..76 % bridge bytes; guest snapshot CPU -60 % (0.90 -> 0.36 ms per snapshot at ~270 walls); host parse proportional to changed walls; makes a remote guest (uk server) cheap | medium, 2 sides | [measured] built and verified frame-by-frame against v1 | 0003 + 0004 |
| 5 | `DEDICATED_FPS 120` (+ a higher `BRIDGE_RATE`) | input pickup 9.6 -> 3.6 ms and a steadier 30/35 ms snapshot cadence, but input -> snapshot stays ~20 ms unless `BRIDGE_RATE` rises too, and guest CPU went 4.7 -> 8.5 % of a core (32 AIs, different rounds). Worth ~10 ms at best | config | [measured] | none |
| 6 | Overview visibility: `entity-broadcast-range-percentage` or smaller `scale` | correctness of the overview, not speed | config | [code] | none |
| 7 | Entity budget / LOD (cap pieces, coarser cells far from any player) | only matters past ~2-3k entities | medium | [estimate] | not written: no measured need |
| 8 | Head-wall grow-in on spawn | removes the ~100 ms "wall ahead of cycle" artifact after turns | small | [code] | not written |
| - | Merge collinear segments | nothing to merge (0 pairs measured) | - | [measured] | no |
| - | Binary encoding | JSON costs < 2 % of a core on the guest and 2.5 % on a background host thread at measured sizes; deltas fix the large-wall case without changing the format | high | [measured] | no |

Not recommended now: throttling head-wall updates to every N ticks (saves packets nobody is short of,
costs smoothness or latency), and a higher Minecraft tick rate (`/tick rate 40` would halve every tick
quantisation hop including client input sampling, but doubles world cost; an experiment for a
dedicated arena server, not a default).

## 5. Patches (unapplied)

All in `docs/perf-patches/`, each with a header saying why, what, base version and how it was checked.
Apply from the repo root with `git apply <file>`. `host/` files are CRLF, and the host patches carry
CRLF context; if a checkout normalised them, use `git apply --ignore-whitespace`.

| patch | files | checked |
|---|---|---|
| `0001-host-render-at-tick-start.patch` | host `Armacraft.java` | `git apply --check`; host main + test + gametest sources compile (javac against the loom/Gradle cache jars). Not run in a server. |
| `0002-host-render-memo.patch` | host `render/ArenaRenderer.java`, `render/EntitySet.java` | same, plus the 60 host unit tests pass with 0001+0002+0004 applied; the bench mirror of the memo pipeline ends every tick with the same number of tracked entities as the current one. Not run in a server. |
| `0003-proto-delta-guest.patch` | `guest/src/gBridge.cpp`, `PROTOCOL.md` | built (image `armacraft-guest:perf-delta`, g++ -std=c++11, no warnings in gBridge.cpp); folded deltas == v1 walls on 3 567/3 567 frames incl. a round change with the final patch, and on 7 142/7 142 with the previous revision (same diff logic; the host hello could not switch back yet); CPU A/B. |
| `0004-proto-delta-host.patch` | host `bridge/Msg.java`, `Protocol.java`, `BridgeClient.java` | `git apply --check`; compiles; host unit tests pass; a scratch check fed delta lines through `BridgeClient.handleLine` (keyframe, in-place head update, record identity kept, removed ids, keyframe replace, v1 state still accepted). Not run end-to-end in Minecraft against 0003. |

Bases: host files as of 2026-10-02 00:01-00:08 BST. `host/` was being edited by another agent while
this was written (`WallKeys` and `rekeyPrefix` landed mid-way; 0002 is rebased onto them), so re-run
`git apply --check` before applying.

## 6. Reproduce

```sh
# pin the image so a rebuild of :dev cannot change it mid-run
docker tag armacraft-guest:dev armacraft-guest:bench
B="--image armacraft-guest:bench"
# guest sweep (own container on 4554/4555; never touches armacraft-guest); ~20 min
python tools/bench_guest.py sweep $B --ais 3,8,16,32 --size-factor 0 --duration 120 --ab-windows 8 --out out/sf0
python tools/bench_guest.py sweep $B --ais 32 --size-factor 3 --duration 180 --ab-windows 8 --out out/sf3
python tools/bench_guest.py report --results out/sf0/results.json
# bridge latency (+ DEDICATED_FPS experiment, + its CPU cost)
python tools/bench_guest.py latency $B --ais 2 --turns 60 --out out/lat40
python tools/bench_guest.py latency $B --ais 2 --turns 60 --set DEDICATED_FPS=120 --out out/lat120
python tools/bench_guest.py sweep $B --ais 32 --duration 90 --ab-windows 0 --set DEDICATED_FPS=120 --out out/fps120
# Python + Java parse, host render mirror (captured median/max frames + synthetic sizes)
python tools/bench_guest.py parse out/sf0/samples_32ai.jsonl
python tools/bench_guest.py pick out/sf0/samples_*ai.jsonl out/sf3/samples_32ai.jsonl --out out/pick.jsonl
python tools/bench_guest.py synth --cycles 32 --walls 100,300,1000,3000,10000 --out out/synth.jsonl
python tools/bench/run_java_bench.py out/pick.jsonl out/synth.jsonl
# delta patch: copy guest/, apply 0003 there, docker build -t armacraft-guest:perf-delta <copy>, then
python tools/bench_guest.py deltacheck --image armacraft-guest:perf-delta --ais 8 --duration 120
python tools/bench_guest.py sweep --image armacraft-guest:perf-delta --ais 32 --size-factor 3 --duration 60 --ab-windows 17 --ab-modes full,delta
```

Left behind on this box: image tags `armacraft-guest:bench` (same image as `:dev` at 23:28) and
`armacraft-guest:perf-delta` (guest + 0003). No containers. `docker rmi` both when done with them.

## Not measured / open

- Minecraft server tick time with real display entities (needs a running server + spark); client FPS
  with ~1-2k block displays; WAN latency.
- Human play: more turns per cycle than IQ-60 AIs, so more walls; the per-wall numbers above scale.
- The full key-press -> pixels path was not timed end to end; the budget combines measured guest
  hops with code-derived Minecraft hops.

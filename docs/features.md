# armacraft: cross-game feature research

Research only, 2026-10-01. Nothing in this document is built. Every item below is a **proposal**, ranked
by value per hour. Each one is grounded in a mechanism read in source.

## Sources and how sure each claim is

| Source | Ref | Used for |
|---|---|---|
| Bridge (guest) | `guest/src/gBridge.cpp`, `guest/patches/0001-armacraft-bridge-hooks.patch` | what is exported today, hook points, limits |
| Host mod | `host/src/main/java/gl/syn/armacraft/**` (read-only, mid-edit by another agent) | renderer, sessions, input, TNT mixin |
| Upstream 0.2.9.3.0 | gitlab armagetronad, tag `v0.2.9.3.0` = `036daaf3` (same pin as the Dockerfile) | maps, zones, recording, client, camera, resources |
| sty+ct+ap | branch `hack-0.2.8-sty+ct+ap` @ `8f7bdadb` (2026-09-01); `major_version` says `0.2.9-sty+ct+ap` | zones v2, ladderlog, SVG output |
| Minecraft 26.3 | decompiled source already in the shared scratch dir (`mcsrc/`) | HUD, packets, sounds, entities |
| universal-modder | `skills/mashup-mods/SKILL.md`, `knowledge/games/gta-v/minecraft-passthrough.md`, `knowledge/techniques/oracles-*.md` | passthrough patterns, oracles |
| RCL binaries | `C:\Users\Jamie\rcl-cfg\league_{server,client}`: printable-string scan only (no `strings` on this box, so a Python byte scan). Not executed, dir not modified. | feature 3 |

Labels used below:
- **[src]**: read in source at the cited file.
- **[obs]**: seen in artifacts other agents left in the shared scratch dir (logs, screenshots). I did not re-run them.
- **[inf]**: inferred, not confirmed.
- **[unv]**: unverified, with the check that would settle it.

What already exists (built; tested by other agents per their gametest/fake-guest logs, not re-run by me):
`round` (rim + map walls), 30 Hz `state`, spawn/turn/death/round_end/chat events, join/input/explode/cmd/chat,
the chase cam, TNT → `explode`, and cycle death → particles + sound.

## Findings that shape everything

1. **Zones are invisible in Minecraft today.** The bridge exports only `se_rimWalls` (`gBridge.cpp` `sg_BuildRoundJson`), and the guest config spawns a win zone after 40 s (`WIN_ZONE_MIN_ROUND_TIME 40`). RCL sumo is fortress-zone play (`rcl-sumobar.cfg`). Zones are the top gap. [src]
2. **Map walls are not network objects.** Every client parses the map XML itself, again at every round (`gGame.cpp` GS_CREATE_GRID → `sg_ParseMap`). So a Minecraft build can become a map **at the next round**. Mid-round walls that real clients can see do not exist in 0.2.9; zones do (they are `eNetGameObject`s). [src]
3. **The current guest has no `SPAWN_ZONE`** (that is sty+ct only, `gZone.cpp:9610`). The bridge can still create zones directly: `tNEW(gDeathZoneHack)(grid,pos)` is exactly how the win zone gets created mid-round (`gGame.cpp:3815` → `gWinZone.cpp:72`). [src]
4. **sty+ct's ladderlog is enough to rebuild a match** with no game client: CYCLE_CREATED, PLAYER_GRIDPOS with `PLAYER_GRIDPOS_ON_TURN 1`, CYCLE_DESTROYED and ZONE_GRIDPOS. `SVG_OUTPUT_TIMING` writes a server-side `map.svg` you can diff against as ground truth. [src]
5. **A headless real client is proven to run.** A stock 0.2.9.3.0 GUI client under Xvfb + Mesa connected to the guest at ~250 FPS and listed the bridge player "Syn" on its scoreboard (`scratchpad/s14.png`, `client/Dockerfile`). [obs]
6. **The `league_*` binaries are not game clients.** They are a Rust matchmaking queue (details in feature 3). [src: strings]
7. **`.aarec` is a deterministic input recording,** not a state log. It only replays on the identical binary (`tRecorderInternal.cpp:651` warns "different version ... high risk of desync"). Useless for RCL archives; fine for armacraft's own sessions. [src]
8. **Vanilla HUD works in spectator mode.** Boss bar, action bar, titles, sidebar and the **locator bar** all render. The locator bar measures bearings from the *camera entity*, which for a rider is the chase cam (`Hud.java:540-570, 1321`; `LocatorBar` uses `getCameraEntity`). That gives a free heading-relative radar. [src: mcsrc]
9. **Bridge line limit is 64 KB, and an oversize line drops the host** (`gBridge.cpp:95, 762-767`). Any message that carries map XML must chunk or raise the limit. [src]
10. **The host parser tolerates extra array elements** (`Protocol.numbers`: `size() < n`). So `static` segments can gain a 5th field (height) without breaking today's host. [src]

## Ranked list

| # | Feature | Hours | Guest change | Host change |
|---|---|---|---|---|
| 1 | Zones over the bridge + ring rendering | 4 | yes (small) | yes |
| 2 | Rider HUD: rubber/speed action bar, countdown/winner titles, zone boss bar, locator radar, scores | 4 | tiny (score, brake fields) | yes |
| 3 | Minecraft build → `.aamap.xml`, live at the next round | 6 | yes (`map` message) | yes |
| 4 | Watch RCL in Minecraft: sty+ct ladderlog → protocol adapter (live or archived) | 7 | none (separate adapter) | none |
| 5 | Arrows, mobs and light walls (host-only, reuses `explode`) | 3–4 | none | yes |
| 6 | Kill-cam / instant replay from a host-side snapshot buffer | 3 | none | yes |
| 7 | Camera modes: server-custom speed cam, first person, top-down, glance back | 2–3 | tiny (`settings` reply) | yes |
| 8 | Sound + particle pass | 2 | none | yes |
| 9 | Mid-round hazard blocks → guest death zones | 3 (after #1) | yes | yes |
| 10 | RCL practice preset (sumobar settings + map vs AIs) | 2 (after #1) | config only | command |
| 11 | Trail fossils: freeze a round's walls as glass blocks | 1–2 | none | yes |
| 12 | Client-mode bridge: headless real client, spectator first | 8–12 (+6 to play) | new build target | small |
| 13 | `.aarec` record/replay of armacraft sessions | 6–10 | yes | small |
| 14 | Server-only mid-round rim walls (experimental, bridge-only arenas) | 4–6 | yes (risky) | yes |

Effort assumes the existing repo, fake guest and gametest harness. It covers code plus one oracle, not polish.

---

## 1. Zones over the bridge, rendered as rings

**What the player sees.** A ring of glowing glass segments appears in the arena. Green is a win zone, red
a death zone, team colour a fortress. It rotates, grows or shrinks like the Armagetron zone, and a
fortress fades grey as it is conquered.

**Mechanism: guest.**
- Every alive zone lives in the grid as an `eGameObject`. Collect them with `dynamic_cast<gZone*>` over `grid->GameObjects()`, the same loop as `sg_CollectCycles`.
- 0.2.9 `gZone` (`src/tron/gWinZone.h`) has public `GetPosition()`, `GetVelocity()`, `GetRadius()`, `GetExpansionSpeed()` and `GetRotationSpeed()`. `color_` is protected, and `gBaseZoneHack::conquered_` / `currentState_` are private.
- Patch: add `friend class gBridge;` to `gZone` and `gBaseZoneHack`. That is the same pattern the hook patch already uses for `gCycleMovement` and `gNetPlayerWall`.
- Type comes from `dynamic_cast` to `gWinZoneHack` / `gDeathZoneHack` / `gBaseZoneHack`. Team is `eNetGameObject::Team()`.
- Emit `zone_spawn` and `zone_gone` events by diffing ids, like `sg_Track` does for cycles.
- Add `height` per static segment: `gWallRim::Height()` (`gWall.cpp:512`); map `<Wall height>` defaults to 10000 in `gParser::parseWall`.

**Mechanism: host.**
- New `Msg.Zone` record and a `ZoneSpec` in `render/Specs.java`: N block displays (N = 11, matching `ZONE_SEGMENTS`) placed on the circle, stained glass by `DyePalette.closest`, glowing.
- Per tick, extrapolate `r += dr*age` (same idea as `Extrapolate.forward`).
- Rotate the ring by updating segment yaw every ~10 ticks with 10-tick interpolation, which keeps packet cost low.
- Height comes from `ZONE_HEIGHT` (fetched via the `get` message in #7) or a config default.

**Protocol additions** (all optional fields, so v1 hosts ignore them):
```json
"zones":[{"id":812,"type":"fortress","x":250,"y":250,"r":18.5,"dr":0,"rot":0.3,
          "color":[0.2,0.4,1],"team":"Syn","conquered":0.35}]      // in every `state`
{"type":"event","ev":"zone_spawn","id":812,"type_":"win","x":..,"y":..,"r":..}
"static":[[x1,y1,x2,y2,height], ...]                                 // 5th element optional
```
`type` is a free string, so sty+ct effects (`rubber`, `teleport`, `sumo`, `koh`, ...) from feature 4 render
through the same path with a colour fallback.

**Effort.** Guest 1.5 h, host 2.5 h.

**Risks.**
- Zone geometry is circles only in both branches (`ShapeCircle`); that's fine.
- On a dedicated server, zone position comes from `tFunction`s evaluated at `se_GameTime()`. Use `GetPosition()`, not raw fields.
- Fortress visuals depend on conquest state, which needs the friend patch. Without it you get position, radius and colour only.

**Evidence it works.**
- Gametest against `fake_guest.py` extended with one zone: assert 11 `armacraft.zone` entities whose positions sit within 0.1 block of radius r.
- Live guest: at t ≈ 40 s the win zone ring appears.
- A same-moment screenshot from the Xvfb client (feature 12 setup) shows the zone at the same arena position.

---

## 2. Rider HUD (vanilla client, server-side only)

**What the player sees.**
- Action bar: `RUBBER ▮▮▮▯▯  SPEED 34  BRAKE ▮▮▯  3 alive`.
- Titles `3 / 2 / 1 / GO` at round start, `Syn wins` at round end, and a subtitle `killed by glTron` on death.
- A boss bar for the nearest zone, e.g. `WIN ZONE 42 m`, or a fortress conquest bar.
- Sidebar scores, and a tab-list header with round and map.
- Coloured markers on the locator bar showing where every other cycle is, relative to your heading.

**Mechanism: host.** All class names verified in `mcsrc` (26.3):
- **Action bar:** `ClientboundSetActionBarTextPacket` (or `ServerPlayer.sendSystemMessage(c, true)`). Data: `cycles[].rubber` (already in protocol, 0 = fresh, 1 = used up), `speed`, `brake`.
- **Titles:** `ClientboundSetTitleTextPacket` / `SetSubtitleTextPacket` / `SetTitlesAnimationPacket`.
  - The countdown needs no guest change: `state.t` runs negative before the start (fake-observer log: `t=-3.40 ... -0.37`). [obs]
  - The killer comes from `death.killer`.
- **Boss bar:** `ServerBossEvent`. Progress = distance to zone, or `zones[].conquered`.
- **Sidebar:** server `Scoreboard` objective in `DisplaySlot.SIDEBAR`. **Tab list:** `ClientboundTabListPacket`.
- **Locator radar:** `ClientboundTrackedWaypointPacket.addWaypointPosition(uuid, Waypoint.Icon{color}, Vec3i)` per enemy cycle, updated each tick.
  - `Hud.nextContextualInfoState()` shows the locator bar whenever waypoints exist.
  - `extractHotbarAndDecorations` draws the contextual bar in spectator mode too.
  - `LocatorBar` measures yaw from `getCameraEntity()`. The rider's camera is the chase-cam display, so bearings are relative to the cycle.

**Mechanism: guest.** Add `score` (`ePlayerNetID::TotalScore()`, `ePlayer.h:396`), `team`, and `brake_res`
(`GetBrakingReservoir()`, `gCycleMovement.h:262`) to `cycles[]`.

**Effort.** Host 3 h, guest 0.5 h.

**Risks.**
- Action-bar text expires after ~2 s, so resend every 10 ticks.
- The locator bar could also pick up vanilla player waypoints from non-riders near the arena. Riders are spectators, who don't transmit, but other players do; give armacraft waypoints distinct UUIDs and colours.
- Titles spam if sent per tick. Send them on state transitions only.

**Evidence it works.**
- Gametest: capture outbound packets for a riding fake player (the gametest already drives sessions) and assert one countdown title per integer second while `t<0`, and one action-bar packet per 10 ticks.
- Manual: a screenshot from a vanilla 26.3 client while riding, with the bar visibly changing as you grind a wall.

---

## 3. Minecraft build → Armagetron map

**What the player sees.** Build a maze on the arena floor, then run `/arma export maze1`. At the next round,
Armagetron's arena *is* that maze for everyone: Minecraft riders, AIs and real Armagetron clients. Gold
blocks become win zones, magma blocks death zones, a beacon a fortress, and a glazed terracotta block a
spawn facing the way it points.

### Format (verified)
- DTD `AATeam/map-0.2.8.0_rc4.dtd` ships with 0.2.9.3.0 (`resource/proto/AATeam/`).
  - `Resource(type=aamap,name,version,author,category) > Map(version) > Settings? > World > Field(logicalBox) > Axes?, (Spawn | Wall | Zone)*`.
  - `Wall(height?) > Point(x,y)+`; `Spawn x,y,xdir,ydir`.
  - `Zone effect=win|death|fortress > ShapeCircle radius,growth > Point`.
- The parser validates against the DTD (`gParser.cpp:1060`, `XML_PARSE_DTDVALID`). Emit exactly `<!DOCTYPE Resource SYSTEM "AATeam/map-0.2.8.0_rc4.dtd">`.
- **Path check:** a server throws `$resource_file_wrong_place` unless the file path equals `author/category/name-version.aamap.xml` from the root attributes (`gParser.cpp:1079-1113`). Names must be path-safe.
- **Coordinates:** the parser multiplies every map coordinate by `sizeMultiplier = 2^(SIZE_FACTOR/2)` (`gGame.cpp:1296`). The guest uses `SIZE_FACTOR -4`, so ×0.25 and the default square is 125×125 grid units, matching the observed `bounds=[0,0,125,125]`.
  - Export: `map = grid / mult`, where `grid = (mc_x-ax)/s, -(mc_z-az)/s` (PROTOCOL mapping).
  - The guest must report `mult`; add it to `hello` or `round`.
- `Axes` defaults to 4 (90° turns). Spawns must use axis directions.

### How it loads (verified)
- `MAP_FILE` is an `nSettingItemWatched` (`gGame.cpp:130`), synced to clients. It is re-parsed at **every** round: `GS_CREATE_GRID` → `sg_ParseMap` (`gGame.cpp:3248`).
- When it changes, the server first `Verify()`s it and reverts to the last good map on failure (`gGame.cpp:3214-3234`). A broken export can't brick the guest.
- Locating: `tResourceManager::locateResource` (`tResourceManager.cpp`).
  1. Local read path first.
  2. Else `MAP_FILE`'s `file(http://uri)` suffix.
  3. Then `RESOURCE_REPOSITORY_SERVER` (an `nSettingItem`, `gStuff.cpp:48`, so the server pushes it to clients).
  4. Then `RESOURCE_REPOSITORY_CLIENT`.
  Downloads are saved to `tDirectories::Resource().GetWritePath(file)`.
- **Clients cache by filename.** Every export needs a new `version` (e.g. a timestamp), or clients keep the old file.
- **HTTP vs HTTPS:** with libcurl, HTTPS is verified. Without it, libxml nanohttp is used (HTTP only). The guest Dockerfile has no libcurl-dev, so the guest build is HTTP-only [inf from Dockerfile deps]. Old 0.2.8 clients are HTTP-only too. Serve maps over plain HTTP, or provide both.

### Mechanism
- **Host:** `/arma export <name> [radius]`.
  1. Scan a 1-block-high slice at `anchor.y` (plus `anchor.y+1`, so a 2-high build counts) inside the rim bounds.
  2. Classify each cell as wall / win / death / fortress / spawn / empty.
  3. Turn wall cells into outlines: emit the boundary edges between solid and empty cells, then merge collinear edges into loops.
  4. Write each loop as one `<Wall>`, plus an outer rim rectangle.
  5. Escape attributes, then send the XML to the guest.
- **Guest:** new `{"type":"map","name":"armacraft/builds/maze1-20261001T2310.aamap.xml","xml":"..."}`.
  - Write to `tDirectories::Resource().GetWritePath(name)`, the same place downloads go. The entrypoint points `--autoresourcedir` at `/var/armagetronad/resource`, and the Dockerfile chowns it to `nobody`.
  - Then `MAP_FILE <name>` at owner level (reuse `sg_HandleCmd`).
  - Reply `{"type":"map_ok"}` or an error.
  - The next `round` already carries the new walls, because `sg_BuildRoundJson` reads `se_rimWalls`, which `gParser::DrawRim` fills (`eAdvWall.cpp:45-60`).
- **Line limit:** a large map exceeds the 64 KB `sg_maxLine`, and the host gets dropped. Chunk it (`map_chunk` with seq/total) or give `map` a separate cap.
- **Real clients:** set `RESOURCE_REPOSITORY_SERVER` to an HTTP prefix that mirrors the write dir (see integrations), or use `MAP_FILE name(http://.../name)`.

### Mid-round changes (what is and isn't possible)
- **Add/remove rim walls mid-round so real clients see them:** not possible in 0.2.9 or sty+ct. No command creates or removes `gWallRim` at runtime (grep for `DrawRim` / `tNEW(gWallRim)` hits only `gParser`), and walls aren't synced.
- **Queue and apply at the next round:** yes. This is the recommended "live sync": every MC block change marks the build dirty, and the export goes out during `round_end`.
- **Hazards mid-round, visible to everyone:** yes, as death zones (feature 9).
- **Server-only walls mid-round:** feature 14. It desyncs real clients.

**Effort.** Host exporter + command 3 h, guest `map` message 1.5 h, oracle 1.5 h.

**Risks.**
- Outline explosion: a checkerboard build becomes thousands of edges in `eGrid`. Cap the edge count (e.g. 2000) and refuse above it.
- Thin-wall semantics: a 1-block wall becomes two parallel walls one unit apart, which matches the look but is not a single "line" wall.
- Spawn placement inside builds: validate that each spawn is in an empty cell with ≥ N cells of clearance ahead.
- `SIZE_FACTOR` changes between export and load scale the map. Store `mult` in the map's `<Settings>`? Allowed settings in maps are not verified [unv: try `SIZE_FACTOR` in `<Settings>` and read the guest log].

**Evidence it works.**
- Round trip: export → guest → next `round.static`. Merge both into the same segment set and assert equality within 1e-3 (outline loops vs `sg_MergeSegments` output).
- The guest log shows no `$map_file_reverting`.
- An Xvfb client screenshot shows the maze.
- A validator pass, `xmllint --valid` with the shipped DTD, runs in the unit test.

---

## 4. Watch RCL matches in Minecraft (ladderlog adapter)

**What the player sees.** A live or archived RCL match plays out in the Minecraft arena: cycles, walls,
fortress zones and kill effects. Spectators fly around it, or follow a player's chase cam.

**Mechanism.** A small stdlib Python process that *is a guest* to the host. It speaks protocol v1 on 4535,
like `tools/fake_guest.py`, and is fed by a sty+ct ladderlog instead of a simulation. All formats are
verified in sty+ct source:

| Ladderlog line | Fields | Use |
|---|---|---|
| `CYCLE_CREATED` (`gCycle.cpp:2444`) | name, x, y, dx, dy, team, time | spawn, wall start |
| `PLAYER_GRIDPOS` (`ePlayer.cpp:8496-8536`; per turn with `PLAYER_GRIDPOS_ON_TURN 1`, `gCycle.cpp:3898,4022`) | name, x, y, dx, dy, speed, rubber, rubber_max, team, braking, brake_res, time | exact turn points → wall polylines; HUD |
| `CYCLE_DESTROYED` (`gCycle.cpp:4347`) | name, x, y, dx, dy, team, time, reason | death, end of trail |
| `ZONE_GRIDPOS` (`gZone.cpp:9676`) + `ZONE_CREATED`/`ZONE_COLLAPSED` | effect, id, name, r, dr, x, y, vx, vy, r, g, b | zones (feature 1 renderer) |
| `CURRENT_MAP`, `NEW_ROUND`, `ROUND_WINNER`, `DEATH_*` | | map fetch, round boundaries, killer |

- Coordinates are map units (`MapPosition` = position / sizeMultiplier). The adapter sends map units plus `mult`.
- Walls: for each cycle, a polyline from the spawn through each turn point to the current extrapolated position.
  - Trim the tail by `WALLS_LENGTH` (400 in `rcl-sumobar.cfg`).
  - Approximate death holes with `EXPLOSION_RADIUS` around `CYCLE_DESTROYED` points.
- Static walls: fetch the `CURRENT_MAP` file from the resource repository and parse `<Wall>`/`<Zone>` with the same code as feature 3, in reverse.

**Where it runs.**
- **Live:** on the host that runs the league server, as `tail -F` on its ladderlog. The bridge TCP goes over an SSH tunnel to the MC server on this PC; v1 has no auth, so never bind it publicly.
- **Archive:** the RCL memory notes say `/data/rcl/cache/rcl-watch/match-logs` already holds ~31 GB of match logs on uk [unv: check whether those logs include PLAYER_GRIDPOS lines; if they only hold scores and deaths, archives cannot be replayed spatially].

**Effort.** 7 h: parser + reconstruction 4 h, map fetch 1 h, oracle 2 h.

**Risks.**
- Needs sty+ct-family servers with `LADDERLOG_WRITE_PLAYER_GRIDPOS`, `_CYCLE_CREATED`, `_CYCLE_DESTROYED`, `_ZONE_GRIDPOS` and `PLAYER_GRIDPOS_ON_TURN 1`. Which fork the fleet runs is **unverified**: uk has an `armagetronad-rcl` workspace per the RCL notes. The `league_client` binary embeds sty+ct command names, which is suggestive [inf].
- Turning those flags on is a league-server config change. It goes through the RCL agent's commit-before-deploy rules, with Jamie's call.
- Log volume: ~100 B per turn × 8 players × ~4 turns/s ≈ 10 MB per hour of play. uk `/data` is the tight disk (20 GB guard); keep it bounded.
- Lag compensation and rubber are only sampled at turns/intervals. This is a viewer, not a referee.
- **Integrity:** a live 3D view in a Minecraft server that league players can join enables ghosting in team modes. Add a broadcast delay option (buffer N seconds), and keep MC chat out of the match.

**Evidence it works.**
- sty+ct `SVG_OUTPUT_TIMING` (`gGame.cpp:136, 3844`; `gSvgOutput.cpp`) writes the server's real walls to `var/map.svg` and logs `SVG_CREATED`.
- Run a local sty+ct server with AIs, then diff the adapter's reconstructed walls at that timestamp against the SVG paths. Pass = every SVG segment matched within 0.5 units.
- Then one archived RCL match renders end to end.

---

## 5. Arrows, mobs and light walls (host-only, using the existing `explode`)

**What the player sees.**
- Non-riding players around the arena shoot arrows. A hit kills the cycle in a burst, and arrows punch small holes in light walls.
- Zombies wandering in are run over by cycles, and fried when they touch a light wall.
- With "solid walls" on, mobs and players can't walk through light walls.

**Mechanism: host only. No guest change, because `explode` already holes walls and kills cycles in a radius
(`gBridge.cpp:1541-1610`).**
- **Arrows:** `Interaction.canBeHitByProjectile()` returns false (`Interaction.java:83`), and display entities have no hitbox, so collision has to be geometric.
  - Each tick, take `AbstractArrow`s in the arena AABB.
  - Map the segment from old to new position into arena coordinates, using the extrapolated `lastRendered` state so hits match what players see.
  - Hit a cycle's body box (2×0.8 blocks along the heading, `Specs.CycleSpec`) → `explode(x,y,r=0.5/scale)`, then discard the arrow.
  - Cross a wall segment → `explode` at the intersection with r = 0.3, which holes the wall (shoot-through gaps). Rate-limit per player.
- **Mobs:**
  - Mob AABB overlaps a cycle body → `mob.hurtServer(...)`; the cycle "runs it over" and the cycle is unaffected.
  - Mob crosses a wall segment → damage plus `ELECTRIC_SPARK` particles.
  - Option: mobs kill cycles via `explode`, as a hazard mode.
- **Solid walls (option):** place `minecraft:barrier` blocks along wall pieces as they grow (≈1.5 blocks/tick per cycle at speed 30, scale 1), and clear them on `round` and on wall disappearance.
  - This is the universal-modder GTA trick of host collision becoming barrier blocks, in reverse.
  - Gotchas from that note apply: proxy entities lose `Invisible` on first sync unless they have an effect, and mobs ignore invulnerable proxies.
- **TNT (exists):** `PrimedTntMixin` → `onExplosion` → `explode(r = power*2/scale)`. Gaps:
  - Vanilla TNT also cratered the MC arena floor. Add a mixin that cancels block damage inside the arena bounds.
  - Kills score as suicide. Add an optional `"by":"mc:<uuid>"` to `explode`, and have the guest `AddScore` the bridge player (`ePlayer.h:394`).
  - Real Armagetron clients see the holes and the deaths but **no blast**: `gExplosion` is not a net object (`gExplosion.h:38`); holes reach clients via wall sync (`sg_ServerSentHoles`, `gWall.cpp:2160-2199`).

**Effort.** 3–4 h for arrows + mobs; barrier walls +1.5 h.

**Risks.**
- Riders are spectators and can't shoot, so this is an audience mode. It changes match outcomes, so keep it off by default.
- Barrier churn on long walls: place only the head piece each tick, and keep walls at least scale ≥ 1, or barriers merge adjacent walls.

**Evidence it works.** A gametest spawns an arrow with a velocity aimed at a fake-guest cycle. Assert that
exactly one `explode` within 0.5 units of the cycle reaches `fake_guest.py`, and that its next `state` has
that cycle `alive:false`.

---

## 6. Kill-cam / instant replay (host-only)

**What the player sees.** On death, the last 5 s replay from the killer's chase cam at half speed, then the
overview.

**Mechanism.**
- The host already receives full snapshots (`state` is a full world, not a delta) at 30 Hz. Keep a ring buffer of the last 10 s: ~300 snapshots × a few KB.
- In `DEATHCAM`, render the buffered states into a **second anchor** (a replay arena offset by +Y 64 or in another dimension) with its own `ArenaRenderer` and `EntitySet` tag, so live rendering for others is untouched.
- Point the dead player's cam at the killer's `CycleSpec`. `death.killer` gives the id.
- **Highlight reel:** dump the buffer to a JSONL file on `round_end`. The fake guest can replay JSONL as a guest, which gives a director/recording path, as in universal-modder's director pattern.

**Effort.** 3 h.

**Risks.**
- A second arena doubles the entity count for that viewer only, inside view range. Pick a replay anchor far enough away.
- Chunk loading at the replay anchor: reuse `attachCamera`'s teleport-to-load logic.

**Evidence it works.** A gametest kills the rider via fake-guest `explode`. Assert the camera entity moves
to replay-arena coordinates within 3 ticks, and that the replay-arena wall pieces match the buffered
snapshot from t-5 s.

---

## 7. Camera modes

**What the player sees.**
- The chase cam pulls back with speed, as in Armagetron.
- **W** cycles chase → first person → top-down.
- **Hold S** glances back.

**Mechanism.**
- Armagetron's modes (`eCamera.h:31`): `IN, CUSTOM, FREE, FOLLOW, SMART, SERVER_CUSTOM, SMART_IN`.
- The server-custom cam is `back = CAMERA_SERVER_CUSTOM_BACK + speed*..._BACK_FROMSPEED`, with rise likewise, plus pitch (`eCamera.cpp:238-269, 1549-1567`; `nSettingItem`, so the server dictates it).
- Guest: generic `{"type":"get","names":[...]}` → `{"type":"settings","values":{...}}` using `tConfItemBase::GetConfItemMap()` (public, `tConfiguration.h:245`) and `WriteVal`. No new patches.
  - The same message serves `ZONE_SEGMENTS`, `ZONE_HEIGHT`, `CYCLE_RUBBER`, `WALLS_LENGTH` and `SIZE_FACTOR`.
- Host:
  - `SessionManager.chaseCam` uses `back/up/pitch` from those values × scale.
  - First person: cam at the cycle nose, pitch ≈ 5°, interp 1–2.
  - Top-down: pitch 90°, height ∝ speed.
  - Glance: yaw + 180 while S is held.
  - `InputTracker` gains `forward()` and `backward()`; the vanilla `Input` record carries them and the mixin already receives it.

**Effort.** 2–3 h.

**Risks.** First person at 20 Hz with interpolation looks floaty on 90° turns. Use interp 1 and snap yaw on
`turn` events.

**Evidence it works.** A unit test of the camera formula against the eCamera numbers. A gametest asserts
the cam spec changes per mode keypress.

---

## 8. Sound and particle pass

**What the player hears and sees.**
- An engine drone whose pitch follows speed.
- A grind sound and sparks when rubber rises (Armagetron's `gSparks` moment).
- A tick on each turn, a chime when a zone spawns, a level-up on a round win, and the existing explosion.

**Mechanism.** Positional `level.playSound` at the cycle, sent to riders. The client's listener is the
camera, so it pans correctly from the chase cam. All names exist in 26.3 `SoundEvents` / `ParticleTypes`:

| Event | Sound / particle |
|---|---|
| engine | `BEACON_AMBIENT`, re-triggered every 3 s, pitch 0.5–2.0 ∝ speed |
| rubber rising | `GRINDSTONE_USE` + `ELECTRIC_SPARK` at the cycle nose |
| turn | `NOTE_BLOCK_HAT` |
| zone spawn / collapse | `BEACON_ACTIVATE` / `BEACON_DEACTIVATE` |
| round win | `PLAYER_LEVELUP` |
| countdown | `NOTE_BLOCK_BIT` |

**Effort.** 2 h. **Risks.** The drone loop is one-shots, so tune by ear. Server-side only; no resource pack.
**Evidence.** Manual listen test, plus a gametest asserting a `ClientboundSoundPacket` count per turn event.

---

## 9. Mid-round hazard blocks → guest death zones

**What the player sees.** A builder drops a magma block in the arena mid-round, and a red death zone
appears there for everyone, real Armagetron clients included. Breaking the block collapses it.

**Mechanism.**
- Guest: `{"type":"zone","op":"add","key":"mc:blk:x,y,z","effect":"death","x":..,"y":..,"r":..}` → `tNEW(gDeathZoneHack)(grid, pos)`, then `SetRadius`, `SetRotationSpeed`, `RequestSync`. That is exactly the parser's sequence (`gParser.cpp:497-519`).
- `op:remove` → `SetExpansionSpeed(-r*2)`. Zones delete themselves when the radius reaches 0 (`gWinZone.cpp:332`).
- `op:move` → `SetPosition` + `RequestSync`, which supports mob-following hazards.
- Host: block place/break events in the arena slice.

**Effort.** 3 h after #1.

**Risks.**
- Each zone is a synced net object. Cap it at ~32 per round.
- Death zones kill rather than block, which is a different feel from walls. It's honest about what 0.2.9 can sync.

**Evidence it works.** A gametest places a magma block, the fake guest receives `zone add`, and the next
state has the zone. Live guest: the Xvfb client screenshot shows the red zone.

---

## 10. RCL practice preset

**What the player sees.** `/arma preset sumobar` turns the arena into the RCL sumo bar setup, with AIs to
practise against.

**Mechanism.**
- Send `rcl-sumobar.cfg` through `cmd`, which allows multi-line, owner level (`gBridge.cpp:1612-1644`).
- Strip `ACCESS_LEVEL*` and spam lines.
- `MAP_FILE tourney/sumobar/8player_sumo-1.aamap.xml` downloads through `RESOURCE_REPOSITORY_SERVER http://resource.armagetronad.net/resource/` [unv: whether that map is hosted there; the guest log shows a download or a revert].
- Add `NUM_AIS`/`AI_IQ`.
- Depends on #1, because sumo is fortress zones, and benefits from #2's conquest boss bar.

**Effort.** 2 h. **Risks.** The guest container needs outbound HTTP for the download; otherwise mount the
map into the resource dir.

**Evidence.** The next `round.static` matches the sumobar map's `<Wall>` set, and one fortress zone per team
appears in `zones`.

---

## 11. Trail fossils

**What the player sees.** At round end, the round's light walls freeze into real stained-glass blocks: in
place, or stacked as a sculpture in a gallery, one layer per round.

**Mechanism.** On `round_end`, take the last `state.walls`. For each solid piece from `WallLayout.pieces`,
rasterize cells (Bresenham at scale) and `setBlock` `DyePalette` glass, at the anchor or a gallery anchor
+Y·round.

**Effort.** 1–2 h. **Risks.** Block writes are persistent; gate behind a command, and never write inside
the live arena slice. **Evidence.** A block count equals the rasterized cell count in the gametest.

---

## 12. Client-mode bridge: Minecraft on real (RCL) servers

**What the player sees.** `/arma watch <server>` shows a live public match in Minecraft. Later, optionally,
`/arma play <server>` lets you ride on it.

### Feasibility (headless client)
- A client build needs SDL1.2-compat + GL (`--enable-glout`, the inverse of `--enable-dedicated`; `configure.ac:88-136`). There is no renderless client target.
- **Proven route:** Xvfb + Mesa llvmpipe. `client/Dockerfile` builds a stock 0.2.9.3.0 client on the guest image, and it connected and played at ~250 FPS (`s14.png`). [obs]
- Cut CPU by forcing `sr_glOut=false` after init. The client already runs that way when unfocused (`rScreen.cpp:1141`, `rSysdep.cpp:479`).
- Bridge hooks: `sg_BridgeFrame` sits in `sg_EnterGameCore`, which clients run too. On a client, `sg_netPlayerWalls` and the cycles exist as synced objects, so `state` export works unchanged [inf; the client-side wall extents come from sync + prediction].
- Connect: call `ConnectToServer(nServerInfoBase*)` (`gGame.cpp:2178`) from a new `--bridge-connect host:port` argument, building the server info the way `gServerFavorites.cpp:98` does.
- **Play mode:** `join` binds to local player 0 instead of creating a server player. `input` → `cycle->Act(&gCycle::se_turnLeft,1)`; on a client with `Owner()==sn_myNetID` this takes the normal client turn path (`gCycle.cpp:3644, 3740`). `cmd` must be disabled in client mode.

### Fair play and social
- **Undetectable by design:** login sends only an `nVersion` protocol range and auth methods (`nNetwork.cpp:1950-1990`). Server admins cannot tell a patched client from a stock one. Honesty has to be social: a visible name tag (e.g. `mc|Syn`), an announcement, and **league admins' consent before any play on league servers**.
- **Automation:** the bridge `input` is a programmable control surface, i.e. a bot interface. In ranked play that's the real risk, not Minecraft. Spectator-only first; play mode only on servers that opt in.
- **Latency is a handicap, not an edge** [inf, unmeasured]: the vanilla client sends input on change at tick granularity, and the server handles it on its next tick, adding ~0–100 ms before the bridge. Measure it before arguing either way (see evidence).
- **Auth:** `rcl-sumobar.cfg` has `ACCESS_LEVEL_PLAY 15` = `tAccessLevel_Remote` (`tConfiguration.h:174`). Playing there requires a global login; spectating doesn't. The user puts credentials in the client's own config themselves. Never route passwords through Minecraft chat or the bridge.
- **Ghosting:** the same broadcast-delay and no-chat-relay rules as feature 4.
- The universal-modder guardrail is "never network into real games' online services". RCL is Jamie's own community fleet, which is why spectator-only + consent is the line here.

### What `league_server` / `league_client` actually are (strings only)
- Both are Rust (rustc, LLD 21 linker, GCC 15.2 crt), unstripped. Built from `/home/frosti/repos/armagetronad_scripts` with a shared library crate `armagetronapi`.
- **`league_server`:**
  - Symbols: `league_server::model::LeagueQueue::{new, handle_input, register_script_client, dequeue, write_to_all_clients}`, `model::request::Request::parse`, `handle_client`.
  - `Mode` displays `Sumo`, `Fortress`, `TST`. It binds `0.0.0.0:7777`.
  - Strings: "You have been queued. There are … players in queue", "No server available for … queue, try again later!", "popped!", and the target `uk.syn.gl:4542`.
  - It embeds the command table including `KICK_TO`/`MOVE_TO` [inf: on pop it redirects queued players to the match server].
- **`league_client`:**
  - Symbols: `armagetronapi::model::ladderlog::LadderLogEntry::parse`, `runtime::chat_command::ChatCommand::{from_raw,execute}`, `runtime::run_suite`, `intercept_unknown_commands`, `league_client::{handle_connection, read_all_the_things}`.
  - Chat help: "Queues you for a league match! WIP: The only valid option is \"sumo\"", "Removes you from the queue for a league match", "WIP: Will tell you who is in the relevant queue, and how many". It connects to `retrocyclesleague.com:7777`.
  - It embeds sty+ct+ap names (`SPAWN_ZONE`, `PLAYER_GRIDPOS_ON_TURN`, `INTERCEPT_UNKNOWN_COMMANDS`, `SPAWN_SCRIPT`, 100+ `LADDERLOG_WRITE_*`).
- **So:** a per-server script (ladderlog in, console commands out [inf]) relays chat queue commands to a central queue that pops matches onto a match server. Neither is an Armagetron client, so neither helps join servers from Minecraft.
- Plugging armacraft into this queue is possible in principle, but it's a **platform move before demand**; see the "don't build yet" list.

**Effort.**
- Spectator first light: 8–12 h. That covers packaging the Xvfb image, `--bridge-connect`, client-mode guards, and verifying `state` on a client against the same match seen by the guest.
- Play: +6 h.

**Risks.**
- Client-side wall and hole state is the client's view, and lags the server's.
- Version: league servers on sty+ct accept 0.2.9 clients, but the patch must stay off sty+ct-only messages.
- GL under Xvfb on a server box costs ~1 core.

**Evidence it works.**
- Point both the patched client (bridge on port A) and the guest's own bridge (port B) at the same local guest match, and record both JSONL streams. Pass = cycle positions agree within 1 unit at matched `t`, and turn events match 1:1.
- Latency: host-side `System.nanoTime` at `handlePlayerInput` vs the guest `turn` event, with the guest clock offset measured at `hello`.

---

## 13. `.aarec` record/replay of armacraft sessions

**What the player sees.** "Replay round 7" re-simulates the round exactly and streams it into Minecraft,
including what Minecraft riders did.

**Mechanism.**
- `--record f.aarec` / `--playback f.aarec` exist in release builds (`tRecorderInternal.cpp:311-350`).
- They archive network packets, timer, RNG, config and input (`tRecorder::Archive` call sites in nSocket, tRandom, eTimer, tConfiguration, nAuthentication, ...).
- Run the guest with `--record`, then replay with the **same image** and `--playback`; the bridge streams as usual.
- Two required patches, because the bridge sits outside the recorder today:
  1. Archive each host line through `tRecorder` in `sg_ProcessInput`, and feed archived lines in playback.
  2. Archive `sg_BridgeNumUsers()`, because it changes game flow via `sg_NumUsers`/`sg_NumHumans` (patch in `gGame.cpp`).
  Without both, playback desyncs the moment a Minecraft rider turns.
- RCL archives: not viable. Replay needs the identical binary that recorded, and league servers aren't recorded with our build. Use feature 4 for RCL.

**Effort.** 6–10 h. **Risks.** Determinism bugs are slow to chase. The replay process must not accept live
input. **Evidence.** Record a 2-round session with one rider, replay it, and compare `state` JSONL streams.
Pass = identical cycle positions at matched `t` (tolerance 1e-3).

---

## 14. Server-only mid-round rim walls (experimental)

**What the player sees.** In a bridge-only arena (no UDP clients), placing blocks mid-round creates real
walls immediately: cycles grind on them and die on them.

**Mechanism.**
- Guest: `{"type":"wall","pts":[...]}` → `grid->Insert(p)`, then for each point `grid->DrawLine(R, next, tNEW(gWallRim)(grid, ...), 0)`, as `gParser::DrawRim` does.
- `eGrid::DrawLine` is also how player walls enter the grid at runtime: `gCycle.cpp:2483, 3782` → `CopyIntoGrid` → `eHalfEdge::CopyIntoGrid` → `DrawLine` (`eGrid.cpp:2325-2328`). So mid-round insertion is a supported operation on the grid. **Not** for rim walls; that's untested.
- Emit a `static_add` event.
- Refuse the request when `sn_NumUsers() > 0` (real clients are connected), since they'd never see the wall and the server would kill their cycles on nothing.
- Removal: not possible; walls last until the round ends.

**Effort.** 4–6 h. **Risks.** High. Topology asserts (`TOPOLOGY_POLICE`, `gWall.cpp:604`, can be turned on
to catch them) and AI sensor surprises are possible. **Evidence.** A fuzz test drops 200 random walls mid-round with AIs running: no assert or crash,
and AIs die on the walls (death events at wall coordinates).

---

## Don't build yet (platform before demand)

Each of these turns a toy into something people depend on:
- **League queue integration:** `/queue sumo` from Minecraft via `league_client`/`league_server`.
- **A public armacraft MC server for the RCL Discord.** It also needs bridge auth (v1 has none, and `cmd` is owner-level console).
- **A map-sharing hub** for MC-built maps on syn.gl.
- **A "fleet wall":** one MC world rendering every live RCL server. The host supports one bridge connection today.

Prove one fun loop with Jamie and two friends first (features 1–3). The evidence for demand is people
asking for the link twice.

---

## Recommended next 3 increments (proposals)

### Increment A: "See the zones, read the HUD" (≈ 6–7 h)
Features 1 + the core of 2:
- `zones` in `state` via friend patches.
- Ring renderer.
- Action-bar rubber/speed.
- Countdown/winner titles from `state.t` and `round_end`.
- Zone boss bar.
- Locator-bar radar.

Test scene: the sumobar map (feature 10), so fortress zones are exercised.

**Done when:**
- The gametest passes against the extended fake guest (zone ring geometry, title count).
- On the live guest the win zone ring appears at t ≈ 40 s.
- A vanilla 26.3 client screenshot shows the rubber bar moving while grinding.

**Integrations that make it real:**
- Run the guest + MC server on this PC for a session with RCL friends over an SSH tunnel or LAN. The bridge stays on 127.0.0.1.
- Record one round to MinIO (ftp.syn.gl) as a clip.

### Increment B: "Build it in Minecraft, race it next round" (≈ 6 h)
Feature 3:
- `/arma export`.
- Chunked `map` message → guest resource dir + `MAP_FILE`.
- Versioned filenames.
- Zones and spawns from marker blocks.
- Round-trip oracle against `round.static` + `xmllint --valid`.

**Integrations that make it real:**
- Point `RESOURCE_REPOSITORY_SERVER` at an **HTTP** (not only HTTPS) public-read prefix on the MinIO at ftp.syn.gl, so real Armagetron clients download MC-built maps automatically. Maps are KB-sized, so uk disk pressure is a non-issue.
- Publish a map to a league server only through the RCL agent's deploy rules.

### Increment C: "Watch RCL in Minecraft" (≈ 7 h)
Feature 4: a stdlib Python ladderlog → protocol v1 adapter, reusing `fake_guest.py`'s server half.

Order of work:
1. A local sty+ct server with AIs and `SVG_OUTPUT_TIMING` as the oracle.
2. One archived RCL match, if the rcl-watch logs carry GRIDPOS.
3. Live via `ssh -L` from the league host.

**Integrations that make it real:**
- Enable `PLAYER_GRIDPOS_ON_TURN 1` + the four `LADDERLOG_WRITE_*` flags on one RCL server (Jamie's call, via the RCL agent).
- Run the adapter on that host.
- Add a broadcast delay for tournaments.
- An OBS capture of a Minecraft spectator becomes a 3D broadcast angle for RCL streams.

Everything above is **proposed**. Nothing has been built, run or deployed in this research pass. The code
facts are [src]. The headless-client and countdown facts are [obs] from other agents' artifacts. The
league-binary roles, the HTTP-only guest build and the latency direction are [inf]. The rcl-watch log
contents, map availability on the public repo and the `<Settings>` allowlist are [unv], each with the check
named in place.

# armacraft dev loops

Fast edit → running loops for each side, independent of each other. The bridge reconnects on
its own, so either side can be rebuilt or restarted while the other keeps running.

| side | lives in | ports (all 127.0.0.1) |
|------|----------|-----------------------|
| dev guest | container `armacraft-guest-dev`, image `armacraft-guest-devenv`, volume `armacraft-guest-dev-work` | 4544/udp (clients), 4545/tcp (bridge) |
| dev MC server | `dev/mcserver/` (plain Fabric server, official launcher) | 25566 (game), 25576 (RCON) |

None of this touches the shared `armacraft-guest` (4534/4535), the `armacraft-guest:dev`
image, `guest/run.ps1`, `host/run`, or port 25565. 4554/4555 are left for the perf agent.

Needs Windows PowerShell 5.1, Docker Desktop, Python 3 (`python` on PATH), and JDK 25 in
`~/.jdks`. A JetBrains Runtime in `~/.jdks` (`jbrsdk-25*`) is picked up for structural hotswap.
`~/.jdks/jbrsdk-25.0.4.1-b623.69` was installed for this from JetBrains' official
cache-redirector, with its SHA-512 checked. Delete it to fall back to Temurin (method-body hotswap only).

## Quick start

```powershell
dev\up.ps1                    # guest + MC server, both connected; prints dev\status.ps1
dev\status.ps1                # one screen: guest, snapshot rate, MC, mod version, bridge
dev\down.ps1                  # MC stop (world saved) + guest container removed (-Wipe: build volume too)
```

`dev\up.ps1 -Mode jar -NoBuild` runs whatever jar is already in `host\build\libs` and does
not invoke gradle in `host/` at all.

## Guest loop (`dev\guest.ps1`)

```powershell
dev\guest.ps1 up                  # first time ~95 s (image + full build); later ~9 s
# edit guest/src/gBridge.cpp
dev\guest.ps1 rebuild             # sync -> incremental make -> swap binary -> restart server process (~5.5 s)
dev\guest.ps1 rebuild -Quick      # same with -O0 for the changed files (~3.7 s)
dev\guest.ps1 rebuild -Full       # re-run bootstrap + configure (configure.ac edits)
dev\guest.ps1 restart             # server process only (~1.3 s)
# edit guest/server/*.cfg
dev\guest.ps1 cfg                 # re-exec settings_custom.cfg in the running server (~0.3 s, no restart)
dev\guest.ps1 cfg bridge.cfg
dev\guest.ps1 console "CYCLE_SPEED 25"
dev\guest.ps1 logs -Follow
dev\guest.ps1 status | shell | down [-Wipe]
```

How it works:
- `guest/Dockerfile.dev` holds the toolchain (+ ccache, mold) and a pristine upstream checkout.
  The upstream ref and commit are read from `guest/Dockerfile`, so the pin has one source of truth.
- `/work` is a named volume holding the patched tree, the out-of-tree build dir, the install prefix and ccache.
  `guest/` is bind-mounted read-only at `/guest`, and `guest/server` at `/etc/armacraft` (as `run.ps1` does).
- `guest/dev/build.sh` copies changed `guest/src` files into the tree (by content, so mtimes
  through the Windows bind mount don't matter). It re-applies `guest/patches` when their hash
  changes: tracked files are restored first, so reverted hunks rebuild correctly. Then it runs
  `make armagetronad_main` in `build/src` and installs the binary by copy + rename, so the running
  server never sees a half-written file.
- `guest/dev/supervise.sh` is the container's main process (`--init`). It restarts the server
  whenever it exits, and the server's stdin is a FIFO, so `cfg`/`console` type into the real
  console. The container and its published ports stay up across rebuilds. That is why MC
  reconnects within its 0.5 s minimum backoff instead of the 0-10 s backoff it falls into
  when the port disappears.
- Config re-exec is `INCLUDE <file>`, resolved against `--userconfigdir`. Round-scoped
  settings apply at the next round, as with any console change.

Where the ~5.5 s goes: compiling `gBridge.cpp` at -O2 takes ~3.8 s (-O0: 1.7 s), `ar`
40 ms, linking with mold 70 ms (GNU ld 210 ms), and docker exec overhead ~0.5 s. After the restart, ~1.0 s
goes to the upstream idle nap (`Select(1.0)`), which delays `accept()` on the bridge. Proposal G1 in
`host-proposals.md` cuts that to ~0.1 s (measured in the dev volume: restart → hello 1.3 s → 0.45 s).
ccache does not help a single edited file. It makes patch re-applies and `-Quick` ↔ normal
switches cheap (a re-patch rebuilt in 1.1 s).

## Host loop (`dev\host.ps1`)

```powershell
dev\host.ps1 up                   # init dev/mcserver, gradle classes, start, wait for RCON + bridge
# edit host/src/main/java/...
dev\host.ps1 hotswap              # gradle classes -> redefine changed classes in the live JVM (~4 s)
dev\host.ps1 redeploy             # gradle -> stop (RCON) -> deploy -> start -> wait (~18-25 s)
dev\host.ps1 redeploy -Mode jar   # same with the real jar in mods/ (mode is sticky)
dev\host.ps1 cfg                  # /arma reload after editing dev/mcserver/config/armacraft.json (proposal 1)
dev\host.ps1 rcon "arma status"
dev\host.ps1 logs -Follow
dev\host.ps1 status | restart | down
dev\host.ps1 hotswap -TargetPid <pid>   # into a gradlew runServer JVM (proposal 4)
```

`-HostDir <path>` (or `$env:ARMA_HOST_DIR`) builds another checkout instead of `host/`.

What was evaluated, and what was picked:

| option | result |
|--------|--------|
| JBR 25 + `-XX:+AllowEnhancedClassRedefinition` | **Works on JBR 25.0.4.1-b623.69.** Adding and removing fields and methods on a live `ArmaController` instance was verified (the new field starts at 0 on the existing object). Stock Temurin refuses the same change with `attempted to change the schema`. Costs ~0.5-1 s of MC startup. Used when present. |
| Loom's hotswap support | Loom 1.18.2 has none: no JBR flag handling and no mixin agent in its jar. Proposal 4 adds the two vmArgs that make `runServer` hotswappable with the same tool (verified in a copy: 4.0-4.5 s, 53 classes redefined on first attach). |
| Gradle settings | Configuration cache and parallel are already on. With a warm daemon, `gradle classes` for one edited file is the floor at 2.8-3.6 s (about 1.8 s of that is client and daemon overhead). Calling `javac` directly with the exported classpath was 2.2 s, because the MC jar has to be loaded, and it invalidates gradle's incremental state (the next gradle build did a 12 s full recompile). Not worth it. |
| standalone Fabric server + restart | Reliable for everything (mixins, resources, init code). MC start dominates, so the JVM flags below were measured. |
| JDWP for hotswap | Works, but the agent costs ~2 s of startup. Replaced by a dynamic-attach `java.lang.instrument` agent (`dev/tools/HotSwap.java`) that needs no port and costs nothing at startup. `-Jdwp` still adds JDWP on 5006 for an IDE debugger. |

Pick: **hotswap first, restart as fallback.** `hotswap` diffs the compiled classes against what
the JVM loaded (SHA-256 manifest written at each start) and redefines only those. It falls back
to `redeploy` automatically when:
- a mixin class changed (mixins are merged into MC classes at load time);
- a resource changed (`fabric.mod.json`, mixin config);
- the JVM refuses the change (stock JDK + schema change);
- in jar mode, a class is new or not loaded yet (the stale jar copy would load).
`-NoFallback` only reports.

Static initialisers, registered callbacks/commands and constructor logic do not re-run on
hotswap. `/arma reload` (proposal 1) covers config. Anything wired up in `onInitialize` needs `redeploy`.

**Mod load modes.** `exploded` (default) loads the mod from `dev/mcserver/.dev/mod` (a synced copy of
`build/classes` + `build/resources`) via `-Dfabric.addMods`. That means no `jar` task, no Windows
file lock on a running jar, and hotswap can add classes. This was verified: a new `DevProbe` class
called from `status()` went live in 6.4 s under load. `jar` copies the real artifact into
`mods/` and is for checking packaging. Fabric reports `armacraft 0.1.0` in both modes.

**Dev JVM flags** (MC start → RCON answering, exploded mod, warm world, 3 runs each, same machine,
background load ~30-60 %):

| flags | start |
|-------|-------|
| Temurin, defaults (what `runServer` uses) | 17.3 / 19.4 / 22.1 s |
| Temurin, `-XX:TieredStopAtLevel=1` (C1 only) | 15.0 / 16.1 / 17.1 s |
| Temurin, C1 + no remote bytecode verification | 12.9 / 13.0 / 12.9 s |
| JBR, C1 + JDWP + enhanced redefinition | 18.2 / 18.3 / 19.4 s |
| JBR, C1 + JDWP + enhanced + no verify | 15.1 / 16.1 / 16.6 s |
| **JBR, C1 + enhanced + no verify + attach agent (default)** | **13.5 / 14.0 / 14.0 s** |

`-C2` and `-Verify` turn those two flags off. Skipping verification means a broken bytecode
transform crashes instead of throwing `VerifyError`. That is acceptable for a dev server and never belongs in deploy/.
`-XX:+UseSerialGC` and AOT/CDS archives were not pursued. Knot loads game classes through a custom
class loader, which JDK 25's AOT cache does not cover.

**Fast world.** Flat world, view distance 6, simulation distance 4, peaceful, no structures, arena anchor on
the flat surface (`y=-60`). On first start, `forceload add -16 -144 144 16` (121 chunks, ~0.6 s at
startup) loads the arena area, so the mod renders entities with no player online. Without it,
`/arma status` shows `entities: 0`, because `EntitySet` only spawns into loaded chunks.

`dev/mcserver/eula.txt` was written by the main session with Jamie's consent. The scripts never
write it. They refuse to start without it.

## Play loop (`dev\play.ps1`): changes into the game being played

For the client the user actually plays (`.work/play-shaders`, else `.work/play`; `-Play <dir>` or
`$env:ARMA_PLAY_DIR` for another copy). Source of truth is `host/`; the play copy's `src/` and `build/`
are never written.

```powershell
dev\play.ps1 launch               # one game window from the staged build (first time: stages host/ itself)
# edit host/src/main or host/src/client
dev\play.ps1 push                 # compile + swap into the running game: 4-7 s, chat says "(hot-pushed: N classes)"
dev\play.ps1 push -DryRun         # what would be swapped or refused, nothing touched
dev\play.ps1 stage                # push said RESTART NEEDED: build into staged/ (safe while playing)
#   quit the game, then
dev\play.ps1 launch               # picks up staged/
dev\play.ps1 launch -Jdk jbr      # JetBrains Runtime + -XX:+AllowEnhancedClassRedefinition (sticky; -Jdk temurin to go back)
dev\play.ps1 launch -World "New World"   # straight into a save (--quickPlaySingleplayer)
dev\play.ps1 status               # game pid, live / staged / host build stamps, JDK
dev\play.ps1 config-diff [-All]   # ride config files against the current defaults
```

`launch` starts exactly one `javaw` process and returns. Nothing watches it and nothing relaunches it;
a second `launch` while that game runs is refused. Exit codes of `push`: 0 live, 10 restart needed
(nothing changed), 1 error (for example a compile error: nothing changed either).

### Why a running game can no longer see half a build

The crash this replaces: new sources were compiled into the play copy's `build/classes` while the game ran
from that directory, and a later lazy class load found a renamed class missing (`ClassNotFoundException`).

Now `<play>/.fastloop` holds everything:

| path | what | written by |
|------|------|-----------|
| `live/` | the mod the game loads: classes + resources of host's `main` and `client`, merged (first class path entry) | `launch`, before the JVM exists; a successful `push`, from inside the JVM |
| `staged/` | the next build; on no class path | `stage` (temp dir + rename) |
| `run.spec` | the play copy's `runClient` class path / JVM args / program args | exported with `dev/tools/play-export.init.gradle` (an init script: the play copy's build files are not edited), only while no game runs |
| `launch.args`, `game.pid`, `jdk`, `logs/` | the java argfile of the last launch, its pid, the sticky JDK choice, stdout/stderr | `launch` |

`push` (`dev/tools/HotSwap.java push`) diffs the new `host/build` against the directory the JVM reports it
loaded the mod from (the code source of `Armacraft` and `RideClientMod`), so there is no manifest to go stale.
Then, all or nothing:

1. Tool side, before the game is touched: refuse on a removed or renamed class, on a changed class in a
   mixin package (taken from the build's `*.mixins.json`), and on a changed resource other than the ones the
   mod re-reads at run time (`armacraft-build.properties`, `data/armacraft/ride_presets/`).
2. In the JVM: every changed class that is not loaded yet is loaded now, from the old files, without
   initialising it. After this no changed class can be lazy-loaded later.
3. New classes are written into `live/` (old code cannot reference them).
4. One `redefineClasses` call swaps all changed classes at a safepoint. If the JVM refuses (a structural
   change on a stock JDK), the new files are deleted again and nothing has changed.
5. Only now the changed `.class` files and live resources are replaced in `live/` (temp file + atomic move).
   That matters for the next start only. If a write fails the game is still consistent and the next push retries.

A game started the old way (`gradlew runClient` in the play copy) is recognised, and `push` answers
RESTART NEEDED without touching it: `stage`, quit, `launch`. `push -GradleGame` applies the same mechanism
to that game's `build/classes`; that path is written but **not tested**.

### What push applies, and what it refuses

| change | Temurin | JBR (`-Jdk jbr`) |
|--------|---------|------------------|
| method bodies, `main` and `client` | live | live |
| new class used from changed code | live | live |
| a changed class that the game has not loaded yet | live (loaded first, then swapped) | live |
| add/remove fields or methods, new lambdas, signature changes | **restart needed** (JVM refuses; nothing changed) | live |
| mixin class changed or added | **restart needed** | **restart needed** |
| class removed or renamed (also inner classes renumbered away) | **restart needed** | **restart needed** |
| `fabric.mod.json`, mixin configs, assets, lang | **restart needed** | **restart needed** |
| ride preset `.cfg` resources, build stamp | live (re-read on use) | live |

As with any hotswap: static initialisers, registered commands, payload types and event callbacks do not
re-run. A new command or payload type needs a restart even though the classes swap.

### Measured (2026-10-02, i9-10900K, hidden scratch client with Sodium + Iris shaders + DH running)

| step | time |
|------|------|
| `push`, method bodies, Temurin game | 4.3 s (gradle 1.5 s, swap 0.18 s) |
| `push`, method bodies, JBR game, 3 runs | 6.3 / 7.1 / 7.1 s (gradle 5.0-5.6 s, swap 0.5-0.6 s) |
| `push`, new field + new class + unloaded class, JBR | 6.9 s |
| refusals (mixin, removed class, startup resource, structural on Temurin) | 3.7-6.3 s, 0 files changed in `live/` |
| `stage` (already compiled) while the game runs | 0.8 s |
| `launch` to process started / to in world | 0.7 s / 41-54 s |

Gradle is the floor: the same edit compiles in 1.5 s when the machine is quiet and about 5 s next to a
shader client.

**JBR.** With `-Jdk jbr` the hidden scratch client started with Sodium 0.9.2, Iris 1.11.7 (Complementary +
Euphoria loaded), Distant Horizons and both armacraft mods, joined a world and took structural pushes. Its
log matched the Temurin run (same single Iris message in both). Frame rate on JBR was **not measured**, so
Temurin stays the default until someone looks at fps with shaders on.

### In-game build stamp

On joining a world, on entering cycle mode, on `/arma whatsnew`, and after every push, chat prints

```
[armacraft] build 2026-10-02 20:13:10 · client 0.1.7 (hot-pushed: 3 classes)
  ...up to 8 lines of <run dir>/config/armacraft-whatsnew.txt (# lines and blank lines skipped)
```

The time is when gradle last saw `host/src/main` or `host/src/client` change (`armaBuildStamp` in
`host/build.gradle`); `client` is the version of the optional `armacraft_client` jar.

### Config: live reload and stale defaults

`/arma reload` (gamemaster) now re-reads all three server-side files and pushes the result to clients. A file
that does not parse is left alone and the running values are kept (it used to be overwritten with defaults).

| file | keys | live via | state |
|------|------|----------|-------|
| `armacraft.json` | bridge host/port, anchor, scale | `/arma reload` | existed |
| `armacraft-ride.json` | `preset`, `overrides`, `blocks_per_unit`, `speed_cap`, `terrain_rubber_factor`, `boost.*` | `/arma reload`, `/arma preset` (running bikes switch at once) | existed |
| | `visual_blocks_per_unit`, `trail_height`, `trail_cap_blocks`, `wall_warning_seconds` | `/arma reload` (riders at once, others at their next deploy) | existed |
| | `lethal_trails`, `sync_range`, `vanilla_fallback` | `/arma reload` (read every tick) | existed |
| | `step_height`, `bike_width` | `/arma reload` | **new**: re-applied to riders on server and client |
| | `lane_centering` | next deploy (it only acts at deploy) | by design |
| | `keys.*`, `armagetron_profile`, `exact_turn_timing`, `cycle_redeploy_*` | `/arma reload`: every modded client re-reads its own copy | **new** |
| | `rider_color`, `rider_name` | `/arma reload`, used at the next deploy | **new** |
| `armacraft-ride-server.json` | frost walker, fall damage, movement checks, crash effect, mob hits (every key) | `/arma reload`, from the next tick | **new** (was restart only) |
| `armacraft_client.json` | camera, render, sound, hud, ride visuals | `/armac reload` (client mod, unchanged here) | existed |
| `armacraft-whatsnew.txt` | notes | read on every print | **new** |

Stale defaults: each of the two ride config files gets a sidecar `<file>.defaults`, the defaults it was last
written with. On load, a key that still equals that snapshot follows a default changed in code (logged); a
key that differs is an override, kept and logged in one line. Files from before this have no snapshot, so
their old values count as overrides until `config-diff` shows them and someone edits or deletes them.

### How this was tested

Unit tests in `host/` (`ConfigDefaultsTest`: default adoption, overrides, unparsable files, reload of the
ride-server config, `config-diff` output). Everything with a game ran against scratch copies of `host/` and
`.work/play-shaders` whose window was created hidden (a scratch-only mixin adding `SDL_WINDOW_HIDDEN` to
`Window.createWindow`; 26.3 uses SDL, not GLFW), sound muted, with a watchdog that kills the process if it
ever owns a visible top-level window (none seen). Not exercised in a running game: `/arma reload` and
`/arma whatsnew` typed in chat, the client key re-read, `push -GradleGame`, and frame rate on JBR.

## Whole stack

```powershell
dev\up.ps1 [-HostDir ...] [-Mode jar] [-NoBuild]
dev\status.ps1 [-Window 2]
dev\down.ps1 [-Wipe]
```

`status.ps1` shows: guest container + server pid + build id; bridge `hello`; snapshot rate measured by
a probe connection (it counts state messages between the first and last snapshot, so the
guest's wake-up from its nap doesn't skew it); MC pid/uptime/mode; deployed build + hotswap count;
armacraft version from `latest.log`; `/arma status` over RCON; and the mod's lines-in rate over
the same window. The probe connects as a bridge host but never joins.

## Timings

Machine: i9-10900K (10C/20T), Docker Desktop 27.5, with other agents' builds, Minecraft clients and
clipfarm containers running (CPU load 30-87 % during these runs). Anything that starts a JVM varies by
±2-5 s. The A/B rows were interleaved so both sides saw the same load.

### Measured

All numbers are wall-clock times on this machine, measured with the scripts in this directory
(marker edits in a scratch copy of `host/`, and a marker in `guest/src/gBridge.cpp` that was
restored byte-identical afterwards). "Live" was checked every time: the edited string showed up in
the guest log or in `/arma status`.

**Guest** (edit `gBridge.cpp` → bridge `hello` on 4545)

| loop | baseline (`guest/run.ps1` flow: docker build + new container) | improved |
|------|------|------|
| code edit → hello | 34.4 / 36.6 / 38.3 s | `rebuild` 5.1-5.9 s (9 runs), `rebuild -Quick` 3.7 s (2 runs) |
| code edit → MC reconnected | the above + 2.1 / 8.1 s of MC backoff lag (22 s / 35 s outage) | 5.8 / 5.9 / 6.3 s |
| cfg-only edit applied | 4.0 / 4.3 / 5.3 s + MC backoff lag | `cfg` 0.23-0.28 s, no restart, MC stays connected |
| restart server process only | as above | 1.3 s (6 runs); 0.4-0.7 s with proposal G1 (tried in the dev volume, reverted) |
| first `up` (cold: image + full build) | n/a | 93.8 s |

**Host** (Java edit → new code live in MC and the bridge connected)

| loop | baseline (`gradlew runServer` restart) | improved |
|------|------|------|
| method-body edit, quiet machine | 20.0 / 20.7 / 20.7 s (+0.7 s to stop the old server) | `hotswap` 3.5-4.7 s (8 runs) |
| same, heavy load, interleaved A/B | 33.3 / 28.3 / 28.6 s (+1.5-3.2 s stop) | `hotswap` 5.6 / 5.4 / 5.4 s; `redeploy` 28.6 / 26.3 / 27.5 s |
| add/remove a field and methods | restart | `hotswap` 4.3-5.2 s (JBR) |
| mixin edit | restart | `hotswap` falls back to `redeploy` automatically: 23.6 s under load |
| `config/armacraft.json` edit | restart | `/arma reload` 0.1-0.2 s (proposal 1, scratch copy) |
| runServer hotswap (proposal 4) | restart | 4.0-4.5 s (scratch copy) |

The restart path (`redeploy`) is only modestly faster than `runServer`, because MC startup
dominates both. The JVM flag table above shows the start itself drops from 17-22 s to 13.5-14 s
on a quiet machine. The ≲15 s host target is met by hotswap, not by restarts.

**Whole stack** from `dev\down.ps1` to both up and connected (`dev\up.ps1 -Mode jar -NoBuild`, warm
volume, heavy load): 32.8 s (guest 9.2 s, MC 23.3 s).

## Files

```
dev/
  up.ps1 down.ps1 status.ps1 guest.ps1 host.ps1 lib.ps1
  play.ps1                push / stage / launch / config-diff for the play client
  tools/bridge_probe.py   readiness + snapshot-rate probe (stdlib)
  tools/rcon.py           RCON client; reads port/password from server.properties
  tools/HotSwap.java      class diff + redefinition (attach agent or JDWP); `push` mode for play.ps1
  tools/play-export.init.gradle   exports a play copy's runClient launch spec (gradle init script)
  host-proposals.md       diffs for host/ (not applied) + guest nap proposal
  mcserver/               generated dev server (gitignored)
guest/
  Dockerfile.dev          dev toolchain image
  dev/build.sh dev/supervise.sh dev/ctl.sh   run inside the container
deploy/                   remote plan (not executed)
```

## Known limits

- Hotswap with `-NoBuild` trusts whatever is in `build/classes`.
- In jar mode, after a hotswap the jar in `mods/` is older than the running code. `restart`
  would revert to it, so use `redeploy` (the script warns).
- The bridge probe in `status.ps1` and `guest.ps1` counts as a bridge host for its 2 s, which
  wakes a napping guest.
- mold warns `symbol type mismatch: login/logout` (upstream globals that shadow libc). The
  same resolution as GNU ld, and the server runs. `DEV_LINKER=bfd` at configure time switches back.

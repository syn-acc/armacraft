# armacraft

Armagetron Advanced inside Minecraft Java 26.3 (Fabric).

Two modes:

- **Ride mode.** Drive a lightcycle through any Minecraft world. The cycle physics (turn delay and queueing, rubber, wall acceleration, brake) are ported from Armagetron's `gCycleMovement.cpp` and replayed against a real Armagetron server in the test suite. One block up or down is free, taller terrain uses rubber, water freezes into a thin lane of frosted ice, trails are lethal.
- **Arena mode.** A patched Armagetron 0.2.9.3.0 dedicated server runs the match and streams it over a TCP bridge; the Minecraft server mod draws it and sends input back. Stock Armagetron clients can join the same match.

## Layout

| Path | What |
|---|---|
| `host/` | The Fabric mod `armacraft` (MIT): bridge, arena renderer for vanilla clients, ride mode. Split `main` / `client` source sets. |
| `client/` | The optional client mod `armacraft_client` (GPL-2.0-or-later): Armagetron's own cycle model, walls, chase camera, HUD and sounds. Loaded next to `host`. |
| `guest/` | The Armagetron bridge patch and Docker build. |
| `PROTOCOL.md` | The bridge contract between `guest` and `host`. |
| `dev/` | Fast loop: `play.ps1` (launch, live code push, stage), `window.ps1` (recording window sizes), server dev loop. |
| `deploy/` | Remote hosting plan. Nothing has been deployed. |
| `docs/` | Feature research, performance, graphics and shader notes. |
| `tools/` | Test hosts and guests, benchmarks, `ride_diff.py` (records manoeuvres on the real engine). |

## Build and run

JDK 25. From `host/`:

```
./gradlew build            # unit tests, including the replay against real-engine recordings
./gradlew runGametest      # in-world tests on a headless server
```

Play: `dev\play.ps1 launch` opens one game window from a play copy; `dev\play.ps1 push` hot-swaps code changes into it. See `dev/README.md`.

In game: `R` enters cycle mode. Keys are imported from an Armagetron profile when one is present.

## State

Runs on one machine. Not deployed, and two real players riding together is untested. The Armagetron assets in `client/` are GPL; see `client/NOTICE.md`.

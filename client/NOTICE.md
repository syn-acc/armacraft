# armacraft_client: licence and attribution

`armacraft_client` is free software under the **GNU General Public License, version 2 or (at your option)
any later version** (`GPL-2.0-or-later`). The full licence text is in `LICENSE` (copied from Armagetron
Advanced's `COPYING.txt`). It is GPL because it ships, and is derived from, Armagetron Advanced.

## Armagetron Advanced

Assets and replicated behaviour come from **Armagetron Advanced 0.2.9.3.0**
(<https://gitlab.com/armagetronad/armagetronad>, tag `v0.2.9.3.0`, commit
`036daaf34f055b1efbcee6a4afc4d90a325ce569`), Copyright (C) 2000 Manuel Moos and the Armagetron Advanced
team (see their `AUTHORS`: z-man, guru3, davidfancella, Lucifer, epsy, jarrt, klaxnek, k_at_work, luke-jr,
dlh3, philippeqc, wrtlprnft and contributors; original font by Fabrice Holbe), GPL-2.0-or-later.

Files shipped unmodified (in `src/client/resources/assets/armacraft_client/`):

| file | upstream |
|------|----------|
| `models/arma/cycle_body.mod`, `cycle_front.mod`, `cycle_rear.mod` | `models/` |
| `textures/arma/cycle_body.png`, `cycle_wheel.png`, `dir_wall.png`, `rim_wall.png`, `floor.png`, `floor_a.png`, `floor_b.png`, `font.png` | `textures/` |

Converted (ffmpeg 7.1.1, libvorbis q6, mono):

| file | from | conversion |
|------|------|------------|
| `sounds/cyclrun.ogg` | `sound/cyclrun.wav` | format only |
| `sounds/expl.ogg` | `sound/expl.wav` | format only |
| `sounds/turn.ogg` | `sound/expl.wav` | resampled to 5x speed (Armagetron plays expl.wav at speed 5 for turns without the moviepack) |
| `sounds/spark.ogg` | `sound/expl.wav` | resampled to 4x speed (grind sparks) |

Code that re-implements Armagetron algorithms (model loader, cycle animation, wall/rim/floor/explosion
rendering, camera, sound mixing, HUD gauges) cites the upstream source file and function in its Javadoc.

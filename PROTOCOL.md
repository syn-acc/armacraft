# armacraft bridge protocol v1

Armagetron Advanced (guest, real simulation) ⇄ Minecraft (host, renderer + input).

```
Armagetron dedicated server (patched)  ── UDP 4534 ──  normal Armagetron clients
        │ TCP 4535, newline-delimited JSON (one object per line, UTF-8)
Minecraft server-side Fabric mod (integrated or dedicated MC server)
        │ vanilla MC protocol
vanilla Minecraft 26.3 clients
```

- The guest **listens**; the host **connects** (and reconnects with backoff).
- Default bind `127.0.0.1:4535`. Never bind publicly without an auth token (v1 has none).
- Unknown `type`s and unknown fields MUST be ignored (forward compatibility).
- All coordinates are Armagetron grid units on the 2D arena plane: `x` east, `y` north.
- Colors are `[r, g, b]` floats in `0..1`.
- `t` is Armagetron game time in seconds (float) from the guest's clock.

## Guest → host

### `hello` (once, on connect)
```json
{"type":"hello","proto":1,"game":"armagetronad","version":"0.2.9.3.0","rate":30,"features":["delta"]}
```
- `features` (optional): optional protocol features this guest can provide. A host turns one on by
  sending its own `hello` (see Host → guest). Hosts that never ask keep getting plain v1 messages.

### `round` (on connect if a round is running, and at every round start)
Static geometry: arena rim + any static map walls, as line segments.
```json
{"type":"round","round":3,"t":0.0,"static":[[x1,y1,x2,y2], ...],"bounds":[minx,miny,maxx,maxy]}
```

### `state` (every frame, capped at `rate` Hz; full snapshot, not a delta)
```json
{"type":"state","t":12.34,
 "cycles":[{"id":17,"name":"Syn","color":[0.1,0.6,1.0],"x":10.0,"y":-4.5,
            "dx":0.0,"dy":1.0,"speed":30.0,"alive":true,"brake":false,
            "rubber":0.2,"bridge_key":"mc:0f3a..."}],
 "walls":[{"id":901,"cycle":17,"x1":0,"y1":0,"x2":0,"y2":40,
           "gaps":[[0.25,0.30]]}]}
```
- `cycles[].id` is stable for the cycle's lifetime (netobject id is fine).
- `cycles[].bridge_key` is present only for cycles driven by a bridge player (else omitted/null).
- `walls[]` is every player wall currently in the world. `cycle` = owning cycle id.
  `x1,y1 → x2,y2` is the wall's current extent (the head wall grows each frame).
- `walls[].gaps` (optional): list of `[from,to]` fractions (0..1 along the wall) that are holes (explosions etc). Omit when none.
- Dead cycles may stay in `cycles` with `"alive":false` while their walls persist.

### `delta` (feature `delta`, only after the host asked for it; replaces `state` for that host)
```json
{"type":"delta","seq":812,"key":false,"t":12.37,
 "cycles":[ ...same as state... ],
 "walls":[{"id":905,"cycle":17,"x1":0,"y1":40,"x2":12.5,"y2":40}],
 "removed":[880,881]}
```
- `cycles` is complete, exactly as in `state`.
- `walls` lists only walls that are new or whose fields changed since the last `delta` this host
  received; `removed` lists wall ids that are gone. Apply in order: if `key`, clear the wall set; drop
  `removed`; upsert `walls`. The result equals what a `state` for that frame would have listed.
- `key:true` on the first delta after the host's request and on the first delta of every round.
- The guest diffs against what it last *sent* to this host, so a delta it skips for a slow host is
  folded into the next one. `seq` increases by one per delta (diagnostics; gaps are not an error).

### `event`
```json
{"type":"event","ev":"spawn","id":17,"x":0,"y":0,"t":0.0}
{"type":"event","ev":"death","id":17,"x":10,"y":5,"killer":23,"t":12.3}
{"type":"event","ev":"turn","id":17,"x":10,"y":5,"dx":1,"dy":0,"t":12.3}
{"type":"event","ev":"round_end","winner":"Syn","t":40.0}
{"type":"event","ev":"chat","name":"Syn","text":"gg"}
```

### `joined` / `left` / `error` (replies)
```json
{"type":"joined","key":"mc:0f3a...","name":"Jamie"}
{"type":"left","key":"mc:0f3a..."}
{"type":"error","msg":"..."}
```

## Host → guest

```json
{"type":"hello","proto":1,"features":["delta"]}      // optional; the features the host wants (a later hello replaces the set, [] = plain v1)
{"type":"join","key":"mc:<uuid>","name":"Jamie","color":[0.1,0.6,1.0]}
{"type":"leave","key":"mc:<uuid>"}
{"type":"input","key":"mc:<uuid>","turn":"left"}     // or "right"; one message per key press
{"type":"input","key":"mc:<uuid>","brake":true}      // hold state; send false on release
{"type":"explode","x":10.0,"y":5.0,"r":8.0}          // optional: blast holes / kill cycles in radius
{"type":"cmd","line":"CYCLE_SPEED 20"}               // run an Armagetron console command (localhost only)
{"type":"chat","name":"Jamie","text":"hi from minecraft"}
```
- `join` creates a server-side Armagetron player named `name`, steered only by bridge input.
  It shows up to real Armagetron clients as a normal player. It spawns a cycle at the next round start (or immediately if the guest allows mid-round spawns).
- A dropped host connection removes that connection's bridge players.

## Host-side coordinate mapping (reference)

Anchor `(ax, ay, az)` in MC, scale `s` blocks per grid unit:
```
mc_x = ax + x * s
mc_y = ay
mc_z = az - y * s          // Armagetron north (+y) = Minecraft north (-z)
yaw  = atan2(-dx, -dy) in degrees   // MC yaw: 0=+z(south), 90=-x(west), 180=-z(north)
```

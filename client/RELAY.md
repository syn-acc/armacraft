# armacraft relay channel `armacraft:bridge` (v1)

Lets a Minecraft server that runs the armacraft host mod feed the optional client renderer
(`armacraft_client`) without the client opening its own TCP connection to the guest. The payload
content is exactly the PROTOCOL.md v1 JSON, one object per payload.

```
Armagetron guest ──TCP 4535 (PROTOCOL.md)── host mod on the MC server ──armacraft:bridge── armacraft_client
```

## Channel and wire format

- Fabric play-phase custom payload, id `armacraft:bridge`, registered in **both** directions.
- Payload body, both directions: `VarInt byteLength` followed by `byteLength` bytes of UTF-8. The bytes are
  one JSON object (PROTOCOL.md) **without** the trailing `\n`.
- Limits: clientbound at most 4 MiB per line (registered with `registerLarge`, Fabric splits it);
  serverbound at most 8 KiB. Oversized payloads are a protocol error (decode throws).
- Implementation: host package `gl.syn.armacraft.relay` (MIT jar only): `BridgePayload` (the payload class,
  registered once on both sides by the `main` entrypoint `BridgeRelay`), `RelayHub` (state, ordering),
  `RelayRules` (client message rules), `RelayLines` (relay line, keyframes). `armacraft_client` depends on
  `armacraft` and uses the host's `BridgePayload`; it compiles against it compile-only and never ships it.
  A client jar next to a host without the relay simply never sees the channel (relay mode unavailable).

## Advertising

The server advertises the relay simply by registering a play receiver for `armacraft:bridge`
(`ServerPlayNetworking.registerGlobalReceiver`). Fabric syncs channel registrations, so the client sees it
via `ClientPlayNetworking.canSend(armacraft:bridge)` / `ServerboundPlayChannelEvents.REGISTER`.

Client mode selection (`config/armacraft_client.json` `"mode"`):

| mode     | behaviour |
|----------|-----------|
| `auto`   | relay if the server advertises it, otherwise direct TCP if `direct.enabled` (default) |
| `relay`  | relay only; nothing if the server does not advertise it |
| `direct` | always direct TCP to `direct.host:direct.port` |
| `off`    | renderer idle |

If the relay is advertised after the client already connected directly, `auto` switches to the relay
(sending `leave` on the direct connection first if the player had joined).

## Server → client

1. Immediately after the player can receive the channel, send one host-originated line describing the
   mapping (new type; the guest never sends it, and per PROTOCOL.md unknown types are ignored elsewhere):

   ```json
   {"type":"relay","proto":1,"anchor":[ax,ay,az],"scale":s,"dimension":"minecraft:overworld"}
   ```
   `anchor`/`scale`/`dimension` are the host's `ArmaConfig` values (PROTOCOL.md "Host-side coordinate
   mapping"). Re-send it whenever they change. Without it the client uses its own config values.

2. Then the guest's lines verbatim, in order, as they arrive from the guest:
   - the current `hello` and, if a round is running, a `round` line (cache the latest of each and replay them
     to a client that starts listening mid-round, before any snapshot; a new `hello` (guest reconnect) clears the
     cached `round`);
   - a **keyframe**: one full `state` line rebuilt from the host's current snapshot, marked `"keyframe":true`
     (omitted if no snapshot arrived since the last `round`/`hello`). Needed because of the next point;
   - every guest line after that, verbatim and in order: `state` **or `delta`** (whichever the host negotiated
     with the guest; the real guest offers `delta` and armacraft asks for it), `event`, `joined`, `left`,
     `error`, and unknown types. A relay client must fold `delta` lines (PROTOCOL.md: `key` clears the wall set,
     drop `removed`, upsert `walls`) on top of the last `state`/keyframe.
   Sending every snapshot (≈29 Hz) is intended: the client dead-reckons between them. Because deltas are
   diffs, a server must not drop or reorder any line. A line over the 4 MiB limit is dropped and logged
   instead of being sent (an oversized payload would make the encoder disconnect the player).
   Subscription and live lines share one lock on the host, so a late subscriber's stream is exactly
   relay, hello, round, keyframe, then contiguous live lines.

## Client → server

Host messages from PROTOCOL.md "Host → guest", one per payload. The client sends:

```json
{"type":"join","key":"mc:<uuid>","name":"Jamie","color":[0.1,0.6,1.0]}
{"type":"leave","key":"mc:<uuid>"}
{"type":"input","key":"mc:<uuid>","turn":"left"}      // or "right": sent on the key-down event
{"type":"input","key":"mc:<uuid>","brake":true}       // hold state; false on release; re-sent after respawn
{"type":"chat","name":"Jamie","text":"hi"}
```

Server rules (as implemented in `RelayRules`/`RelayHub`/`BridgeRelay`):

- Parse the JSON; drop anything that is not an object or whose `type` is not one of
  `join`, `leave`, `input`, `chat`. `cmd`, `explode`, `hello` and unknown types are never forwarded.
- Every accepted message is **rebuilt** with the host's protocol builders, so no client-chosen field reaches
  the guest: `key` is always `"mc:" + sender UUID`; `join` and `chat` `name` is always the sender's profile
  name (control characters removed, at most 32 chars); `color` is three numbers clamped to 0..1, else the
  host's per-UUID colour; `input` carries `turn` (`left`/`right` only) and/or `brake` (boolean); `chat` text
  has control characters removed, at most 256 code points, non-empty, and is only forwarded when the host
  config `forward_chat` is on.
- `input`, `leave` and `chat` are accepted only from a player who joined through the relay.
- Rate limits per player: 40 messages burst, 20/s sustained; chat additionally 4 burst, one per 2 s. Excess
  is dropped, never coalesced (every accepted turn is forwarded once, immediately, from the server thread).
- When the player disconnects, the server sends `leave` for that key to the guest. A guest `left` for a relay
  rider's key ends the rider (no second `leave`). After a guest reconnect (new `hello`) the server re-sends
  each rider's sanitised `join`, as it does for its own sessions.

Relay riders and the server's own sessions: a relay rider gets **no** server session: no spectator mode, no
server chase cam, no teleport, and vanilla movement packets do not steer (only relay `input` does). A relay
`join` from a player who has a server session ends that session first (`leave`, gamemode and position
restored). `/arma join` is refused while relay-riding.

## Server camera vs client camera

The server does not run its camera mechanism for relay riders (see above); the client drives the Minecraft
camera every frame while riding. Players who joined with `/arma join` keep the server's spectator chase cam,
and a modded client then only renders. The client hides `block_display` entities inside the arena bounds
(toggle `render.hide_fallback_displays`, `/armac fallback false`) so the fallback is not drawn twice.

The client identifies its own cycle by `mc:<uuid of the in-world player>` (the uuid the server forces), not
the launcher profile id, which differs on offline-mode servers.

## Versioning

`proto` in the `relay` line is 1. Additive fields/types only; anything breaking gets a new channel id.

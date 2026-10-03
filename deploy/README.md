# armacraft remote deploy: plan only

**Status: proposal. Nothing here has been run against uk or the RCL fleet.** No ssh, no
images pushed, no jars uploaded. Deploys need Jamie's explicit approval, every time.

Files:
- `docker-compose.yml`: guest + Fabric MC server, bridge on the project network only.
- `mc.Dockerfile`: pinned Fabric server image (Temurin 25 JRE + official launcher).
- The `deploy.sh` sketch below is not a file on purpose. Promote it once the plan is approved.

## Target

`/data/armacraft` on **uk**. It already runs game servers, MinIO (ftp.syn.gl) and the RCL
stacks, and `/data` has a 20 GB free-space guard (see the RCL disk-bounds work). The footprint
is about 180 MB for the guest image, about 500 MB for the MC image, a few MB for the world and jars,
and logs capped at 3 x 10 MB per service. The same compose file runs on any RCL regional host
if latency to MC players matters more than co-location.

```
/data/armacraft/
  docker-compose.yml  mc.Dockerfile  .env          # .env: ARMACRAFT_TAG, ARMACRAFT_MC_TAG, PREV_*
  guest-server/        # guest/server/*.cfg from the release tag (+ prod overrides)
  mc-data/             # world, server.properties, eula.txt (Jamie), config/armacraft.json
  releases/<tag>/      # armacraft-<ver>.jar + .sha256, fabric-api jar, guest cfg snapshot
  mods -> releases/<tag>/mods   # symlink swapped by deploy/rollback
  backups/             # world tarballs, newest 5
```

## Security (hard requirements)

- **Bridge port 4535 is never public.** Protocol v1 has no auth token, and `{"type":"cmd"}` runs
  console lines at owner level (`BRIDGE_ALLOW_CMD 1`). In compose it is only `expose`d. The
  MC container reaches it as `guest:4535`. Publishing it for debugging means
  `127.0.0.1:4535:4535` on the host, never `0.0.0.0`, and only temporarily.
  Before any multi-host setup (MC on one box, guest on another), v2 needs a token or mTLS,
  or the link must run over the tailnet with host firewall rules. Until then, keep both on one host.
- Prod cfg sets `BRIDGE_ALLOW_CMD 0` unless `/arma cmd` (op-only in MC) is wanted live.
- RCON is either disabled or unpublished. Its password lives only in `mc-data/server.properties`.
- `eula.txt` is written by Jamie on the target host. No script sets `eula=true`.
- MC runs `online-mode=true`. Use a whitelist while testing.
- MinIO credentials stay in `~/.mc/config.json` on the machines that use them. Never put them in this repo or in `.env`.

## Release = tag + push

A release pins one guest image and one mod jar that speak the same protocol (`hello.proto` = 1).

1. **Tag.** `git tag armacraft-vX.Y.Z` on a commit where `guest/` and `host/` are both green
   (host `gradlew build runGametest`, guest image builds, `tools/fake_host.py` sees state).
2. **Guest image.** `docker build -t armacraft-guest:vX.Y.Z guest`, built from scratch by the existing
   Dockerfile with upstream pinned by commit. Ship it **without a registry**:
   `docker save armacraft-guest:vX.Y.Z | gzip | ssh uk 'gunzip | docker load'` (about 60 MB).
   Alternatives, if a fleet of hosts needs pulls: a `registry:2` on uk bound to the tailnet
   (self-hosted), or GHCR. GHCR is SaaS and only worth it if CI builds the images.
3. **Mod jar → MinIO.** `host/gradlew -p host build`, then
   `mc cp host/build/libs/armacraft-X.Y.Z.jar syn/armacraft/releases/vX.Y.Z/` plus a
   `sha256sum` file. The Fabric API jar of the matching `fabric_api_version` goes next to it.
   MinIO at ftp.syn.gl is Jamie's own store, so no third party holds release artifacts.
4. **MC image.** `docker compose build mc` on the target. Only the launcher download is pinned
   by version. Rebuilding is only needed when MC, loader or installer versions change.
   Tag it `armacraft-mc:mc26.3-l0.19.5`.

## Deploy (sketch, not executed)

```bash
# /data/armacraft/deploy.sh <tag>   (proposal)
set -euo pipefail
cd /data/armacraft
tag=$1
free_gb=$(df --output=avail -BG /data | tail -1 | tr -dc 0-9)
[ "$free_gb" -ge 20 ] || { echo "abort: /data has ${free_gb}G free (<20G guard)"; exit 1; }
docker image inspect "armacraft-guest:$tag" >/dev/null           # loaded in step 2
mkdir -p "releases/$tag/mods"
mc cp "syn/armacraft/releases/$tag/" "releases/$tag/mods/" --recursive
(cd "releases/$tag/mods" && sha256sum -c ./*.sha256)
tar -C mc-data -czf "backups/world-$(date +%Y%m%dT%H%M%S)-pre-$tag.tgz" world
ls -1t backups/*.tgz | tail -n +6 | xargs -r rm --
. ./.env
sed -i "s/^PREV_ARMACRAFT_TAG=.*/PREV_ARMACRAFT_TAG=$ARMACRAFT_TAG/; s/^ARMACRAFT_TAG=.*/ARMACRAFT_TAG=$tag/" .env
ln -sfn "releases/$tag/mods" mods
docker compose up -d
# verify. A running container is not proof of health:
timeout 120 sh -c 'until docker compose logs mc 2>&1 | grep -q "\[arma\] connected to guest"; do sleep 2; done' \
  || { echo "bridge never connected: rolling back"; exec ./deploy.sh rollback; }
```

What "deployed" means here: guest is healthy (the `hello` healthcheck), MC logs `[arma] connected to guest`
after this start, and `/arma status` shows `round N, cycles > 0` within a round. Adoption
(someone actually playing) is a separate check.

## Rollback

- `.env` keeps `ARMACRAFT_TAG` / `PREV_ARMACRAFT_TAG` (same for the MC image tag). Rollback swaps
  them, re-points `mods -> releases/<prev>/mods` and runs `docker compose up -d`. That takes about
  as long as an MC start (about 20 s here, likely more on uk) and the guest restarts in about 1 s.
- Keep the last 3 guest images and release dirs, and prune older ones (`docker image rm`, `rm -r releases/<old>`)
  inside the disk guard's budget.
- World safety: armacraft's display entities are never saved (EntityMixin) and stale tagged
  entities are refused on load, so mod rollbacks leave no world debris. The pre-deploy tarball
  covers everything else. Restore it only if a release changed world data.
- Protocol mismatch (guest and mod from different tags) shows as `guest hello ... proto N` in
  the MC log and a failed verify step. Always roll both back together.

## Open questions for Jamie

1. Should real Armagetron clients connect to this guest (publish 4534/udp), or is it MC-only?
2. Host: uk (co-located with MinIO, disk-constrained) or an RCL regional host nearer the players?
3. Is `/arma cmd` wanted in prod? If not, `BRIDGE_ALLOW_CMD 0`.
4. Registry: is `docker save | ssh` enough, or should there be a tailnet `registry:2` on uk?

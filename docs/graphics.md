# armacraft graphics: DLSS 5, AI shaders and realism shaders for MC 26.3 on an RTX 3090

Written 2026-10-02. Status words used here: **shipping** means generally available, **announced** means official but not out, **preview** means opt-in, beta or experimental, and **unverified** means secondary sources only. "Tested" means run here, on this PC, in `.work/play-shaders`.

## TL;DR

- **DLSS 5 does not run on a 3090, and nothing brings it to Minecraft.** It shipped on 2026-09-01 (driver 616.64) for RTX 50 only, in one game (NBA 2K27). NVIDIA has given no RTX 30 plan. On Ampere you get DLSS Super Resolution and Ray Reconstruction (transformer models). Frame generation needs RTX 40 or newer.
- **Minecraft Java 26.3 has two render paths that cannot be combined:**
  - OpenGL (the default in 26.3): Iris shaderpacks work.
  - Vulkan (opt-in in 26.3, default from the 26.4 snapshots): the DLSS and RT mods target this, but Iris refuses it. The Iris 26.3 source says: "Iris cannot run when using Vulkan".
  - No DLSS or FSR mod is tagged 26.3 yet. The hardware-RT renderers target 26.2 (Caustica) or 1.21.4 (Radiance).
- **What runs on 26.3 + 3090 today, tested here:** Sodium 0.9.2 + Iris 1.11.7 + Distant Horizons 3.3.4 + Complementary Unbound r5.9.3 + Euphoria Patches 1.10.5. Alpha Piscium 1.10.0 is the most realistic alternative.
- **Under every pack, our ride visuals broke until we fixed them.** Bike, trail walls and light pool rendered as screen-filling garbage triangles. The fix is a pipeline assignment through the Iris API. It is in the `client/` patch, and a shim in the play copy does the same job. With the fix, bike and trails glow and feed bloom in Complementary.
- **Cost of the recommended pack:** about 20–40% of frames on flat ground and about 40–60% over real terrain with DH, while riding at 18 b/s at 1600×900. Shaders on gave 110–160 fps average (1% low 38–90) on superflat, and 55–78 fps on real terrain with DH. These numbers are noisy because your own client was rendering on the same GPU the whole time.
- **The AI realism filter is real but a toy for this game.** StreamDiffusion-class img2img runs locally at 512² and tens of fps, adds latency and flickers. Use it for spectating and trailers, not for racing.

---

## 1. DLSS 5 and neural rendering (October 2026)

| Thing | Status | RTX 3090 (Ampere) | Source |
|---|---|---|---|
| **DLSS 5 "3D-Guided Neural Rendering"**: a generative model that "infuses pixels with photoreal lighting and materials". Input is the frame's colour and motion vectors; it runs "one-frame-in, one-frame-out" | **Shipping** since 2026-09-01: NBA 2K27, driver 616.64, Streamline 2.14+. Announced at GTC on 2026-03-16 | **No.** Supported on "all GeForce RTX 50 Series" only. Wccftech (2026-09-03) quotes NVIDIA planning RTX 40 support later; nothing for RTX 30 | [GeForce news 2026-09-01](https://www.nvidia.com/en-us/geforce/news/dlss-5-3d-guided-neural-rendering/), [driver post 2026-09-03](https://www.nvidia.com/en-us/geforce/news/nba-2k27-dlss-5-3d-guided-neural-rendering-geforce-game-ready-driver/), [dev blog 2026-09-22](https://developer.nvidia.com/blog/whats-new-for-game-developers-dlss-5-with-3d-guided-neural-rendering-nvidia-ace-updates-and-new-rtx-kit-capabilities/), [nvidianews 2026-03-16](https://nvidianews.nvidia.com/news/nvidia-dlss-5-delivers-ai-powered-breakthrough-in-visual-fidelity-for-games) |
| DLSS 5 SDK for developers | Not public. The dev blog only offers "sign up to be notified". Streamline 2.14.1 and DLSS SDK 310.9.1 (2026-09-08) don't include it | — | dev blog above; [Streamline releases](https://github.com/NVIDIA-RTX/Streamline/releases) |
| DLSS 4 transformer Super Resolution, Ray Reconstruction, DLAA | Shipping (since 2025-01) | **Yes** | [GeForce 2025-01-06](https://www.nvidia.com/en-us/geforce/news/dlss4-multi-frame-generation-ai-innovations/) |
| DLSS 4.5 2nd-gen transformer SR (models M/L) | Shipping (2026-01-06) | **Yes, but slower.** NVIDIA says 20/30-series "lack native FP8 support, so Models M and L carry a heavier performance impact" and suggests staying on Model K | [GeForce 2026-01-06](https://www.nvidia.com/en-us/geforce/news/dlss-4-5-dynamic-multi-frame-gen-6x-2nd-gen-transformer-super-res/) |
| Frame Generation / Multi Frame Gen / Dynamic MFG | Shipping | **No.** FG needs RTX 40; MFG and dynamic MFG need RTX 50 | same |
| RTX Neural Shaders (RTXNS 1.4, cooperative vectors) | SDK released. The DX12 path is preview (D3D12 LinAlg, SM 6.10). No shipped game found | Vulkan path: yes (RTX 20+) | [RTXNS](https://github.com/NVIDIA-RTX/RTXNS), [DirectX blog 2026-04-27](https://devblogs.microsoft.com/directx/d3d12-linalg-preview/) |
| RTX Neural Texture Compression 0.10 | Beta | Inference on load: yes. Inference on sample: Ada+ recommended | [RTXNTC](https://github.com/NVIDIA-RTX/RTXNTC) |
| Neural Radiance Cache | Shipping only inside RTX Remix | — | dev blog above |
| RTX Remix 1.5.2 (2026-06-16) | Shipping | Yes. It hooks **DX8/9 fixed-function** games only and has no OpenGL support. A modern-shader OpenGL game like MC Java can't be hooked even with a wrapper. **No Remix-for-Java project exists** | [Remix compatibility docs](https://docs.omniverse.nvidia.com/kit/docs/rtx_remix/latest/docs/introduction/intro-compatibility.html), [releases](https://github.com/NVIDIAGameWorks/rtx-remix/releases) |
| "DLSS 5 in any game" via DLSS5-Feeder + ReShade | **Unofficial.** Uses a runtime that leaked from NBA 2K27 | Needs a *modified* `nvngx_dlssnr.dll` from a Discord. Treat it as an untrusted binary. Reported ~20–30 fps on a 3090/3080 Ti. No Minecraft reports | [DLSS5-Feeder](https://github.com/jlrouzies-fr/DLSS5-Feeder) (unverified figures: [Wccftech 2026-08-29](https://wccftech.com/nvidia-dlss-5-neural-rendering-in-10-modern-games-the-best-unofficial-dlss-5-on-vs-off-comparisons-so-far/), [The PC Enthusiast 2026-08-30](https://thepcenthusiast.com/dlss-5-mod-games-rtx-50-40-30-gpus/)). Don't. |

**Can any of this reach Minecraft Java?**
- Streamline needs DX11/DX12/Vulkan, and NGX ships no OpenGL header.
- So DLSS reaches Java only through Mojang's new Vulkan backend. That backend excludes Iris (next section).

### Mojang: Vulkan and Vibrant Visuals

- **2026-02-18:** Mojang announced it is "switching from OpenGL to Vulkan", with OpenGL to be removed once Vulkan is stable. The stated goal is Vibrant Visuals on Java. [minecraft.net](https://www.minecraft.net/en-us/article/another-step-towards-vibrant-visuals-for-java-edition)
- **26.2 Snapshot 1 (2026-04-07)** added the Vulkan backend and the "Graphics API" option (Default / Prefer Vulkan / Prefer OpenGL). It requires Vulkan 1.2 with dynamic rendering and push descriptors. [minecraft.net](https://www.minecraft.net/en-us/article/minecraft-26-2-snapshot-1)
- **26.3 (current, our version):** Default still means OpenGL. Our own log shows `preferredGraphicsBackend:"default"` and "Using graphics backend OpenGL … RTX 3090".
- **26.4 Snapshot 1 (2026-09-22):** "'Default' now behaves the same as 'Prefer Vulkan'". [minecraft.net](https://www.minecraft.net/en-us/article/minecraft-26-4-snapshot-1)
  - **Implication for us:** after 26.4 the default leaves Iris behind. Keep **Graphics API = Prefer OpenGL** for shaderpacks.
- **Vibrant Visuals for Java: announced, no date.** Bedrock ray tracing (RTX) is maintenance-only ("no further changes are planned", per minecraft.wiki's citation of minecraft.net; not re-checked here).

## 2. Minecraft Java 26.3 graphics mods that exist today

All versions below were checked against the Modrinth API on 2026-10-02. Files were downloaded from `cdn.modrinth.com` and checked against the API's sha512. The full manifest, with sha512, size, URL and version page, is in `.work/play-shaders/graphics-stack.json`.

| Mod / pack | Version for 26.3 | Date | Notes |
|---|---|---|---|
| Sodium | 0.9.2 (release); 0.9.3-alpha.1 also exists | 2026-09-15 | Iris 1.11.7 lists 0.9.2 (`bAZQdGpg`) as its required version on Modrinth; its fabric.mod.json accepts `0.9.x` |
| Iris | 1.11.7+26.3 | 2026-09-30 | "Fixes a lot of 26.3 bugs". **OpenGL only.** It undoes 26.x reverse-Z while a pack is active (`UndoReverseZ*` mixins) |
| Distant Horizons | 3.3.4-26.3 | 2026-09-29 | Works with Iris. Bug seen: DH sends its startup chat warnings during a render pass, which throws `Close the existing render pass…`. The warnings are lost; no other effect seen |
| VulkanMod | latest 0.6.8 is for 26.1.2 | 2026-06-14 | **No 26.3 build**, and moot now that vanilla has Vulkan |
| Radiance (Vulkan C++ path tracer, DLSS RR/SR, FSR3, XeSS) | 0.1.6-alpha for 1.20.1 / 1.21.1 / 1.21.4 only | 2026-09-29 | Not usable on 26.3. 26.2 support was requested ([#264](https://github.com/Minecraft-Radiance/Radiance/issues/264)) |
| Caustica (RT renderer on vanilla's Vulkan backend, DLSS RR, experimental FG; LGPL) | targets 26.2 | 2026-07 | Not 26.3. [GitHub](https://github.com/ComfyFluffy/Caustica) |
| mc-dlss (Streamline DLSS SR presets K/L/M + FG on the Vulkan backend; MIT) | targets 26.2 | 2026-08 | Not 26.3. No Iris. [GitHub](https://github.com/snowmii/mc-dlss) |
| FSR Reloaded (FSR 1/2/3, FSR3 FG) | up to 26.1.2 | 2026-09-13 | Not 26.3 |
| Salt's Anti-Aliasing (TAA, FSR2/3, NIS) | up to 26.2 | 2026-08-01 | Its own page says "DLSS is not functional in the current release" |
| Purescale ("real NVIDIA DLSS", frame-blend FG) | up to 26.2 | 2026-09-11 | Closed source, all rights reserved, unverifiable. Skipped |
| Vulkanite (RT inside Iris packs) | 1.20.1 only | 2023 | Dead |

**Realism shaderpacks.** Which are tagged 26.3, and how they handled our visuals once the fix was in:

| Pack | Version | Tagged 26.3 | Notes |
|---|---|---|---|
| **Complementary Unbound** + **Euphoria Patches** (Fabric mod that patches the pack) | r5.9.3 + 1.10.5-r5.9.3 | yes (2026-09-15) | The most robust choice. Voxel colored lighting (an Iris option), DH support. **spidereyes = glow**, so `emissive` mode works. **Recommended** |
| **Alpha Piscium** | 1.10.0 | yes (2026-09-29) | The most realistic: ReSTIR screen-space GI with ReBLUR denoising, Nubis clouds, Hillaire atmosphere. Iris only, GPL. Its `gbuffers_spidereyes` and `gbuffers_beaconbeam` are **empty**, so use `lit` mode: no light pool, opaque walls. About half the frames |
| **Noble** (BelmuTM) | 1.9.9 | yes (2026-09-28) | Modrinth tags it "path-tracing". Blend is forced off for every gbuffers program, so our additive light pool is opaque (black edges by day). Bike and walls are fine |
| Bliss | 2.1.2 | yes (dated 2025-11) | Not tested |
| Photon | v1.3b | no (tagged up to 26.1.2) | The author says packs rarely depend on MC version. Not tested |
| Solas | 3.7b | no (up to 26.2) | Fantasy style. Not tested |
| Rethinking Voxels | r0.1-beta9 | no (1.21.10) | Stale |
| SEUS PTGI | public: E12 (2020-01), HRR test 2.1 (2020-12); HRR 3 is Patreon-only | — | [sonicether.com](https://www.sonicether.com/seus/). Sites claiming "SEUS PTGI 26.3" are mirrors, not official. Not tested |

**PBR (LabPBR) resource packs:** these are on Modrinth.
- Free and tagged 26.3: Simplista, Bare Bones PBR x128 (MIT), Emissive Ores labPBR.
- Realistic, tagged 26.3-pre: PrettyRealistic PBR 0.44.
  - It is downloaded to `run-client/resourcepacks`.
  - **26.3 release rejected it as incompatible** ("Removed resource pack … no longer compatible") and I did not force it.
- Patrix, the reference realistic LabPBR pack, is not on Modrinth.
- **PBR doesn't touch our bike or walls.** Their textures are generated at runtime (`ArmaTextures`), so there are no `_n`/`_s` maps.

## 3. "AI realism filters" (img2img on game frames)

| Tool | Local? | Reported speed | Source |
|---|---|---|---|
| StreamDiffusion (SD-Turbo, TensorRT) | yes, Apache-2.0 | 1-step img2img **93.9 fps on an RTX 4090** (README table). The paper says "up to 91.07fps". No 3090 figure is published | [repo](https://github.com/cumulo-autumn/StreamDiffusion), [arXiv 2312.12491](https://arxiv.org/abs/2312.12491) |
| StreamDiffusionV2 (Wan2.1 1.3B, rolling KV cache, motion-aware noise) | yes | 64.5 fps on **4×H100**. "20+ fps on a 4090" appears only in secondary sources | [arXiv 2511.07399](https://arxiv.org/abs/2511.07399) |
| MLLM-conditioned edit diffusion (single author, research) | weights out | **3090 Ti: 27–30 fps at 512²** | [arXiv 2606.05981](https://arxiv.org/abs/2606.05981) |
| Decart MirageLSD / Oasis 2.0 Fabric mod | **cloud** | 24 fps, <40 ms (vendor claim). The mod is 1.21.4/1.21.8 only | not self-hosted, skip |

**Feasibility on this 3090, while the game shares the GPU.** These are estimates, not measurements:
- **Throughput and resolution:** a 3090 runs SD-Turbo 1-step at roughly 40–55 fps at 512², about half a 4090. That's before Minecraft takes its share.
- **Latency:** capture plus inference plus display adds about 50–80 ms. At 18 b/s that's 1–1.5 blocks of extra lag on wall reads.
- **Flicker:** per-frame img2img invents detail differently every frame.
  - A thin, fast, glowing trail is the worst case. It will shimmer, smear or be reinterpreted as something else.
  - Depth ControlNet, a fixed seed, low strength and V2-style KV caching reduce this. They don't remove it.
  - DLSS 5 avoids it because it gets engine motion vectors and is deterministic. A screen-capture filter gets neither.

**How to capture locally:**
- There is no Spout mod for Java.
- **Window-capture route:** Windows Graphics Capture (the Python `windows-capture` package), or OBS game capture plus the [obs-spout2-plugin](https://github.com/Off-World-Live/obs-spout2-plugin), feeding TouchDesigner with StreamDiffusionTD, or a Python StreamDiffusion loop showing its output in a second window.
- **Buffer-export route:** read `mainRenderTarget` from a Fabric mod at frame end. armacraft's bench already does this for screenshots. This route could export depth and motion vectors too.

**Verdict:** spectator and streaming eye-candy, or an **offline video-to-video pass on recorded rides** for trailers, run on Colab Pro+ or overnight on the 3090. It is not a play mode.

## 4. Recommendation

**Most impressive stack that actually runs on 26.3 + RTX 3090 today** (installed and tested in `.work/play-shaders`):

1. Graphics API: **Prefer OpenGL**. Iris requires it, and 26.4 will flip the default.
2. Sodium 0.9.2, Iris 1.11.7, Distant Horizons 3.3.4.
3. **Complementary Unbound r5.9.3 + Euphoria Patches 1.10.5.** Optional tuning: turn on colored lighting and Euphoria's extras in the shader menu.
4. **armacraft Iris fix:** `docs/graphics-patches/0001-client-iris-pipeline-programs.patch` for `client/`. Until that is applied, use the shim in the play copy. `render.iris_program=emissive` is the default.
5. **Expected frame rate:** about 110–160 fps on flat ground and about 55–80 fps over real terrain with DH, at 1600×900 with your other client running.

**For stills or slower play:** swap to **Alpha Piscium 1.10.0** with `iris_program=lit`. It has the best sky and GI, costs about half the frames, and drops the light pool.

**Wild option, in order of payoff:**
1. **Hardware path tracing + DLSS Ray Reconstruction on the Vulkan backend.** Caustica, on a 26.2 copy, as a side experiment. RR runs on Ampere.
   - Our mod would need to work on 26.2's Vulkan path.
   - Our custom pipelines would need to work under Caustica's renderer (unknown).
   - You lose Iris.
   - This is the real "RTX" look. It's a separate track, not a toggle.
2. **Local AI realism window.** StreamDiffusion with TensorRT, SD-Turbo at 512², depth ControlNet, fed by Windows Graphics Capture, for spectating.
3. **Offline v2v of recorded rides** on Colab Pro+ (StreamDiffusionV2 or a Wan2.1 v2v pipeline) for trailers.
   - This is the highest quality-per-effort of the three AI options.
   - It is the only one where flicker can be fixed with slow, multi-frame models.

What not to do: the leaked DLSS 5 DLL route (untrusted binary, RTX 50 code path, no Minecraft support); Purescale (closed and unverifiable); Remix (cannot hook MC Java).

---

## 5. What was integrated (Phase 2)

**Location: `.work/play-shaders`.**
- It is a copy of `.work/play`, made without `build/` and `.gradle/`, and including the worlds.
- Nothing in `.work/play`, `host/`, `client/` or `guest/` was modified.
- `client/` was only *read*, and copied to the scratchpad to build and diff the patch.

**Mods and packs (`run-client/`):**
- `mods/`: Sodium 0.9.2, Iris 1.11.7, DH 3.3.4, EuphoriaPatcher 1.10.5-r5.9.3.
- `shaderpacks/`: Complementary Unbound r5.9.3 (plus the Euphoria-patched folder it generates), Alpha Piscium 1.10.0, Noble 1.9.9.
- `resourcepacks/`: PrettyRealistic 0.44 (rejected by 26.3).
- `config/iris.properties`: shaders on, pack set to Complementary + Euphoria.

**`build.gradle` changes in the copy:**
- **Modrinth maven:** an `exclusiveContent` repository for `maven.modrinth`, used only for the compile-only Iris API.
- **`src/iriscompat`** (removed at finalisation): mod id `armacraft_iris_shim`. It did the same `IrisApi.assignPipeline` calls as patch 0001, reflectively, on the unchanged 0.1.2 play jar. It was needed until 0.1.6 shipped IrisCompat.
- **Guest bridge:** `runClient` gets `-Darmacraft.autoConnect=false`. `config/armacraft.json` and `armacraft_client.json` have auto_connect set to false. This stops the copy bridging into the guest your play client is connected to on 127.0.0.1:4535.
- **`-PclientJar=libs/armacraft-client-0.1.2-iris.jar`:** runs a client jar built from `client/` with patch 0001.
  - That jar also contains newer `client/` camera work: ArmaCam04 imports the Armagetron camera settings.
  - Its chase camera sits much farther back.
- **`src/bench` + `runBench`:** mod id `armacraft_bench`, a scripted harness.
  - It opens a world through the client's DevTools.
  - It deploys the cycle and drives zigzag or square-spiral right turns by injecting RideInput key events. Key codes come from `InputConstants`, because 26.x input is SDL-based.
  - It toggles shaders via the Iris API.
  - It takes **same-frame shader/vanilla pairs**: an invisible pause screen freezes the ride, then it shoots, switches shaders, and shoots again.
  - It logs `BENCH` frame-time lines to `run-client/bench-results.tsv`.
  - For the run only, it sets vsync off, a 260 cap, no inactivity limiter and no pause-on-focus-loss, then restores and saves your options on quit.
  - Scripts are in `bench/*.txt`.
  - Usage: `./gradlew runBench --no-configuration-cache -Pbench=<label> -Pbench.world=shaderbench_flat "-Pbench.script=@<abs path>/bench/pairs.txt" [-PclientJar=...]`

**To play with shaders:** from PowerShell, run:
```
cd C:\Users\Jamie\armacraft\.work\play-shaders; $env:JAVA_HOME='C:\Users\Jamie\.jdks\jdk-25.0.4.1+1'; .\gradlew.bat runClient --no-configuration-cache
```
- **Shader menu:** F8 outside cycle mode, or Video Settings → Shader Packs.
- **Alpha Piscium:** set `render.iris_program` to `lit` in `run-client/config/armacraft_client.json`.

### Final state of the shaders copy (2026-10-02, about 13:55)

**What changed in `.work/play-shaders`:**
- **Sources:** `src/` was refreshed from `host/src`, which brings cycle mode, the visual scale and the profile import. `src/bench` is kept.
- **Client jar:** `runClient` now defaults to `libs/armacraft-client-0.1.6.jar`, which is 0.1.5 plus IrisCompat (patch 0001, applied by the coordinator). sha256 is `9746c048…`.
- **Shim removed:** the `src/iriscompat` shim is gone. Its source is backed up in the scratchpad.
- **Configs:** `armacraft-ride.json` and `armacraft_client.json` were copied from `.work/play`.
- **`options.txt`:** it is your play copy's file, plus Iris keys:
  - reload on F6, toggle shaders on F7, pack selection on F8;
  - the defaults were R, K and I, which clash with cycle mode and turn-right.
  - Sodium, DH and Euphoria register no keybinds.
  - DH's debug keys would use F6–F8, but only if `enableDebugKeybindings` is switched on. It is off by default.
- **DH update check:** off (`enableAutoUpdater=false`), so the pinned versions stay as they are.

**Verification run:**
- **Run:** `bench_final016_{day,night}.png`, with Complementary + Euphoria, 0.1.6 and no shim. The log shows IrisCompat assigning all four pipelines and no "Missing program".
- **Visuals:** bike, trail, light pool and HUD render correctly, and the bike blooms at night.
- **Frame rate:** about 106 fps average (1% low about 35) while riding, with your client running.

### Compatibility findings

**Under any pack, the bike, walls and light pool turn into giant garbage triangles.**
- Iris logs `Missing program armacraft_client:pipeline/arma_model|arma_wall|arma_additive in override list`.
- **Cause:**
  - While a pack is active, Iris swaps `DefaultVertexFormat.ENTITY` for its extended format. It does this in `RenderPipeline.getVertexFormatBinding(s)` and `RenderType.format()`, so our buffers get the longer stride.
  - Unassigned pipelines keep the vanilla program with the *original* attribute layout. That is a stride mismatch, which produces garbage positions.
  - They would also miss TAA jitter.
  - Confirmed in Iris 26.3 source: `MixinRenderPipeline`, `MixinShaderManager_Overrides`, `IrisPipelines`.
- **Fix:** `IrisApi.assignPipeline` (API v0.3).
- **Screenshots, broken:** `bench_cu2_*`, `bench_ap1_*`. **Fixed:** `bench_cu3fix_*`, `bench_cu4shim_*`, `bench_cu5_*`, `bench_show1_*`.

**Which program to use depends on the pack:**
- **`emissive`** (gbuffers_spidereyes) is what vanilla uses for the EYES pipeline our pipelines are built from.
  - In Complementary it is unlit and boosted (`pow(color,1.5)*1.5`). Bike, walls and pool **glow and bloom**.
  - Translucency and the dir_wall stripes survive; Complementary sets alphaTest 0.0001 for spidereyes.
  - Downside: by day the bike's body detail washes out.
- **Alpha Piscium stubs spidereyes**: its `main()` is empty, so `emissive` makes the bike invisible.
  - `lit` (gbuffers_entities) renders a properly lit bike. Walls go opaque because AP forces blend off.
  - The light pool stays on spidereyes, so AP doesn't draw it. Routing it through gbuffers_textured gave opaque white quads (`bench_ap2lit_day_shader.png`, from before that change).
- **Noble** forces blend off everywhere, so the additive light pool is opaque: black edges by day (`bench_nb1_day_shader.png`), fine at night.
  - I tried an alpha-weighted (SRC_ALPHA, ONE) pool. It gave opaque lavender quads instead (`bench_nb2fix_day_shader.png`), so it was dropped.
  - With Noble by day, use `ride.light=false`.

**Things that need no fix:**
- Trail top edges and other lines use vanilla `linesTranslucent`, which Iris maps to LINES. They render fine.
- The HUD is fine in every pack.
- OIT pipelines aren't used under Iris; it forces improved transparency off.
- Reverse-Z depth compares are flipped by Iris globally, so ours are covered.

**Glow:**
- In Complementary, `emissive` makes the bike, trails and light pool emissive bloom sources through the standard spidereyes route.
- An `entity.properties` ID or LabPBR emissive isn't usable:
  - Our geometry isn't an entity. Iris captures the *current* entity ID when a custom-geometry submit is made, and nothing is being rendered at that point.
  - Our textures are generated at runtime, so there are no `_s` maps.
- Our geometry casts no shadows under packs. `IrisApi.registerShadowRenderCallback` (API v0.4) could add that.

**Patch:** `docs/graphics-patches/0001-client-iris-pipeline-programs.patch`.
- Unapplied. `git apply --check` is clean against `client/` as of 13:45.
- It adds `render/IrisCompat.java`, `render.iris_program`, a compile-only Iris dependency from Modrinth maven, and one init call.
- The header explains the mechanism and what was checked.

### FPS while riding at 18 b/s

Measured speed was 17.8–18.4 b/s. All rows are in-window frame times over 8 s. Setup: 1600×900 windowed, render distance 16, DH on, vsync off.
- **Your play client was running on the same 3090 the whole time.** nvidia-smi showed 84% utilisation and 15.6 GB in use during one run.
- So absolute numbers move ±30% run to run. Compare on/off within a run.

| Run (screenshots `run-client/screenshots/bench_<run>_*`) | Shaders on, avg fps (1% low) | Off, avg fps (1% low) | On/off |
|---|---|---|---|
| cu5: Complementary+Euphoria, shim, superflat, day | 131 (75) | 213 (112) | 0.61 |
| cu5: same, night | 159 (91) | 273 (139) | 0.58 |
| cu4shim: same pack, day (two windows) | 110 / 121 (61 / 38) | 154 / 153 (90 / 29) | 0.72–0.79 |
| show1: Complementary, New World sky platform, terrain + DH, day | 78 (42) | 133 (76) | 0.59 |
| show1: same, night (p99 61 ms; an autosave landed in the window) | 55 (17) | 133 (67) | 0.41 |
| ap2lit: Alpha Piscium, lit, superflat, day | 80 (40) | 160 (94) | 0.50 |
| ap2lit: same, night | 80 (38) | 195 (84) | 0.41 |
| nb1: Noble, emissive, superflat, day / night | 112 (53) / 118 (68) | 209 (111) / 236 (128) | 0.54 / 0.50 |

The broken-geometry runs (cu2, ap1) and the patched-jar runs (cu3fix, nb2fix, which use a different camera) are in `bench-results.tsv` but left out of this table.

### Screenshots reviewed

All are in `.work/play-shaders/run-client/screenshots/`.
- **Same-frame pairs (`*_shader` / `*_vanilla`):**
  - `bench_cu5_{day,sunset,night,night_b}_*`
  - `bench_show1_{day,sunset,sunset_b,night,night_b}_*`. The best ones: a black platform in the sky, glowing translucent walls with dir_wall stripes, the bike in profile mid-turn at night.
  - `bench_ap2lit_*`, `bench_nb1_*`, `bench_nb2fix_*`
- **Broken state:** `bench_cu2_{day1,sunset2,night1}.png`, `bench_ap1_{day2,night2}.png`.
- **First forest run:** `run1-forest/`. There were no turns yet; it crashed into a tree.

### Untested / open

- **Arena mode** (floor grid, rim, arena walls) under shaders. It uses the same pipelines, so the fix should cover it, but it wasn't run.
- **Multiplayer remote riders under shaders.** Same pipelines; not run.
- **The final patch revision.** Its per-pipeline "already assigned" handling compiled but wasn't re-run in game. The patch's `client/` tests didn't compile *with or without* the patch, because of `client/` camera work in progress, so they weren't run.
- **FPS conditions not measured:** fps without your other client running, fullscreen or 1440p/4K, and with DH off.
- **Pack settings not tuned:** Complementary colored lighting and Euphoria extras weren't enabled. Bliss, Photon and Solas weren't tested.
- **Not done:** the PBR pack (rejected by 26.3; not forced), any Vulkan-backend renderer or DLSS mod (none exists for 26.3), and the AI filter (not built or run).
- **Your session:** my test windows (11 launches, about 2–3 minutes each) shared the GPU with your play session between 13:03 and 13:44.

---

## 6. Textures, tuning, the 60 fps floor, lines, ice, HUD (scratch `.work/play-shaders-tex`, 2026-10-02)

This work was done in a scratch copy of play-shaders; play-shaders itself was not touched.
- **Screenshots:** `.work/play-shaders-tex/run-client/screenshots/`
- **Results:** `run-client/bench-results.tsv`
- **Run logs:** `bench/run-*.log`
- **Deliverable files:** `.work/play-shaders-tex/deliver/`

### Texture packs
- **Best realistic LabPBR pack for 26.3: Patrix 32x basic + addon.**
  - From Modrinth, free tier, pack format 97.1. It has about 11k `_n` maps and 10.7k `_s` maps.
  - Its connected textures need **Continuity 3.0.1+26.3**.
  - Versions and sha512 are in `graphics-stack-tex.json`. 64x and higher resolutions are Patreon-only.
- **PrettyRealistic 0.44** (format 88) does render when force-enabled through `incompatibleResourcePacks`. It looks grittier and flatter than Patrix (`bench_tex1_sunset_{before,patrix,prettyrealistic}.png`).
- **Resolution and VRAM:**
  - The block atlas grows from 2048×2048 (vanilla) to 8192×8192 with Patrix. Iris adds normal and specular atlases of the same size.
  - By arithmetic, that is about 1 GB of VRAM for block textures, against tens of MB without the pack. This was not measured on its own; the 3090 has 24 GB.
- **Cost:** at 5120×1440, Patrix (RP_MODE=3) costs 8–15 fps compared with the same preset using integrated PBR (perf5 and perf7 rows). It never met the 60/50 floor at that resolution.

### Complementary + Euphoria tuning
**Presets** are in `run-client/shader-presets/`:
- **`balanced-labpbr.txt`:**
  - LabPBR, POM 64/32, AF 4, TAA, shadows 2.
  - Volumetric light 2; sunset light-shaft intensity 130, saturation 0.35.
  - Clouds 2 with cloud shadows.
  - Waving 1.25 and interactive foliage.
  - Warm golden-hour light: LIGHT_MORNING 1.20/1.00/0.80, ATM_MORNING 1.10/1.00/0.90. Vibrance 1.10.
- **`tuned-labpbr.txt`:** the full-quality variant.
- **`floorD.txt`:** the preset that meets the fps floor (see below).

**Bike and trail glow** are kept in every preset, because the emissive (spidereyes) route doesn't depend on these options.

**Before/after screenshots** at the same spot and time 12300: `bench_tex3_{vista,foliage,ground}_{before,balanced,full}.png`. Montages: scratchpad `tex3_vista.png`, `tex3_foliage.png`.

### FPS floor (target: average ≥60 and 1% low ≥50 while riding)
**Setup for the floor runs:**
- Only the bench window running, at 5120×1440 fullscreen.
- Autopilot riding at 17–19 b/s through generated plains and river terrain, 60 s per scene.
- `save-off` so no autosave lands in a measurement window.
- ZGC, render distance 8, DH radius 64 with 4 threads, vsync off.

| Preset (run) | Day avg / 1% low | Sunset | Night | Meets floor |
|---|---|---|---|---|
| **floorD** (final60d): integrated PBR, no sun shadows, no volumetrics/SSAO/reflections/TAA/clouds | **99.8 / 61.8** | **90.4 / 62.1** | **90.2 / 60.5** | **yes** |
| floorC (final60c): floorD + low sun shadows (SHADOW_QUALITY 0) | 65.8 / 41.2 | 74.7 / 46.8 | 78.7 / 54.8 | no (lows) |
| floorB (final60b, 40 s): floorC + water reflections 1 | 73.0 / 51.3 | 69.8 / 49.1 | 73.6 / 54.2 | borderline |
| Patrix lite (final60) | 64.5 / 42.7 | 56.9 / 39.8 | 57.3 / 39.5 | no |
| Patrix + balanced at 1468×900 windowed (user1) | 68.4 / 38.9 | 75.0 / 45.0 | 76.5 / 45.3 | no (lows) |
| Shaders off (final60, day) | 141.6 / 85.2 | | | |

**Caveat: the floorC, floorB and final60 runs were not done under the same conditions as floorD.**
- They ran at different times, and desktop apps put a variable load on the GPU. For example, it showed 38% utilisation with no game running at 17:10.
- The floorD vs floorC gap (+34 fps) is larger than the shadow cost seen inside a single run (perf7: +7 fps from turning shadows off).
- An A/B of floorC under floorD's conditions was planned but blocked, because C: ran out of space during the final run.

**What costs the most at this resolution:**
- LabPBR/POM costs the most, followed by sun shadows, volumetrics and SSAO.
- TAA (DETAIL_QUALITY 2) costs 3–8 fps.
- Lowering the DH radius from 256 to 64 helped the 1% lows. Lowering render distance from 16 to 8 helped both average and lows.

**Mod cost**, timed around COLLECT_SUBMITS and the HUD element:
- Bike + trail + light pool: **0.25–0.51 ms/frame**.
- Ride HUD: **0.03–0.06 ms/frame**.
- Neither matters for the floor.

### "Lines are wonky / interact with surface detail"
**Reproduced** with frozen-frame pairs: `bench_lines2_frozen_{def,taaoff,vanilla}.png`. Crops are in the scratchpad: `lines2_frozen_near.png`, `lines4_*`, `lines5_*`, `lines6_*`.

**Cause:**
- Under Complementary in daylight, the wall body is blended into an HDR buffer where sunlit terrain is several times brighter than the wall colour.
- The pack's haze and exposure then make the body pale tan, with terrain showing through.
- The top edge is drawn by the pack's fixed 2 px `gbuffers_line` program.
- Night looks correct.

**Ruled out:**
- A missing pipeline registration: lines map to LINES correctly.
- POM or normals: the problem is there with RP_MODE 1 and with POM off.
- Z-fighting from the 0.015 lift.

**Code experiments, all dropped:**
- Tried in scratch jars `-p2` and `-p3`: camera-facing ribbons, a colour/alpha/brightness scale under packs, and depth-writing walls.
- None gave a material improvement. Ribbons made the edges orange-white.

**No client patch for this.** It is how the pack looks.

### Log errors
- **"Close the existing render pass…"**
  - Source: Distant Horizons sends chat warnings (high vanilla render distance; G1 garbage collector) while a render pass is open.
  - Fix, in config: `showHighVanillaRenderDistanceWarning=false` and `showGarbageCollectorWarning=false` in the delivered `DistantHorizons.toml`. ZGC also removes the cause of the GC warning.
- **"Unexpected; somehow the Opaque + Translucent pass ran with shaders on"**
  - Iris logs it once at world join when DH is loaded. It appears in every run, with or without our geometry.
  - It comes from Iris+DH, not from us. No visible effect was found.

### Ice
**Reproduced** at sunset on a frozen lake: `bench_ice2_lake_*`, `bench_ice5_fix3_*`, `bench_ice6_fix4_*`. Crops: `ice2_crop.png`, `ice5_crop.png`, `ice6_crop.png`.

**It is not shadows.** The dark rectangles stay with SHADOW_QUALITY -1, and they disappear with DH rendering off.

**Cause: Distant Horizons LODs.**
- DH keeps stale LODs of frosted ice and water. Adding `minecraft:frosted_ice` to DH `ignoredRenderBlockCsv` removes the stale rectangles in the distance. That change is in the delivered toml.
- A darker band near the frost strip remains. It is still there with overdraw prevention 1.0 and with fast-movement overdraw reduction off.
- The same dark DH-water rectangle shows on plain river water at render distance 8 (`bench_final60d_D_day.png`, bottom right). So it is DH LOD water against vanilla water under Complementary, not something specific to ice. This part is unresolved.

**Frost settings** (radius, strip width, melt time) are not the root cause, so no change to `armacraft-ride-server.json` is proposed.

### HUD (huge needles, only Boost + speed visible)
- At 1468×900 all gauges render normally (`bench_user1_hud_boost_day.png`, crop `hud_crop.png`).
- ArmaScreen sizes the HUD as a fraction of the window. The reported image fits a crop of a much taller window that cut off the lower gauges.
- Not a client bug, so no patch.

### Patch 0002: ride trails never lowered
- **File:** `docs/graphics-patches/0002-client-ride-trails-never-lowered.patch`. Unapplied; `git apply --check` is clean against `client/`.
- **Change:**
  - `ArmaTrailVisual` drops the per-segment CAMERA_VISIBILITY_LOWER_WALL logic, so trails are always full height.
  - This matches Armagetron 0.4, where only rim walls lower.
  - Rim lowering in `ArenaRenderer`/`ArenaMesher` is untouched.
- **Unit test:** `ArmaTrailVisualTest.rideTrailsAreNeverLowered`. The `client/` tests pass: 67 passed, 1 skipped (the skip was there before).
- **In-game proof**, with CAMERA_VISIBILITY_LOWER_WALL forced to 1: `bench_user1_trail_behind_{day_a,day_b,sunset,night}.png`.

### Untested / open (this section)
- **floorC (with shadows)** was not re-measured under floorD's conditions, because the C: disk filled during that run.
- **Shaders-off** was not measured in the floorD run.
- **floorD windowed at 1468×900** was not run. It is expected to be faster than fullscreen 5120×1440.
- **ZGC vs G1** was not A/B'd.
- **Still open from above:** arena mode, multiplayer, and the near ice band.

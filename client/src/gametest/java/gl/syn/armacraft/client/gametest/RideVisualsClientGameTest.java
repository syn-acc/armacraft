package gl.syn.armacraft.client.gametest;

import gl.syn.armacraft.client.ridekit.ArmaRideVisuals;
import gl.syn.armacraft.client.ridekit.RideTrace;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Ride mode with armacraft_client's visuals, real dev client, real keys, an ordinary generated (hilly) world:
 * deploy with R, drive with A/D, screenshots, and a per-frame camera trace (eye vs bike) summarised as jitter numbers.
 * Select with -Darmacraft_client.gametest=ride (default runs relay and ride).
 */
public class RideVisualsClientGameTest implements FabricClientGameTest {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft_client/ride-gametest");
	private static final int SECOND = 20;

	static boolean selected(String name) {
		String only = System.getProperty("armacraft_client.gametest", "");
		return only.isBlank() || only.equals(name);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if ("camrec".equals(System.getProperty("armacraft_client.gametest", ""))) {
			camRecord(ctx);
			return;
		}
		if ("light".equals(System.getProperty("armacraft_client.gametest", ""))) {
			light(ctx);
			return;
		}
		String pullIn = System.getProperty("armacraft_client.gametest.pullin");
		if (pullIn != null) {
			ctx.runOnClient(mc -> {
				var ride = gl.syn.armacraft.client.ArenaClient.get().config().ride;
				ride.pullIn = pullIn;
				LOG.info("ride.pull_in = {}", ride.pullIn);
			});
		}
		if ("steps".equals(System.getProperty("armacraft_client.gametest", ""))) {
			steps(ctx, System.getProperty("armacraft_client.gametest.tag", "x"));
			return;
		}
		if ("hills".equals(System.getProperty("armacraft_client.gametest", ""))) {
			hills(ctx, System.getProperty("armacraft_client.gametest.tag", "x"));
			return;
		}
		if ("proportions".equals(System.getProperty("armacraft_client.gametest", ""))) {
			proportions(ctx);
			return;
		}
		if ("compare".equals(System.getProperty("armacraft_client.gametest", ""))) {
			compare(ctx, System.getProperty("armacraft_client.gametest.tag", "x"));
			return;
		}
		if (!selected("ride")) {
			return;
		}
		String seed = System.getProperty("armacraft_client.gametest.seed", "armacraft-ride");
		try (TestSingleplayerContext sp = ctx.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> {
				s.setSeed(seed);
				s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			})
			.create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runCommand("time set day");
			sp.getServer().runCommand("weather clear");
			// open, hilly terrain (1-block steps, few trees): nearest meadow / windswept hills / plains
			String biome = System.getProperty("armacraft_client.gametest.biome", "plains");
			int[] at = sp.getServer().computeOnServer(server -> {
				var level = server.overworld();
				var origin = server.getPlayerList().getPlayers().get(0).blockPosition();
				var key = switch (biome) {
					case "hills" -> net.minecraft.world.level.biome.Biomes.WINDSWEPT_HILLS;
					case "plains" -> net.minecraft.world.level.biome.Biomes.PLAINS;
					case "savanna" -> net.minecraft.world.level.biome.Biomes.SAVANNA_PLATEAU;
					default -> net.minecraft.world.level.biome.Biomes.MEADOW;
				};
				var found = level.findClosestBiome3d(h -> h.is(key), origin, 6400, 32, 64);
				return found == null ? null : new int[] {found.getFirst().getX(), found.getFirst().getZ()};
			});
			if (at != null) {
				LOG.info("riding in {} at {} {}", biome, at[0], at[1]);
				sp.getServer().runCommand("spreadplayers " + at[0] + " " + at[1] + " 0 4 false @a");
				ctx.waitTicks(40);
				sp.getConnection().waitForChunksRender();
			} else {
				LOG.info("no {} biome nearby, riding at spawn", biome);
			}
			ctx.waitTicks(20);
			check(ctx.computeOnClient(mc -> RideClientHooks.usesArmaCamera() && RideClientHooks.usesArmaBike() && RideClientHooks.usesArmaHud() && RideClientHooks.usesArmaTrailAndEngine()),
				"armacraft did not pick up armacraft_client's ride visuals: " + ctx.computeOnClient(mc -> RideClientHooks.describe()));

			ctx.runOnClient(mc -> ArmaRideVisuals.CAMERA.trace().clear());
			faceOpenGround(ctx);
			ctx.getInput().pressKey(RideClientHooks.rideKey());
			ctx.waitFor(mc -> RideClientHooks.riding(), 10 * SECOND);
			ctx.waitTicks(30);
			ctx.takeScreenshot("ride-arma-1-deployed");
			log(ctx, "deployed");
			// armacraft's wall warning in the Armagetron HUD: ride until it shows (the test rides into terrain often)
			for (int i = 0; i < 400; i++) {
				if (ctx.computeOnClient(mc -> ArmaRideVisuals.HUD.warningOn())) {
					ctx.takeScreenshot("ride-arma-wall-warning");
					LOG.info("WALL warning shown after {} ticks", i);
					break;
				}
				if (i % 40 == 39) {
					redeployIfCrashed(ctx);
				}
				ctx.waitTick();
			}
			// broadside: orbit the camera with mouse look to see the wall from the side, then a corner after a turn
			sideShot(ctx, "ride-arma-trail-side");
			if (RideClientHooks.riding()) {
				ctx.getInput().pressKey(o -> o.keyRight);
				ctx.waitTicks(16);
				sideShot(ctx, "ride-arma-trail-corner");
			}

			// A/D turns with straights in between; screenshot mid-swing and after each pair of legs
			int[] plan = {0, -1};
			int shot = 2;
			for (int i = 0; i < plan.length; i++) {
				redeployIfCrashed(ctx);
				if (plan[i] > 0) {
					ctx.getInput().pressKey(o -> o.keyRight);
				} else if (plan[i] < 0) {
					ctx.getInput().pressKey(o -> o.keyLeft);
				}
				if (plan[i] != 0 && shot < 5) {
					ctx.waitTicks(3);
					ctx.takeScreenshot("ride-arma-" + (shot++) + "-turning");
				}
				ctx.waitTicks(35);
				log(ctx, "leg " + i);
				if (i % 3 == 2) {
					ctx.takeScreenshot("ride-arma-" + (shot++) + "-leg" + i);
				}
			}
			// Armagetron glance left (host: Q held), on a live ride
			redeployIfCrashed(ctx);
			ctx.waitTicks(10);
			redeployIfCrashed(ctx);
			float yawBefore = ctx.computeOnClient(mc -> ArmaRideVisuals.CAMERA.yaw());
			ctx.getInput().holdKey(com.mojang.blaze3d.platform.InputConstants.KEY_Q);
			ctx.waitTicks(12);
			ctx.takeScreenshot("ride-arma-8-glance-left");
			float yawGlance = ctx.computeOnClient(mc -> ArmaRideVisuals.CAMERA.yaw());
			LOG.info("glance: camera yaw {} -> {} while Q held (riding {})", yawBefore, yawGlance, ctx.computeOnClient(mc -> RideClientHooks.riding()));
			ctx.getInput().releaseKey(com.mojang.blaze3d.platform.InputConstants.KEY_Q);
			ctx.waitTicks(20);
			ctx.takeScreenshot("ride-arma-9-glance-released");
			RideTrace trace = ArmaRideVisuals.CAMERA.trace();
			String summary = ctx.computeOnClient(mc -> trace.summary());
			LOG.info("CAMERA TRACE: {}", summary);
			List<RideTrace.Sample> samples = ctx.computeOnClient(mc -> trace.samples());
			long pulled = samples.stream().filter(x -> x.pull() < 0.999).count();
			LOG.info("CAMERA TRACE: {} of {} frames pulled in by terrain (min pull {}), blocks that pulled it in: {}", pulled, samples.size(),
				String.format("%.2f", samples.stream().mapToDouble(RideTrace.Sample::pull).min().orElse(1)), gl.syn.armacraft.client.ridekit.ArmaChaseCamera.PULL_HITS);
			int from = Math.max(0, samples.size() - 12);
			for (RideTrace.Sample x : samples.subList(from, samples.size())) {
				LOG.info(String.format("frame %.1f ms: eye (%.3f, %.3f, %.3f) bike (%.3f, %.3f, %.3f) eye-bike (%.3f, %.3f, %.3f) yaw %.2f pitch %.2f pull %.3f",
					(x.nanos() - samples.get(from).nanos()) / 1e6, x.ex(), x.ey(), x.ez(), x.bx(), x.by(), x.bz(),
					x.ex() - x.bx(), x.ey() - x.by(), x.ez() - x.bz(), x.yaw(), x.pitch(), x.pull()));
			}
			if (RideClientHooks.riding()) {
				ctx.getInput().pressKey(o -> o.keyShift);
				ctx.waitFor(mc -> !RideClientHooks.riding(), 5 * SECOND);
			}
		}
	}

	/**
	 * Records the camera targets of real rides over plains and windswept hills (dt + Target per frame) plus the
	 * heightmap around the path, for offline camera replays (ArmaChaseCameraReplayTest). Short; closes itself.
	 */
	private void camRecord(ClientGameTestContext ctx) {
		java.nio.file.Path out = java.nio.file.Path.of(System.getProperty("armacraft_client.gametest.out", "camrec"));
		try (TestSingleplayerContext sp = ctx.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> {
				s.setSeed(System.getProperty("armacraft_client.gametest.seed", "armacraft-ride"));
				s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			})
			.create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runCommand("time set day");
			sp.getServer().runCommand("weather clear");
			for (String biome : new String[] {"plains", "hills"}) {
				var key = "hills".equals(biome) ? net.minecraft.world.level.biome.Biomes.WINDSWEPT_HILLS : net.minecraft.world.level.biome.Biomes.PLAINS;
				int[] at = sp.getServer().computeOnServer(server -> {
					var found = server.overworld().findClosestBiome3d(h -> h.is(key), server.getPlayerList().getPlayers().get(0).blockPosition(), 6400, 32, 64);
					return found == null ? null : new int[] {found.getFirst().getX(), found.getFirst().getZ()};
				});
				if (at == null) {
					LOG.info("camrec: no {} nearby", biome);
					continue;
				}
				if (RideClientHooks.riding()) {
					ctx.getInput().pressKey(o -> o.keyShift);
					ctx.waitFor(mc -> !RideClientHooks.riding(), 5 * SECOND);
				}
				sp.getServer().runCommand("spreadplayers " + at[0] + " " + at[1] + " 0 4 false @a");
				ctx.waitTicks(30);
				sp.getConnection().waitForChunksRender();
				java.util.List<double[]> rec = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
				faceOpenGround(ctx);
				ctx.getInput().pressKey(RideClientHooks.rideKey());
				ctx.waitFor(mc -> RideClientHooks.riding(), 10 * SECOND);
				ctx.runOnClient(mc -> {
					ArmaRideVisuals.CAMERA.trace().clear();
					gl.syn.armacraft.client.ridekit.ArmaChaseCamera.RECORD = rec;
				});
				int[] plan = {0, 0, +1, 0, -1, 0, -1, 0};
				for (int i = 0; i < plan.length; i++) {
					if (!ctx.computeOnClient(mc -> RideClientHooks.riding())) {
						ctx.runOnClient(mc -> gl.syn.armacraft.client.ridekit.ArmaChaseCamera.RECORD = null);
						redeployIfCrashed(ctx);
						ctx.runOnClient(mc -> gl.syn.armacraft.client.ridekit.ArmaChaseCamera.RECORD = rec);
					}
					if (plan[i] > 0) {
						ctx.getInput().pressKey(o -> o.keyRight);
					} else if (plan[i] < 0) {
						ctx.getInput().pressKey(o -> o.keyLeft);
					}
					ctx.waitTicks(30);
					if (i == 1) {
						ctx.takeScreenshot("camrec-" + biome + "-straight-behind");
					}
				}
				ctx.runOnClient(mc -> gl.syn.armacraft.client.ridekit.ArmaChaseCamera.RECORD = null);
				String live = ctx.computeOnClient(mc -> ArmaRideVisuals.CAMERA.trace().summary());
				LOG.info("camrec {}: {} frames; live trace: {}", biome, rec.size(), live);
				// heightmap around the path
				StringBuilder hm = new StringBuilder();
				ctx.runOnClient(mc -> {
					java.util.Set<Long> done = new java.util.HashSet<>();
					synchronized (rec) {
						for (double[] r : rec) {
							int cx = (int) Math.floor(r[1]);
							int cz = (int) Math.floor(r[3]);
							for (int dx = -18; dx <= 18; dx++) {
								for (int dz = -18; dz <= 18; dz++) {
									int x = cx + dx;
									int z = cz + dz;
									if (done.add(((long) x << 32) ^ (z & 0xffffffffL)) && mc.level.hasChunk(x >> 4, z >> 4)) {
										hm.append(x).append(',').append(z).append(',')
											.append(mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)).append('\n');
									}
								}
							}
						}
					}
				});
				try {
					java.nio.file.Files.createDirectories(out);
					StringBuilder fr = new StringBuilder();
					synchronized (rec) {
						for (double[] r : rec) {
							for (int k = 0; k < r.length; k++) {
								fr.append(k == 0 ? "" : ",").append(r[k]);
							}
							fr.append('\n');
						}
					}
					java.nio.file.Files.writeString(out.resolve(biome + "-frames.csv"), fr);
					java.nio.file.Files.writeString(out.resolve(biome + "-heights.csv"), hm);
				} catch (java.io.IOException e) {
					throw new RuntimeException(e);
				}
			}
			if (RideClientHooks.riding()) {
				ctx.getInput().pressKey(o -> o.keyShift);
				ctx.waitFor(mc -> !RideClientHooks.riding(), 5 * SECOND);
			}
		}
	}

	/** Bike light: night and day screenshots on plains, frame time and light-pool CPU cost with the light off vs on. */
	private void light(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> {
				s.setSeed(System.getProperty("armacraft_client.gametest.seed", "armacraft-ride"));
				s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			})
			.create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runCommand("weather clear");
			sp.getServer().runCommand("gamerule advance_time false");
			int[] at = sp.getServer().computeOnServer(server -> {
				var found = server.overworld().findClosestBiome3d(h -> h.is(net.minecraft.world.level.biome.Biomes.PLAINS),
					server.getPlayerList().getPlayers().get(0).blockPosition(), 6400, 32, 64);
				return found == null ? null : new int[] {found.getFirst().getX(), found.getFirst().getZ()};
			});
			if (at != null) {
				sp.getServer().runCommand("spreadplayers " + at[0] + " " + at[1] + " 0 4 false @a");
				ctx.waitTicks(30);
				sp.getConnection().waitForChunksRender();
			}
			for (String time : new String[] {"night", "day"}) {
				// an open, leaf-free, flat-ish spot with a long clear run east
				String tp = sp.getServer().computeOnServer(server -> {
					var pl = server.getPlayerList().getPlayers().get(0);
					return openSpot(server.overworld(), pl.getBlockX(), pl.getBlockZ());
				});
				LOG.info("LIGHT spot: {}", tp);
				if (tp != null) {
					if (RideClientHooks.riding()) {
						ctx.getInput().pressKey(o -> o.keyShift);
						ctx.waitFor(mc -> !RideClientHooks.riding(), 5 * SECOND);
						ctx.waitTicks(10);
					}
					sp.getServer().runCommand(tp);
					ctx.waitTicks(30);
					sp.getConnection().waitForChunksRender();
				}
				sp.getServer().runCommand("time set " + (time.equals("night") ? "midnight" : "noon"));
				ctx.waitTicks(10);
				if (RideClientHooks.riding()) {
					ctx.getInput().pressKey(o -> o.keyShift);
					ctx.waitFor(mc -> !RideClientHooks.riding(), 5 * SECOND);
					ctx.waitTicks(10);
				}
				ctx.runOnClient(mc -> {
					mc.player.setYRot(-90f);
					mc.player.yRotO = -90f;
				});
				ctx.getInput().pressKey(RideClientHooks.rideKey());
				ctx.waitFor(mc -> RideClientHooks.riding(), 10 * SECOND);
				double[] ms = new double[2];
				for (int phase = 0; phase < 2; phase++) {
					boolean on = phase == 1;
					if (!ctx.computeOnClient(mc -> RideClientHooks.riding())) {
						String tp3 = sp.getServer().computeOnServer(server -> {
							var pl = server.getPlayerList().getPlayers().get(0);
							return openSpot(server.overworld(), pl.getBlockX(), pl.getBlockZ());
						});
						if (tp3 != null) {
							sp.getServer().runCommand(tp3);
							ctx.waitTicks(20);
						}
						ctx.runOnClient(mc -> {
							mc.player.setYRot(-90f);
							mc.player.yRotO = -90f;
						});
						ctx.getInput().pressKey(RideClientHooks.rideKey());
						ctx.waitFor(mc -> RideClientHooks.riding(), 10 * SECOND);
					}
					ctx.runOnClient(mc -> {
						mc.gui.hud.getChat().clearMessages(false);
						gl.syn.armacraft.client.ArenaClient.get().config().ride.light = on;
						gl.syn.armacraft.client.ridekit.BikeGlow.resetStats();
						ArmaRideVisuals.CAMERA.trace().clear();
					});
					ctx.waitTicks(30);
					ms[phase] = ctx.computeOnClient(mc -> {
						var sm = ArmaRideVisuals.CAMERA.trace().samples();
						return sm.size() < 2 ? Double.NaN : (sm.getLast().nanos() - sm.getFirst().nanos()) / 1e6 / (sm.size() - 1);
					});
					if (on) {
						String st = ctx.computeOnClient(mc -> gl.syn.armacraft.client.ridekit.BikeGlow.stats());
						LOG.info("LIGHT {}: light-pool cost {}", time, st);
						ctx.takeScreenshot("light-" + time + "-behind");
						sideShot(ctx, "light-" + time + "-side");
					} else {
						ctx.takeScreenshot("light-" + time + "-off");
					}
				}
				LOG.info("LIGHT {}: frame time light off {} ms, on {} ms", time, String.format("%.2f", ms[0]), String.format("%.2f", ms[1]));
			}
			if (RideClientHooks.riding()) {
				ctx.getInput().pressKey(o -> o.keyShift);
				ctx.waitFor(mc -> !RideClientHooks.riding(), 5 * SECOND);
			}
		}
	}

	/**
	 * A staircase of 1-block steps (3 blocks each): ten up, a plateau, ten down, ridden straight east with the user's
	 * camera. Writes the per-frame eye-to-bike distance trace to build/clientGametest/steps-&lt;tag&gt;.csv.
	 */
	private void steps(ClientGameTestContext ctx, String tag) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().adjustSettings(c -> c.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE)).create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runCommand("gamerule advance_time false");
			sp.getServer().runCommand("time set noon");
			sp.getServer().runOnServer(srv -> gl.syn.armacraft.Armacraft.ride().config().lethalTrails = false);
			int y = sp.getServer().computeOnServer(srv -> srv.overworld().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0));
			for (int i = 1; i <= 10; i++) {
				int x0 = 20 + 3 * (i - 1);
				sp.getServer().runCommand("fill " + x0 + " " + y + " -4 " + (x0 + 2) + " " + (y + i - 1) + " 4 minecraft:stone");
				int x1 = 60 + 3 * (10 - i);
				sp.getServer().runCommand("fill " + x1 + " " + y + " -4 " + (x1 + 2) + " " + (y + i - 1) + " 4 minecraft:stone");
			}
			sp.getServer().runCommand("fill 50 " + y + " -4 59 " + (y + 9) + " 4 minecraft:stone");
			sp.getServer().runOnServer(srv -> srv.getPlayerList().getPlayers().get(0).teleportTo(srv.overworld(), 0.5, y, 0.5, java.util.Set.of(), -90f, 15f, true));
			ctx.waitTicks(20);
			ctx.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
			ctx.getInput().pressKey(RideClientHooks.rideKey());
			ctx.waitFor(mc -> RideClientHooks.riding(), 10 * SECOND);
			ctx.runOnClient(mc -> ArmaRideVisuals.CAMERA.trace().clear());
			boolean shotUp = false, shotDown = false;
			for (int i = 0; i < 140; i++) {
				double x = ctx.computeOnClient(mc -> mc.player.getX());
				if (!shotUp && x > 34) {
					shotUp = true;
					ctx.takeScreenshot("steps-" + tag + "-up");
				}
				if (!shotDown && x > 72) {
					shotDown = true;
					ctx.takeScreenshot("steps-" + tag + "-down");
				}
				if (x > 100 || !ctx.computeOnClient(mc -> RideClientHooks.riding())) {
					break;
				}
				ctx.waitTick();
			}
			List<RideTrace.Sample> sm = ctx.computeOnClient(mc -> ArmaRideVisuals.CAMERA.trace().samples());
			StringBuilder csv = new StringBuilder("ms,bx,by,bz,ex,ey,ez,dist,pull,lift,pitch\n");
			double min = 1e9, max = 0, flat = Double.NaN;
			int pulled = 0;
			for (RideTrace.Sample q : sm) {
				csv.append(String.format(java.util.Locale.ROOT, "%.1f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.2f\n", (q.nanos() - sm.get(0).nanos()) / 1e6,
					q.bx(), q.by(), q.bz(), q.ex(), q.ey(), q.ez(), q.dist(), q.pull(), q.lift(), q.pitch()));
				if (q.bx() > 12 && q.bx() < 19) {
					flat = q.dist();
				}
				if (q.bx() > 19 && q.bx() < 92) {
					min = Math.min(min, q.dist());
					max = Math.max(max, q.dist());
					if (q.pull() < 0.999) {
						pulled++;
					}
				}
			}
			try {
				java.nio.file.Files.writeString(java.nio.file.Path.of("steps-" + tag + ".csv"), csv);
			} catch (java.io.IOException e) {
				throw new RuntimeException(e);
			}
			LOG.info(String.format(java.util.Locale.ROOT, "STEPS %s: %d frames; eye-to-bike distance on the flat before the stairs %.2f; over the stairs min %.2f max %.2f blocks;"
				+ " frames pulled in %d; riding at end %s", tag, sm.size(), flat, min, max, pulled, RideClientHooks.riding()));

			// corner: a right turn on the flat after the stairs; per-frame view yaw and distance change through it
			if (ctx.computeOnClient(mc -> RideClientHooks.riding())) {
				ctx.runOnClient(mc -> ArmaRideVisuals.CAMERA.trace().clear());
				ctx.waitTicks(4);
				ctx.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard.l"));
				ctx.waitTicks(30);
				List<RideTrace.Sample> tm = ctx.computeOnClient(mc -> ArmaRideVisuals.CAMERA.trace().samples());
				double maxYaw = 0, maxDd = 0, maxEye = 0, yaw0 = tm.isEmpty() ? 0 : tm.get(0).yaw(), yaw1 = tm.isEmpty() ? 0 : tm.get(tm.size() - 1).yaw();
				for (int i = 1; i < tm.size(); i++) {
					RideTrace.Sample u = tm.get(i - 1), v = tm.get(i);
					maxYaw = Math.max(maxYaw, Math.abs(net.minecraft.util.Mth.wrapDegrees(v.yaw() - u.yaw())));
					maxDd = Math.max(maxDd, Math.abs(v.dist() - u.dist()));
					maxEye = Math.max(maxEye, Math.sqrt(Math.pow(v.ex() - u.ex(), 2) + Math.pow(v.ey() - u.ey(), 2) + Math.pow(v.ez() - u.ez(), 2)));
				}
				LOG.info(String.format(java.util.Locale.ROOT, "CORNER %s: view yaw %.1f -> %.1f over %d frames; per frame max: yaw %.2f deg, eye-to-bike distance %.3f blocks, eye move %.3f blocks",
					tag, yaw0, yaw1, tm.size(), maxYaw, maxDd, maxEye));
				ctx.takeScreenshot("steps-" + tag + "-corner");
			}
			if (RideClientHooks.riding()) {
				ctx.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard.backspace"));
				ctx.waitTicks(10);
			}
		}
	}

	/** Natural hills (generated world): eye-to-bike distance range and pull-ins over ~9 s of riding. */
	private void hills(ClientGameTestContext ctx, String tag) {
		try (TestSingleplayerContext sp = ctx.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(c -> {
				c.setSeed(System.getProperty("armacraft_client.gametest.seed", "armacraft-ride"));
				c.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			})
			.create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runCommand("gamerule advance_time false");
			sp.getServer().runCommand("time set noon");
			sp.getServer().runOnServer(srv -> gl.syn.armacraft.Armacraft.ride().config().lethalTrails = false);
			int[] at = sp.getServer().computeOnServer(server -> {
				var found = server.overworld().findClosestBiome3d(h -> h.is(net.minecraft.world.level.biome.Biomes.WINDSWEPT_HILLS),
					server.getPlayerList().getPlayers().get(0).blockPosition(), 6400, 32, 64);
				return found == null ? null : new int[] {found.getFirst().getX(), found.getFirst().getZ()};
			});
			if (at != null) {
				sp.getServer().runCommand("spreadplayers " + at[0] + " " + at[1] + " 0 4 false @a");
				ctx.waitTicks(40);
				sp.getConnection().waitForChunksRender();
			}
			ctx.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
			// controlled comparison: three cameras on the same ride, one per ride.pull_in mode
			String[] modes = {"off", "tall_only", "on"};
			ctx.runOnClient(mc -> gl.syn.armacraft.client.ridekit.ArmaChaseCamera.SHADOWS = java.util.Arrays.stream(modes)
				.map(ArmaRideVisuals.CAMERA::shadow).toList());
			faceOpenGround(ctx);
			ctx.getInput().pressKey(RideClientHooks.rideKey());
			ctx.waitFor(mc -> RideClientHooks.riding(), 10 * SECOND);
			ctx.runOnClient(mc -> {
				ArmaRideVisuals.CAMERA.trace().clear();
				gl.syn.armacraft.client.ridekit.ArmaChaseCamera.SHADOWS.forEach(s -> s.trace().clear());
				gl.syn.armacraft.client.ridekit.ArmaChaseCamera.PULL_HITS.clear();
			});
			String[] plan = {"", "", "l", "", "d", "", "d", "", "l"};
			for (int i = 0; i < plan.length; i++) {
				if (!ctx.computeOnClient(mc -> RideClientHooks.riding())) {
					// cycle mode death cam: a turn key redeploys (after a short minimum), or the auto redeploy does
					ctx.waitTicks(12);
					faceOpenGround(ctx);
					ctx.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard.d"));
					ctx.waitFor(mc -> RideClientHooks.riding(), 10 * SECOND);
				}
				if (!plan[i].isEmpty()) {
					ctx.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard." + plan[i]));
				}
				ctx.waitTicks(20);
				if (i == 1 || i == 3 || i == 6) {
					ctx.takeScreenshot("steps-" + tag + "-hills-" + i);
				}
			}
			for (int m = 0; m < modes.length; m++) {
				int mi = m;
				String line = ctx.computeOnClient(mc -> {
					List<RideTrace.Sample> tm = gl.syn.armacraft.client.ridekit.ArmaChaseCamera.SHADOWS.get(mi).trace().samples();
					double mn = 1e9, mx = 0, sm = 0, dd = 0;
					int pulled = 0, close = 0, hidden = 0, inBlock = 0;
					StringBuilder sc = new StringBuilder("ms,bx,by,bz,ex,ey,ez,dist,pull,lift,pitch,hidden,in_block\n");
					for (int i = 0; i < tm.size(); i++) {
						RideTrace.Sample q = tm.get(i);
						net.minecraft.world.phys.Vec3 eye = new net.minecraft.world.phys.Vec3(q.ex(), q.ey(), q.ez());
						boolean hid = gl.syn.armacraft.client.ridekit.ArmaChaseCamera.hidden(eye, new net.minecraft.world.phys.Vec3(q.bx(), q.by(), q.bz()));
						boolean in = gl.syn.armacraft.client.ridekit.ArmaChaseCamera.inBlock(eye);
						sc.append(String.format(java.util.Locale.ROOT, "%.1f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.2f,%d,%d\n", (q.nanos() - tm.get(0).nanos()) / 1e6,
							q.bx(), q.by(), q.bz(), q.ex(), q.ey(), q.ez(), q.dist(), q.pull(), q.lift(), q.pitch(), hid ? 1 : 0, in ? 1 : 0));
						mn = Math.min(mn, q.dist());
						mx = Math.max(mx, q.dist());
						sm += q.dist();
						pulled += q.pull() < 0.999 ? 1 : 0;
						close += q.dist() < 8 ? 1 : 0;
						hidden += hid ? 1 : 0;
						inBlock += in ? 1 : 0;
						if (i > 0 && q.nanos() - tm.get(i - 1).nanos() < 100_000_000L) {
							dd = Math.max(dd, Math.abs(q.dist() - tm.get(i - 1).dist()));
						}
					}
					try {
						java.nio.file.Files.writeString(java.nio.file.Path.of("hills-" + tag + "-ab-" + modes[mi] + ".csv"), sc);
					} catch (java.io.IOException e) {
						throw new RuntimeException(e);
					}
					return String.format(java.util.Locale.ROOT, "HILLS-AB %s pull_in=%s: %d frames; distance mean %.2f min %.2f max %.2f; under 8 blocks %d; pulled in %d;"
						+ " bike hidden %d; eye inside a block %d; max change per frame %.3f", tag, modes[mi], tm.size(), tm.isEmpty() ? 0 : sm / tm.size(), mn, mx, close, pulled,
						hidden, inBlock, dd);
				});
				LOG.info(line);
			}
			ctx.runOnClient(mc -> gl.syn.armacraft.client.ridekit.ArmaChaseCamera.SHADOWS = null);
			List<RideTrace.Sample> hm = ctx.computeOnClient(mc -> ArmaRideVisuals.CAMERA.trace().samples());
			double hmin = 1e9, hmax = 0, sum = 0, maxDd = 0;
			int hp = 0, close = 0;
			StringBuilder hc = new StringBuilder("ms,bx,by,bz,ex,ey,ez,dist,pull,lift,pitch\n");
			for (int i = 0; i < hm.size(); i++) {
				RideTrace.Sample q = hm.get(i);
				hc.append(String.format(java.util.Locale.ROOT, "%.1f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.2f\n", (q.nanos() - hm.get(0).nanos()) / 1e6,
					q.bx(), q.by(), q.bz(), q.ex(), q.ey(), q.ez(), q.dist(), q.pull(), q.lift(), q.pitch()));
				hmin = Math.min(hmin, q.dist());
				hmax = Math.max(hmax, q.dist());
				sum += q.dist();
				if (q.pull() < 0.999) {
					hp++;
				}
				if (q.dist() < 8) {
					close++;
				}
				if (i > 0 && q.nanos() - hm.get(i - 1).nanos() < 100_000_000L) {
					maxDd = Math.max(maxDd, Math.abs(q.dist() - hm.get(i - 1).dist()));
				}
			}
			try {
				java.nio.file.Files.writeString(java.nio.file.Path.of("hills-" + tag + ".csv"), hc);
			} catch (java.io.IOException e) {
				throw new RuntimeException(e);
			}
			String hits = ctx.computeOnClient(mc -> gl.syn.armacraft.client.ridekit.ArmaChaseCamera.PULL_HITS.toString());
			LOG.info(String.format(java.util.Locale.ROOT, "HILLS %s: %d frames; eye-to-bike distance mean %.2f min %.2f max %.2f blocks; frames under 8 blocks %d; frames pulled in %d;"
				+ " max change per frame %.3f; blockers %s", tag, hm.size(), hm.isEmpty() ? 0 : sum / hm.size(), hmin, hmax, close, hp, maxDd, hits));
			if (RideClientHooks.riding()) {
				ctx.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard.backspace"));
				ctx.waitTicks(5);
			}
		}
	}

	/** Bike vs trail proportions: side view, a turn corner at night, and the bike stopped at a 2-block wall by rubber. */
	private void proportions(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().adjustSettings(c -> c.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE)).create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runCommand("gamerule advance_time false");
			sp.getServer().runCommand("time set midnight");
			sp.getServer().runOnServer(srv -> gl.syn.armacraft.Armacraft.ride().config().lethalTrails = false);
			// a long rubber budget so the bike is held at the wall long enough to photograph (rcl preset: 3 blocks = 0.17 s)
			sp.getServer().runOnServer(srv -> gl.syn.armacraft.Armacraft.ride().config().overrides.put("CYCLE_RUBBER", 60.0));
			int y = sp.getServer().computeOnServer(srv -> srv.overworld().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0));
			sp.getServer().runCommand("fill 40 " + y + " -12 40 " + (y + 1) + " 12 minecraft:stone");
			sp.getServer().runOnServer(srv -> srv.getPlayerList().getPlayers().get(0).teleportTo(srv.overworld(), 0.5, y, 0.5, java.util.Set.of(), -90f, 15f, true));
			ctx.waitTicks(20);
			ctx.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
			ctx.getInput().pressKey(RideClientHooks.rideKey());
			ctx.waitFor(mc -> RideClientHooks.riding(), 10 * SECOND);
			ctx.waitTicks(16);
			orbitShot(ctx, "prop-side", 90f, 0f);
			ctx.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard.l")); // turn right (user keymap)
			ctx.waitTicks(7);
			orbitShot(ctx, "prop-corner", 140f, 20f);
			ctx.waitTicks(1);
			ctx.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard.d")); // turn left: east again, at the wall
			for (int i = 0; i < 200; i++) {
				double[] st = ctx.computeOnClient(mc -> new double[] {mc.player.getX(), gl.syn.armacraft.client.ride.RideClientMod.client().movement() == null ? -1
					: gl.syn.armacraft.client.ride.RideClientMod.client().movement().speed(), RideClientHooks.riding() ? 1 : 0});
				if (st[2] < 1) {
					LOG.info("PROP not riding any more at x {}", st[0]);
					break;
				}
				if (st[0] > 39.5) {
					break; // at the wall: rubber holds the cycle (at full speed, as in Armagetron)
				}
				ctx.waitTick();
			}
			String at = ctx.computeOnClient(mc -> {
				var lp = gl.syn.armacraft.client.ridekit.ArmaBikeVisual.lastLocal;
				double x = mc.player.getX();
				double vis = lp == null ? 1 : lp.scale();
				return String.format(java.util.Locale.ROOT, "pos x %.3f, wall face x 40.000: pos-to-wall %.3f blocks; visual scale %.2f -> nose (pos + .39 u) at %.3f = %.3f blocks %s the face; "
					+ "rear wheel back at %.3f; player box front at %.3f; speed %.2f", x, 40 - x, vis, x + .39 * vis, Math.abs(x + .39 * vis - 40),
					x + .39 * vis > 40 ? "past" : "short of", x - 1.12 * vis, x + 0.3,
					gl.syn.armacraft.client.ride.RideClientMod.client().movement() == null ? -1 : gl.syn.armacraft.client.ride.RideClientMod.client().movement().speed());
			});
			LOG.info("PROP at the wall: {}", at);
			orbitShot(ctx, "prop-wall-side", 90f, 10f);
			orbitShot(ctx, "prop-wall-behind", 25f, 0f);
			if (RideClientHooks.riding()) {
				ctx.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard.backspace"));
				ctx.waitTicks(5);
			}
		}
	}

	/** Orbit the camera with mouse look (yaw/pitch offsets), screenshot, restore. */
	private static void orbitShot(ClientGameTestContext ctx, String name, float dyaw, float dpitch) {
		ctx.runOnClient(mc -> {
			mc.player.setYRot(mc.player.getYRot() + dyaw);
			mc.player.yRotO = mc.player.getYRot();
			mc.player.setXRot(mc.player.getXRot() + dpitch);
			mc.player.xRotO = mc.player.getXRot();
		});
		ctx.waitTicks(2);
		ctx.takeScreenshot(name);
		ctx.runOnClient(mc -> {
			mc.player.setYRot(mc.player.getYRot() - dyaw);
			mc.player.yRotO = mc.player.getYRot();
			mc.player.setXRot(mc.player.getXRot() - dpitch);
			mc.player.xRotO = mc.player.getXRot();
		});
	}

	/** Same ride for before/after camera comparisons: open plains at noon, east, straight, a right turn, glance left. */
	private void compare(ClientGameTestContext ctx, String tag) {
		try (TestSingleplayerContext sp = ctx.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> {
				s.setSeed(System.getProperty("armacraft_client.gametest.seed", "armacraft-ride"));
				s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			})
			.create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runCommand("weather clear");
			sp.getServer().runCommand("gamerule advance_time false");
			sp.getServer().runCommand("time set noon");
			int[] at = sp.getServer().computeOnServer(server -> {
				var found = server.overworld().findClosestBiome3d(h -> h.is(net.minecraft.world.level.biome.Biomes.PLAINS),
					server.getPlayerList().getPlayers().get(0).blockPosition(), 6400, 32, 64);
				return found == null ? null : new int[] {found.getFirst().getX(), found.getFirst().getZ()};
			});
			if (at != null) {
				sp.getServer().runCommand("spreadplayers " + at[0] + " " + at[1] + " 0 4 false @a");
				ctx.waitTicks(30);
				String tp = sp.getServer().computeOnServer(server -> {
					var pl = server.getPlayerList().getPlayers().get(0);
					return openSpot(server.overworld(), pl.getBlockX(), pl.getBlockZ());
				});
				LOG.info("COMPARE spot: {}", tp);
				if (tp != null) {
					sp.getServer().runCommand(tp);
				}
				ctx.waitTicks(30);
				sp.getConnection().waitForChunksRender();
			}
			ctx.runOnClient(mc -> {
				mc.gui.hud.getChat().clearMessages(false);
				mc.player.setYRot(-90f);
				mc.player.yRotO = -90f;
			});
			ctx.getInput().pressKey(RideClientHooks.rideKey());
			ctx.waitFor(mc -> RideClientHooks.riding(), 10 * SECOND);
			ctx.waitTicks(30);
			cam(ctx, tag, "straight");
			// the host's ride keys follow the Armagetron keymap: arrows turn, A/Q glance left, V glance back
			ctx.getInput().holdKey(com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT);
			ctx.waitTicks(2);
			ctx.getInput().releaseKey(com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT);
			ctx.waitTicks(3);
			cam(ctx, tag, "turn-mid");
			ctx.waitTicks(30);
			cam(ctx, tag, "after-turn");
			ctx.getInput().holdKey(com.mojang.blaze3d.platform.InputConstants.KEY_Q);
			ctx.waitTicks(10);
			cam(ctx, tag, "glance-left");
			ctx.getInput().releaseKey(com.mojang.blaze3d.platform.InputConstants.KEY_Q);
			ctx.waitTicks(6);
			cam(ctx, tag, "glance-released");
			ctx.getInput().holdKey(com.mojang.blaze3d.platform.InputConstants.KEY_V);
			ctx.waitTicks(4);
			cam(ctx, tag, "glance-back");
			ctx.getInput().releaseKey(com.mojang.blaze3d.platform.InputConstants.KEY_V);
			ctx.waitTicks(4);
			cam(ctx, tag, "glance-back-released");
			if (RideClientHooks.riding()) {
				ctx.getInput().pressKey(o -> o.keyShift);
				ctx.waitFor(mc -> !RideClientHooks.riding(), 5 * SECOND);
			}
		}
	}

	private static void cam(ClientGameTestContext ctx, String tag, String what) {
		ctx.takeScreenshot("compare-" + tag + "-" + what);
		LOG.info(ctx.computeOnClient(mc -> {
			Vec3 p = mc.player.position();
			Vec3 e = ArmaRideVisuals.CAMERA.eye();
			var lp = gl.syn.armacraft.client.ridekit.ArmaBikeVisual.lastLocal;
			return String.format(java.util.Locale.ROOT, "COMPARE %s %s: riding=%s back %.2f up %.2f blocks, yaw %.1f pitch %.1f, fov %.1f | bike submits %d, last %s",
				tag, what, RideClientHooks.riding(), Math.hypot(e.x - p.x, e.z - p.z), e.y - p.y, ArmaRideVisuals.CAMERA.yaw(), ArmaRideVisuals.CAMERA.pitch(),
				ArmaRideVisuals.CAMERA.fov(), gl.syn.armacraft.client.ridekit.ArmaBikeVisual.submits, lp == null ? "-" : lp.pos() + " a " + lp.alpha() + " s " + lp.scale());
		}));
	}

	/** "tp @a x y z -90 10" for a spot whose 9x9 surroundings and a 40-block run east have no leaves and no 2-block rise. */
	private static String openSpot(net.minecraft.server.level.ServerLevel level, int cx, int cz) {
		var leaves = net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING;
		var ground = net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
		for (int r = 0; r <= 96; r += 4) {
			for (int dx = -r; dx <= r; dx += 4) {
				for (int dz = -r; dz <= r; dz += 4) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					int x = cx + dx;
					int z = cz + dz;
					for (int lx = (x - 4) >> 4; lx <= (x + 44) >> 4; lx++) {
						for (int lz = (z - 4) >> 4; lz <= (z + 4) >> 4; lz++) {
							level.getChunk(lx, lz); // load: heightmaps of unloaded chunks read as the world bottom
						}
					}
					int y0 = level.getHeight(ground, x, z);
					if (y0 <= level.getMinY() + 1) {
						continue;
					}
					boolean ok = true;
					for (int i = -4; i <= 44 && ok; i++) {
						for (int j = -4; j <= 4 && ok; j++) {
							if (i > 4 && Math.abs(j) > 1) {
								continue;
							}
							int g = level.getHeight(ground, x + i, z + j);
							ok = level.getHeight(leaves, x + i, z + j) == g && Math.abs(g - y0) <= 1
								&& !level.getBlockState(new net.minecraft.core.BlockPos(x + i, g - 1, z + j)).liquid();
						}
					}
					if (ok) {
						return "tp @a " + (x + 0.5) + " " + y0 + " " + (z + 0.5) + " -90 10";
					}
				}
			}
		}
		return null;
	}

	private static void sideShot(ClientGameTestContext ctx, String name) {
		ctx.runOnClient(mc -> {
			mc.player.setYRot(mc.player.getYRot() + 110f);
			mc.player.yRotO = mc.player.getYRot();
		});
		ctx.waitTicks(4);
		ctx.takeScreenshot(name);
		ctx.runOnClient(mc -> {
			mc.player.setYRot(mc.player.getYRot() - 110f);
			mc.player.yRotO = mc.player.getYRot();
		});
	}

	private static void redeployIfCrashed(ClientGameTestContext ctx) {
		if (!ctx.computeOnClient(mc -> RideClientHooks.riding())) {
			String crash = ctx.computeOnClient(mc -> RideClientHooks.lastCrash());
			LOG.info("not riding ({}), redeploying", crash);
			ctx.waitTicks(10);
			faceOpenGround(ctx);
			ctx.getInput().pressKey(RideClientHooks.rideKey());
			ctx.waitFor(mc -> RideClientHooks.riding(), 10 * SECOND);
		}
	}

	/** Face the cardinal direction with the longest run of ground the bike can drive (no rise of 2+ blocks). */
	private static void faceOpenGround(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			var level = mc.level;
			var p = mc.player.blockPosition();
			float[] yaws = {180f, -90f, 0f, 90f}; // north, east, south, west
			int[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
			int best = 0;
			int bestRun = -1;
			for (int d = 0; d < 4; d++) {
				int y = p.getY();
				int run = 0;
				for (int i = 1; i <= 48; i++) {
					int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX() + dirs[d][0] * i, p.getZ() + dirs[d][1] * i);
					if (top - y >= 2) {
						break;
					}
					y = Math.max(top, y - 3);
					run++;
				}
				if (run > bestRun) {
					bestRun = run;
					best = d;
				}
			}
			mc.player.setYRot(yaws[best]);
			mc.player.yRotO = yaws[best];
			LOG.info("facing {} ({} open blocks)", new String[] {"north", "east", "south", "west"}[best], bestRun);
		});
	}

	private static void log(ClientGameTestContext ctx, String what) {
		LOG.info(ctx.computeOnClient(mc -> {
			Vec3 p = mc.player.position();
			Vec3 cam = mc.gameRenderer.mainCamera().position();
			return String.format("%s: riding=%s pos=(%.1f, %.1f, %.1f) camera=(%.1f, %.1f, %.1f) dist %.1f fov-override %.1f",
				what, RideClientHooks.riding(), p.x, p.y, p.z, cam.x, cam.y, cam.z, cam.distanceTo(p), ArmaRideVisuals.CAMERA.fov());
		}));
	}

	private static void check(boolean ok, String msg) {
		if (!ok) {
			throw new AssertionError(msg);
		}
	}
}

package gl.syn.armacraft.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import gl.syn.armacraft.Armacraft;
import gl.syn.armacraft.client.ride.RideClient;
import gl.syn.armacraft.client.ride.RideClientMod;
import gl.syn.armacraft.client.ride.RideKeys;
import gl.syn.armacraft.client.ride.RideTrace;
import gl.syn.armacraft.ride.core.CycleMovement;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ride mode with the real dev client and real key presses.
 *
 * <p>Phases (select with -Darmacraft.clientgametest.only=explore|ride|arena; default runs ride + arena):
 * <ul>
 *   <li>explore: an ordinary generated world (not superflat), deploy with R and drive around with A/D, screenshots.</li>
 * </ul>
 */
public class RideClientGameTest implements FabricClientGameTest {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft/ride-gametest");
	private static final int SECOND = 20;
	private static final String NL = System.lineSeparator();

	static boolean selected(String phase) {
		String only = System.getProperty("armacraft.clientgametest.only", "");
		if (only.isBlank()) {
			return "sync".equals(phase) || "controls".equals(phase) || "ride".equals(phase) || "arena".equals(phase);
		}
		return only.equals(phase);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (selected("explore")) {
			explore(ctx);
		}
		if (selected("visual")) {
			visual(ctx);
		}
		if (selected("smooth")) {
			smooth(ctx);
		}
		if (selected("hills")) {
			hills(ctx);
		}
		if (selected("timing")) {
			timing(ctx);
		}
		if (selected("remote")) {
			String server = System.getProperty("armacraft.clientgametest.server", "");
			String rcon = System.getProperty("armacraft.clientgametest.rcon", "");
			if (server.isBlank() || rcon.isBlank()) {
				throw new AssertionError("remote needs -Parmacraft.clientgametest.server=host:port and -Parmacraft.clientgametest.rcon=<server.properties>");
			}
			try {
				new RideRemoteTest(ctx, server, java.nio.file.Path.of(rcon)).run();
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		}
		if (selected("sync")) {
			new RideSyncTest(ctx).run();
		}
		if (selected("controls")) {
			new RideControlsTest(ctx).run();
		}
		if (selected("ride")) {
			new RideCourse(ctx).run();
		}
	}

	private void explore(ClientGameTestContext ctx) {
		LOG.info("ride explore: ordinary generated world");
		try (TestSingleplayerContext sp = ctx.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> {
				s.setSeed("armacraft-ride");
				s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			})
			.create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runCommand("time set day");
			sp.getServer().runCommand("weather clear");
			ctx.waitTicks(20);
			ctx.takeScreenshot("ride-explore-0-spawn");
			Vec3 start = ctx.computeOnClient(mc -> mc.player.position());
			LOG.info("spawn at {}", start);

			ctx.getInput().pressKey(RideKeys.RIDE);
			ctx.waitFor(mc -> RideClientMod.client().riding(), 10 * SECOND);
			int riders = sp.getServer().computeOnServer(s -> Armacraft.ride().riders().size());
			LOG.info("deployed: server riders = {}", riders);
			ctx.waitTicks(30);
			ctx.takeScreenshot("ride-explore-1-riding");
			log(ctx, "after 1.5 s");

			int[] plan = {+1, 0, -1, 0, -1, +1, 0, +1};
			for (int i = 0; i < plan.length; i++) {
				redeployIfCrashed(ctx);
				if (plan[i] > 0) {
					ctx.getInput().pressKey(InputConstants.getKey("key.keyboard.j"));
				} else if (plan[i] < 0) {
					ctx.getInput().pressKey(InputConstants.getKey("key.keyboard.w"));
				}
				ctx.waitTicks(40);
				log(ctx, "leg " + i);
				if (i % 2 == 1) {
					ctx.takeScreenshot("ride-explore-" + (2 + i / 2) + "-leg" + i);
				}
			}
			ctx.getInput().holdKey(InputConstants.getKey("key.keyboard.down"));
			ctx.waitTicks(10);
			log(ctx, "braking");
			ctx.getInput().releaseKey(InputConstants.getKey("key.keyboard.down"));
			ctx.takeScreenshot("ride-explore-6-braked");
			Vec3 end = ctx.computeOnClient(mc -> mc.player.position());
			RideClient.Stats st = RideClientMod.client().stats;
			LOG.info("explore summary: travelled {} blocks straight-line, ticks {}, turns {}, crashes {} ({}), blocked ticks {}, held ticks {}, max speed {}, min rubber {}",
				String.format("%.1f", end.distanceTo(start)), st.ticks, st.turns, st.crashes, st.lastCause, st.blockedTicks, st.heldTicks,
				String.format("%.1f", st.maxSpeed), String.format("%.2f", st.minRubberLeft));
			if (RideClientMod.client().riding()) {
				ctx.getInput().pressKey(InputConstants.getKey("key.keyboard.backspace"));
				ctx.waitFor(mc -> !RideClientMod.client().riding(), 5 * SECOND);
			}
			ctx.waitTicks(10);
			ctx.takeScreenshot("ride-explore-7-stowed");
			if (st.ticks < 100) {
				throw new AssertionError("rode only " + st.ticks + " ticks");
			}
		}
	}

	/**
	 * Smoothness measurement: straight driving, a 1-block step up and down, and turns, with the per-frame trace
	 * (camera eye/yaw, bike render position, vanilla view bob) and second-difference stats per phase.
	 */
	private void smooth(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().adjustSettings(s -> s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE)).create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runOnServer(server -> {
				var l = server.overworld();
				for (int x = 40; x <= 45; x++) {
					for (int z = -1; z <= 1; z++) {
						l.setBlockAndUpdate(new net.minecraft.core.BlockPos(x, -60, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
					}
				}
			});
			ctx.runOnClient(mc -> {
				mc.player.setYRot(-90f);
				mc.player.setXRot(10f);
			});
			ctx.waitTicks(5);
			RideTrace.enabled = true;
			RideTrace.drain();
			ctx.getInput().pressKey(RideKeys.RIDE);
			ctx.waitFor(mc -> RideClientMod.client().riding(), 10 * SECOND);
			RideTrace.phase = "start";
			ctx.waitTicks(20);
			RideTrace.phase = "straight";
			ctx.waitFor(mc -> mc.player.getX() > 36, 10 * SECOND);
			RideTrace.phase = "step";
			ctx.waitFor(mc -> mc.player.getX() > 49, 10 * SECOND);
			RideTrace.phase = "turn";
			ctx.getInput().pressKey(InputConstants.getKey("key.keyboard.j"));
			ctx.waitTicks(10);
			ctx.getInput().pressKey(InputConstants.getKey("key.keyboard.w"));
			ctx.waitTicks(10);
			RideTrace.phase = "";
			ctx.getInput().pressKey(InputConstants.getKey("key.keyboard.backspace"));
			ctx.waitTicks(3);
			java.util.List<RideTrace.Frame> frames = RideTrace.drain();
			RideTrace.enabled = false;
			String tag = System.getProperty("armacraft.ride.traceTag", "trace");
			RideTrace.writeCsv(frames, java.nio.file.Path.of("ride-" + tag + ".csv").toAbsolutePath());
			LOG.info("smoothness ({} frames):" + NL + "  {}" + NL + "  {}" + NL + "  {}", frames.size(), RideTrace.stats(frames, "straight"), RideTrace.stats(frames, "step"),
				RideTrace.stats(frames, "turn"));
		}
	}

	/**
	 * Vertical smoothness on hills: a 2-blocks-per-step staircase up 4 and down 4, then a steep one (a step every
	 * block) up 4 and down 4, ridden east at full speed with the per-frame trace. Writes ride-hills-&lt;tag&gt;.csv.
	 */
	private void hills(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().adjustSettings(s -> s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE)).create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runOnServer(server -> {
				var l = server.overworld();
				var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
				// gentle: x 60..61 h1, 62..63 h2, 64..65 h3, 66..69 h4, 70..71 h3, 72..73 h2, 74..75 h1
				int[][] gentle = {{60, 61, 1}, {62, 63, 2}, {64, 65, 3}, {66, 69, 4}, {70, 71, 3}, {72, 73, 2}, {74, 75, 1}};
				// steep: one step per block: x 90 h1 .. 93 h4, 94..96 h4, 97 h3 .. 99 h1
				int[][] steep = {{90, 90, 1}, {91, 91, 2}, {92, 92, 3}, {93, 96, 4}, {97, 97, 3}, {98, 98, 2}, {99, 99, 1}};
				// ridge: steep up 3, straight back down (no plateau)
				int[][] ridge = {{110, 110, 1}, {111, 111, 2}, {112, 112, 3}, {113, 113, 2}, {114, 114, 1}};
				for (int[][] hill : new int[][][] {gentle, steep, ridge}) {
					for (int[] h : hill) {
						for (int x = h[0]; x <= h[1]; x++) {
							for (int z = -2; z <= 2; z++) {
								for (int y = 0; y < h[2]; y++) {
									l.setBlockAndUpdate(new net.minecraft.core.BlockPos(x, -60 + y, z), stone);
								}
							}
						}
					}
				}
				var p = server.getPlayerList().getPlayers().get(0);
				p.teleportTo(l, 40.5, -60, 0.5, java.util.Set.of(), -90f, 10f, true);
			});
			ctx.waitFor(mc -> Math.abs(mc.player.getX() - 40.5) < 0.1, 5 * SECOND);
			ctx.waitTicks(10);
			RideTrace.enabled = true;
			RideTrace.drain();
			ctx.getInput().pressKey(RideKeys.RIDE);
			ctx.waitFor(mc -> RideClientMod.client().riding(), 10 * SECOND);
			String[][] phases = {{"flat", "59"}, {"up", "66"}, {"plateau", "69.5"}, {"down", "77"}, {"flat2", "89"}, {"upSteep", "93.5"},
				{"top", "96.5"}, {"downSteep", "101"}, {"flat3", "109"}, {"ridgeUp", "112.5"}, {"ridgeDown", "117"}};
			for (String[] ph : phases) {
				RideTrace.phase = ph[0];
				double until = Double.parseDouble(ph[1]);
				ctx.waitFor(mc -> mc.player.getX() > until || !RideClientMod.client().riding(), 10 * SECOND);
			}
			RideTrace.phase = "";
			boolean riding = ctx.computeOnClient(mc -> RideClientMod.client().riding());
			ctx.getInput().pressKey(InputConstants.getKey("key.keyboard.backspace"));
			ctx.waitTicks(3);
			java.util.List<RideTrace.Frame> frames = RideTrace.drain();
			RideTrace.enabled = false;
			String tag = System.getProperty("armacraft.ride.traceTag", "trace");
			RideTrace.writeCsv(frames, java.nio.file.Path.of("ride-hills-" + tag + ".csv").toAbsolutePath());
			StringBuilder sb = new StringBuilder("hill smoothness (" + frames.size() + " frames, rode through: " + riding + "):");
			for (String[] ph : phases) {
				sb.append(NL).append("  ").append(RideTrace.verticalStats(frames, ph[0]));
			}
			LOG.info(sb.toString());
			if (!riding) {
				throw new AssertionError("crashed on the hills: " + RideClientMod.client().stats.lastCause);
			}
		}
	}

	/**
	 * Turn timing in game: a background thread presses J / W (right / left, alternating) at random wall-clock times,
	 * posted to the client like real key events (between frames, at any point of a tick), while riding straight on a
	 * flat world. For each press: where the corner landed against where the bike was drawn at the press, the
	 * key-to-physics and key-to-visible latency. Runs the legacy timing and the exact timing back to back.
	 */
	private void timing(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().adjustSettings(s -> s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE)).create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runOnServer(srv -> Armacraft.ride().config().lethalTrails = false);
			String[] report = new String[2];
			String[] cost = new String[2];
			boolean[] modes = {false, true};
			for (int m = 0; m < 2; m++) {
				boolean exact = modes[m];
				ctx.runOnClient(mc -> {
					RideClientMod.client().localConfig().exactTurnTiming = exact;
					mc.player.setYRot(-90f);
				});
				ctx.getInput().pressKey(RideKeys.RIDE);
				ctx.waitFor(mc -> RideClientMod.client().riding(), 5 * SECOND);
				ctx.waitTicks(30);
				ctx.runOnClient(mc -> {
					RideClientMod.client().probe().clear();
					gl.syn.armacraft.client.ride.RideFrameCost.reset();
					gl.syn.armacraft.client.ride.RideFrameCost.enabled = true;
				});
				gl.syn.armacraft.client.ride.TurnTimingProbe.enabled = true;
				int presses = 24;
				java.util.concurrent.atomic.AtomicBoolean doneFlag = new java.util.concurrent.atomic.AtomicBoolean();
				Thread injector = new Thread(() -> {
					java.util.Random rnd = new java.util.Random(exact ? 4243 : 4242);
					net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
					try {
						for (int i = 0; i < presses; i++) {
							Thread.sleep(300 + rnd.nextInt(250));
							int key = i % 2 == 0 ? InputConstants.KEY_J : InputConstants.KEY_W;
							int code = i % 2 == 0 ? 'j' : 'w';
							mc.execute(() -> {
								long h = mc.getWindow().handle();
								mc.keyboardHandler.keyPress(h, 1, new net.minecraft.client.input.KeyEvent(key, code, 0));
								mc.keyboardHandler.keyPress(h, 0, new net.minecraft.client.input.KeyEvent(key, code, 0));
							});
						}
						Thread.sleep(400);
					} catch (InterruptedException ignored) {
						Thread.currentThread().interrupt();
					}
					doneFlag.set(true);
				}, "armacraft-turn-injector");
				injector.setDaemon(true);
				injector.start();
				ctx.waitFor(mc -> doneFlag.get() || !RideClientMod.client().riding(), 60 * SECOND);
				gl.syn.armacraft.client.ride.TurnTimingProbe.enabled = false;
				cost[m] = ctx.computeOnClient(mc -> {
					gl.syn.armacraft.client.ride.RideFrameCost.enabled = false;
					return gl.syn.armacraft.client.ride.RideFrameCost.summary(exact ? "cost exact " : "cost legacy");
				});
				java.util.List<gl.syn.armacraft.client.ride.TurnTimingProbe.Rec> rs = ctx.computeOnClient(mc -> RideClientMod.client().probe().results());
				report[m] = gl.syn.armacraft.client.ride.TurnTimingProbe.summary(exact ? "exact timing " : "legacy timing", rs);
				StringBuilder rows = new StringBuilder();
				for (var r : rs) {
					rows.append(String.format(java.util.Locale.ROOT, "%n    phase %.2f  along %+.3f  phys %.1f ms  visible %.1f ms", r.pressPhase, r.along, r.physMs,
						r.visibleMs));
				}
				LOG.info("{}{}", report[m], rows);
				boolean stillRiding = ctx.computeOnClient(mc -> RideClientMod.client().riding());
				ctx.getInput().pressKey(InputConstants.getKey("key.keyboard.backspace"));
				ctx.waitFor(mc -> !RideClientMod.client().cycleMode(), 3 * SECOND);
				if (!stillRiding || rs.size() < presses * 3 / 4) {
					throw new AssertionError("timing run incomplete: riding " + stillRiding + ", " + rs.size() + " of " + presses + " presses measured");
				}
			}
			sp.getServer().runOnServer(srv -> Armacraft.ride().config().lethalTrails = true);
			LOG.info("turn timing:" + NL + "  " + report[0] + NL + "  " + report[1]);
			LOG.info("ride client CPU:" + NL + "  " + cost[0] + NL + "  " + cost[1]);
		}
	}

	/** Flat world, fixed heading: is the bike, trail and chase cam drawn? */
	private void visual(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().adjustSettings(s -> s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE)).create()) {
			sp.getConnection().waitForChunksRender();
			ctx.runOnClient(mc -> {
				mc.player.setYRot(-90f);
				mc.player.setXRot(10f);
			});
			ctx.waitTicks(5);
			ctx.getInput().pressKey(RideKeys.RIDE);
			ctx.waitFor(mc -> RideClientMod.client().riding(), 10 * SECOND);
			ctx.waitTicks(30);
			ctx.takeScreenshot("ride-visual-1-chase");
			String camInfo = ctx.computeOnClient(mc -> {
				Vec3 cam = mc.gameRenderer.mainCamera().position();
				return "camera " + cam + " player " + mc.player.position() + " dist " + String.format("%.2f", cam.distanceTo(mc.player.position()))
					+ " bikeSubmits " + RideClientMod.bikeSubmits + " trailSubmits " + RideClientMod.trailSubmits;
			});
			LOG.info("visual: {}", camInfo);
			ctx.getInput().pressKey(InputConstants.getKey("key.keyboard.j"));
			ctx.waitTicks(4);
			ctx.takeScreenshot("ride-visual-2-turning");
			ctx.waitTicks(20);
			ctx.takeScreenshot("ride-visual-3-after-turn");
			ctx.getInput().pressKey(InputConstants.getKey("key.keyboard.backspace"));
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> {
				mc.player.setXRot(60f);
			});
			ctx.waitTicks(5);
			ctx.takeScreenshot("ride-visual-4-stowed");
		}
	}

	private static void redeployIfCrashed(ClientGameTestContext ctx) {
		if (!RideClientMod.client().riding()) {
			LOG.info("not riding (crash: {}), redeploying with a turn key", RideClientMod.client().lastCrashCause());
			ctx.waitTicks(14);
			ctx.getInput().pressKey(InputConstants.getKey("key.keyboard.j"));
			ctx.waitFor(mc -> RideClientMod.client().riding(), 10 * SECOND);
		}
	}

	private static void log(ClientGameTestContext ctx, String what) {
		String s = ctx.computeOnClient(mc -> {
			RideClient rc = RideClientMod.client();
			CycleMovement m = rc.movement();
			Vec3 p = mc.player.position();
			return String.format("%s: riding=%s pos=(%.1f, %.1f, %.1f) heading=%s speed=%.1f rubber=%.2f ahead=%s space=%.2f onGround=%s",
				what, rc.riding(), p.x, p.y, p.z, m == null ? "-" : m.heading(), m == null ? 0 : m.speed(), m == null ? 0 : m.rubberLeft(),
				rc.stats.lastAhead, rc.stats.lastSpace, mc.player.onGround());
		});
		LOG.info(s);
	}
}

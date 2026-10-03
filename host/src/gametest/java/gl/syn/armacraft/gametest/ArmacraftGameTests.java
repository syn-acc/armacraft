package gl.syn.armacraft.gametest;

import com.mojang.math.Transformation;
import gl.syn.armacraft.ArmaController;
import gl.syn.armacraft.Armacraft;
import gl.syn.armacraft.bridge.Msg;
import gl.syn.armacraft.bridge.Protocol;
import gl.syn.armacraft.config.ArmaConfig;
import gl.syn.armacraft.gametest.mixin.DisplayAccessor;
import gl.syn.armacraft.gametest.mixin.GameTestHelperAccessor;
import gl.syn.armacraft.logic.ArenaMapping;
import gl.syn.armacraft.render.ArenaRenderer;
import gl.syn.armacraft.render.EntitySet;
import gl.syn.armacraft.session.SessionManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Headless in-world checks on a real (GameTest) Minecraft 26.3 server:
 * <ol>
 *   <li>Renderer: protocol JSON -> display entities (block, yaw, position, glow, brightness, gap split, grid split,
 *       rim), head-wall growth updates the same entity's transformation with interpolation, removal, not saved.</li>
 *   <li>Bridge end to end: real TCP to a guest (in-process MiniGuest, or tools/fake_guest.py via
 *       -Darmacraft.gametest.guest=host:port), join, chase-cam attach in spectator, A -> "turn left" through the
 *       handlePlayerInput mixin, jump -> brake, shift does not detach, leave restores gamemode, disconnect purges.</li>
 * </ol>
 * One test method so the phases never run concurrently (the bridge phase purges every armacraft entity).
 */
public class ArmacraftGameTests {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft/gametest");
	private static final double[][] DIRS = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};

	private static final String ROUND = "{\"type\":\"round\",\"round\":1,\"t\":0,"
		+ "\"static\":[[-20,-20,20,-20],[20,-20,20,20],[20,20,-20,20],[-20,20,-20,-20]],\"bounds\":[-20,-20,20,20]}";
	private static final String STATE1 = "{\"type\":\"state\",\"t\":1.0,"
		+ "\"cycles\":[{\"id\":17,\"name\":\"Syn\",\"color\":[0.1,0.6,1.0],\"x\":0,\"y\":10,\"dx\":0,\"dy\":1,\"speed\":20,\"alive\":true},"
		+ "{\"id\":18,\"name\":\"Dead\",\"color\":[1,0,0],\"x\":15,\"y\":-15,\"dx\":1,\"dy\":0,\"speed\":0,\"alive\":false}],"
		+ "\"walls\":[{\"id\":901,\"cycle\":17,\"x1\":0,\"y1\":-10,\"x2\":0,\"y2\":10},"
		+ "{\"id\":904,\"cycle\":17,\"x1\":-10,\"y1\":-10,\"x2\":0,\"y2\":-10,\"gaps\":[[0.25,0.30]]},"
		+ "{\"id\":902,\"cycle\":18,\"x1\":-15,\"y1\":-15,\"x2\":15,\"y2\":-15}],\"future_field\":1}";
	private static final String STATE2 = STATE1
		.replace("\"x\":0,\"y\":10,", "\"x\":0,\"y\":12,")
		.replace("\"x2\":0,\"y2\":10}", "\"x2\":0,\"y2\":12}");
	private static final String STATE_EMPTY = "{\"type\":\"state\",\"t\":3.0,\"cycles\":[],\"walls\":[]}";
	// live guest behaviour: fresh head walls arrive with id 0 until Armagetron numbers them
	private static final String STATE_ID0_A = "{\"type\":\"state\",\"t\":4.0,"
		+ "\"cycles\":[{\"id\":21,\"color\":[1,0,0],\"x\":0,\"y\":8,\"dx\":0,\"dy\":1,\"speed\":20},"
		+ "{\"id\":22,\"color\":[0,0,1],\"x\":8,\"y\":0,\"dx\":1,\"dy\":0,\"speed\":20}],"
		+ "\"walls\":[{\"id\":0,\"cycle\":21,\"x1\":0,\"y1\":0,\"x2\":0,\"y2\":8},"
		+ "{\"id\":0,\"cycle\":22,\"x1\":2,\"y1\":0,\"x2\":8,\"y2\":0}]}";
	private static final String STATE_ID0_B = STATE_ID0_A
		.replace("\"id\":0,\"cycle\":21,\"x1\":0,\"y1\":0,\"x2\":0,\"y2\":8", "\"id\":31,\"cycle\":21,\"x1\":0,\"y1\":0,\"x2\":0,\"y2\":9")
		.replace("\"id\":0,\"cycle\":22,\"x1\":2,\"y1\":0,\"x2\":8,\"y2\":0", "\"id\":32,\"cycle\":22,\"x1\":2,\"y1\":0,\"x2\":9,\"y2\":0");

	private static final class Ctx {
		ArenaRenderer renderer;
		ArmaConfig cfg;
		ArenaMapping map;
		Msg.Round round;
		Vec3 origin;
		UUID headWallUuid;
		UUID cycleUuid;
		MiniGuest mini;
		ServerPlayer player;
		GameType originalMode;
		String key;
		int dirBefore;
		long pressedAt;
		java.util.Set<UUID> id0Walls;
		double spawnX;
		double spawnY;
		RelayProbe relay; // armacraft-relay
		net.minecraft.world.level.GameType relayMode; // armacraft-relay
	}

	private static void pressLeft(Ctx x, ArmaController c) {
		Msg.Cycle bc = bridgeCycle(c, x.key);
		if (bc != null) {
			x.dirBefore = dirIndex(bc.dx(), bc.dy());
		}
		x.pressedAt = System.currentTimeMillis();
		// A pressed: goes through ServerGamePacketListenerImpl.handlePlayerInput -> mixin -> "turn left"
		x.player.connection.handlePlayerInput(new ServerboundPlayerInputPacket(Input.EMPTY));
		x.player.connection.handlePlayerInput(new ServerboundPlayerInputPacket(new Input(false, false, true, false, false, false, false)));
	}

	private static Msg.Cycle anyBridgeCycle(ArmaController c, String key) {
		Msg.Cycle alive = bridgeCycle(c, key);
		if (alive != null) {
			return alive;
		}
		Msg.State st = c.bridge().latestState();
		if (st != null) {
			for (Msg.Cycle cy : st.cycles()) {
				if (key.equals(cy.bridgeKey())) {
					return cy;
				}
			}
		}
		return null;
	}

	/** The GameTest server ticks unthrottled while guests run in real time: bound waits by wall clock instead. */
	private static GameTestHelper current;

	private static void within(long[] deadline, String what) {
		if (System.currentTimeMillis() > deadline[0]) {
			deadline[0] = Long.MAX_VALUE; // report once
			((GameTestHelperAccessor) current).armacraft$testInfo()
				.fail(net.minecraft.network.chat.Component.literal("timed out (wall clock) waiting for: " + what));
		}
	}

	@GameTest(maxTicks = 1_000_000)
	public void arenaRenderAndBridge(GameTestHelper helper) {
		if (System.getProperty("armacraft.gametest.soak") != null) {
			helper.succeed(); // soak mode: the soak test owns the global controller
			return;
		}
		current = helper;
		ServerLevel level = helper.getLevel();
		Ctx x = new Ctx();
		// Centre of this test's own 8x8x8 structure. The phase-A arena is +-2 blocks, so it stays inside the
		// structure: the framework clears entities inside neighbouring tests' bounds when they start/finish.
		x.origin = helper.absoluteVec(new Vec3(4.5, 1, 4.5));
		long[] deadline = {Long.MAX_VALUE};
		ArmaController c = Armacraft.controller();
		helper.assertTrue(c != null, "controller not initialised");

		helper.startSequence()
			// ---------------- phase A: renderer ----------------
			.thenExecute(() -> {
				c.disconnect(); // stop any auto-connect from the default config
				x.cfg = new ArmaConfig();
				x.cfg.maxPieceBlocks = 1.5;   // 15 grid units at scale 0.1 -> forces the grid split
				x.cfg.interpTicks = 3;
				x.map = new ArenaMapping(x.origin.x, x.origin.y, x.origin.z, 0.1);
				x.round = (Msg.Round) Protocol.parse(ROUND);
				x.renderer = new ArenaRenderer();
				x.renderer.render(level, x.map, x.cfg, x.round, (Msg.State) Protocol.parse(STATE1));
			})
			.thenExecuteAfter(1, () -> {
				List<Display> cycles = near(level, "armacraft.cycle", x.origin);
				helper.assertTrue(cycles.size() == 1, "expected 1 live cycle entity, got " + cycles.size());
				Display cy = cycles.get(0);
				x.cycleUuid = cy.getUUID();
				helper.assertTrue(cy instanceof Display.BlockDisplay bd && bd.getBlockState().equals(concrete(DyeColor.LIGHT_BLUE)),
					"cycle block should be light_blue_concrete, got " + ((Display.BlockDisplay) cy).getBlockState());
				helper.assertTrue(Math.abs(Math.abs(Mth.wrapDegrees(cy.getYRot())) - 180f) < 0.01f, "north heading -> yaw 180, got " + cy.getYRot());
				helper.assertTrue(close(cy.getX(), x.origin.x) && close(cy.getZ(), x.origin.z - 1.0) && close(cy.getY(), x.origin.y),
					"cycle position mapped wrong: " + cy.position() + " origin " + x.origin);
				helper.assertTrue(cy.hasGlowingTag() && cy.getGlowColorOverride() == 0x1A99FF, "cycle glow " + Integer.toHexString(cy.getGlowColorOverride()));
				helper.assertTrue(cy.getPackedBrightnessOverride() == Brightness.FULL_BRIGHT.pack(), "cycle not full-bright");
				helper.assertTrue(cy.getPosRotInterpolationDuration() == 3, "cycle teleport interpolation");
				helper.assertTrue(!cy.shouldBeSaved(), "armacraft entities must not be saved");

				List<Display> walls = near(level, "armacraft.wall", x.origin);
				for (Display w : walls) {
					LOG.info("wall piece at {} scale {}", w.position(), DisplayAccessor.armacraft$createTransformation(w.getEntityData()).scale());
				}
				// 901 (20u, 15u grid) -> 2 pieces; 904 (10u, one gap) -> 2 pieces; 902 (30u) -> 2 pieces
				helper.assertTrue(walls.size() == 6, "expected 6 wall pieces, got " + walls.size());
				int blue = 0;
				int red = 0;
				for (Display w : walls) {
					BlockState bs = ((Display.BlockDisplay) w).getBlockState();
					helper.assertTrue(w.hasGlowingTag() && w.getPackedBrightnessOverride() == Brightness.FULL_BRIGHT.pack(), "wall glow/brightness");
					if (bs.equals(glass(DyeColor.LIGHT_BLUE))) {
						blue++;
						helper.assertTrue(w.getGlowColorOverride() == 0x1A99FF, "blue wall glow colour");
					} else if (bs.equals(glass(DyeColor.RED))) {
						red++;
						helper.assertTrue(w.getGlowColorOverride() == 0xFF0000, "red wall glow colour");
					}
				}
				helper.assertTrue(blue == 4 && red == 2, "wall colours blue=" + blue + " red=" + red);
				Display head = null;
				for (Display w : walls) {
					// growing cell of wall 901 starts at grid (0,5) -> z = origin - 0.5
					if (close(w.getX(), x.origin.x) && close(w.getZ(), x.origin.z - 0.5)) {
						head = w;
					}
				}
				helper.assertTrue(head != null, "head wall piece not found at its start point");
				Transformation t = DisplayAccessor.armacraft$createTransformation(head.getEntityData());
				helper.assertTrue(Math.abs(t.scale().z() - 0.5f) < 1e-4 && Math.abs(t.scale().y() - 3.0f) < 1e-4,
					"head wall scale " + t.scale());
				x.headWallUuid = head.getUUID();

				List<Display> rim = near(level, "armacraft.rim", x.origin);
				if (rim.size() != 12) {
					LOG.warn("rim mismatch: {} origin {}", x.renderer.diagnostics(), x.origin);
					for (Entity e : level.getAllEntities()) {
						if (e != null && e.entityTags().contains("armacraft.rim")) {
							LOG.warn("  rim entity {} removed={} at {}", e.getUUID(), e.isRemoved(), e.position());
						}
					}
				}
				helper.assertTrue(rim.size() == 12, "expected 12 rim pieces (4 sides x 3 cells), got " + rim.size() + " [" + x.renderer.diagnostics() + "]");
				for (Display r : rim) {
					helper.assertTrue(((Display.BlockDisplay) r).getBlockState().equals(concrete(DyeColor.LIGHT_GRAY)) && !r.hasGlowingTag(), "rim style");
				}
				helper.assertTrue(near(level, "armacraft.name", x.origin).size() == 1, "one name tag (dead cycles have none)");
				List<String> geo = wallGeometryViolationsFor(level, x.map, (Msg.State) Protocol.parse(STATE1));
				helper.assertTrue(geo.isEmpty(), "wall geometry: " + geo);
			})
			.thenExecute(() -> x.renderer.render(level, x.map, x.cfg, x.round, (Msg.State) Protocol.parse(STATE2)))
			.thenExecuteAfter(1, () -> {
				Entity head = level.getEntity(x.headWallUuid);
				helper.assertTrue(head instanceof Display && !head.isRemoved(), "head wall entity must be reused, not respawned");
				Display d = (Display) head;
				Transformation t = DisplayAccessor.armacraft$createTransformation(d.getEntityData());
				helper.assertTrue(Math.abs(t.scale().z() - 0.7f) < 1e-4, "head wall should grow to 0.7, got " + t.scale().z());
				helper.assertTrue(d.getTransformationInterpolationDuration() == 3, "growth interpolation duration");
				Entity cy = level.getEntity(x.cycleUuid);
				helper.assertTrue(cy != null && !cy.isRemoved() && close(cy.getZ(), x.origin.z - 1.2), "cycle moved in place");
			})
			.thenExecute(() -> x.renderer.render(level, x.map, x.cfg, x.round, (Msg.State) Protocol.parse(STATE_EMPTY)))
			.thenExecuteAfter(1, () -> {
				helper.assertTrue(near(level, "armacraft.cycle", x.origin).isEmpty(), "cycles removed");
				helper.assertTrue(near(level, "armacraft.wall", x.origin).isEmpty(), "walls removed");
				helper.assertTrue(near(level, "armacraft.name", x.origin).isEmpty(), "names removed");
				helper.assertTrue(near(level, "armacraft.rim", x.origin).size() == 12, "rim stays");
				x.renderer.render(level, x.map, x.cfg, x.round, (Msg.State) Protocol.parse(STATE_ID0_A));
			})
			.thenExecuteAfter(1, () -> {
				List<Display> walls = near(level, "armacraft.wall", x.origin);
				helper.assertTrue(walls.size() == 2, "two id-0 head walls of different cycles must both render, got " + walls.size());
				x.id0Walls = walls.stream().map(Entity::getUUID).collect(java.util.stream.Collectors.toSet());
				x.renderer.render(level, x.map, x.cfg, x.round, (Msg.State) Protocol.parse(STATE_ID0_B));
			})
			.thenExecuteAfter(1, () -> {
				List<Display> walls = near(level, "armacraft.wall", x.origin);
				java.util.Set<UUID> now = walls.stream().map(Entity::getUUID).collect(java.util.stream.Collectors.toSet());
				helper.assertTrue(now.equals(x.id0Walls), "walls must be reused when the guest assigns ids: " + x.id0Walls + " -> " + now);
				for (Display w : walls) {
					float len = DisplayAccessor.armacraft$createTransformation(w.getEntityData()).scale().z();
					helper.assertTrue(Math.abs(len - 0.9f) < 1e-4 || Math.abs(len - 0.7f) < 1e-4, "grown lengths 0.9 / 0.7, got " + len);
				}
				x.renderer.clear();
			})
			.thenExecuteAfter(1, () -> {
				helper.assertTrue(near(level, EntitySet.TAG, x.origin).isEmpty(), "clear() removes everything");
				LOG.info("phase A (renderer) passed");
			})
			// ---------------- phase B: bridge end to end ----------------
			.thenExecute(() -> {
				String ext = System.getProperty("armacraft.gametest.guest");
				String host = "127.0.0.1";
				int port;
				if (ext != null && !ext.isBlank()) {
					int i = ext.lastIndexOf(':');
					host = ext.substring(0, i);
					port = Integer.parseInt(ext.substring(i + 1));
					LOG.info("phase B against external guest {}:{}", host, port);
				} else {
					try {
						x.mini = new MiniGuest();
					} catch (java.io.IOException e) {
						throw new RuntimeException(e);
					}
					port = x.mini.port();
					LOG.info("phase B against in-process MiniGuest on {}", port);
				}
				ArmaConfig cfg = c.config();
				cfg.anchor.dimension = level.dimension().identifier().toString();
				cfg.anchor.x = x.origin.x;
				cfg.anchor.y = x.origin.y;
				cfg.anchor.z = x.origin.z;
				cfg.scale = 0.1;
				cfg.maxPieceBlocks = 32;
				c.markLayoutDirty();
				c.connect(host, port);
				deadline[0] = System.currentTimeMillis() + 60_000;
			})
			.thenWaitUntil(() -> {
				within(deadline, "connect + round");
				helper.assertTrue(c.bridge().isConnected() && c.round() != null, "no round yet");
			})
			.thenExecute(() -> {
				// Fit any guest arena into ~5 blocks centred on the test origin (the real guest's arena is 0..125).
				double[] b = c.round().bounds() != null ? c.round().bounds() : new double[] {-100, -100, 100, 100};
				double extent = Math.max(Math.abs(b[2] - b[0]), Math.abs(b[3] - b[1]));
				ArmaConfig cfg = c.config();
				cfg.scale = 5.0 / Math.max(1, extent);
				cfg.anchor.x = x.origin.x - (b[0] + b[2]) / 2 * cfg.scale;
				cfg.anchor.z = x.origin.z + (b[1] + b[3]) / 2 * cfg.scale;
				c.markLayoutDirty();
				LOG.info("phase B: guest round {} bounds {} -> scale {}", c.round().round(), java.util.Arrays.toString(b), cfg.scale);
				x.player = helper.makeMockServerPlayerInLevel();
				x.originalMode = x.player.gameMode.getGameModeForPlayer();
				x.key = SessionManager.keyFor(x.player.getUUID());
				c.sessions().join(x.player, c.bridge());
			})
			.thenWaitUntil(() -> {
				within(deadline, "rendering");
				helper.assertTrue(c.bridge().isConnected(), "bridge not connected");
				helper.assertTrue(c.renderer().rimCount() > 0, "no rim rendered yet");
				helper.assertTrue(c.renderer().cycleCount() >= 1, "no cycles rendered yet");
			})
			.thenWaitUntil(() -> {
				within(deadline, "riding with chase cam (real guest spawns ~5 s after join)");
				SessionManager.Session s = c.sessions().get(x.player.getUUID());
				helper.assertTrue(s != null && s.phase == SessionManager.Phase.RIDING, "session not riding: " + (s == null ? null : s.phase));
				Entity cam = x.player.getCamera();
				helper.assertTrue(cam instanceof Display && cam.entityTags().contains("armacraft.cam"), "camera not attached to chase cam: " + cam);
				helper.assertTrue(x.player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR, "player should be spectator");
				helper.assertTrue(bridgeCycle(c, x.key) != null, "bridge cycle missing from state");
			})
			.thenExecute(() -> {
				Msg.State st = c.lastRendered();
				Msg.Cycle bc = null;
				for (Msg.Cycle cy : st.cycles()) {
					if (x.key.equals(cy.bridgeKey())) {
						bc = cy;
					}
				}
				helper.assertTrue(bc != null, "bridge cycle not in rendered state");
				ArenaMapping m = c.mapping();
				double[] f = ArenaMapping.facing(ArenaMapping.yaw(bc.dx(), bc.dy()));
				Entity cam = x.player.getCamera();
				double rx = cam.getX() - m.mcX(bc.x());
				double rz = cam.getZ() - m.mcZ(bc.y());
				double along = rx * f[0] + rz * f[1];
				double camYawErr = Math.abs(Mth.wrapDegrees(cam.getYRot() - ArenaMapping.yaw(bc.dx(), bc.dy())));
				LOG.info("chase cam relative to cycle: along-heading {} (expect about -{}), yaw error {}", along, c.config().camera.back, camYawErr);
				helper.assertTrue(along < -c.config().camera.back * 0.5 && along > -c.config().camera.back * 1.5, "chase cam must sit behind the cycle, along=" + along);
				helper.assertTrue(camYawErr < 1f, "chase cam must look along the heading, err=" + camYawErr);
			})
			.thenExecute(() -> {
				Msg.Cycle bc = bridgeCycle(c, x.key);
				x.spawnX = bc.x();
				x.spawnY = bc.y();
			})
			.thenWaitUntil(() -> {
				// The real guest holds new cycles still for a ~4 s countdown and drops turns sent during it.
				within(deadline, "bridge cycle starts moving");
				Msg.Cycle bc = bridgeCycle(c, x.key);
				helper.assertTrue(bc != null && Math.abs(bc.x() - x.spawnX) + Math.abs(bc.y() - x.spawnY) > 0.5, "not moving yet");
			})
			.thenExecute(() -> pressLeft(x, c))
			.thenWaitUntil(() -> {
				within(deadline, "left turn reflected in guest state");
				Msg.Cycle bc = bridgeCycle(c, x.key);
				if (bc != null && dirIndex(bc.dx(), bc.dy()) == x.dirBefore && System.currentTimeMillis() - x.pressedAt > 3000) {
					pressLeft(x, c); // not applied (e.g. guest-side delay window): release and press again
				}
				helper.assertTrue(bc != null && dirIndex(bc.dx(), bc.dy()) == Math.floorMod(x.dirBefore - 1, 4),
					"bridge cycle did not turn left (before " + x.dirBefore + ")");
				LOG.info("phase B: left turn applied by guest: dir {} -> {}", x.dirBefore, dirIndex(bc.dx(), bc.dy()));
			})
			.thenExecute(() -> {
				x.player.connection.handlePlayerInput(new ServerboundPlayerInputPacket(Input.EMPTY));
				x.player.connection.handlePlayerInput(new ServerboundPlayerInputPacket(new Input(false, false, false, false, true, false, false)));
			})
			.thenWaitUntil(() -> {
				within(deadline, "brake reflected in guest state");
				Msg.Cycle bc = anyBridgeCycle(c, x.key);
				helper.assertTrue(bc != null && bc.brake(), "jump did not brake");
			})
			.thenExecute(() -> {
				x.player.connection.handlePlayerInput(new ServerboundPlayerInputPacket(Input.EMPTY));
				x.player.setShiftKeyDown(true);
			})
			.thenExecuteFor(5, () -> {
				// Mock players are not ticked by a connection; tick by hand so ServerPlayer.tick's
				// "camera != this && wantsToStopRiding() -> setCamera(this)" path actually runs.
				x.player.doTick();
				SessionManager.Session s = c.sessions().get(x.player.getUUID());
				if (s != null && s.phase != SessionManager.Phase.WAITING) {
					helper.assertTrue(x.player.getCamera() instanceof Display, "shift must not detach the chase cam");
				}
			})
			.thenExecute(() -> x.player.setShiftKeyDown(false))
			.thenWaitUntil(() -> {
				within(deadline, "brake release reflected in guest state");
				Msg.Cycle bc = anyBridgeCycle(c, x.key);
				// a dead cycle may keep its last brake flag on the real guest; the MiniGuest always reflects it
				helper.assertTrue(bc != null && (!bc.brake() || (x.mini == null && !bc.alive())), "brake release not reflected by guest");
			})
			.thenExecute(() -> helper.assertTrue(c.sessions().leave(x.player, c.bridge()), "leave"))
			.thenWaitUntil(() -> {
				within(deadline, "leave + restore");
				helper.assertTrue(x.player.getCamera() == x.player, "camera not restored");
				helper.assertTrue(x.player.gameMode.getGameModeForPlayer() == x.originalMode,
					"gamemode not restored: " + x.player.gameMode.getGameModeForPlayer() + " vs " + x.originalMode);
				helper.assertTrue(bridgeCycle(c, x.key) == null, "guest still has the bridge cycle after leave");
				helper.assertTrue(near(level, "armacraft.cam", x.origin, 64).isEmpty(), "chase cam not removed");
			})
			// ---------------- phase C: relay (client/RELAY.md) through Fabric's real channel paths ----------------
			.thenExecute(() -> {
				x.relay = new RelayProbe(helper.makeMockServerPlayerInLevel());
				x.relayMode = x.relay.player.gameMode.getGameModeForPlayer();
				var hub = gl.syn.armacraft.relay.BridgeRelay.hub();
				helper.assertTrue(hub != null, "relay entrypoint did not run");
				helper.assertTrue(!hub.isSubscribed(x.relay.player.getUUID()) && x.relay.lines.isEmpty(), "a client without the channel must get nothing");
				x.relay.registerChannel();
			})
			.thenWaitUntil(() -> {
				within(deadline, "relay subscription, keyframe and live states");
				helper.assertTrue(gl.syn.armacraft.relay.BridgeRelay.hub().isSubscribed(x.relay.player.getUUID()), "not subscribed after registering armacraft:bridge");
				helper.assertTrue(x.relay.count("state") >= 4, "waiting for live states: " + x.relay.types());
			})
			.thenExecute(() -> {
				List<String> types = x.relay.types();
				helper.assertTrue(types.subList(0, 4).equals(List.of("relay", "hello", "round", "state")), "relay, cached hello/round, keyframe first: " + types);
				com.google.gson.JsonObject relay = com.google.gson.JsonParser.parseString(x.relay.lines.get(0)).getAsJsonObject();
				ArmaConfig cfg = c.config();
				helper.assertTrue(relay.get("proto").getAsInt() == 1 && relay.get("scale").getAsDouble() == cfg.scale
					&& relay.get("dimension").getAsString().equals(cfg.anchor.dimension)
					&& relay.getAsJsonArray("anchor").get(0).getAsDouble() == cfg.anchor.x
					&& relay.getAsJsonArray("anchor").get(2).getAsDouble() == cfg.anchor.z, "relay line does not match the config: " + relay);
				helper.assertTrue(x.relay.lines.get(3).contains("\"keyframe\":true"), "4th line must be the keyframe");
				helper.assertTrue(!x.relay.lines.get(4).contains("\"keyframe\""), "then live guest lines verbatim");
				x.relay.send("{\"type\":\"join\",\"key\":\"mc:evil\",\"name\":\"Mallory\",\"color\":[2,-1,0.5]}");
				x.relay.send("{\"type\":\"cmd\",\"line\":\"KICK everyone\"}");
				x.relay.send("{\"type\":\"explode\",\"x\":0,\"y\":0,\"r\":99}");
				// a relay rider must not also get the spectator chase-cam session
				level.getServer().getCommands().performPrefixedCommand(x.relay.player.createCommandSourceStack(), "arma join");
			})
			.thenWaitUntil(() -> {
				within(deadline, "relay join reaches the guest and comes back over the relay as a bridge cycle");
				helper.assertTrue(gl.syn.armacraft.relay.BridgeRelay.hub().isRider(x.relay.player.getUUID()), "not a relay rider");
				helper.assertTrue(c.sessions().get(x.relay.player.getUUID()) == null, "relay rider must not get a vanilla session");
				helper.assertTrue(x.relay.player.getCamera() == x.relay.player && x.relay.player.gameMode.getGameModeForPlayer() == x.relayMode,
					"relay rider must keep camera and gamemode");
				helper.assertTrue(x.relay.lines.stream().anyMatch(l -> l.contains("\"bridge_key\":\"" + x.relay.key() + "\"")), "relayed state never showed the rider's cycle");
			})
			.thenExecute(() -> x.relay.send("{\"type\":\"input\",\"key\":\"mc:evil\",\"turn\":\"left\"}"))
			.thenWaitUntil(() -> {
				within(deadline, "relay input forwarded with the sender's key");
				String want = "{\"type\":\"input\",\"key\":\"" + x.relay.key() + "\",\"turn\":\"left\"}";
				helper.assertTrue(x.mini != null ? x.mini.received.contains(want) : gl.syn.armacraft.relay.BridgeRelay.hub().recentForwarded().contains(want), "guest never got " + want);
			})
			.thenExecute(() -> x.relay.disconnect("relay test done"))
			.thenWaitUntil(() -> {
				within(deadline, "relay leave on disconnect");
				var hub = gl.syn.armacraft.relay.BridgeRelay.hub();
				helper.assertTrue(!hub.isSubscribed(x.relay.player.getUUID()) && !hub.isRider(x.relay.player.getUUID()), "relay state not cleared on disconnect");
				String leave = "{\"type\":\"leave\",\"key\":\"" + x.relay.key() + "\"}";
				helper.assertTrue(x.mini == null || x.mini.received.contains(leave), "guest never got " + leave);
			})
			.thenExecute(() -> {
				if (x.mini != null) {
					List<String> got = x.mini.received;
					String join = got.stream().filter(l -> l.contains("\"type\":\"join\"") && l.contains(x.relay.key())).findFirst().orElse("");
					helper.assertTrue(join.contains("\"name\":\"" + x.relay.player.getGameProfile().name() + "\"") && join.contains("\"color\":[1.0,0.0,0.5]"),
						"join must carry the profile name and a clamped colour: " + join);
					helper.assertTrue(got.stream().noneMatch(l -> l.contains("mc:evil") || l.contains("Mallory") || l.contains("KICK") || l.contains("explode")),
						"client-chosen key/name or host-only messages reached the guest: " + got);
				}
				LOG.info("phase C (relay) passed: {} lines relayed to the probe, first {}", x.relay.lines.size(), x.relay.types().subList(0, Math.min(6, x.relay.lines.size())));
			})
			// ---------------- end phase C (armacraft-relay)
			.thenExecute(() -> {
				if (x.mini != null) {
					List<String> got = x.mini.received;
					helper.assertTrue(got.stream().anyMatch(l -> l.contains("\"type\":\"join\"") && l.contains(x.key)), "guest never got join: " + got);
					helper.assertTrue(got.stream().anyMatch(l -> l.contains("\"turn\":\"left\"")), "guest never got turn left");
					helper.assertTrue(got.stream().anyMatch(l -> l.contains("\"brake\":true")), "guest never got brake true");
					helper.assertTrue(got.stream().anyMatch(l -> l.contains("\"brake\":false")), "guest never got brake false");
					helper.assertTrue(got.stream().anyMatch(l -> l.contains("\"type\":\"leave\"")), "guest never got leave");
					helper.assertTrue(got.stream().noneMatch(l -> l.contains("\"turn\":\"right\"")), "spurious right turn");
					LOG.info("MiniGuest received: {}", got);
				}
				c.disconnect();
				if (x.mini != null) {
					x.mini.close();
				}
				level.getServer().getPlayerList().remove(x.player);
			})
			.thenWaitUntil(() -> {
				int left = 0;
				for (Entity e : level.getAllEntities()) {
					if (e != null && !e.isRemoved() && e.entityTags().contains(EntitySet.TAG)) {
						left++;
					}
				}
				helper.assertTrue(left == 0, left + " armacraft entities leaked after disconnect");
				LOG.info("phase B (bridge e2e) passed");
			})
			.thenSucceed();
	}

	/**
	 * Opt-in soak against a live guest (normally tools/fake_guest.py with collisions and short rounds):
	 * -Parmacraft.gametest.soak=127.0.0.1:4600 . Renders a full arena at scale 0.25 for 30 s of wall-clock time
	 * (the GameTest server ticks unthrottled, the guest runs in real time) with a joined mock player and checks
	 * every tick that the world holds exactly the entities the renderer tracks (no leaks/orphans), that every
	 * alive cycle in the snapshot has an entity, and that round changes were seen.
	 */
	@GameTest(maxTicks = 1_000_000)
	public void soakAgainstGuest(GameTestHelper helper) {
		String soak = System.getProperty("armacraft.gametest.soak");
		if (soak == null) {
			helper.succeed();
			return;
		}
		current = helper;
		ServerLevel level = helper.getLevel();
		ArmaController c = Armacraft.controller();
		Vec3 mid = helper.absoluteVec(new Vec3(4.5, 1, 4.5));
		Vec3 origin = new Vec3(Math.floor(mid.x / 16.0) * 16 + 8.5, Math.floor(mid.y), Math.floor(mid.z / 16.0) * 16 + 8.5);
		java.util.Set<Long> rounds = new java.util.TreeSet<>();
		int[] stats = new int[6]; // ticks, maxEntities, maxWallPieces, deathsSeen(unused), mismatchTicks, phaseRidingTicks
		ServerPlayer[] player = new ServerPlayer[1];
		int errorsBefore = Armacraft.TICK_ERRORS.get();
		int[] fxBefore = {c.deathFxCount()};
		long[] deadline = new long[1];
		List<String> violations = new ArrayList<>();
		helper.startSequence()
			.thenExecute(() -> {
				int i = soak.lastIndexOf(':');
				ArmaConfig cfg = c.config();
				cfg.anchor.dimension = level.dimension().identifier().toString();
				cfg.anchor.x = origin.x;
				cfg.anchor.y = origin.y;
				cfg.anchor.z = origin.z;
				cfg.scale = 0.25;
				cfg.maxPieceBlocks = 32;
				c.markLayoutDirty();
				c.connect(soak.substring(0, i), Integer.parseInt(soak.substring(i + 1)));
				deadline[0] = System.currentTimeMillis() + 30_000;
			})
			.thenWaitUntil(() -> {
				within(deadline, "connect + round");
				helper.assertTrue(c.bridge().isConnected() && c.round() != null, "no round yet");
			})
			.thenExecute(() -> {
				// fit the guest arena into ~40 blocks centred on the origin
				double[] b = c.round().bounds() != null ? c.round().bounds() : new double[] {-100, -100, 100, 100};
				double extent = Math.max(Math.abs(b[2] - b[0]), Math.abs(b[3] - b[1]));
				ArmaConfig cfg = c.config();
				cfg.scale = 40.0 / Math.max(1, extent);
				cfg.anchor.x = origin.x - (b[0] + b[2]) / 2 * cfg.scale;
				cfg.anchor.z = origin.z + (b[1] + b[3]) / 2 * cfg.scale;
				c.markLayoutDirty();
				LOG.info("soak: guest bounds {} -> scale {}", java.util.Arrays.toString(b), cfg.scale);
				player[0] = helper.makeMockServerPlayerInLevel();
				c.sessions().join(player[0], c.bridge());
			})
			.thenWaitUntil(() -> {
				within(deadline, "rendering");
				helper.assertTrue(c.renderer().cycleCount() > 0, "not rendering yet");
			})
			.thenIdle(20) // let the other (instantly finishing) tests in the batch clean up their areas first
			.thenExecute(() -> deadline[0] = System.currentTimeMillis() + Long.getLong("armacraft.gametest.soakMillis", 30_000L))
			.thenWaitUntil(() -> {
				stats[0]++;
				Msg.State st = c.lastRendered();
				if (c.round() != null) {
					rounds.add(c.round().round());
				}
				int tagged = 0;
				for (Entity e : level.getAllEntities()) {
					if (e != null && !e.isRemoved() && e.entityTags().contains(EntitySet.TAG)) {
						tagged++;
					}
				}
				int tracked = c.renderer().cycleCount() + c.renderer().wallCount() + c.renderer().rimCount()
					+ c.renderer().nameCount() + c.sessions().camCount();
				if (tagged != tracked && violations.size() < 20) {
					violations.add("tick " + stats[0] + ": world has " + tagged + " armacraft entities, renderer tracks " + tracked);
				}
				if (st != null && stats[0] % 50 == 0) {
					for (String v : wallGeometryViolations(level, c, st)) {
						if (violations.size() < 20) {
							violations.add("tick " + stats[0] + ": " + v);
						}
					}
				}
				stats[1] = Math.max(stats[1], tagged);
				stats[2] = Math.max(stats[2], c.renderer().wallCount());
				if (st != null) {
					long alive = st.cycles().stream().filter(Msg.Cycle::alive).count();
					if (alive != c.renderer().cycleCount()) {
						stats[4]++;
					}
				}
				SessionManager.Session s = c.sessions().get(player[0].getUUID());
				if (s != null && s.phase == SessionManager.Phase.RIDING && player[0].getCamera() instanceof Display) {
					stats[5]++;
				}
				helper.assertTrue(System.currentTimeMillis() >= deadline[0], "soaking");
			})
			.thenExecute(() -> {
				LOG.info("soak: ticks={} maxEntities={} maxWallPieces={} cycleMismatchTicks={} ridingAttachedTicks={} rounds={} tickErrors={} deathFx={}",
					stats[0], stats[1], stats[2], stats[4], stats[5], rounds, Armacraft.TICK_ERRORS.get() - errorsBefore, c.deathFxCount() - fxBefore[0]);
				helper.assertTrue(c.deathFxCount() > fxBefore[0], "no death explosion played during soak");
				helper.assertTrue(violations.isEmpty(), "entity bookkeeping violations: " + violations);
				helper.assertTrue(Armacraft.TICK_ERRORS.get() == errorsBefore, "controller tick threw during soak");
				helper.assertTrue(rounds.size() >= 2, "expected to see a round change, saw " + rounds);
				// a cycle can be momentarily absent (chunk not loaded / one-tick lag after spawn), but not often
				helper.assertTrue(stats[4] < stats[0] / 10, "alive cycles vs entities mismatched on " + stats[4] + " of " + stats[0] + " ticks");
				helper.assertTrue(stats[5] > 0, "player never rode their cycle");
				c.sessions().leave(player[0], c.bridge());
				c.disconnect();
				level.getServer().getPlayerList().remove(player[0]);
			})
			.thenWaitUntil(() -> {
				int left = 0;
				for (Entity e : level.getAllEntities()) {
					if (e != null && !e.isRemoved() && e.entityTags().contains(EntitySet.TAG)) {
						left++;
					}
				}
				helper.assertTrue(left == 0, left + " armacraft entities leaked after soak");
			})
			.thenSucceed();
	}

	/**
	 * Every armacraft wall entity must lie on a wall segment of the snapshot it was rendered from: both ends of
	 * the entity's world-space box centre line within 0.25 blocks of one snapshot wall. Catches walls drawn
	 * past their cycle, mirrored, or left behind by a reused key.
	 */
	static List<String> wallGeometryViolations(ServerLevel level, ArmaController c, Msg.State st) {
		return wallGeometryViolationsFor(level, c.mapping(), st);
	}

	static List<String> wallGeometryViolationsFor(ServerLevel level, ArenaMapping m, Msg.State st) {
		List<String> out = new ArrayList<>();
		for (Entity e : level.getAllEntities()) {
			if (!(e instanceof Display d) || d.isRemoved() || !d.entityTags().contains("armacraft.wall")) {
				continue;
			}
			Transformation t = DisplayAccessor.armacraft$createTransformation(d.getEntityData());
			org.joml.Matrix4f mat = new org.joml.Matrix4f(t.getMatrix());
			org.joml.Vector3f a = mat.transformPosition(new org.joml.Vector3f(0.5f, 0f, 0f));
			org.joml.Vector3f b = mat.transformPosition(new org.joml.Vector3f(0.5f, 0f, 1f));
			double ax = d.getX() + a.x();
			double az = d.getZ() + a.z();
			double bx = d.getX() + b.x();
			double bz = d.getZ() + b.z();
			double best = Double.MAX_VALUE;
			for (Msg.Wall w : st.walls()) {
				double sx = m.mcX(w.x1());
				double sz = m.mcZ(w.y1());
				double ex = m.mcX(w.x2());
				double ez = m.mcZ(w.y2());
				best = Math.min(best, Math.max(pointSegDist(ax, az, sx, sz, ex, ez), pointSegDist(bx, bz, sx, sz, ex, ez)));
			}
			if (best > 0.25) {
				out.add(String.format("wall entity %s from (%.2f, %.2f) to (%.2f, %.2f) is %.2f blocks off every snapshot wall",
					d.getUUID(), ax, az, bx, bz, best));
			}
		}
		return out;
	}

	private static double pointSegDist(double px, double pz, double sx, double sz, double ex, double ez) {
		double dx = ex - sx;
		double dz = ez - sz;
		double l2 = dx * dx + dz * dz;
		double u = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - sx) * dx + (pz - sz) * dz) / l2));
		return Math.hypot(px - (sx + u * dx), pz - (sz + u * dz));
	}

	private static Msg.Cycle bridgeCycle(ArmaController c, String key) {
		Msg.State st = c.bridge().latestState();
		if (st == null) {
			return null;
		}
		for (Msg.Cycle cy : st.cycles()) {
			if (key.equals(cy.bridgeKey()) && cy.alive()) {
				return cy;
			}
		}
		return null;
	}

	private static int dirIndex(double dx, double dy) {
		int best = 0;
		double bestDot = -2;
		for (int i = 0; i < 4; i++) {
			double dot = DIRS[i][0] * dx + DIRS[i][1] * dy;
			if (dot > bestDot) {
				bestDot = dot;
				best = i;
			}
		}
		return best;
	}

	private static BlockState concrete(DyeColor c) {
		return Blocks.CONCRETE.pick(c).defaultBlockState();
	}

	private static BlockState glass(DyeColor c) {
		return Blocks.STAINED_GLASS.pick(c).defaultBlockState();
	}

	private static boolean close(double a, double b) {
		return Math.abs(a - b) < 1e-3;
	}

	private static List<Display> near(ServerLevel level, String tag, Vec3 c) {
		return near(level, tag, c, 16);
	}

	private static List<Display> near(ServerLevel level, String tag, Vec3 c, double r) {
		List<Display> out = new ArrayList<>();
		for (Entity e : level.getAllEntities()) {
			if (e instanceof Display d && !d.isRemoved() && d.entityTags().contains(tag) && d.position().distanceTo(c) <= r) {
				out.add(d);
			}
		}
		return out;
	}
}

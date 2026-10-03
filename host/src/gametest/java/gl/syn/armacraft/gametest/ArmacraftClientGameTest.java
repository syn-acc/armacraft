package gl.syn.armacraft.gametest;

import gl.syn.armacraft.ArmaController;
import gl.syn.armacraft.Armacraft;
import gl.syn.armacraft.bridge.Msg;
import gl.syn.armacraft.config.ArmaConfig;
import gl.syn.armacraft.render.EntitySet;
import gl.syn.armacraft.session.SessionManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Real client, real keys: a singleplayer world (integrated server running armacraft) with the dev client's
 * LocalPlayer. Proves the hard requirement end to end: while the client is in spectator mode and its camera is
 * the chase-cam display entity, KeyboardInput still ticks and LocalPlayer.sendChanges still sends
 * ServerboundPlayerInputPacket, which the server mixin turns into "input turn left" for the guest.
 *
 * <p>Guest: in-process MiniGuest by default, or -Darmacraft.clientgametest.guest=host:port (e.g. the real
 * Armagetron guest on 127.0.0.1:4535). Run with: gradlew runClientGametest
 */
public class ArmacraftClientGameTest implements FabricClientGameTest {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft/clientgametest");
	private static final double[][] DIRS = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
	private static final int SECOND = 20;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!RideClientGameTest.selected("arena")) {
			return;
		}
		String remote = System.getProperty("armacraft.clientgametest.server");
		if (remote != null && !remote.isBlank()) {
			runAgainstDedicatedServer(ctx, remote);
			return;
		}
		String ext = System.getProperty("armacraft.clientgametest.guest");
		MiniGuest mini = null;
		String host = "127.0.0.1";
		int port;
		try {
			if (ext != null && !ext.isBlank()) {
				int i = ext.lastIndexOf(':');
				host = ext.substring(0, i);
				port = Integer.parseInt(ext.substring(i + 1));
			} else {
				mini = new MiniGuest();
				port = mini.port();
			}
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
		final String guestHost = host;
		final int guestPort = port;
		LOG.info("client game test against guest {}:{}", guestHost, guestPort);

		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();

			// --- server side: connect the bridge, wait for the round, place the arena in front of the player
			sp.getServer().runOnServer(server -> Armacraft.controller().connect(guestHost, guestPort));
			sp.getServer().waitFor(server -> Armacraft.controller().bridge().isConnected() && Armacraft.controller().round() != null, 30 * SECOND);
			String key = sp.getServer().computeOnServer(server -> {
				ArmaController c = Armacraft.controller();
				ServerPlayer p = server.getPlayerList().getPlayers().get(0);
				double[] b = c.round().bounds() != null ? c.round().bounds() : new double[] {-100, -100, 100, 100};
				double extent = Math.max(Math.abs(b[2] - b[0]), Math.abs(b[3] - b[1]));
				ArmaConfig cfg = c.config();
				cfg.anchor.dimension = p.level().dimension().identifier().toString();
				cfg.scale = 32.0 / Math.max(1, extent);
				// arena centre 24 blocks north of the player, on the player's feet level
				double cx = p.getX();
				double cz = p.getZ() - 24;
				cfg.anchor.x = cx - (b[0] + b[2]) / 2 * cfg.scale;
				cfg.anchor.y = Math.floor(p.getY());
				cfg.anchor.z = cz + (b[1] + b[3]) / 2 * cfg.scale;
				c.markLayoutDirty();
				LOG.info("arena bounds {} scale {} anchor {} {} {}", java.util.Arrays.toString(b), cfg.scale, cfg.anchor.x, cfg.anchor.y, cfg.anchor.z);
				return SessionManager.keyFor(p.getUUID());
			});
			sp.getServer().waitFor(server -> Armacraft.controller().renderer().rimCount() > 0, 10 * SECOND);
			ctx.waitTicks(10);
			ctx.takeScreenshot("armacraft-arena-before-join");

			// --- join through the same code /arma join uses
			sp.getServer().runOnServer(server -> {
				ServerPlayer p = server.getPlayerList().getPlayers().get(0);
				Armacraft.controller().sessions().join(p, Armacraft.controller().bridge());
			});

			// client side: spectator, and the client's camera entity is the chase-cam display entity
			ctx.waitFor(mc -> mc.player != null && mc.player.isSpectator() && mc.getCameraEntity() instanceof Display, 60 * SECOND);
			String camInfo = ctx.computeOnClient(mc -> mc.getCameraEntity() + " tags=" + mc.getCameraEntity().entityTags());
			LOG.info("CLIENT camera entity is now: {}", camInfo);

			// the real guest holds new cycles for a countdown; wait until ours moves
			double[] spawn = sp.getServer().computeOnServer(server -> pos(bridgeCycle(key)));
			sp.getServer().waitFor(server -> {
				Msg.Cycle bc = bridgeCycle(key);
				return bc != null && Math.abs(bc.x() - spawn[0]) + Math.abs(bc.y() - spawn[1]) > 0.5;
			}, 30 * SECOND);
			ctx.waitTicks(5);
			ctx.takeScreenshot("armacraft-chase-cam");

			// --- real key press on the real client: A
			int before = sp.getServer().computeOnServer(server -> dirIndex(bridgeCycle(key)));
			ctx.getInput().holdKey(o -> o.keyLeft);
			ctx.waitTicks(2);
			Input sent = ctx.computeOnClient(mc -> mc.player.getLastSentInput());
			boolean camStillChase = ctx.computeOnClient(mc -> mc.getCameraEntity() instanceof Display);
			ctx.getInput().releaseKey(o -> o.keyLeft);
			LOG.info("CLIENT LocalPlayer.getLastSentInput() while A held: {} (camera still chase cam: {})", sent, camStillChase);
			if (!sent.left()) {
				throw new AssertionError("client did not send left=true in ServerboundPlayerInputPacket while spectating the chase cam: " + sent);
			}
			sp.getServer().waitFor(server -> {
				Msg.Cycle bc = bridgeCycle(key);
				return bc != null && dirIndex(bc) == Math.floorMod(before - 1, 4);
			}, 10 * SECOND);
			LOG.info("GUEST state: bridge cycle turned left {} -> {}", before, Math.floorMod(before - 1, 4));
			ctx.waitTicks(10);
			ctx.takeScreenshot("armacraft-after-left-turn");

			// --- jump = brake (hold state)
			ctx.getInput().holdKey(o -> o.keyJump);
			sp.getServer().waitFor(server -> {
				Msg.Cycle bc = anyBridgeCycle(key);
				return bc != null && bc.brake();
			}, 10 * SECOND);
			ctx.getInput().releaseKey(o -> o.keyJump);
			LOG.info("GUEST state: brake=true while jump held");

			// --- shift must not drop the chase cam while riding
			ctx.getInput().holdKey(o -> o.keyShift);
			ctx.waitTicks(10);
			boolean afterShift = ctx.computeOnClient(mc -> mc.getCameraEntity() instanceof Display);
			ctx.getInput().releaseKey(o -> o.keyShift);
			boolean riding = sp.getServer().computeOnServer(server -> {
				SessionManager.Session s = Armacraft.controller().sessions().all().iterator().next();
				return s.phase != SessionManager.Phase.WAITING;
			});
			LOG.info("CLIENT camera after holding shift: chase cam = {} (session still riding/deathcam: {})", afterShift, riding);
			if (riding && !afterShift) {
				throw new AssertionError("shift detached the chase cam");
			}

			if (mini != null) {
				List<String> got = mini.received;
				LOG.info("MiniGuest received: {}", got);
				if (got.stream().noneMatch(l -> l.contains("\"turn\":\"left\""))) {
					throw new AssertionError("guest never received turn left");
				}
			}

			// --- leave restores the player
			sp.getServer().runOnServer(server -> {
				ServerPlayer p = server.getPlayerList().getPlayers().get(0);
				Armacraft.controller().sessions().leave(p, Armacraft.controller().bridge());
			});
			ctx.waitFor(mc -> mc.player != null && !mc.player.isSpectator() && mc.getCameraEntity() == mc.player, 10 * SECOND);
			LOG.info("CLIENT restored: gamemode not spectator, camera = player");

			// --- disconnect purges everything
			sp.getServer().runOnServer(server -> Armacraft.controller().disconnect());
			sp.getServer().waitFor(server -> tagged(server) == 0, 5 * SECOND);
			LOG.info("SERVER after /arma disconnect: 0 armacraft entities");
		} finally {
			if (mini != null) {
				mini.close();
			}
		}
		LOG.info("client game test passed");
	}

	/**
	 * Joins an already running dedicated dev server (gradlew runServer, online-mode=false) as the offline dev
	 * player, then plays like a person would: chat command /arma join, real A / jump / shift key presses,
	 * /arma leave. Evidence is on both sides: client-side assertions here, and the dev server log
	 * ("input ...: turn left (sent)", "guest turn event for ...").
	 */
	private void runAgainstDedicatedServer(ClientGameTestContext ctx, String addr) {
		LOG.info("client game test: joining dedicated server {}", addr);
		ctx.runOnClient(mc -> net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
			new net.minecraft.client.gui.screens.TitleScreen(), mc,
			net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(addr),
			new net.minecraft.client.multiplayer.ServerData("armacraft dev", addr, net.minecraft.client.multiplayer.ServerData.Type.OTHER),
			false, null));
		ctx.waitFor(mc -> mc.player != null && mc.level != null && mc.gui.screen() == null, 120 * SECOND);
		ctx.waitTicks(40);
		ctx.takeScreenshot("remote-1-joined-server");

		ctx.runOnClient(mc -> mc.player.connection.sendCommand("arma join"));
		ctx.waitFor(mc -> mc.player.isSpectator() && mc.getCameraEntity() instanceof Display, 120 * SECOND);
		String camDesc = ctx.computeOnClient(mc -> String.valueOf(mc.getCameraEntity()));
		LOG.info("CLIENT spectating chase cam: {}", camDesc);

		net.minecraft.world.phys.Vec3 p0 = ctx.computeOnClient(mc -> mc.getCameraEntity().position());
		ctx.waitFor(mc -> mc.getCameraEntity() instanceof Display d && d.position().distanceTo(p0) > 1.0, 60 * SECOND);
		ctx.waitTicks(10);
		ctx.takeScreenshot("remote-2-chase-cam");

		float yaw0 = ctx.computeOnClient(mc -> mc.getCameraEntity().getYRot());
		ctx.getInput().holdKey(o -> o.keyLeft);
		ctx.waitTicks(2);
		Input sent = ctx.computeOnClient(mc -> mc.player.getLastSentInput());
		ctx.getInput().releaseKey(o -> o.keyLeft);
		LOG.info("CLIENT LocalPlayer.getLastSentInput() while A held (spectating chase cam): {}", sent);
		if (!sent.left()) {
			throw new AssertionError("client did not send left=true while spectating the chase cam: " + sent);
		}
		// The guest applies the turn and the chase cam (driven by the guest state) swings 90 degrees left.
		ctx.waitFor(mc -> mc.getCameraEntity() instanceof Display d
			&& Math.abs(net.minecraft.util.Mth.wrapDegrees(d.getYRot() - (yaw0 - 90f))) < 5f, 10 * SECOND);
		float yaw1 = ctx.computeOnClient(mc -> mc.getCameraEntity().getYRot());
		LOG.info("CLIENT chase cam yaw {} -> {} after A (guest-confirmed left turn)", yaw0, yaw1);
		ctx.waitTicks(10);
		ctx.takeScreenshot("remote-3-after-left-turn");

		ctx.getInput().holdKey(o -> o.keyJump);
		ctx.waitTicks(10);
		Input braking = ctx.computeOnClient(mc -> mc.player.getLastSentInput());
		ctx.getInput().releaseKey(o -> o.keyJump);
		LOG.info("CLIENT last sent input while jump held: {}", braking);
		ctx.waitTicks(20);
		ctx.takeScreenshot("remote-3b-riding");

		ctx.getInput().holdKey(o -> o.keyShift);
		ctx.waitTicks(10);
		boolean afterShift = ctx.computeOnClient(mc -> mc.getCameraEntity() instanceof Display);
		ctx.getInput().releaseKey(o -> o.keyShift);
		LOG.info("CLIENT camera still chase cam after holding shift: {}", afterShift);

		ctx.runOnClient(mc -> mc.player.connection.sendCommand("arma leave"));
		ctx.waitFor(mc -> !mc.player.isSpectator() && mc.getCameraEntity() == mc.player, 20 * SECOND);
		String modeDesc = ctx.computeOnClient(mc -> String.valueOf(mc.gameMode.getPlayerMode()));
		LOG.info("CLIENT restored after /arma leave: gamemode {}, camera = player", modeDesc);
		ctx.takeScreenshot("remote-4-after-leave");

		ctx.runOnClient(mc -> mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("armacraft client test done")));
		ctx.waitFor(mc -> mc.level == null, 30 * SECOND);
		ctx.runOnClient(mc -> mc.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen()));
		LOG.info("client game test (dedicated server) passed");
	}

	private static int tagged(MinecraftServer server) {
		int n = 0;
		for (ServerLevel level : server.getAllLevels()) {
			for (Entity e : level.getAllEntities()) {
				if (e != null && !e.isRemoved() && e.entityTags().contains(EntitySet.TAG)) {
					n++;
				}
			}
		}
		return n;
	}

	private static double[] pos(Msg.Cycle c) {
		return c == null ? new double[] {0, 0} : new double[] {c.x(), c.y()};
	}

	private static Msg.Cycle bridgeCycle(String key) {
		Msg.State st = Armacraft.controller().bridge().latestState();
		if (st == null) {
			return null;
		}
		for (Msg.Cycle c : st.cycles()) {
			if (key.equals(c.bridgeKey()) && c.alive()) {
				return c;
			}
		}
		return null;
	}

	private static Msg.Cycle anyBridgeCycle(String key) {
		Msg.Cycle alive = bridgeCycle(key);
		if (alive != null) {
			return alive;
		}
		Msg.State st = Armacraft.controller().bridge().latestState();
		if (st != null) {
			for (Msg.Cycle c : st.cycles()) {
				if (key.equals(c.bridgeKey())) {
					return c;
				}
			}
		}
		return null;
	}

	private static int dirIndex(Msg.Cycle c) {
		if (c == null) {
			return -1;
		}
		int best = 0;
		double bestDot = -2;
		for (int i = 0; i < 4; i++) {
			double dot = DIRS[i][0] * c.dx() + DIRS[i][1] * c.dy();
			if (dot > bestDot) {
				bestDot = dot;
				best = i;
			}
		}
		return best;
	}
}

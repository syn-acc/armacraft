package gl.syn.armacraft.client.gametest;

import gl.syn.armacraft.ArmaController;
import gl.syn.armacraft.Armacraft;
import gl.syn.armacraft.client.ArenaClient;
import gl.syn.armacraft.client.core.ArenaMapping;
import gl.syn.armacraft.client.core.ArenaModel;
import gl.syn.armacraft.client.input.ArmaKeys;
import gl.syn.armacraft.config.ArmaConfig;
import gl.syn.armacraft.relay.BridgeRelay;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * armacraft_client + armacraft (host) in one real dev client, against the integrated server of a singleplayer world:
 * the host's relay (gl.syn.armacraft.relay) feeds this client over {@code armacraft:bridge}. Checks that the client in
 * {@code auto} mode picks the relay (direct is pointed at a closed port so it never touches a live guest), takes the
 * server's mapping, folds the host's negotiated {@code delta} lines (DeltaGuest offers the feature), joins with the
 * real J key, rides its own cycle (found by the key the server forced), turns with the real A key, and leaves.
 * Run with: gradlew runClientGametest
 */
public class RelayClientGameTest implements FabricClientGameTest {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft_client/clientgametest");
	private static final int SECOND = 20;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!RideVisualsClientGameTest.selected("relay")) {
			return;
		}
		DeltaGuest guest;
		try {
			guest = new DeltaGuest();
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
		ctx.runOnClient(mc -> {
			var cfg = ArenaClient.get().config();
			cfg.mode = "auto";
			cfg.direct.host = "127.0.0.1";
			cfg.direct.port = 1;   // nothing listens there: the fallback can never reach a live guest
		});
		try (guest; TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();

			// --- server: host bridge to the DeltaGuest, arena placed in front of the player
			sp.getServer().runOnServer(server -> {
				ArmaController c = Armacraft.controller();
				ServerPlayer p = server.getPlayerList().getPlayers().get(0);
				ArmaConfig cfg = c.config();
				cfg.anchor.dimension = p.level().dimension().identifier().toString();
				cfg.scale = 0.5;
				cfg.anchor.x = Math.floor(p.getX()) + 0.5;
				cfg.anchor.y = Math.floor(p.getY());
				cfg.anchor.z = Math.floor(p.getZ()) - 40.5;
				c.markLayoutDirty();
				c.connect("127.0.0.1", guest.port());
			});
			sp.getServer().waitFor(server -> Armacraft.controller().bridge().isConnected() && Armacraft.controller().round() != null, 30 * SECOND);
			UUID uuid = sp.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().get(0).getUUID());
			String key = "mc:" + uuid;
			double[] serverMap = sp.getServer().computeOnServer(server -> {
				ArmaConfig cfg = Armacraft.controller().config();
				return new double[] {cfg.anchor.x, cfg.anchor.y, cfg.anchor.z, cfg.scale};
			});

			// --- client: auto mode chose the relay, took the server's mapping, has the arena
			ctx.waitFor(mc -> {
				ArenaClient a = ArenaClient.get();
				return "relay".equals(a.transportKind()) && a.model().hasArena() && !a.model().walls().isEmpty();
			}, 30 * SECOND);
			String transport = ctx.computeOnClient(mc -> ArenaClient.get().transportDescription());
			ArenaMapping m = ctx.computeOnClient(mc -> ArenaClient.get().mapping());
			LOG.info("CLIENT transport: {} | relay offered: {} | mapping {}", transport, ctx.computeOnClient(mc -> ArenaClient.get().relayOffered()), m);
			check(m.ax() == serverMap[0] && m.ay() == serverMap[1] && m.az() == serverMap[2] && m.scale() == serverMap[3],
				"client mapping must come from the relay line: " + m + " vs server " + java.util.Arrays.toString(serverMap));
			check(guest.deltasSent > 0 && guest.keyDeltasSent >= 1, "host never negotiated delta with the guest");

			// --- client folds the relayed deltas into exactly the guest's wall set (across leg changes and removals)
			int before = guest.wallIds().stream().mapToInt(Integer::intValue).max().orElse(0);
			ctx.waitFor(mc -> guest.wallIds().stream().mapToInt(Integer::intValue).min().orElse(0) > before, 30 * SECOND); // every wall replaced: legs, removals
			ctx.waitFor(mc -> clientWallIds().equals(guest.wallIds()), 10 * SECOND);
			LOG.info("CLIENT walls folded from {} deltas ({} keyframes from the guest): {}", guest.deltasSent, guest.keyDeltasSent, clientWallIds());
			ctx.takeScreenshot("armacraft-relay-arena");

			// --- real J key: join through the relay
			ctx.getInput().pressKey(ArmaKeys.JOIN);
			ctx.waitFor(mc -> guest.received.stream().anyMatch(l -> l.contains("\"type\":\"join\"") && l.contains(key)), 10 * SECOND);
			String join = guest.received.stream().filter(l -> l.contains("\"type\":\"join\"")).findFirst().orElseThrow();
			String profile = sp.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().get(0).getGameProfile().name());
			check(join.contains("\"key\":\"" + key + "\"") && join.contains("\"name\":\"" + profile + "\""), "guest join: " + join);
			boolean rider = sp.getServer().computeOnServer(server -> BridgeRelay.hub().isRider(uuid));
			boolean session = sp.getServer().computeOnServer(server -> Armacraft.controller().sessions().get(uuid) != null);
			GameType mode = sp.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().get(0).gameMode.getGameModeForPlayer());
			check(rider && !session && mode != GameType.SPECTATOR, "relay rider must not get the vanilla session: rider " + rider + " session " + session + " mode " + mode);

			// --- the client finds its own cycle by the key the server forced, and rides it
			ctx.waitFor(mc -> {
				ArenaModel.Cycle own = ArenaClient.get().ownCycle();
				return own != null && own.alive && ArenaClient.get().riding() && ArenaClient.get().camera().override();
			}, 15 * SECOND);
			ctx.waitTicks(20);
			ctx.takeScreenshot("armacraft-relay-riding");

			// --- real A key: turn left, forwarded with the forced key, applied by the guest, seen by the client
			int dirBefore = guest.bridgeDir();
			ctx.getInput().pressKey(ArmaKeys.TURN_LEFT);
			String want = "{\"type\":\"input\",\"key\":\"" + key + "\",\"turn\":\"left\"}";
			ctx.waitFor(mc -> guest.received.contains(want), 10 * SECOND);
			ctx.waitFor(mc -> guest.bridgeDir() == Math.floorMod(dirBefore - 1, 4), 10 * SECOND);
			ctx.waitFor(mc -> {
				ArenaModel.Cycle own = ArenaClient.get().ownCycle();
				return own != null && own.track.headingAt(ArenaClient.get().gameTime()).x() < -0.5; // north -> west
			}, 10 * SECOND);
			ctx.waitTicks(10);
			ctx.takeScreenshot("armacraft-relay-after-left-turn");

			// --- real J key again: leave
			ctx.getInput().pressKey(ArmaKeys.JOIN);
			ctx.waitFor(mc -> guest.received.contains("{\"type\":\"leave\",\"key\":\"" + key + "\"}"), 10 * SECOND);
			sp.getServer().waitFor(server -> !BridgeRelay.hub().isRider(uuid), 5 * SECOND);
			check(guest.received.stream().noneMatch(l -> l.contains("\"type\":\"cmd\"") || l.contains("\"type\":\"explode\"")), "host-only messages from the client");
			LOG.info("relay client game test passed; guest received {}", guest.received);
		}
	}

	private static Set<Integer> clientWallIds() {
		Set<Integer> ids = new TreeSet<>();
		for (ArenaModel.Wall w : ArenaClient.get().model().walls()) {
			ids.add(w.id());
		}
		return ids;
	}

	private static void check(boolean ok, String msg) {
		if (!ok) {
			throw new AssertionError(msg);
		}
	}
}

package gl.syn.armacraft.gametest;

import gl.syn.armacraft.Armacraft;
import gl.syn.armacraft.client.ride.RideClientMod;
import gl.syn.armacraft.client.ride.RemoteRiders;
import gl.syn.armacraft.client.ride.RideKeys;
import gl.syn.armacraft.ride.RideService;
import gl.syn.armacraft.ride.core.Heading;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Another rider as a modded client sees it: the server syncs its pose and trail (armacraft:ride_sync), the client
 * draws them and treats the trail as a wall (client-predicted rubber, then a crash), and the server's display-entity
 * stand-ins are not sent to this client. The other rider is a server-side virtual rider (trail and pose, no player).
 */
final class RideSyncTest {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft/ride-sync");
	private static final int SECOND = 20;
	private static final int Y = -60;

	private final ClientGameTestContext ctx;
	private final List<String> results = new ArrayList<>();

	RideSyncTest(ClientGameTestContext ctx) {
		this.ctx = ctx;
	}

	void run() {
		try (TestSingleplayerContext sp = ctx.worldBuilder().adjustSettings(c -> c.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE)).create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runOnServer(s -> Armacraft.ride().config().lethalTrails = true); // never trust a config an earlier run saved
			UUID other = UUID.randomUUID();
			// the other rider's trail crosses our lane (z = 0.5) at x = 30, running north-south from z = -15 to z = 15
			sp.getServer().runOnServer(s -> {
				RideService.Rider r = Armacraft.ride().addVirtualRider(s.overworld().dimension(), other, "virtualRider", 0x30FF60, Heading.SOUTH, 30.5, Y, 15.5);
				r.trail.append(30.5, Y, -15.5, 0);
				r.trail.append(30.5, Y, 15.5, 0);
				var p = s.getPlayerList().getPlayers().get(0);
				p.teleportTo(s.overworld(), 0.5, Y, 0.5, Set.of(), -90f, 15f, true);
			});
			ctx.waitTicks(10);
			ctx.waitFor(mc -> RideClientMod.remote().all().stream().anyMatch(r -> r.trail.size() >= 2), 5 * SECOND);
			RemoteRiders.Remote seen = ctx.computeOnClient(mc -> RideClientMod.remote().all().iterator().next());
			check(seen.trail.size() >= 2 && seen.riding, "client received the other rider: riding, trail of " + seen.trail.size() + " points");
			int fallbackOnClient = ctx.computeOnClient(mc -> mc.level.getEntitiesOfClass(Display.class, new AABB(20, Y - 5, -25, 40, Y + 10, 25)).size());
			check(fallbackOnClient == 0, "no display-entity stand-ins sent to a modded client (" + fallbackOnClient + " displays near the other rider)");

			ctx.getInput().pressKey(RideKeys.RIDE);
			ctx.waitFor(mc -> RideClientMod.client().riding(), 5 * SECOND);
			ctx.waitFor(mc -> mc.player.getX() > 22, 5 * SECOND);
			ctx.takeScreenshot("ride-sync-other-trail");
			double[] maxX = {0};
			ctx.waitFor(mc -> {
				maxX[0] = Math.max(maxX[0], mc.player.getX());
				return !RideClientMod.client().riding();
			}, 6 * SECOND);
			String cause = ctx.computeOnClient(mc -> RideClientMod.client().stats.lastCause);
			check(cause.contains("light trail"), "crashed into the other rider's trail (" + cause + ")");
			check(maxX[0] < 30.5, "stopped short of it, client-predicted (max x " + String.format("%.3f", maxX[0]) + ")");
			sp.getServer().runOnServer(s -> Armacraft.ride().removeVirtualRider(other));
			LOG.info("sync results:{}  {}", System.lineSeparator(), String.join(System.lineSeparator() + "  ", results));
		}
	}

	private void check(boolean ok, String what) {
		results.add((ok ? "PASS " : "FAIL ") + what);
		LOG.info("{} {}", ok ? "PASS" : "FAIL", what);
		if (!ok) {
			ctx.takeScreenshot("ride-sync-FAIL");
			throw new AssertionError(what + System.lineSeparator() + String.join(System.lineSeparator(), results));
		}
	}
}

package gl.syn.armacraft.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import gl.syn.armacraft.client.ride.RideClientMod;
import gl.syn.armacraft.client.ride.RideKeys;
import gl.syn.armacraft.ride.core.CycleMovement;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The dev client joins a real dedicated server (gradlew runClientGametest -Parmacraft.clientgametest.server=host:port
 * -Parmacraft.clientgametest.rcon=&lt;server.properties&gt; -Parmacraft.clientgametest.only=remote) in survival and
 * rides a small course built over RCON: 1-block step, a pool, a staircase up to a plateau and a 4-block drop. Proves
 * the dedicated server's movement checks do not kick or rubber-band a rider: still connected at the end, and the
 * server log has no "moved wrongly", "moved too quickly" or "floating too long" line for this player.
 */
final class RideRemoteTest {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft/ride-remote");
	private static final int SECOND = 20;
	private static final int X = 2000;
	private static final int Z = 2000;
	private static final int Y = -60;

	private final ClientGameTestContext ctx;
	private final String addr;
	private final Path props;
	private final List<String> results = new ArrayList<>();

	RideRemoteTest(ClientGameTestContext ctx, String addr, Path props) {
		this.ctx = ctx;
		this.addr = addr;
		this.props = props;
	}

	void run() throws IOException {
		Path log = props.resolveSibling("logs").resolve("latest.log");
		ctx.runOnClient(mc -> ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(addr),
			new ServerData("armacraft dev", addr, ServerData.Type.OTHER), false, null));
		ctx.waitFor(mc -> mc.player != null && mc.level != null && mc.gui.screen() == null, 120 * SECOND);
		ctx.waitTicks(20);
		String name = ctx.computeOnClient(mc -> mc.player.getGameProfile().name());
		LOG.info("joined {} as {}", addr, name);
		long logStart = Files.exists(log) ? Files.readAllLines(log, StandardCharsets.UTF_8).size() : 0;
		try (Rcon rcon = Rcon.fromServerProperties(props)) {
			rcon.command("forceload add " + (X - 10) + " " + (Z - 10) + " " + (X + 110) + " " + (Z + 10));
			rcon.command("gamemode survival " + name);
			rcon.command("effect clear " + name);
			buildCourse(rcon);
			rcon.command("tp " + name + " " + (X + 0.5) + " " + Y + " " + (Z + 0.5) + " -90 10");
		}
		ctx.waitFor(mc -> Math.abs(mc.player.getX() - (X + 0.5)) < 0.1 && Math.abs(mc.player.getZ() - (Z + 0.5)) < 0.1, 10 * SECOND);
		ctx.waitTicks(30);
		float hp0 = ctx.computeOnClient(mc -> mc.player.getHealth());
		ctx.takeScreenshot("ride-remote-1-start");

		ctx.getInput().pressKey(RideKeys.RIDE);
		ctx.waitFor(mc -> RideClientMod.client().riding(), 10 * SECOND);
		check(true, "dedicated server started the ride (ride_start payload)");
		double[] maxStep = {-1000};
		double[] minWater = {1000};
		double[] maxPlateau = {-1000};
		ctx.waitFor(mc -> {
			double x = mc.player.getX();
			double y = mc.player.getY();
			if (x > X + 10.5 && x < X + 14.5) {
				maxStep[0] = Math.max(maxStep[0], y);
			}
			if (x > X + 30.5 && x < X + 45.5) {
				minWater[0] = Math.min(minWater[0], y);
			}
			if (x > X + 60 && x < X + 70) {
				maxPlateau[0] = Math.max(maxPlateau[0], y);
			}
			return x > X + 90 || !RideClientMod.client().riding();
		}, 20 * SECOND);
		ctx.takeScreenshot("ride-remote-2-after-course");
		check(ctx.computeOnClient(mc -> RideClientMod.client().riding()), "rode the whole course (" + RideClientMod.client().stats.lastCause + ")");
		check(Math.abs(maxStep[0] - (Y + 1)) < 0.01, "stepped up on the server (y " + maxStep[0] + ")");
		check(minWater[0] >= Y - 0.001, "rode over the pool without dipping (min y " + minWater[0] + ")");
		check(Math.abs(maxPlateau[0] - (Y + 4)) < 0.01, "climbed the staircase to the plateau (y " + maxPlateau[0] + ")");
		double y = ctx.computeOnClient(mc -> mc.player.getY());
		check(Math.abs(y - Y) < 0.01, "dropped 4 blocks back to the ground (y " + y + ")");

		// turns, brake, boost at speed on the server
		ctx.getInput().pressKey(InputConstants.getKey("key.keyboard.j"));
		ctx.waitTicks(10);
		ctx.getInput().holdKey(InputConstants.getKey("key.keyboard.down"));
		ctx.waitTicks(10);
		ctx.getInput().releaseKey(InputConstants.getKey("key.keyboard.down"));
		ctx.getInput().holdKey(InputConstants.getKey("key.keyboard.left.shift"));
		ctx.waitTicks(10);
		ctx.getInput().releaseKey(InputConstants.getKey("key.keyboard.left.shift"));
		ctx.getInput().pressKey(InputConstants.getKey("key.keyboard.w"));
		ctx.waitTicks(20);
		double maxSpeed = ctx.computeOnClient(mc -> RideClientMod.client().stats.maxSpeed);
		check(ctx.computeOnClient(mc -> RideClientMod.client().riding()), "turned, braked and boosted (max speed " + String.format("%.1f", maxSpeed) + " b/s)");
		float hp1 = ctx.computeOnClient(mc -> mc.player.getHealth());
		check(hp1 >= hp0, "no fall damage on the dedicated server (health " + hp0 + " -> " + hp1 + ")");
		ctx.getInput().pressKey(InputConstants.getKey("key.keyboard.backspace"));
		ctx.waitFor(mc -> !RideClientMod.client().cycleMode(), 5 * SECOND);
		ctx.waitTicks(20);
		boolean connected = ctx.computeOnClient(mc -> mc.getConnection() != null && mc.player != null && mc.level != null);
		check(connected, "still connected after the ride (no kick)");

		List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
		int wrongly = 0;
		int quickly = 0;
		int floating = 0;
		for (int i = (int) Math.min(logStart, lines.size()); i < lines.size(); i++) {
			String l = lines.get(i);
			if (!l.contains(name)) {
				continue;
			}
			wrongly += l.contains("moved wrongly") ? 1 : 0;
			quickly += l.contains("moved too quickly") ? 1 : 0;
			floating += l.contains("floating too long") ? 1 : 0;
		}
		check(wrongly == 0 && quickly == 0 && floating == 0,
			"server log during the ride: " + wrongly + " moved wrongly, " + quickly + " moved too quickly, " + floating + " floating too long");

		try (Rcon rcon = Rcon.fromServerProperties(props)) {
			clearCourse(rcon);
			rcon.command("forceload remove " + (X - 10) + " " + (Z - 10) + " " + (X + 110) + " " + (Z + 10));
		}
		ctx.runOnClient(mc -> mc.disconnectFromWorld(Component.literal("armacraft ride remote test done")));
		ctx.waitFor(mc -> mc.level == null, 30 * SECOND);
		ctx.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		LOG.info("remote ride results:{}  {}", System.lineSeparator(), String.join(System.lineSeparator() + "  ", results));
	}

	private static void buildCourse(Rcon r) throws IOException {
		int z0 = Z - 1;
		int z1 = Z + 1;
		r.command(fill(X + 10, Y, z0, X + 14, Y, z1, "stone"));
		r.command(fill(X + 30, Y - 3, Z - 3, X + 45, Y - 1, Z + 3, "water"));
		// staircase up 4 blocks, plateau to x+70, then a 4-block drop
		for (int i = 0; i < 4; i++) {
			r.command(fill(X + 55 + i, Y, z0, X + 55 + i, Y + i, z1, "stone"));
		}
		r.command(fill(X + 59, Y, z0, X + 70, Y + 3, z1, "stone"));
	}

	private static void clearCourse(Rcon r) throws IOException {
		r.command(fill(X - 2, Y, Z - 4, X + 105, Y + 10, Z + 4, "air"));
		r.command(fill(X + 30, Y - 3, Z - 3, X + 45, Y - 2, Z + 3, "dirt"));
		r.command(fill(X + 30, Y - 1, Z - 3, X + 45, Y - 1, Z + 3, "grass_block"));
	}

	private static String fill(int x0, int y0, int z0, int x1, int y1, int z1, String block) {
		return "fill " + x0 + " " + y0 + " " + z0 + " " + x1 + " " + y1 + " " + z1 + " " + block;
	}

	private void check(boolean ok, String what) {
		results.add((ok ? "PASS " : "FAIL ") + what);
		LOG.info("{} {}", ok ? "PASS" : "FAIL", what);
		if (!ok) {
			ctx.takeScreenshot("ride-remote-FAIL");
			throw new AssertionError(what + System.lineSeparator() + String.join(System.lineSeparator(), results));
		}
	}
}

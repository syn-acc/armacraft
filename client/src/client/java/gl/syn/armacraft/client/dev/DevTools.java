package gl.syn.armacraft.client.dev;

import gl.syn.armacraft.client.ArenaClient;
import gl.syn.armacraft.client.core.ArenaModel;
import gl.syn.armacraft.client.core.V2;
import gl.syn.armacraft.client.core.WallGeom;
import gl.syn.armacraft.client.mixin.CreateWorldScreenInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Debug helpers for self-verification, all off unless a system property is set:
 * <ul>
 *   <li>{@code armacraft.devWorld=<name>}: on the title screen open that save, creating a creative superflat
 *       world if it does not exist (singleplayer needs no EULA).</li>
 *   <li>{@code armacraft.devJoin=true}: join the arena once connected.</li>
 *   <li>{@code armacraft.devAutopilot=true}: steer the own cycle away from walls (exercises the real key path).</li>
 *   <li>{@code armacraft.devShots=<seconds>}: write framebuffer PNGs to run/screenshots every N seconds
 *       ({@code armacraft.devShotsMax} caps the count); {@code /armac shot [label]} does one on demand.</li>
 *   <li>{@code armacraft.devQuitAfter=<seconds>}: close the client after N seconds in a world.</li>
 *   <li>{@code armacraft.devCommands=a;b;c}: run these chat commands once in the world (e.g. "time set night");
 *       {@code @path} reads them from a file, one per line.</li>
 * </ul>
 */
public final class DevTools {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft_client/dev");
	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HHmmss_SSS");

	private final String devWorld = System.getProperty("armacraft.devWorld");
	private final boolean devJoin = Boolean.getBoolean("armacraft.devJoin");
	private final boolean autopilot = Boolean.getBoolean("armacraft.devAutopilot");
	private final double shotEvery = parse(System.getProperty("armacraft.devShots"), 0);
	private final int shotsMax = (int) parse(System.getProperty("armacraft.devShotsMax"), 200);
	private final double quitAfter = parse(System.getProperty("armacraft.devQuitAfter"), 0);
	private final String devCommands = System.getProperty("armacraft.devCommands");
	/** e.g. "custom:6,close:4,side:4,in:4": camera presets cycled while riding (verification only). */
	private final String camSeq = System.getProperty("armacraft.devCamSeq");
	private double camSeqStart = Double.NaN;
	private String camPreset = "";
	private boolean ownWasDead = true;
	private final int turnShots = (int) parse(System.getProperty("armacraft.devTurnShots"), 0);
	private int turnShotsTaken;
	private double lastOwnTurnT = Double.NaN;
	private double turnShotAt = Double.NaN;
	private double deathShotAt = Double.NaN;
	private boolean worldTried;
	private boolean joined;
	private boolean commandsRun;
	private double worldSince = Double.NaN;
	private double nextShot = Double.NaN;
	private int shots;
	private double nextTurnT;
	private final Random rnd = new Random(7);
	private String pendingLabel;

	private static double parse(String s, double def) {
		if (s == null) {
			return def;
		}
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return def;
		}
	}

	public void requestShot(String label) {
		pendingLabel = label == null || label.isBlank() ? "cmd" : label.replaceAll("[^A-Za-z0-9_-]", "_");
	}

	public void tick() {
		Minecraft mc = Minecraft.getInstance();
		ArenaClient client = ArenaClient.get();
		var screen = mc.gui.screen();
		if (devWorld != null && !worldTried && mc.level == null && screen != null
			&& (screen instanceof TitleScreen || screen.getClass().getSimpleName().contains("Onboarding"))) {
			worldTried = true;
			openOrCreate(mc, devWorld);
		}
		if (mc.level == null) {
			worldSince = Double.NaN;
			return;
		}
		double now = ArenaClient.localNow();
		if (Double.isNaN(worldSince)) {
			worldSince = now;
		}
		if (devCommands != null && !commandsRun && now - worldSince > 2 && mc.player != null) {
			commandsRun = true;
			String cmds = devCommands;
			if (cmds.startsWith("@")) {
				try {
					cmds = String.join(";", Files.readAllLines(Path.of(cmds.substring(1))));
				} catch (java.io.IOException e) {
					LOG.warn("devCommands file: {}", e.getMessage());
				}
			}
			for (String c : cmds.split(";")) {
				if (!c.isBlank()) {
					mc.player.connection.sendCommand(c.trim());
				}
			}
		}
		if (devJoin && !joined && client != null && client.active()) {
			joined = true;
			client.join();
		}
		if (autopilot && client != null) {
			autopilot(client);
		}
		if (camSeq != null && client != null && client.riding()) {
			camSequence(client, now);
		}
		if (client != null && shotEvery > 0 && client.active()) {
			ArenaModel.Cycle own = client.ownCycle();
			boolean dead = own != null && !own.alive;
			if (dead && !ownWasDead) {
				deathShotAt = now + 0.35;
			}
			ownWasDead = own == null || dead;
			if (own != null && own.alive && turnShotsTaken < turnShots) {
				double lt = own.track.lastTurnTime();
				if (Double.isFinite(lt) && lt != lastOwnTurnT && Double.isNaN(turnShotAt)) {
					turnShotAt = now + 0.07;
				}
				lastOwnTurnT = lt;
			}
			if (!Double.isNaN(turnShotAt) && now >= turnShotAt) {
				turnShotAt = Double.NaN;
				turnShotsTaken++;
				shoot(mc, "turn" + turnShotsTaken);
			}
			if (!Double.isNaN(deathShotAt) && now >= deathShotAt) {
				deathShotAt = Double.NaN;
				shoot(mc, "explosion");
			}
		}
		if (shotEvery > 0 && shots < shotsMax && client != null && client.active()) {
			if (Double.isNaN(nextShot)) {
				nextShot = now + shotEvery;
			} else if (now >= nextShot) {
				nextShot = now + shotEvery;
				shots++;
				shoot(mc, "auto" + shots);
			}
		}
		if (pendingLabel != null) {
			shoot(mc, pendingLabel);
			pendingLabel = null;
		}
		if (quitAfter > 0 && now - worldSince > quitAfter) {
			LOG.info("devQuitAfter reached, stopping client");
			mc.stop();
		}
	}

	private static void openOrCreate(Minecraft mc, String name) {
		try {
			if (mc.getLevelSource().levelExists(name)) {
				LOG.info("dev: opening world {}", name);
				mc.createWorldOpenFlows().openWorld(name, () -> mc.gui.setScreen(new TitleScreen()));
				return;
			}
			LOG.info("dev: creating superflat world {}", name);
			CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(new TitleScreen()));
			if (!(mc.gui.screen() instanceof CreateWorldScreen screen)) {
				LOG.warn("dev: CreateWorldScreen did not open");
				return;
			}
			WorldCreationUiState ui = screen.getUiState();
			Holder<WorldPreset> flat = ui.getSettings().worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT);
			ui.setWorldType(new WorldCreationUiState.WorldTypeEntry(flat));
			ui.setName(name);
			ui.setSeed("1");
			ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			ui.setAllowCommands(true);
			ui.setGenerateStructures(false);
			ui.getGameRules().set(GameRules.ADVANCE_TIME, false, null);
			ui.getGameRules().set(GameRules.ADVANCE_WEATHER, false, null);
			ui.getGameRules().set(GameRules.SPAWN_MOBS, false, null);
			((CreateWorldScreenInvoker) screen).armacraft$onCreate();
		} catch (RuntimeException e) {
			LOG.error("dev world setup failed", e);
		}
	}

	private void shoot(Minecraft mc, String label) {
		File dir = new File(mc.gameDirectory, "screenshots");
		String file = "armacraft_" + LocalDateTime.now().format(TS) + "_" + label + ".png";
		Path path = dir.toPath().resolve(file);
		try {
			Files.createDirectories(dir.toPath());
			Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> net.minecraft.util.Util.ioPool().execute(() -> {
				try (image) {
					image.writeToFile(path);
					LOG.info("dev screenshot {}", path);
				} catch (Exception e) {
					LOG.warn("screenshot failed: {}", e.getMessage());
				}
			}));
		} catch (Exception e) {
			LOG.warn("screenshot failed: {}", e.getMessage());
		}
	}

	private void camSequence(ArenaClient client, double now) {
		if (Double.isNaN(camSeqStart)) {
			camSeqStart = now;
		}
		String[] parts = camSeq.split(",");
		double total = 0;
		double[] dur = new double[parts.length];
		String[] names = new String[parts.length];
		for (int i = 0; i < parts.length; i++) {
			String[] kv = parts[i].trim().split(":");
			names[i] = kv[0];
			dur[i] = kv.length > 1 ? parse(kv[1], 4) : 4;
			total += dur[i];
		}
		double tt = (now - camSeqStart) % total;
		String name = names[names.length - 1];
		for (int i = 0; i < parts.length; i++) {
			if (tt < dur[i]) {
				name = names[i];
				break;
			}
			tt -= dur[i];
		}
		if (name.equals(camPreset)) {
			return;
		}
		camPreset = name;
		var c = client.config().camera;
		var chase = client.camera().chase();
		chase.configure(c.customBack, c.customRise, c.customPitch, c.customZoom, c.customTurnSpeed, c.customTurnSpeed180, c.inTurnSpeed);
		chase.glance(false, false, false);
		chase.setMode(gl.syn.armacraft.client.kit.ChaseCam.Mode.CHASE);
		switch (name) {
			case "close" -> chase.configure(3.2, 1.3, -0.3, c.customZoom, c.customTurnSpeed, c.customTurnSpeed180, c.inTurnSpeed);
			case "side" -> {
				chase.configure(3.2, 0.9, -0.2, c.customZoom, c.customTurnSpeed, c.customTurnSpeed180, c.inTurnSpeed);
				chase.glance(true, false, false);
			}
			case "mid" -> chase.configure(10, 6, -0.55, c.customZoom, c.customTurnSpeed, c.customTurnSpeed180, c.inTurnSpeed);
			case "in" -> chase.setMode(gl.syn.armacraft.client.kit.ChaseCam.Mode.IN_CYCLE);
			default -> {
			}
		}
		LOG.info("dev camera preset {}", name);
	}

	/** Look ahead along the heading; turn towards the side with more room, plus occasional random turns. */
	private void autopilot(ArenaClient client) {
		ArenaModel.Cycle own = client.ownCycle();
		double t = client.gameTime();
		if (own == null || !own.alive || !(t > 0.2)) {
			return;
		}
		if (t < nextTurnT) {
			return;
		}
		List<WallGeom.Seg> segs = new ArrayList<>(client.model().rim());
		for (ArenaModel.Wall w : client.model().walls()) {
			segs.add(new WallGeom.Seg(w.x1(), w.y1(), w.x2(), w.y2()));
		}
		V2 p = client.renderPos(own);
		V2 d = own.track.headingAt(t);
		double ahead = WallGeom.raycast(p, d, 200, segs, 0.5);
		double left = WallGeom.raycast(p, d.turn(0, 1), 200, segs, 0.5);
		double right = WallGeom.raycast(p, d.turn(0, -1), 200, segs, 0.5);
		double need = own.speed * 0.4 + 3;
		int turn = 0;
		if (ahead < need) {
			turn = left > right ? -1 : 1;
		} else if (rnd.nextDouble() < 0.02 && ahead > need * 2 && Math.max(left, right) > 30) {
			turn = left > right ? -1 : 1;
		}
		if (turn != 0) {
			client.turnKey(turn);
			nextTurnT = t + 0.25;
		}
	}
}

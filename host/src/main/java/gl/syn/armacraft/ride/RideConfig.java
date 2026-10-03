package gl.syn.armacraft.ride;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import gl.syn.armacraft.config.ConfigDefaults;
import gl.syn.armacraft.ride.core.ArmaSettings;
import gl.syn.armacraft.ride.core.CycleParams;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * config/armacraft-ride.json.
 *
 * <p>Physics: {@link #preset} picks the Armagetron settings ({@code "rcl"}, the default: the physics lines of RCL's
 * rcl-sumobar.cfg over Armagetron defaults; {@code "armagetron"}: stock 0.2.9.3.0; {@code "file:<path>"}: any
 * Armagetron .cfg), {@link #overrides} sets single Armagetron settings by name in Armagetron units
 * ({@code {"CYCLE_RUBBER": 10}}), and {@link #blocksPerUnit} converts lengths to blocks, so time-based behaviour is
 * exactly Armagetron's. The server's resolved settings travel to the client in the ride start payload.
 *
 * <p>Keys ({@link Keys}) are read from the client's own copy of this file.
 */
public final class RideConfig {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft/ride");
	static final Gson GSON = new GsonBuilder()
		.setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
		.setPrettyPrinting()
		.create();

	/** Built-in presets (also accepted: "file:&lt;path to an Armagetron .cfg&gt;", and "resolved" as sent by a server). */
	public static final java.util.List<String> PRESETS = java.util.List.of("rcl", "sumobar", "fortress", "armagetron");

	/**
	 * "rcl" (default) / "sumobar" (the same: RCL's rcl-sumobar.cfg physics), "fortress" (RCL fortress pickup),
	 * "armagetron" (stock 0.2.9.3.0), "file:&lt;path to an Armagetron .cfg&gt;", or "resolved" (as sent by a server).
	 */
	public String preset = "rcl";
	/** Armagetron settings by console name, Armagetron units, applied over the preset. */
	public Map<String, Double> overrides = new LinkedHashMap<>();
	/** Blocks per Armagetron length unit. 0.6 makes CYCLE_SPEED 30 = 18 blocks/s, which chunk loading keeps up with. */
	public double blocksPerUnit = 0.6;
	/**
	 * Visual size only: blocks per Armagetron unit for the bike and trail models. 1.0 with trail_height 1 gives
	 * Armagetron's cycle-to-wall proportions; physics (speed, rubber, distances) keep using blocks_per_unit.
	 */
	public double visualBlocksPerUnit = 1.0;
	/** Minecraft: hard speed cap as a ratio of CYCLE_SPEED (chunk loading). 0 = only CYCLE_SPEED_MAX. */
	public double speedCap = 2.0;
	/**
	 * NOT Armagetron: rubber budget multiplier against Minecraft terrain walls (2+ blocks, low headroom, lava).
	 * Trails always use the preset's exact rubber.
	 */
	public double terrainRubberFactor = 4.0;
	/** NOT Armagetron: warn (HUD flash + sound) when a wall is less than this many seconds ahead. 0 = off. */
	public double wallWarningSeconds = 0.0;
	/** Minecraft: trail length cap in blocks when WALLS_LENGTH is infinite (-1). */
	public double trailCapBlocks = 300.0;

	/** NOT Armagetron: boost (hold V), the brake mirrored. Armagetron units like CYCLE_BRAKE. */
	public static final class Boost {
		public boolean enabled = true;
		/** Acceleration while boosting (units/s^2; CYCLE_BRAKE is 30). */
		public double accel = 20.0;
		public double deplete = 1.0;
		public double refill = 0.1;
		/** Speed cap while boosting, ratio of CYCLE_SPEED. */
		public double speedMax = 1.5;
	}

	public Boost boost = new Boost();

	/**
	 * Cycle-mode keys, by Minecraft key name (key.keyboard.*). Each press of a turn key is one 90 degree turn. A list
	 * left out (null) comes from the imported Armagetron profile if it binds that action, else from
	 * {@link #builtinKeys()}; a list given here always wins.
	 */
	public static final class Keys {
		public List<String> turnLeft;
		public List<String> turnRight;
		/** Armagetron CYCLE_BRAKE: held. */
		public List<String> brake;
		/** Armagetron CYCLE_BRAKE_TOGGLE: each press flips the brake. */
		public List<String> brakeToggle;
		/** NOT Armagetron: boost (held). */
		public List<String> accelerate;
		/** Armagetron GLANCE_LEFT / GLANCE_RIGHT / GLANCE_BACK (held). */
		public List<String> glanceLeft;
		public List<String> glanceRight;
		public List<String> glanceBack;
		/** Leave cycle mode (back to vanilla Minecraft). */
		public List<String> exit;
	}

	/** Built-in binds (the user's Armagetron profile, plus brake hold on Down, boost on Left Shift, exit on Backspace). */
	public static Keys builtinKeys() {
		Keys k = new Keys();
		k.turnLeft = keys("d", "e", "f", "s", "w", "left");
		k.turnRight = keys("i", "j", "k", "l", "o", "right");
		k.brake = keys("down");
		k.brakeToggle = keys("space");
		k.accelerate = keys("left.shift");
		k.glanceLeft = keys("a", "q");
		k.glanceRight = keys("p", "semicolon");
		k.glanceBack = keys("v");
		k.exit = keys("backspace");
		return k;
	}

	private static List<String> keys(String... names) {
		List<String> out = new ArrayList<>();
		for (String n : names) {
			out.add("key.keyboard." + n);
		}
		return out;
	}

	public Keys keys = new Keys();

	/**
	 * Armagetron profile to import binds, colour and name from (read-only): "auto" (the usual install locations),
	 * "off", or a path. Settings in this file win over the import.
	 */
	public String armagetronProfile = "auto";
	/** Rider colour 0..1 rgb; null = imported COLOR_*_1, else derived from the player's UUID. */
	public double[] riderColor;
	/** Rider name for trails and crash messages; null = imported PLAYER_1, else the Minecraft name. */
	public String riderName;
	/**
	 * Turn at the exact time of the key press (the tick is split there) and draw the bike at frame time, like
	 * Armagetron's client; false = the old behaviour (turns on the next 20 Hz tick, drawing one tick behind).
	 */
	public boolean exactTurnTiming = true;
	/** Cycle mode: redeploy this many seconds after a crash (0 = only on a turn key). */
	public double cycleRedeploySeconds = 3.0;
	/** Cycle mode: a turn key redeploys only after this many seconds of death cam (no instant re-crash). */
	public double cycleRedeployMinSeconds = 0.6;

	// ---- Minecraft side (blocks, seconds)
	/** Trails count as walls for everyone (own trail included, minus the two freshest segments). */
	public boolean lethalTrails = true;
	/** Visual and collision height of a trail above the ground it was laid on (blocks). */
	public double trailHeight = 1.0;
	/** step_height attribute while riding. */
	public double stepHeight = 1.0;
	// frost walker, fall damage, crash effect and mob hits are server features: config/armacraft-ride-server.json
	/**
	 * &gt; 0: a deploy starts on the centre line of its block (a sideways snap of under half a block). Nothing moves
	 * the bike sideways after that: a trail between two turns is one straight axis-aligned line. (Earlier builds eased
	 * the bike towards the block centre while driving, which bent the trail; the number no longer is a speed.) 0 = off.
	 */
	public double laneCentering = 3.0;
	/**
	 * Width of the bike's terrain collision box while riding (blocks; a standing player is 0.6). Narrow, so a bike on
	 * any lane passes a 1-wide gap unless it is within half this of the gap's edge. Trails ignore it: for them the
	 * bike is a point.
	 */
	public double bikeWidth = 0.2;
	/** Display-entity stand-in bike and trail for clients without the mod. */
	public boolean vanillaFallback = true;
	/** Riders and trails are synced to modded clients within this horizontal range (blocks). */
	public double syncRange = 160.0;

	private transient ArmaSettings resolved;

	/** The Armagetron settings in effect: preset, then overrides. */
	public ArmaSettings settings() {
		if (resolved == null) {
			ArmaSettings s = presetSettings(preset);
			if (overrides != null) {
				for (Map.Entry<String, Double> e : overrides.entrySet()) {
					if (e.getValue() != null && !s.set(e.getKey(), e.getValue())) {
						LOG.warn("armacraft-ride.json: unknown or unsupported Armagetron setting {}", e.getKey());
					}
				}
			}
			resolved = s;
		}
		return resolved;
	}

	static ArmaSettings presetSettings(String preset) {
		ArmaSettings s = ArmaSettings.defaults();
		String p = preset == null ? "rcl" : preset.strip();
		if (p.equalsIgnoreCase("armagetron") || p.equalsIgnoreCase("resolved")) {
			return s;
		}
		if (p.regionMatches(true, 0, "file:", 0, 5)) {
			Path f = Path.of(p.substring(5));
			try {
				return s.apply(Files.readString(f, StandardCharsets.UTF_8));
			} catch (IOException e) {
				LOG.warn("ride preset {}: cannot read {} ({}); using Armagetron defaults", p, f, e.getMessage());
				return s;
			}
		}
		String file = "rcl.cfg";
		if (p.equalsIgnoreCase("fortress")) {
			file = "fortress.cfg";
		} else if (!p.equalsIgnoreCase("rcl") && !p.equalsIgnoreCase("sumobar")) {
			LOG.warn("unknown ride preset '{}', using rcl", p);
		}
		try (InputStream in = RideConfig.class.getResourceAsStream("/data/armacraft/ride_presets/" + file)) {
			if (in != null) {
				s.apply(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
		} catch (IOException e) {
			LOG.warn("cannot read the bundled rcl preset: {}", e.getMessage());
		}
		return s;
	}

	public CycleParams params() {
		CycleParams p = settings().params(blocksPerUnit, speedCap);
		p.terrainRubberFactor = terrainRubberFactor > 0 ? terrainRubberFactor : 1.0;
		if (boost != null && boost.enabled) {
			p.boostAccel = boost.accel * blocksPerUnit;
			p.boostDeplete = boost.deplete;
			p.boostRefill = boost.refill;
			p.boostSpeedMax = boost.speedMax;
		}
		return p;
	}

	/** Trail length in blocks: WALLS_LENGTH * scale, or {@link #trailCapBlocks} for Armagetron's "-1 = infinite". */
	public double trailLengthBlocks() {
		double len = settings().get("WALLS_LENGTH");
		return len > 0 ? len * blocksPerUnit : trailCapBlocks;
	}

	/** CYCLE_RUBBER_WALL_SHRINK: used rubber shortens the trail (gCycle::ThisWallsLength, gCycle.cpp:1730-1741). */
	public double rubberWallShrink() {
		return settings().get("CYCLE_RUBBER_WALL_SHRINK");
	}

	public double wallsStayUp() {
		return settings().get("CYCLE_WALLS_STAY_UP_DELAY");
	}

	/** DOUBLEBIND_TIME (ui/uInput.cpp:281-295): a second press for the same action from another key within it is ignored; &lt;= 0 off. */
	public double doublebindTime() {
		return settings().get("DOUBLEBIND_TIME");
	}

	public void sanitize() {
		if (!(blocksPerUnit > 0) || !Double.isFinite(blocksPerUnit)) {
			blocksPerUnit = 0.6;
		}
		if (preset == null || preset.isBlank()) {
			preset = "rcl";
		}
		if (overrides == null) {
			overrides = new LinkedHashMap<>();
		}
		if (boost == null) {
			boost = new Boost();
		}
		if (keys == null || isPreviousDefaultKeys(keys)) {
			keys = new Keys(); // never customised: follow the profile import and the built-ins
		}
		if (armagetronProfile == null || armagetronProfile.isBlank()) {
			armagetronProfile = "auto";
		}
		if (riderColor != null && riderColor.length != 3) {
			riderColor = null;
		}
		if (!(cycleRedeploySeconds >= 0)) {
			cycleRedeploySeconds = 3.0;
		}
		if (!(cycleRedeployMinSeconds >= 0)) {
			cycleRedeployMinSeconds = 0.6;
		}
		if (!(terrainRubberFactor > 0)) {
			terrainRubberFactor = 4.0;
		}
		if (!(wallWarningSeconds >= 0)) {
			wallWarningSeconds = 0.0;
		}
		if (!(speedCap >= 0)) {
			speedCap = 2.0;
		}
		if (!(trailCapBlocks > 0)) {
			trailCapBlocks = 300;
		}
		if (!(visualBlocksPerUnit > 0) || visualBlocksPerUnit > 4) {
			visualBlocksPerUnit = 1.0;
		}
		if (!(trailHeight > 0)) {
			trailHeight = 1.0;
		}
		if (!(stepHeight >= 0) || stepHeight > 10) {
			stepHeight = 1.0;
		}
		if (!(laneCentering >= 0)) {
			laneCentering = 3.0;
		}
		if (!(bikeWidth >= 0.05) || bikeWidth > 0.6) {
			bikeWidth = 0.2;
		}
		if (!(syncRange > 0)) {
			syncRange = 160;
		}
		resolved = null;
	}

	/** The key lists earlier builds wrote as defaults; such a file was never customised. */
	private static boolean isPreviousDefaultKeys(Keys k) {
		return keys("w", "e", "f", "left").equals(k.turnLeft) && keys("j", "i", "o", "right").equals(k.turnRight)
			&& keys("space", "down").equals(k.brake) && keys("v").equals(k.accelerate) && keys("q").equals(k.glanceLeft)
			&& keys("p").equals(k.glanceRight) && k.brakeToggle == null && k.glanceBack == null && k.exit == null;
	}

	/** The profile to import, or null ("off", or nothing found). */
	public @Nullable ArmagetronProfile importProfile() {
		String p = armagetronProfile == null ? "auto" : armagetronProfile.strip();
		if (p.equalsIgnoreCase("off")) {
			return null;
		}
		List<Path> candidates = p.equalsIgnoreCase("auto") ? ArmagetronProfile.autoCandidates() : List.of(Path.of(p));
		for (Path c : candidates) {
			if (Files.isRegularFile(c)) {
				ArmagetronProfile prof = ArmagetronProfile.read(c);
				if (prof != null) {
					LOG.info("imported Armagetron profile {} ({} binds{}{})", c, prof.binds.values().stream().mapToInt(List::size).sum(),
						prof.color != null ? ", colour" : "", prof.name != null ? ", name" : "");
					return prof;
				}
			}
		}
		return null;
	}

	/** Every action's keys: this file's list if given, else the profile's, else the built-in one. */
	public Keys effectiveKeys(@Nullable ArmagetronProfile profile) {
		Keys b = builtinKeys();
		Keys k = keys == null ? new Keys() : keys;
		Keys out = new Keys();
		out.turnLeft = pick(k.turnLeft, profile, "turn_left", b.turnLeft);
		out.turnRight = pick(k.turnRight, profile, "turn_right", b.turnRight);
		out.brake = pick(k.brake, profile, "brake", b.brake);
		out.brakeToggle = pick(k.brakeToggle, profile, "brake_toggle", b.brakeToggle);
		out.accelerate = pick(k.accelerate, null, "", b.accelerate);
		out.glanceLeft = pick(k.glanceLeft, profile, "glance_left", b.glanceLeft);
		out.glanceRight = pick(k.glanceRight, profile, "glance_right", b.glanceRight);
		out.glanceBack = pick(k.glanceBack, profile, "glance_back", b.glanceBack);
		out.exit = pick(k.exit, null, "", b.exit);
		return out;
	}

	private static List<String> pick(List<String> explicit, ArmagetronProfile profile, String action, List<String> builtin) {
		if (explicit != null) {
			return explicit;
		}
		if (profile != null && profile.binds.containsKey(action)) {
			return profile.binds.get(action);
		}
		return builtin;
	}

	/** Rider colour packed 0xRRGGBB: this file, else the profile, else -1 (the server derives one). */
	public int riderRgb(@Nullable ArmagetronProfile profile) {
		double[] c = riderColor != null ? riderColor : profile != null ? profile.color : null;
		if (c == null) {
			return -1;
		}
		int r = (int) Math.round(Math.max(0, Math.min(1, c[0])) * 255);
		int g = (int) Math.round(Math.max(0, Math.min(1, c[1])) * 255);
		int b = (int) Math.round(Math.max(0, Math.min(1, c[2])) * 255);
		return (r << 16) | (g << 8) | b;
	}

	/** Rider name: this file, else the profile, else "" (the server uses the player name). */
	public String riderNameOr(@Nullable ArmagetronProfile profile) {
		if (riderName != null && !riderName.isBlank()) {
			return riderName;
		}
		return profile != null && profile.name != null ? profile.name : "";
	}

	/**
	 * Reads the file against the current defaults ({@link ConfigDefaults}: never-touched keys follow changed defaults,
	 * overrides are logged), sanitizes it and rewrites it with every key. {@code config()} is null when the file is
	 * missing or does not parse; then nothing is written, so a typo made while editing never costs the file.
	 */
	public static ConfigDefaults.Loaded<RideConfig> read(Path file) {
		ConfigDefaults.Loaded<RideConfig> l = ConfigDefaults.read(file, GSON, RideConfig.class, new RideConfig(), LOG);
		if (l.config() != null) {
			l.config().sanitize();
			l.config().save(file);
		}
		return l;
	}

	/** {@link #read}, or the defaults: written out when the file is missing, in memory only when it does not parse. */
	public static RideConfig load(Path file) {
		ConfigDefaults.Loaded<RideConfig> l = read(file);
		if (l.config() != null) {
			return l.config();
		}
		RideConfig cfg = new RideConfig();
		cfg.sanitize();
		if (!l.existed()) {
			cfg.save(file);
			ConfigDefaults.writeSnapshot(file, GSON, new RideConfig(), LOG);
		}
		return cfg;
	}

	public void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			LOG.warn("could not write {}: {}", file, e.getMessage());
		}
	}

	public String toJson() {
		return GSON.toJson(this);
	}

	/** This config with its preset resolved into explicit settings (what a server sends to its riders). */
	public String toResolvedJson() {
		RideConfig c = GSON.fromJson(GSON.toJson(this), RideConfig.class);
		c.preset = "resolved";
		c.overrides = settings().asMap();
		return GSON.toJson(c);
	}

	public static RideConfig fromJson(String json) {
		RideConfig c = GSON.fromJson(json, RideConfig.class);
		if (c == null) {
			c = new RideConfig();
		}
		c.sanitize();
		return c;
	}
}

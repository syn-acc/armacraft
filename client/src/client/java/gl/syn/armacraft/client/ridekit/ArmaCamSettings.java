package gl.syn.armacraft.client.ridekit;

import gl.syn.armacraft.client.ClientConfig;
import gl.syn.armacraft.client.core.ArmaCam04;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The rider's own Armagetron camera settings, read at startup (and on {@code /armac reload}) from the Armagetron
 * install's {@code config/settings_visual.cfg} and the player profile {@code user_*.cfg}. Precedence, lowest first:
 * eCamera.cpp compiled-in defaults, the fallback values below (this user's known 0.4.0_alpha_z5693 setup, used when
 * the files are missing), the install file, the profile, then {@code ride.arma_settings} in armacraft_client.json.
 * Player settings (START_FOV, CAMWOBBLE, ...) are read for {@code ride.arma_player} (START_FOV_1 etc.).
 */
public final class ArmaCamSettings {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft_client/armacam");

	/** Camera keys that are imported (Armagetron names; player keys without the _N suffix). */
	public static final List<String> KEYS = List.of(
		"CAMERA_CUSTOM_BACK", "CAMERA_CUSTOM_RISE", "CAMERA_CUSTOM_BACK_FROMSPEED", "CAMERA_CUSTOM_RISE_FROMSPEED", "CAMERA_CUSTOM_PITCH",
		"CAMERA_CUSTOM_ZOOM", "CAMERA_CUSTOM_TURN_SPEED", "CAMERA_CUSTOM_TURN_SPEED_180", "CAMERA_GLANCE_MODE", "CAMERA_GLANCE_ANGULAR_VELOCITY",
		"CAMERA_GLANCE_ANGULAR_VELOCITY_BONUS", "CAMERA_GLANCE_STACKING", "CAMERA_GLANCE_RETURN", "CAMERA_GLANCE_RETURN_STOP", "CAMERA_GLANCE_SNAP",
		"CAMERA_VISIBILITY_LOWER_WALL", "CAMERA_IN_TURN_SPEED", "START_CAM", "START_FOV", "CAMWOBBLE", "CAMCENTER", "SMART_GLANCE_CUSTOM", "AUTO_INCAM");
	public static final List<String> PLAYER_KEYS = List.of("START_CAM", "START_FOV", "CAMWOBBLE", "CAMCENTER", "SMART_GLANCE_CUSTOM", "AUTO_INCAM");

	/** eCamera.cpp compiled-in defaults (248, 276-283, 309, 71) and ePlayer defaults. */
	static final Map<String, String> COMPILED = Map.ofEntries(
		Map.entry("CAMERA_CUSTOM_BACK", "30"), Map.entry("CAMERA_CUSTOM_RISE", "20"), Map.entry("CAMERA_CUSTOM_BACK_FROMSPEED", "0"),
		Map.entry("CAMERA_CUSTOM_RISE_FROMSPEED", "0"), Map.entry("CAMERA_CUSTOM_PITCH", "-.7"), Map.entry("CAMERA_CUSTOM_ZOOM", ".5"),
		Map.entry("CAMERA_CUSTOM_TURN_SPEED", "40"), Map.entry("CAMERA_CUSTOM_TURN_SPEED_180", "2"), Map.entry("CAMERA_GLANCE_MODE", "2"),
		Map.entry("CAMERA_GLANCE_ANGULAR_VELOCITY", String.valueOf(4 * Math.PI)), Map.entry("CAMERA_GLANCE_ANGULAR_VELOCITY_BONUS", "12"),
		Map.entry("CAMERA_GLANCE_STACKING", "0"), Map.entry("CAMERA_GLANCE_RETURN", "1"), Map.entry("CAMERA_GLANCE_RETURN_STOP", ".99"),
		Map.entry("CAMERA_GLANCE_SNAP", "-.5"), Map.entry("CAMERA_VISIBILITY_LOWER_WALL", "1"), Map.entry("CAMERA_IN_TURN_SPEED", "40"),
		Map.entry("START_CAM", "1"), Map.entry("START_FOV", "90"), Map.entry("CAMWOBBLE", "0"), Map.entry("CAMCENTER", "0"),
		Map.entry("SMART_GLANCE_CUSTOM", "1"), Map.entry("AUTO_INCAM", "0"));

	/** This user's Armagetron Experimental 0.4.0_alpha_z5693 setup (install settings_visual.cfg + profile), used when the files are missing. */
	static final Map<String, String> FALLBACK = Map.ofEntries(
		Map.entry("CAMERA_CUSTOM_BACK", "6"), Map.entry("CAMERA_CUSTOM_RISE", "4"), Map.entry("CAMERA_CUSTOM_BACK_FROMSPEED", ".5"),
		Map.entry("CAMERA_CUSTOM_RISE_FROMSPEED", ".4"), Map.entry("CAMERA_CUSTOM_PITCH", "-.58"), Map.entry("CAMERA_CUSTOM_ZOOM", "0"),
		Map.entry("CAMERA_CUSTOM_TURN_SPEED", "4"), Map.entry("CAMERA_CUSTOM_TURN_SPEED_180", "4"), Map.entry("CAMERA_GLANCE_MODE", "0"),
		Map.entry("CAMERA_GLANCE_STACKING", "0"), Map.entry("CAMERA_GLANCE_RETURN", "1"), Map.entry("CAMERA_GLANCE_RETURN_STOP", ".99"),
		Map.entry("CAMERA_GLANCE_SNAP", "-.5"), Map.entry("CAMERA_VISIBILITY_LOWER_WALL", "1"), Map.entry("CAMERA_IN_TURN_SPEED", "40"),
		Map.entry("START_CAM", "1"), Map.entry("START_FOV", "85"), Map.entry("CAMWOBBLE", "0"), Map.entry("CAMCENTER", "1"),
		Map.entry("SMART_GLANCE_CUSTOM", "1"), Map.entry("AUTO_INCAM", "0"));

	/** Resolved values. */
	public record Resolved(ArmaCam04.Settings camera, double fov, boolean lowerWall, int startCam, boolean wobble, boolean center,
		Map<String, String> values, Map<String, String> sources) {}

	private static volatile Resolved current;
	private static Resolved fallback;

	/** The fallback values alone (no files, no overrides): tests and replays. */
	public static synchronized Resolved fallback() {
		if (fallback == null) {
			fallback = resolve(Map.of(), Map.of(), Map.of(), 1, "-", "-");
		}
		return fallback;
	}

	private ArmaCamSettings() {}

	public static Resolved current() {
		Resolved r = current;
		if (r == null) {
			synchronized (ArmaCamSettings.class) {
				if (current == null) {
					current = load(ArmaRideVisuals.cfg());
				}
				r = current;
			}
		}
		return r;
	}

	/** Re-read the files on next use (config reload). */
	public static void invalidate() {
		current = null;
	}

	/** Reads both files (when enabled and present) and logs every imported value with its source. */
	public static Resolved load(ClientConfig.Ride cfg) {
		Map<String, String> install = Map.of();
		Map<String, String> profile = Map.of();
		String installName = "-";
		String profileName = "-";
		if (cfg.armaImport) {
			Path ip = installPath(cfg);
			Path pp = profilePath(cfg);
			if (ip != null && Files.isRegularFile(ip)) {
				install = read(ip);
				installName = ip.toString();
			} else {
				LOG.info("Armagetron install settings not found ({}), using fallback values", ip);
			}
			if (pp != null && Files.isRegularFile(pp)) {
				profile = read(pp);
				profileName = pp.toString();
			} else {
				LOG.info("Armagetron profile not found ({}), using fallback values", pp);
			}
		}
		Resolved r = resolve(install, profile, cfg.armaSettings == null ? Map.of() : cfg.armaSettings, cfg.armaPlayer, installName, profileName);
		StringBuilder sb = new StringBuilder("Armagetron camera settings (player ").append(cfg.armaPlayer).append("):");
		for (String k : KEYS) {
			sb.append("\n  ").append(k).append(" = ").append(r.values().get(k)).append("  [").append(r.sources().get(k)).append(']');
		}
		LOG.info(sb.toString());
		return r;
	}

	static Path installPath(ClientConfig.Ride cfg) {
		if (cfg.armaInstall != null && !cfg.armaInstall.isBlank()) {
			return Path.of(cfg.armaInstall);
		}
		String pf = System.getenv("ProgramFiles(x86)");
		return pf == null ? null : Path.of(pf, "Armagetron Experimental", "config", "settings_visual.cfg");
	}

	static Path profilePath(ClientConfig.Ride cfg) {
		if (cfg.armaProfile != null && !cfg.armaProfile.isBlank()) {
			return Path.of(cfg.armaProfile);
		}
		String appdata = System.getenv("APPDATA");
		return appdata == null ? null : Path.of(appdata, "Armagetron Experimental", "config", "user_3_1_utf8.cfg");
	}

	/** Armagetron config lines: "KEY value ..." with any leading whitespace; '#' starts a comment; the last line for a key wins. */
	public static Map<String, String> parse(List<String> lines) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String raw : lines) {
			String line = raw;
			int hash = line.indexOf('#');
			if (hash >= 0) {
				line = line.substring(0, hash);
			}
			line = line.strip();
			if (line.isEmpty()) {
				continue;
			}
			String[] f = line.split("\\s+", 3);
			if (f.length >= 2 && f[0].matches("[A-Z0-9_]+")) {
				out.put(f[0], f[1]);
			}
		}
		return out;
	}

	private static Map<String, String> read(Path p) {
		try {
			return parse(Files.readAllLines(p, StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException e) {
			try {
				return parse(Files.readAllLines(p, StandardCharsets.ISO_8859_1));
			} catch (IOException e2) {
				LOG.warn("could not read {}: {}", p, e2.getMessage());
				return Map.of();
			}
		}
	}

	/** Pure resolution: compiled defaults < fallback < install < profile < overrides. */
	public static Resolved resolve(Map<String, String> install, Map<String, String> profile, Map<String, String> overrides, int player,
		String installName, String profileName) {
		Map<String, String> values = new LinkedHashMap<>();
		Map<String, String> sources = new LinkedHashMap<>();
		for (String k : KEYS) {
			String fileKey = PLAYER_KEYS.contains(k) ? k + "_" + player : k;
			String v = COMPILED.get(k);
			String src = "eCamera.cpp default";
			if (FALLBACK.containsKey(k)) {
				v = FALLBACK.get(k);
				src = "fallback (z5693 setup)";
			}
			if (install.containsKey(fileKey)) {
				v = install.get(fileKey);
				src = "install " + installName;
			}
			if (profile.containsKey(fileKey)) {
				v = profile.get(fileKey);
				src = "profile " + profileName;
			}
			String ov = overrides.containsKey(k) ? overrides.get(k) : overrides.get(fileKey);
			if (ov != null) {
				v = ov;
				src = "armacraft_client.json ride.arma_settings";
			}
			values.put(k, v);
			sources.put(k, src);
		}
		ArmaCam04.Settings s = new ArmaCam04.Settings();
		s.back = num(values, "CAMERA_CUSTOM_BACK", s.back);
		s.rise = num(values, "CAMERA_CUSTOM_RISE", s.rise);
		s.backFromSpeed = num(values, "CAMERA_CUSTOM_BACK_FROMSPEED", s.backFromSpeed);
		s.riseFromSpeed = num(values, "CAMERA_CUSTOM_RISE_FROMSPEED", s.riseFromSpeed);
		s.pitch = num(values, "CAMERA_CUSTOM_PITCH", s.pitch);
		s.zoom = num(values, "CAMERA_CUSTOM_ZOOM", s.zoom);
		s.turnSpeed = num(values, "CAMERA_CUSTOM_TURN_SPEED", s.turnSpeed);
		s.turnSpeed180 = num(values, "CAMERA_CUSTOM_TURN_SPEED_180", s.turnSpeed180);
		s.glanceMode = (int) num(values, "CAMERA_GLANCE_MODE", s.glanceMode);
		s.glanceAngularVelocity = num(values, "CAMERA_GLANCE_ANGULAR_VELOCITY", s.glanceAngularVelocity);
		s.glanceAngularVelocityBonus = num(values, "CAMERA_GLANCE_ANGULAR_VELOCITY_BONUS", s.glanceAngularVelocityBonus);
		s.glanceStacking = bool(values, "CAMERA_GLANCE_STACKING", s.glanceStacking);
		s.glanceReturn = bool(values, "CAMERA_GLANCE_RETURN", s.glanceReturn);
		s.glanceReturnStop = num(values, "CAMERA_GLANCE_RETURN_STOP", s.glanceReturnStop);
		s.glanceSnap = num(values, "CAMERA_GLANCE_SNAP", s.glanceSnap);
		return new Resolved(s, num(values, "START_FOV", 90), bool(values, "CAMERA_VISIBILITY_LOWER_WALL", true), (int) num(values, "START_CAM", 1),
			bool(values, "CAMWOBBLE", false), bool(values, "CAMCENTER", false), values, sources);
	}

	static double num(Map<String, String> m, String k, double def) {
		try {
			double v = Double.parseDouble(m.get(k).trim());
			return Double.isFinite(v) ? v : def;
		} catch (RuntimeException e) {
			return def;
		}
	}

	static boolean bool(Map<String, String> m, String k, boolean def) {
		String v = m.get(k);
		if (v == null) {
			return def;
		}
		v = v.trim().toLowerCase(Locale.ROOT);
		if (v.equals("true")) {
			return true;
		}
		if (v.equals("false")) {
			return false;
		}
		try {
			return Double.parseDouble(v) != 0;
		} catch (NumberFormatException e) {
			return def;
		}
	}
}

package gl.syn.armacraft.ride;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only import of an Armagetron Advanced user profile (user.cfg / user_3_1_utf8.cfg): player 1's key binds,
 * colour and name. Only those lines are looked at; everything else in the file (including credentials) is ignored
 * and never kept, logged or written anywhere.
 *
 * <p>Bind lines look like {@code KEYBOARD SCANCODE_D PLAYER_BIND CYCLE_TURN_LEFT 1} (the last field is the player,
 * 0 = global). Colour is {@code COLOR_R_1 .. COLOR_B_1} on Armagetron's 0..15 scale, the name {@code PLAYER_1}.
 */
public final class ArmagetronProfile {

	/** Armagetron action names this port understands, mapped to armacraft ride actions. */
	public static final Map<String, String> ACTIONS = Map.of(
		"CYCLE_TURN_LEFT", "turn_left",
		"CYCLE_TURN_RIGHT", "turn_right",
		"CYCLE_BRAKE", "brake",
		"CYCLE_BRAKE_TOGGLE", "brake_toggle",
		"GLANCE_LEFT", "glance_left",
		"GLANCE_RIGHT", "glance_right",
		"GLANCE_BACK", "glance_back");

	/** action (armacraft name) -> Minecraft key names (key.keyboard.*), in file order. */
	public final Map<String, List<String>> binds = new LinkedHashMap<>();
	/** 0..1 rgb, or null if the profile has no complete COLOR_*_1. */
	public double @Nullable [] color;
	public @Nullable String name;

	public static @Nullable ArmagetronProfile read(Path file) {
		try {
			return parse(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	public static ArmagetronProfile parse(String text) {
		ArmagetronProfile p = new ArmagetronProfile();
		double[] rgb = {Double.NaN, Double.NaN, Double.NaN};
		for (String raw : text.split("\\R")) {
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			String[] f = line.split("\\s+");
			if (f[0].equals("KEYBOARD") && f.length >= 5 && f[2].equals("PLAYER_BIND")) {
				String action = ACTIONS.get(f[3]);
				String key = keyName(f[1]);
				boolean player1 = f[4].equals("1");
				if (action != null && key != null && player1) {
					p.binds.computeIfAbsent(action, k -> new ArrayList<>()).add(key);
				}
			} else if (f.length == 2) {
				switch (f[0]) {
					case "COLOR_R_1" -> rgb[0] = channel(f[1]);
					case "COLOR_G_1" -> rgb[1] = channel(f[1]);
					case "COLOR_B_1" -> rgb[2] = channel(f[1]);
					case "PLAYER_1" -> p.name = unescape(f[1]);
					default -> {
					}
				}
			}
		}
		if (!Double.isNaN(rgb[0]) && !Double.isNaN(rgb[1]) && !Double.isNaN(rgb[2])) {
			p.color = rgb;
		}
		return p;
	}

	private static double channel(String v) {
		try {
			double d = Double.parseDouble(v);
			return Math.max(0, Math.min(15, d)) / 15.0;
		} catch (NumberFormatException e) {
			return Double.NaN;
		}
	}

	private static String unescape(String s) {
		return s.replace("\\ ", " ");
	}

	/**
	 * Armagetron's SDL scancode name ({@code SCANCODE_D}, {@code SCANCODE_;}, {@code SCANCODE_SPACE}, ...) to
	 * Minecraft's key name ({@code key.keyboard.d}, {@code key.keyboard.semicolon}, ...); null if unknown.
	 */
	public static @Nullable String keyName(String scancode) {
		if (!scancode.startsWith("SCANCODE_")) {
			return null;
		}
		String k = scancode.substring("SCANCODE_".length());
		if (k.length() == 1 && Character.isLetterOrDigit(k.charAt(0))) {
			return "key.keyboard." + k.toLowerCase(Locale.ROOT);
		}
		if (k.matches("F([1-9]|1[0-9]|2[0-5])")) {
			return "key.keyboard." + k.toLowerCase(Locale.ROOT);
		}
		if (k.startsWith("KEYPAD_") && k.length() == 8 && Character.isDigit(k.charAt(7))) {
			return "key.keyboard.keypad." + k.charAt(7);
		}
		return switch (k) {
			case ";" -> "key.keyboard.semicolon";
			case "'" -> "key.keyboard.apostrophe";
			case "," -> "key.keyboard.comma";
			case "." -> "key.keyboard.period";
			case "/" -> "key.keyboard.slash";
			case "-" -> "key.keyboard.minus";
			case "=" -> "key.keyboard.equal";
			case "`" -> "key.keyboard.grave.accent";
			case "[" -> "key.keyboard.left.bracket";
			case "]" -> "key.keyboard.right.bracket";
			case "BACKSLASH" -> "key.keyboard.backslash";
			case "SPACE" -> "key.keyboard.space";
			case "RETURN" -> "key.keyboard.enter";
			case "ESCAPE" -> "key.keyboard.escape";
			case "TAB" -> "key.keyboard.tab";
			case "BACKSPACE" -> "key.keyboard.backspace";
			case "UP" -> "key.keyboard.up";
			case "DOWN" -> "key.keyboard.down";
			case "LEFT" -> "key.keyboard.left";
			case "RIGHT" -> "key.keyboard.right";
			case "LSHIFT" -> "key.keyboard.left.shift";
			case "RSHIFT" -> "key.keyboard.right.shift";
			case "LCTRL" -> "key.keyboard.left.control";
			case "RCTRL" -> "key.keyboard.right.control";
			case "LALT" -> "key.keyboard.left.alt";
			case "RALT" -> "key.keyboard.right.alt";
			case "PAGEUP" -> "key.keyboard.page.up";
			case "PAGEDOWN" -> "key.keyboard.page.down";
			case "HOME" -> "key.keyboard.home";
			case "END" -> "key.keyboard.end";
			case "INSERT" -> "key.keyboard.insert";
			case "DELETE" -> "key.keyboard.delete";
			default -> null;
		};
	}

	/** Profile locations tried for {@code armagetron_profile: "auto"}, in order. */
	public static List<Path> autoCandidates() {
		List<Path> out = new ArrayList<>();
		String appdata = System.getenv("APPDATA");
		if (appdata != null) {
			out.add(Path.of(appdata, "Armagetron Experimental", "config", "user_3_1_utf8.cfg"));
			out.add(Path.of(appdata, "Armagetron Advanced", "var", "user.cfg"));
			out.add(Path.of(appdata, "Armagetron", "var", "user.cfg"));
		}
		String home = System.getProperty("user.home");
		if (home != null) {
			out.add(Path.of(home, ".armagetronad", "var", "user.cfg"));
		}
		return out;
	}
}

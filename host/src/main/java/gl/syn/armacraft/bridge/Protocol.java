package gl.syn.armacraft.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import gl.syn.armacraft.logic.WallLayout;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Lenient parser and builder for the armacraft bridge protocol v1 (newline-delimited JSON).
 *
 * <p>Rules: unknown {@code type}s become {@link Msg.Unknown}; unknown fields are ignored; missing or
 * mistyped optional fields fall back to defaults; malformed array entries are skipped rather than failing
 * the whole message. Only an unparseable line or a non-object returns null.
 */
public final class Protocol {

	public static final int VERSION = 1;

	private Protocol() {}

	public static @Nullable Msg parse(String line) {
		return parse(line, System.nanoTime());
	}

	public static @Nullable Msg parse(String line, long nowNanos) {
		if (line == null) {
			return null;
		}
		String trimmed = line.strip();
		if (trimmed.isEmpty()) {
			return null;
		}
		JsonElement el;
		try {
			el = JsonParser.parseString(trimmed);
		} catch (RuntimeException e) {
			return null;
		}
		if (!el.isJsonObject()) {
			return null;
		}
		JsonObject o = el.getAsJsonObject();
		String type = getString(o, "type", "");
		return switch (type) {
			case "hello" -> new Msg.Hello((int) getLong(o, "proto", VERSION), getString(o, "game", ""), getString(o, "version", ""), getDouble(o, "rate", 0),
				strings(o.get("features")));
			case "round" -> parseRound(o);
			case "state" -> parseState(o, nowNanos);
			case "delta" -> parseDelta(o, nowNanos);
			case "event" -> new Msg.Event(getString(o, "ev", ""), o, nowNanos);
			case "joined" -> new Msg.Joined(getString(o, "key", ""), getString(o, "name", ""));
			case "left" -> new Msg.Left(getString(o, "key", ""));
			case "error" -> new Msg.Error(getString(o, "msg", ""));
			default -> new Msg.Unknown(type);
		};
	}

	static Msg.Round parseRound(JsonObject o) {
		List<Msg.Seg> segs = new ArrayList<>();
		JsonArray arr = getArray(o, "static");
		if (arr != null) {
			for (JsonElement e : arr) {
				double[] v = numbers(e, 4);
				if (v != null) {
					segs.add(new Msg.Seg(v[0], v[1], v[2], v[3]));
				}
			}
		}
		double[] bounds = numbers(o.get("bounds"), 4);
		return new Msg.Round(getLong(o, "round", 0), getDouble(o, "t", 0), segs, bounds);
	}

	static Msg.State parseState(JsonObject o, long nowNanos) {
		List<Msg.Cycle> cycles = new ArrayList<>();
		JsonArray ca = getArray(o, "cycles");
		if (ca != null) {
			for (JsonElement e : ca) {
				if (!e.isJsonObject()) {
					continue;
				}
				JsonObject c = e.getAsJsonObject();
				if (!isNumber(c.get("id"))) {
					continue;
				}
				double[] col = numbers(c.get("color"), 3);
				if (col == null) {
					col = new double[] {1, 1, 1};
				}
				String key = getString(c, "bridge_key", null);
				if (key != null && key.isEmpty()) {
					key = null;
				}
				cycles.add(new Msg.Cycle(
					getLong(c, "id", -1),
					getString(c, "name", ""),
					col[0], col[1], col[2],
					getDouble(c, "x", 0), getDouble(c, "y", 0),
					getDouble(c, "dx", 0), getDouble(c, "dy", 1),
					getDouble(c, "speed", 0),
					getBool(c, "alive", true),
					getBool(c, "brake", false),
					getDouble(c, "rubber", 0),
					key
				));
			}
		}
		List<Msg.Wall> walls = new ArrayList<>();
		JsonArray wa = getArray(o, "walls");
		if (wa != null) {
			for (JsonElement e : wa) {
				if (!e.isJsonObject()) {
					continue;
				}
				JsonObject w = e.getAsJsonObject();
				if (!isNumber(w.get("id"))) {
					continue;
				}
				List<WallLayout.Gap> gaps = new ArrayList<>();
				JsonArray ga = getArray(w, "gaps");
				if (ga != null) {
					for (JsonElement g : ga) {
						double[] v = numbers(g, 2);
						if (v != null) {
							gaps.add(new WallLayout.Gap(v[0], v[1]));
						}
					}
				}
				walls.add(new Msg.Wall(
					getLong(w, "id", -1), getLong(w, "cycle", -1),
					getDouble(w, "x1", 0), getDouble(w, "y1", 0),
					getDouble(w, "x2", 0), getDouble(w, "y2", 0),
					gaps
				));
			}
		}
		return new Msg.State(getDouble(o, "t", 0), cycles, walls, nowNanos);
	}

	/** Same cycles/walls shape as a state; plus seq, key and removed wall ids. */
	static Msg.Delta parseDelta(JsonObject o, long nowNanos) {
		Msg.State body = parseState(o, nowNanos);
		JsonArray ra = getArray(o, "removed");
		long[] removed = new long[ra == null ? 0 : ra.size()];
		int n = 0;
		if (ra != null) {
			for (JsonElement e : ra) {
				if (isNumber(e)) {
					removed[n++] = e.getAsLong();
				}
			}
		}
		return new Msg.Delta(getLong(o, "seq", 0), getBool(o, "key", false), body.t(), body.cycles(), body.walls(),
			n == removed.length ? removed : java.util.Arrays.copyOf(removed, n), nowNanos);
	}

	static List<String> strings(@Nullable JsonElement e) {
		if (e == null || !e.isJsonArray()) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (JsonElement x : e.getAsJsonArray()) {
			if (x.isJsonPrimitive() && x.getAsJsonPrimitive().isString()) {
				out.add(x.getAsString());
			}
		}
		return List.copyOf(out);
	}

	// ---- host -> guest builders ----

	/** Host hello: asks the guest for optional features it offered in its own hello. Older guests ignore it. */
	public static JsonObject hello(List<String> features) {
		JsonObject o = typed("hello");
		o.addProperty("proto", VERSION);
		JsonArray a = new JsonArray();
		for (String f : features) {
			a.add(f);
		}
		o.add("features", a);
		return o;
	}

	public static JsonObject join(String key, String name, double[] color) {
		JsonObject o = typed("join");
		o.addProperty("key", key);
		o.addProperty("name", name);
		o.add("color", color(color));
		return o;
	}

	public static JsonObject leave(String key) {
		JsonObject o = typed("leave");
		o.addProperty("key", key);
		return o;
	}

	public static JsonObject turn(String key, String dir) {
		JsonObject o = typed("input");
		o.addProperty("key", key);
		o.addProperty("turn", dir);
		return o;
	}

	public static JsonObject brake(String key, boolean on) {
		JsonObject o = typed("input");
		o.addProperty("key", key);
		o.addProperty("brake", on);
		return o;
	}

	public static JsonObject explode(double x, double y, double r) {
		JsonObject o = typed("explode");
		o.addProperty("x", round3(x));
		o.addProperty("y", round3(y));
		o.addProperty("r", round3(r));
		return o;
	}

	public static JsonObject cmd(String line) {
		JsonObject o = typed("cmd");
		o.addProperty("line", line);
		return o;
	}

	public static JsonObject chat(String name, String text) {
		JsonObject o = typed("chat");
		o.addProperty("name", name);
		o.addProperty("text", text);
		return o;
	}

	private static JsonObject typed(String type) {
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		return o;
	}

	private static JsonArray color(double[] c) {
		JsonArray a = new JsonArray();
		for (int i = 0; i < 3; i++) {
			a.add(round3(c != null && c.length > i ? c[i] : 1.0));
		}
		return a;
	}

	private static double round3(double v) {
		return Math.round(v * 1000.0) / 1000.0;
	}

	// ---- lenient field access ----

	static boolean isNumber(@Nullable JsonElement e) {
		if (e == null || !e.isJsonPrimitive()) {
			return false;
		}
		JsonPrimitive p = e.getAsJsonPrimitive();
		if (p.isNumber()) {
			return true;
		}
		if (p.isString()) {
			try {
				Double.parseDouble(p.getAsString());
				return true;
			} catch (NumberFormatException ex) {
				return false;
			}
		}
		return false;
	}

	public static double getDouble(JsonObject o, String key, double def) {
		JsonElement e = o.get(key);
		if (!isNumber(e)) {
			return def;
		}
		try {
			double v = e.getAsDouble();
			return Double.isFinite(v) ? v : def;
		} catch (RuntimeException ex) {
			return def;
		}
	}

	public static long getLong(JsonObject o, String key, long def) {
		JsonElement e = o.get(key);
		if (!isNumber(e)) {
			return def;
		}
		try {
			double d = e.getAsDouble();
			return Double.isFinite(d) ? (long) d : def;
		} catch (RuntimeException ex) {
			return def;
		}
	}

	public static boolean getBool(JsonObject o, String key, boolean def) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive()) {
			return def;
		}
		JsonPrimitive p = e.getAsJsonPrimitive();
		if (p.isBoolean()) {
			return p.getAsBoolean();
		}
		if (p.isNumber()) {
			return p.getAsDouble() != 0;
		}
		if (p.isString()) {
			String s = p.getAsString();
			if (s.equalsIgnoreCase("true")) {
				return true;
			}
			if (s.equalsIgnoreCase("false")) {
				return false;
			}
		}
		return def;
	}

	public static String getString(JsonObject o, String key, String def) {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
			return def;
		}
		return e.getAsString();
	}

	static @Nullable JsonArray getArray(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
	}

	/** First n numbers of a JSON array, or null if it is not an array of at least n numbers. */
	static double @Nullable [] numbers(@Nullable JsonElement e, int n) {
		if (e == null || !e.isJsonArray()) {
			return null;
		}
		JsonArray a = e.getAsJsonArray();
		if (a.size() < n) {
			return null;
		}
		double[] out = new double[n];
		for (int i = 0; i < n; i++) {
			JsonElement x = a.get(i);
			if (!isNumber(x)) {
				return null;
			}
			out[i] = x.getAsDouble();
			if (!Double.isFinite(out[i])) {
				return null;
			}
		}
		return out;
	}
}

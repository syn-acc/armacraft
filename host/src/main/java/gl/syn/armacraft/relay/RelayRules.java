package gl.syn.armacraft.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import gl.syn.armacraft.bridge.Protocol;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Client-to-server rules of the relay (client/RELAY.md "Client → server"). Pure: no Minecraft state.
 *
 * <p>Only {@code join}, {@code leave}, {@code input} and {@code chat} pass. Each accepted message is rebuilt from
 * scratch with the {@link Protocol} builders, so nothing the client adds reaches the guest: {@code key} is always
 * {@code "mc:" + sender uuid}, the join and chat {@code name} is always the sender's profile name, colours are
 * clamped to 0..1, chat text is stripped of control characters and capped. {@code cmd}, {@code explode},
 * {@code hello} and anything unknown or malformed is dropped.
 */
public final class RelayRules {

	public static final Set<String> ALLOWED = Set.of("join", "leave", "input", "chat");
	/** Armagetron itself caps names at 15 printable characters; this only bounds what we forward. */
	public static final int MAX_NAME_CHARS = 32;
	public static final int MAX_CHAT_CHARS = 256;

	private RelayRules() {}

	/** Result of one client line: what to forward to the guest (in order), or why nothing was. */
	public record Inbound(String type, List<JsonObject> forward, @Nullable String dropped) {
		static Inbound drop(String type, String why) {
			return new Inbound(type, List.of(), why);
		}

		public boolean accepted() {
			return dropped == null;
		}
	}

	/**
	 * @param line         raw payload text from the client
	 * @param sender       the sending player's uuid (the only key this player may act for)
	 * @param profileName  the sending player's profile name
	 * @param defaultColor colour used when the client's colour is missing or malformed
	 */
	public static Inbound inbound(String line, UUID sender, String profileName, double[] defaultColor) {
		JsonObject o;
		try {
			JsonElement e = JsonParser.parseString(line);
			if (e == null || !e.isJsonObject()) {
				return Inbound.drop("?", "not a JSON object");
			}
			o = e.getAsJsonObject();
		} catch (RuntimeException ex) {
			return Inbound.drop("?", "malformed JSON");
		}
		JsonElement te = o.get("type");
		String type = te != null && te.isJsonPrimitive() && te.getAsJsonPrimitive().isString() ? te.getAsString() : "?";
		if (!ALLOWED.contains(type)) {
			return Inbound.drop(type, "type not allowed from a client");
		}
		String key = keyFor(sender);
		String name = cleanName(profileName);
		return switch (type) {
			case "join" -> new Inbound(type, List.of(Protocol.join(key, name, color(o.get("color"), defaultColor))), null);
			case "leave" -> new Inbound(type, List.of(Protocol.leave(key)), null);
			case "input" -> input(o, key);
			case "chat" -> chat(o, name);
			default -> Inbound.drop(type, "type not allowed from a client");
		};
	}

	public static String keyFor(UUID uuid) {
		return "mc:" + uuid;
	}

	private static Inbound input(JsonObject o, String key) {
		List<JsonObject> out = new ArrayList<>(2);
		JsonElement turn = o.get("turn");
		if (turn != null && turn.isJsonPrimitive() && turn.getAsJsonPrimitive().isString()) {
			String dir = turn.getAsString();
			if (!dir.equals("left") && !dir.equals("right")) {
				return Inbound.drop("input", "turn must be left or right");
			}
			out.add(Protocol.turn(key, dir));
		}
		JsonElement brake = o.get("brake");
		if (brake != null && brake.isJsonPrimitive() && brake.getAsJsonPrimitive().isBoolean()) {
			out.add(Protocol.brake(key, brake.getAsBoolean()));
		}
		return out.isEmpty() ? Inbound.drop("input", "no turn or brake") : new Inbound("input", List.copyOf(out), null);
	}

	private static Inbound chat(JsonObject o, String name) {
		JsonElement t = o.get("text");
		if (t == null || !t.isJsonPrimitive() || !t.getAsJsonPrimitive().isString()) {
			return Inbound.drop("chat", "no text");
		}
		String text = printable(t.getAsString(), MAX_CHAT_CHARS).strip();
		if (text.isEmpty()) {
			return Inbound.drop("chat", "empty text");
		}
		return new Inbound("chat", List.of(Protocol.chat(name, text)), null);
	}

	/** Profile name with control characters removed, capped; "player" if nothing is left. */
	public static String cleanName(String profileName) {
		String n = printable(profileName == null ? "" : profileName, MAX_NAME_CHARS).strip();
		return n.isEmpty() ? "player" : n;
	}

	/** Three finite numbers, each clamped to 0..1; otherwise the default. */
	static double[] color(@Nullable JsonElement e, double[] def) {
		if (e == null || !e.isJsonArray()) {
			return def;
		}
		JsonArray a = e.getAsJsonArray();
		if (a.size() < 3) {
			return def;
		}
		double[] out = new double[3];
		for (int i = 0; i < 3; i++) {
			JsonElement x = a.get(i);
			if (!x.isJsonPrimitive()) {
				return def;
			}
			JsonPrimitive p = x.getAsJsonPrimitive();
			if (!p.isNumber()) {
				return def;
			}
			double v = p.getAsDouble();
			if (!Double.isFinite(v)) {
				return def;
			}
			out[i] = Math.max(0, Math.min(1, v));
		}
		return out;
	}

	/** Drops ISO control characters and caps the length (by code points, never splitting a surrogate pair). */
	static String printable(String s, int maxChars) {
		StringBuilder sb = new StringBuilder(Math.min(s.length(), maxChars));
		int count = 0;
		for (int i = 0; i < s.length() && count < maxChars; ) {
			int cp = s.codePointAt(i);
			i += Character.charCount(cp);
			if (Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029) {
				continue;
			}
			sb.appendCodePoint(cp);
			count++;
		}
		return sb.toString();
	}
}

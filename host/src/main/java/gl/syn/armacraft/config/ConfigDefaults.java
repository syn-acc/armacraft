package gl.syn.armacraft.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps config files from masking new defaults.
 *
 * <p>The config files are rewritten with every key on load, so a file written by an older build holds that build's
 * defaults, and a default changed in code never shows up. Next to each config file lives a snapshot of the defaults
 * it was last written with ({@code <file>.defaults}). On load, per key:
 * <ul>
 * <li>file value == snapshot default, and the current default differs: the key was never touched, so the new default
 *     is adopted (logged);</li>
 * <li>file value != current default otherwise: an override, kept (logged, so it is visible what overrides the
 *     defaults).</li>
 * </ul>
 * A file without a snapshot (written before this existed) cannot be told apart from a customised one: its differing
 * keys are logged as overrides and kept. {@code dev\play.ps1 config-diff} prints the same comparison.
 */
public final class ConfigDefaults {

	private ConfigDefaults() {}

	public enum Kind {
		/** The file differs from the current default; the file wins. */
		OVERRIDE,
		/** The file still had the previous default; the current default is taken. */
		ADOPTED
	}

	/** One key (dotted path) whose file value differs from the current default. Values are JSON text; "unset" = absent. */
	public record Change(String key, String fileValue, String defaultValue, Kind kind) {
		@Override
		public String toString() {
			return kind == Kind.ADOPTED
				? key + " " + fileValue + " -> " + defaultValue
				: key + "=" + fileValue + " (default " + defaultValue + ")";
		}
	}

	public record Result(JsonObject merged, List<Change> changes) {
		public List<Change> of(Kind k) {
			List<Change> out = new ArrayList<>();
			for (Change c : changes) {
				if (c.kind == k) {
					out.add(c);
				}
			}
			return out;
		}
	}

	/** " (3 keys differ from the defaults: a=1, b=2, c=3)" for command feedback, or "". */
	public static String summary(List<Change> changes) {
		if (changes.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		int n = 0;
		for (Change c : changes) {
			if (n++ < 6) {
				sb.append(sb.isEmpty() ? "" : ", ").append(c.kind == Kind.ADOPTED ? c.key + " -> " + c.defaultValue + " (new default)" : c.key + "=" + c.fileValue);
			}
		}
		return " (" + changes.size() + (changes.size() == 1 ? " key differs" : " keys differ") + " from the defaults: " + sb + (n > 6 ? ", ..." : "") + ")";
	}

	public static Path snapshotFile(Path configFile) {
		return configFile.resolveSibling(configFile.getFileName() + ".defaults");
	}

	/**
	 * @param file        the config file's content
	 * @param oldDefaults the defaults the file was last written with, or null (no snapshot)
	 * @param defaults    the current defaults
	 * @return the file with never-touched keys moved to the current defaults, and every difference
	 */
	public static Result reconcile(JsonObject file, @Nullable JsonObject oldDefaults, JsonObject defaults) {
		JsonObject merged = file.deepCopy();
		Map<String, JsonElement> have = flatten(file);
		Map<String, JsonElement> old = oldDefaults == null ? Map.of() : flatten(oldDefaults);
		Map<String, JsonElement> now = flatten(defaults);
		List<Change> changes = new ArrayList<>();
		for (Map.Entry<String, JsonElement> e : have.entrySet()) {
			String k = e.getKey();
			JsonElement v = e.getValue();
			JsonElement d = now.get(k);
			if (v.equals(d)) {
				continue;
			}
			if (d != null && v.equals(old.get(k))) {
				set(merged, k, d);
				changes.add(new Change(k, v.toString(), d.toString(), Kind.ADOPTED));
			} else {
				changes.add(new Change(k, v.toString(), d == null ? "unset" : d.toString(), Kind.OVERRIDE));
			}
		}
		return new Result(merged, changes);
	}

	/** Leaf values by dotted path; nested objects are walked, arrays are leaves. Empty objects carry no keys. */
	static Map<String, JsonElement> flatten(JsonObject o) {
		Map<String, JsonElement> out = new LinkedHashMap<>();
		flatten("", o, out);
		return out;
	}

	private static void flatten(String prefix, JsonObject o, Map<String, JsonElement> out) {
		for (Map.Entry<String, JsonElement> e : o.entrySet()) {
			String k = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
			if (e.getValue().isJsonObject()) {
				flatten(k, e.getValue().getAsJsonObject(), out);
			} else {
				out.put(k, e.getValue());
			}
		}
	}

	private static void set(JsonObject root, String dotted, JsonElement value) {
		// keys never contain dots themselves except under free-form maps; walk as far as objects exist
		JsonObject o = root;
		String rest = dotted;
		while (true) {
			if (o.has(rest) && !o.get(rest).isJsonObject()) {
				o.add(rest, value);
				return;
			}
			int dot = rest.indexOf('.');
			if (dot < 0 || !o.has(rest.substring(0, dot)) || !o.get(rest.substring(0, dot)).isJsonObject()) {
				o.add(rest, value);
				return;
			}
			o = o.getAsJsonObject(rest.substring(0, dot));
			rest = rest.substring(dot + 1);
		}
	}

	/** What {@link #read} found. {@code config} is null when the file is missing or unreadable ({@code error} says why). */
	public record Loaded<T>(@Nullable T config, List<Change> changes, @Nullable String error, boolean existed) {}

	/**
	 * Reads a config file through {@link #reconcile}, logs what differs from the current defaults, and refreshes the
	 * defaults snapshot. Never writes the config file itself, and never touches anything when the file does not parse
	 * (a typo made while editing must not cost the file).
	 */
	public static <T> Loaded<T> read(Path file, Gson gson, Class<T> type, T defaults, Logger log) {
		if (!Files.isRegularFile(file)) {
			return new Loaded<>(null, List.of(), null, false);
		}
		JsonObject raw;
		try {
			JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			if (!parsed.isJsonObject()) {
				throw new JsonParseException("not a JSON object");
			}
			raw = parsed.getAsJsonObject();
		} catch (IOException | JsonParseException e) {
			String why = String.valueOf(e.getMessage());
			log.warn("could not read {} (file left as it is): {}", file, why);
			return new Loaded<>(null, List.of(), why, true);
		}
		JsonObject now = gson.toJsonTree(defaults).getAsJsonObject();
		Path snap = snapshotFile(file);
		JsonObject old = null;
		if (Files.isRegularFile(snap)) {
			try {
				old = JsonParser.parseString(Files.readString(snap, StandardCharsets.UTF_8)).getAsJsonObject();
			} catch (IOException | RuntimeException e) {
				log.warn("could not read {}: {}", snap, e.getMessage());
			}
		}
		Result r = reconcile(raw, old, now);
		String name = String.valueOf(file.getFileName());
		for (Change c : r.of(Kind.ADOPTED)) {
			log.info("{}: {} (default changed; the file still had the old default, so the new one applies)", name, c);
		}
		List<Change> overrides = r.of(Kind.OVERRIDE);
		if (!overrides.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			for (Change c : overrides) {
				sb.append(sb.isEmpty() ? "" : ", ").append(c);
			}
			log.info("{} overrides {} default{}{}: {}", name, overrides.size(), overrides.size() == 1 ? "" : "s",
				old == null ? " (no defaults snapshot yet: values written by an older build count as overrides)" : "", sb);
		}
		T cfg;
		try {
			cfg = gson.fromJson(r.merged(), type);
		} catch (JsonParseException e) {
			log.warn("could not read {} (file left as it is): {}", file, e.getMessage());
			return new Loaded<>(null, List.of(), String.valueOf(e.getMessage()), true);
		}
		writeSnapshot(file, gson, defaults, log);
		return new Loaded<>(cfg, r.changes(), null, true);
	}

	/** Records the defaults a config file is written with. */
	public static <T> void writeSnapshot(Path file, Gson gson, T defaults, Logger log) {
		Path snap = snapshotFile(file);
		String json = gson.toJson(defaults);
		try {
			if (Files.isRegularFile(snap) && Files.readString(snap, StandardCharsets.UTF_8).equals(json)) {
				return;
			}
			Files.createDirectories(snap.toAbsolutePath().getParent());
			Path tmp = snap.resolveSibling(snap.getFileName() + ".tmp");
			Files.writeString(tmp, json, StandardCharsets.UTF_8);
			Files.move(tmp, snap, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			log.warn("could not write {}: {}", snap, e.getMessage());
		}
	}
}

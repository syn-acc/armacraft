package gl.syn.armacraft.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gl.syn.armacraft.ride.RideConfig;
import gl.syn.armacraft.ride.server.RideServerConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;

/**
 * {@code dev\play.ps1 config-diff}: every key of config/armacraft-ride.json and config/armacraft-ride-server.json
 * against the defaults of this build, so it is visible at a glance which file values override (or mask) them.
 * Read-only; needs no Minecraft classes (gson and slf4j-api on the class path).
 *
 * <pre>java -cp ... gl.syn.armacraft.config.ConfigDiff &lt;config dir&gt; [--all]</pre>
 *
 * Marks: {@code *} the file overrides the current default; {@code ~} the file still holds the previous default (it
 * was never edited), so the next load adopts the current one; {@code +} a key the defaults do not have.
 * Without {@code --all} only marked keys are listed. Exit code 0 always (2 on usage errors).
 */
public final class ConfigDiff {

	private ConfigDiff() {}

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.err.println("usage: ConfigDiff <config dir> [--all]");
			System.exit(2);
		}
		Path dir = Path.of(args[0]);
		boolean all = args.length > 1 && args[1].equals("--all");
		Gson gson = new Gson().newBuilder().setFieldNamingPolicy(com.google.gson.FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();
		System.out.print(report(dir.resolve("armacraft-ride.json"), gson.toJsonTree(new RideConfig()).getAsJsonObject(), all));
		System.out.print(report(dir.resolve(RideServerConfig.FILE_NAME), gson.toJsonTree(new RideServerConfig()).getAsJsonObject(), all));
	}

	/** The comparison of one file as text. */
	public static String report(Path file, JsonObject defaults, boolean all) {
		StringBuilder sb = new StringBuilder();
		String name = String.valueOf(file.getFileName());
		if (!Files.isRegularFile(file)) {
			return sb.append(name).append(": no such file (the defaults apply)\n").toString();
		}
		JsonObject raw;
		JsonObject old = null;
		try {
			raw = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			Path snap = ConfigDefaults.snapshotFile(file);
			if (Files.isRegularFile(snap)) {
				old = JsonParser.parseString(Files.readString(snap, StandardCharsets.UTF_8)).getAsJsonObject();
			}
		} catch (Exception e) {
			return sb.append(name).append(": DOES NOT PARSE (").append(e.getMessage()).append("); the game keeps its running values\n").toString();
		}
		Map<String, JsonElement> have = ConfigDefaults.flatten(raw);
		Map<String, JsonElement> now = ConfigDefaults.flatten(defaults);
		Map<String, ConfigDefaults.Change> changes = new java.util.LinkedHashMap<>();
		for (ConfigDefaults.Change c : ConfigDefaults.reconcile(raw, old, defaults).changes()) {
			changes.put(c.key(), c);
		}
		long overrides = changes.values().stream().filter(c -> c.kind() == ConfigDefaults.Kind.OVERRIDE).count();
		long adopted = changes.size() - overrides;
		sb.append(name).append(": ").append(overrides).append(" of ").append(have.size()).append(" keys override the current defaults");
		if (adopted > 0) {
			sb.append(", ").append(adopted).append(" hold an old default (adopted at the next load)");
		}
		if (old == null) {
			sb.append(" [no defaults snapshot yet: values written by an older build count as overrides]");
		}
		sb.append('\n');
		TreeSet<String> missing = new TreeSet<>(now.keySet());
		missing.removeAll(have.keySet());
		int width = 8;
		for (String k : have.keySet()) {
			width = Math.max(width, k.length());
		}
		for (Map.Entry<String, JsonElement> e : have.entrySet()) {
			ConfigDefaults.Change c = changes.get(e.getKey());
			if (c == null && !all) {
				continue;
			}
			String mark = c == null ? " " : c.kind() == ConfigDefaults.Kind.ADOPTED ? "~" : now.containsKey(e.getKey()) ? "*" : "+";
			sb.append("  ").append(mark).append(' ').append(pad(e.getKey(), width)).append("  ").append(pad(e.getValue().toString(), 14));
			if (c != null) {
				sb.append("  default ").append(c.defaultValue());
			}
			sb.append('\n');
		}
		if (!missing.isEmpty()) {
			sb.append("    not in the file (defaults apply): ").append(String.join(", ", missing)).append('\n');
		}
		return sb.toString();
	}

	private static String pad(String s, int w) {
		return s.length() >= w ? s : s + " ".repeat(w - s.length());
	}
}

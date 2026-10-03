package gl.syn.armacraft.client.ride;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * "Which build am I looking at": on joining a world, on entering cycle mode, on {@code /arma whatsnew} and after a
 * dev hot-push, chat shows
 *
 * <pre>
 * [armacraft] build 2026-10-02 20:41:07 (middle dot) client 0.1.7
 * (up to 8 lines of config/armacraft-whatsnew.txt)
 * </pre>
 *
 * The build time comes from {@code armacraft-build.properties}, written by gradle whenever the mod's sources change
 * (build.gradle, armaBuildStamp). It is read again on every print, so a hot-pushed build shows its own time without a
 * restart. The notes file is plain text, maintained by hand; blank lines and lines starting with # are skipped.
 */
public final class BuildStamp {

	public static final String NOTES_FILE = "armacraft-whatsnew.txt";
	public static final int MAX_NOTES = 8;
	private static final int MAX_LINE = 200;
	// by code point: the source stays plain ASCII whatever an editor or shell does to its encoding
	private static final char MIDDLE_DOT = (char) 0xB7;
	private static final char BOM = (char) 0xFEFF;

	private static boolean pending;

	private BuildStamp() {}

	/** The build time baked into this build, or "dev" when the stamp resource is missing (an older build script). */
	public static String build() {
		try (InputStream in = BuildStamp.class.getResourceAsStream("/armacraft-build.properties")) {
			if (in != null) {
				Properties p = new Properties();
				p.load(in);
				String b = p.getProperty("build", "").strip();
				if (!b.isEmpty()) {
					return b;
				}
			}
		} catch (IOException | RuntimeException e) {
			// fall through
		}
		return "dev";
	}

	/** Version of the optional armacraft_client jar (real Armagetron visuals), or "none". */
	public static String clientVersion() {
		return FabricLoader.getInstance().getModContainer("armacraft_client")
			.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("none");
	}

	public static String header(String suffix) {
		return "[armacraft] build " + build() + " " + MIDDLE_DOT + " client " + clientVersion() + (suffix == null || suffix.isEmpty() ? "" : " " + suffix);
	}

	/** Up to {@link #MAX_NOTES} lines of {@code <gameDir>/config/armacraft-whatsnew.txt}; empty when there is no file. */
	public static List<String> notes(Path configDir) {
		return notesFrom(configDir.resolve(NOTES_FILE));
	}

	static List<String> notesFrom(Path file) {
		List<String> out = new ArrayList<>();
		if (!Files.isRegularFile(file)) {
			return out;
		}
		try {
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				// PowerShell's Set-Content -Encoding UTF8 writes a byte order mark
				String s = (!line.isEmpty() && line.charAt(0) == BOM ? line.substring(1) : line).strip();
				if (s.isEmpty() || s.startsWith("#")) {
					continue;
				}
				out.add(s.length() > MAX_LINE ? s.substring(0, MAX_LINE) : s);
				if (out.size() >= MAX_NOTES) {
					break;
				}
			}
		} catch (IOException | RuntimeException e) {
			// unreadable notes are no reason to bother the player
		}
		return out;
	}

	/**
	 * Print now (client thread). One header line only, so it never covers a shot; a {@code null} suffix
	 * ({@code /arma whatsnew}) also prints the notes.
	 */
	public static void print(Minecraft mc, String suffix) {
		if (mc.player == null) {
			return;
		}
		mc.player.sendSystemMessage(Component.literal(header(suffix == null ? "" : suffix)));
		if (suffix != null) {
			return;
		}
		for (String n : notes(FabricLoader.getInstance().getConfigDir())) {
			mc.player.sendSystemMessage(Component.literal("  " + n));
		}
	}

	/** World joined: print once the player exists and the loading screen is gone (so the lines are seen, not scrolled past). */
	public static void onJoin() {
		pending = true;
	}

	public static void tick(Minecraft mc) {
		if (pending && mc.player != null && mc.gui.screen() == null) {
			pending = false;
			print(mc, "");
		}
	}

	/**
	 * Called by the dev hot-push agent (dev/tools/HotSwap.java, by reflection, from the attach thread) after it
	 * redefined classes in this JVM: say so in chat, with the new build time.
	 */
	public static void onHotPush(String what) {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null) {
			mc.execute(() -> print(mc, "(hot-pushed: " + what + ")"));
		}
	}
}

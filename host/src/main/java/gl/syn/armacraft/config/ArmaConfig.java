package gl.syn.armacraft.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** config/armacraft.json. Field names are snake_case on disk. Missing fields keep their defaults. */
public final class ArmaConfig {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft/config");
	static final Gson GSON = new GsonBuilder()
		.setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
		.setPrettyPrinting()
		.create();

	public static final class Anchor {
		public String dimension = "minecraft:overworld";
		public double x = 0.5;
		public double y = 100.0;
		public double z = 0.5;
	}

	public static final class Camera {
		/** Blocks behind the cycle. */
		public double back = 8.0;
		/** Blocks above the arena floor. Keep well above wall_height or your own trail fills the view. */
		public double up = 7.0;
		/** Degrees, positive looks down. */
		public double pitch = 30.0;
		/** Position/rotation interpolation of the chase cam (ticks); higher = softer swing on turns. */
		public int interpTicks = 5;
	}

	public String host = "127.0.0.1";
	public int port = 4535;
	/** Start the bridge (and keep retrying) when the server starts. */
	public boolean autoConnect = true;
	public Anchor anchor = new Anchor();
	/** Blocks per Armagetron grid unit. */
	public double scale = 1.0;
	public double wallHeight = 3.0;
	public double wallThickness = 0.2;
	/** "glass" (stained glass) or "concrete" for player walls. */
	public String wallMaterial = "glass";
	/** Longest single wall display entity, in blocks. */
	public double maxPieceBlocks = 32.0;
	/** Client-side interpolation (ticks) for cycle movement and wall growth. */
	public int interpTicks = 3;
	/** Display entity view range multiplier (x64 blocks). */
	public float viewRange = 6.0f;
	/** Max seconds a snapshot is extrapolated forward to smooth 30 Hz snapshots onto 20 Hz ticks. */
	public double maxExtrapolate = 0.05;
	public boolean nameTags = true;
	public Camera camera = new Camera();
	/** Ticks spent on the death cam before switching to the arena overview. */
	public int deathCamTicks = 40;
	/** Forward primed-TNT explosions inside the arena to the guest as "explode". */
	public boolean forwardTnt = true;
	/** Forward chat from joined players to the guest. */
	public boolean forwardChat = true;

	public static ArmaConfig load(Path file) {
		ArmaConfig cfg = null;
		if (Files.isRegularFile(file)) {
			try {
				cfg = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ArmaConfig.class);
			} catch (IOException | JsonParseException e) {
				LOG.warn("could not read {}, using defaults: {}", file, e.getMessage());
			}
		}
		if (cfg == null) {
			cfg = new ArmaConfig();
		}
		cfg.sanitize();
		cfg.save(file);
		return cfg;
	}

	public void sanitize() {
		if (anchor == null) {
			anchor = new Anchor();
		}
		if (camera == null) {
			camera = new Camera();
		}
		if (host == null || host.isBlank()) {
			host = "127.0.0.1";
		}
		if (port <= 0 || port > 65535) {
			port = 4535;
		}
		if (!(scale > 0) || !Double.isFinite(scale)) {
			scale = 1.0;
		}
		if (!(wallHeight > 0)) {
			wallHeight = 3.0;
		}
		if (!(wallThickness > 0)) {
			wallThickness = 0.2;
		}
		if (!(maxPieceBlocks >= 1)) {
			maxPieceBlocks = 32.0;
		}
		if (wallMaterial == null) {
			wallMaterial = "glass";
		}
		interpTicks = Math.max(0, Math.min(59, interpTicks));
		camera.interpTicks = Math.max(1, Math.min(59, camera.interpTicks));
		if (!(viewRange > 0)) {
			viewRange = 6.0f;
		}
		if (!(maxExtrapolate >= 0)) {
			maxExtrapolate = 0.05;
		}
		if (anchor.dimension == null || anchor.dimension.isBlank()) {
			anchor.dimension = "minecraft:overworld";
		}
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
}

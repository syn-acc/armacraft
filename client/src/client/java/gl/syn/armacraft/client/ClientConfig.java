package gl.syn.armacraft.client;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import gl.syn.armacraft.client.core.ArenaMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * config/armacraft_client.json (snake_case on disk; missing fields keep defaults). Armagetron-derived values
 * name their source setting; everything else is a Minecraft-side choice and says so.
 */
public final class ClientConfig {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft_client/config");
	static final Gson GSON = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).setPrettyPrinting().create();

	public static final class Anchor {
		public String dimension = "minecraft:overworld";
		/** Same defaults as host/config ArmaConfig.Anchor. */
		public double x = 0.5;
		public double y = 100.0;
		public double z = 0.5;
	}

	public static final class Direct {
		/** Fall back to a direct TCP connection to a guest when the server does not offer the relay. */
		public boolean enabled = true;
		public String host = "127.0.0.1";
		public int port = 4535;
		/** Connect as soon as a world is joined (otherwise use /armac connect). */
		public boolean autoConnect = true;
	}

	public static final class Camera {
		/** "custom" (Armagetron START_CAM default) or "in". */
		public String mode = "custom";
		/** START_FOV (horizontal, with rViewport::Perspective's widescreen correction). */
		public double fov = 90;
		public double customBack = 30;       // CAMERA_CUSTOM_BACK
		public double customRise = 20;       // CAMERA_CUSTOM_RISE
		public double customPitch = -0.7;    // CAMERA_CUSTOM_PITCH
		public double customZoom = 0.5;      // CAMERA_CUSTOM_ZOOM
		public double customTurnSpeed = 40;  // CAMERA_CUSTOM_TURN_SPEED
		public double customTurnSpeed180 = 2;// CAMERA_CUSTOM_TURN_SPEED_180
		public double inTurnSpeed = 40;      // CAMERA_IN_TURN_SPEED
		/** Seconds the camera stays on your explosion before switching to the free spectator camera. */
		public double deathHold = 2.0;
		/** Free camera speed, blocks per second (Minecraft-side choice). */
		public double freeSpeed = 24.0;
	}

	public static final class Render {
		/** Blocks per Armagetron unit when the server does not advertise one (host default 1.0). */
		public double scale = 1.0;
		/**
		 * Rim wall height in Armagetron units. Armagetron uses 10000 (gWallRim default h); a finite height lets
		 * the surrounding Minecraft world show over it. Walls blocking the camera are lowered either way.
		 */
		public double rimHeight = 50;
		/** Opacity of the dim body of trail walls; the bright dir_wall stripes stay opaque (user request: translucent walls). */
		public double wallBodyAlpha = 0.55;
		/**
		 * Brightness of the dim body of dir_wall relative to its stripes. Armagetron's texture has 102/255 = 0.4;
		 * translucent walls over daylit terrain read murky at 0.4, so the default is brighter. 0.4 = exact.
		 */
		public double wallBodyBrightness = 0.75;
		/** FLOOR_RED/GREEN/BLUE = .15/.3/.15, additive like rFLOOR_TWOTEXTURE; gain makes it read on daylit terrain. */
		public double floorGain = 2.0;
		public boolean floor = true;
		/** Height of the floor overlay above the anchor, blocks (avoids z-fighting with the terrain top). */
		public double floorLift = 0.02;
		/** Max seconds of dead reckoning past the newest data. */
		public double maxExtrapolate = 0.25;
		/** CYCLE_WALLS_STAY_UP_DELAY of the guest (gGame.cpp default 2.0). */
		public double wallsStayUpDelay = 2.0;
		/** CYCLE_DELAY of the guest (settings.cfg .1): local turn prediction waits as long as the guest will. */
		public double cycleDelay = 0.1;
		/** Predict own turns locally the moment the key goes down. */
		public boolean predictTurns = true;
		/** Hide block_display entities inside the arena while this renderer is active. */
		public boolean hideFallbackDisplays = true;
		/** Line width (pixels) for wall top edges and explosion rays (GL default line width is 1). */
		public double lineWidth = 2.0;
		/**
		 * Iris shaderpack switch (restart to change): "off" leaves the armacraft pipelines unassigned (broken geometry,
		 * for comparison only); "lit" also sends the walls through gbuffers_entities (packs that draw nothing in
		 * spidereyes, e.g. Alpha Piscium); anything else uses the per-pipeline programs below.
		 */
		public String irisProgram = "emissive";
		/**
		 * Pack program for the bike and rim walls (opaque): "entities" (gbuffers_entities, pack-lit, full-bright
		 * lightmap). Not "emissive": Iris blends gbuffers_spidereyes additively, which makes the bike see-through.
		 */
		public String irisModelProgram = "entities";
		/**
		 * Pack program for trail and arena walls: "emissive" (gbuffers_spidereyes: additive, glows, feeds bloom) or
		 * "entities_translucent" (alpha blended and pack-lit: solid-looking walls in daylight, no glow).
		 */
		public String irisWallProgram = "emissive";
		/** Pack program for the light pool and the floor grid (additive). */
		public String irisLightProgram = "emissive";
		/**
		 * Draw walls and light pools after translucent terrain (water, ice, glass) instead of before it, so a trail
		 * over a lake is as visible as on land. Matters with improved transparency off and under shaderpacks.
		 */
		public boolean wallsAfterWater = true;
		/**
		 * Ride trail at terrain steps: blocks of run over which the wall top ramps up to the higher level, per block
		 * of rise, on the low side of the step (1 = 45 degrees). The wall then covers the block face it climbs, so
		 * the two levels stay one surface. 0 = plain vertical joins: with a wall as tall as the step the two levels
		 * only touch along a line and read as triangular spikes from the chase camera.
		 */
		public double trailStepRamp = 1.0;
	}

	public static final class Sound {
		public boolean enabled = true;
		/** Armagetron mixes at most 0.4 per channel; Minecraft volume = arma volume * gain. */
		public double gain = 6.0;
		public double soundSpeed = 30.0;     // CYCLE_SOUND_SPEED (settings.cfg)
	}

	public static final class Hud {
		public boolean enabled = true;
		public boolean hideVanilla = true;
		/** CYCLE_RUBBER of the guest (settings.cfg 1.0): the rubber gauge maximum. */
		public double rubberMax = 1.0;
	}

	/**
	 * Ride mode (host's armacraft ride, through the {@code armacraft:ride_visuals} entrypoint). Lengths are Armagetron
	 * units, multiplied by the ride's blocks-per-unit at run time.
	 */
	public static final class Ride {
		/** Use the Armagetron bike / camera / HUD / engine / trail for ride mode (false = armacraft's placeholder). Restart to apply. */
		public boolean bike = true, camera = true, hud = true, engine = true, trail = true;
		/**
		 * The camera is Armagetron 0.4's custom camera with the rider's own Armagetron settings: read from the install's
		 * config/settings_visual.cfg and the profile (user_3_1_utf8.cfg) when {@code armaImport} is on; profile values
		 * override the install, {@code armaSettings} (Armagetron names, e.g. "CAMERA_CUSTOM_TURN_SPEED": "8",
		 * "START_FOV": "90") overrides both. Empty paths mean the standard Windows locations. Reload: /armac reload.
		 */
		public boolean armaImport = true;
		public int armaPlayer = 1;
		public String armaInstall = "";
		public String armaProfile = "";
		public java.util.Map<String, String> armaSettings = new java.util.LinkedHashMap<>();
		/** Minecraft-side: stiffness of the critically damped camera height spring (1/s). */
		public double heightFollow = 3;
		/**
		 * Minecraft-side: "lift" (default) rises over the ground between bike and camera on springs; "follow" is the
		 * 0.1.0 behaviour (exponential height follow, pull-in only), kept for comparison.
		 */
		public String heightMode = "lift";
		/**
		 * Minecraft-side: move the camera in when something hides the bike for a quarter second. "tall_only" (default):
		 * only obstacles reaching 2.25 blocks above the bike's ground (trees, walls, overhangs); "on": anything above
		 * the bike's top; "off": never (the camera still rises over terrain). 1-block steps never trigger it.
		 */
		public String pullIn = "tall_only";
		/** Minecraft-side: closest the pulled-in camera gets to the bike, blocks (it would rather sit inside a block than closer). */
		public double pullInMin = 4;
		/** Minecraft-side: spring stiffness of the camera easing back out after an obstacle pulled it in (1/s). */
		public double pullOutRate = 4;
		/** NOT Armagetron: the bike lights the ground around it in its colour (additive pool, stronger at night). */
		public boolean light = true;
		public double lightIntensity = 0.8;
		/** Light pool radius, blocks. */
		public double lightRadius = 4.5;
		/** A fainter glow along the newest few blocks of the trail. */
		public boolean trailLight = true;
		/** Minecraft-side: mouse look orbits the camera around the bike (yaw and pitch offsets from the heading). */
		public boolean mouseOrbit = true;

		/** An independent copy (test instrumentation). */
		public Ride copy() {
			return GSON.fromJson(GSON.toJson(this), Ride.class);
		}
	}

	/** "auto" (relay if the server offers it, else direct), "relay", "direct" or "off". */
	public String mode = "auto";
	public Anchor anchor = new Anchor();
	public Direct direct = new Direct();
	public Camera camera = new Camera();
	public Render render = new Render();
	public Sound sound = new Sound();
	public Hud hud = new Hud();
	public Ride ride = new Ride();
	/** Player name sent with join; empty = Minecraft name. */
	public String name = "";
	/** Cycle colour sent with join (0..1); null = derived from the player uuid. */
	public double[] color = null;

	private transient Path file;

	public ArenaMapping mapping() {
		return new ArenaMapping(anchor.x, anchor.y, anchor.z, render.scale);
	}

	public static ClientConfig load(Path file) {
		ClientConfig cfg = null;
		if (Files.isRegularFile(file)) {
			try {
				cfg = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ClientConfig.class);
			} catch (IOException | JsonParseException e) {
				LOG.warn("could not read {}, using defaults: {}", file, e.getMessage());
			}
		}
		if (cfg == null) {
			cfg = new ClientConfig();
		}
		cfg.file = file;
		cfg.sanitize();
		cfg.save();
		return cfg;
	}

	public void sanitize() {
		if (anchor == null) {
			anchor = new Anchor();
		}
		if (direct == null) {
			direct = new Direct();
		}
		if (camera == null) {
			camera = new Camera();
		}
		if (render == null) {
			render = new Render();
		}
		if (sound == null) {
			sound = new Sound();
		}
		if (hud == null) {
			hud = new Hud();
		}
		if (ride == null) {
			ride = new Ride();
		}
		ride.heightFollow = clamp(ride.heightFollow, 0.5, 60);
		ride.pullOutRate = clamp(ride.pullOutRate, 0.2, 60);
		ride.pullInMin = clamp(ride.pullInMin, 1.2, 12);
		if (ride.armaSettings == null) {
			ride.armaSettings = new java.util.LinkedHashMap<>();
		}
		if (ride.armaPlayer < 1 || ride.armaPlayer > 4) {
			ride.armaPlayer = 1;
		}
		ride.lightIntensity = clamp(ride.lightIntensity, 0, 2);
		ride.lightRadius = clamp(ride.lightRadius, 1, 12);
		if (ride.pullIn == null || !(ride.pullIn.equals("on") || ride.pullIn.equals("off") || ride.pullIn.equals("tall_only"))) {
			ride.pullIn = "tall_only";
		}
		if (ride.heightMode == null || !(ride.heightMode.equals("lift") || ride.heightMode.equals("follow"))) {
			ride.heightMode = "lift";
		}
		if (mode == null || !(mode.equals("auto") || mode.equals("relay") || mode.equals("direct") || mode.equals("off"))) {
			mode = "auto";
		}
		if (direct.host == null || direct.host.isBlank()) {
			direct.host = "127.0.0.1";
		}
		if (direct.port <= 0 || direct.port > 65535) {
			direct.port = 4535;
		}
		if (!(render.scale > 0) || !Double.isFinite(render.scale)) {
			render.scale = 1.0;
		}
		if (!(render.rimHeight > 0)) {
			render.rimHeight = 50;
		}
		render.wallBodyAlpha = clamp(render.wallBodyAlpha, 0.05, 1);
		render.wallBodyBrightness = clamp(render.wallBodyBrightness, 0.05, 1);
		render.maxExtrapolate = clamp(render.maxExtrapolate, 0, 1);
		render.lineWidth = clamp(render.lineWidth, 0.5, 8);
		render.trailStepRamp = clamp(render.trailStepRamp, 0, 4);
		if (camera.mode == null || !(camera.mode.equals("custom") || camera.mode.equals("in"))) {
			camera.mode = "custom";
		}
		camera.fov = clamp(camera.fov, 30, 160);
		if (anchor.dimension == null || anchor.dimension.isBlank()) {
			anchor.dimension = "minecraft:overworld";
		}
		if (name == null) {
			name = "";
		}
		if (color != null && color.length != 3) {
			color = null;
		}
	}

	private static double clamp(double v, double lo, double hi) {
		if (!Double.isFinite(v)) {
			return lo;
		}
		return Math.max(lo, Math.min(hi, v));
	}

	public void save() {
		if (file == null) {
			return;
		}
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

package gl.syn.armacraft.ride.server;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import gl.syn.armacraft.config.ConfigDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * config/armacraft-ride-server.json: the server-side ride features (frost walker, fall damage, movement-check
 * relaxation, crash effect, mob hits). Blocks, seconds, ticks. Loaded at init and again by {@code /arma reload};
 * missing keys keep defaults and the file is rewritten with every key so it documents itself.
 */
public final class RideServerConfig {

	public static final String FILE_NAME = "armacraft-ride-server.json";

	private static final Logger LOG = LoggerFactory.getLogger("armacraft/ride-server");
	static final Gson GSON = new GsonBuilder()
		.setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
		.setPrettyPrinting()
		.create();

	// ---- frost walker (cosmetic: riders drive on water without it). Defaults: a thin lane right at the bike.
	public boolean frostWalker = true;
	/**
	 * Disk radius (blocks). 1 = the rider's own column (plus a neighbour whose centre is under a block away).
	 * Vanilla Frost Walker I = 3, II = 4; clamped 0..16 like vanilla.
	 */
	public int frostRadius = 1;
	/** Freeze a strip ahead along the heading, this many seconds of travel long (speed x seconds). */
	public double frostLookaheadSeconds = 0.1;
	/** Hard cap on the strip length (blocks). */
	public int frostLookaheadMaxBlocks = 2;
	/** Strip half-width in block rows either side of the rider's row (0 = one row, 1 = 3 wide). */
	public int frostStripHalfWidth = 0;
	/** Also search this many layers above and below the support layer for a water surface (1-block steps). */
	public int frostVerticalReach = 1;

	// ---- fall damage
	public boolean noFallDamage = true;
	/** No fall damage for this long after a DISMOUNT or CRASH stop. */
	public double fallGraceSeconds = 2.0;

	// ---- movement checks (ServerGamePacketListenerImpl), only while RideRegistry.isRiding
	public boolean relaxMovementChecks = true;
	/** Sane upper bound on bike speed (blocks/s): reported speeds are clamped to it and allowances sized by it. */
	public double maxBikeSpeed = 40.0;
	/** "moved too quickly": per-packet allowance = maxBikeSpeed/20 * this. */
	public double tooQuicklySlack = 1.25;
	/** "moved too quickly": catch-up bursts count at most this many packets (vanilla resets >5 to 1). */
	public int tooQuicklyBurstPackets = 10;
	/** "moved wrongly": tolerance = this many ticks of travel at the reported speed ... */
	public double movedWronglyTicks = 1.25;
	/** ... capped here (blocks). Kept below 1 + bike width (1.6) so a full block can never be skipped. */
	public double movedWronglyMaxBlocks = 1.5;
	/** Floating kick: vanilla's limit (80 ticks at normal gravity) times this. */
	public double floatingTicksMultiplier = 2.0;

	// ---- crash
	public boolean crashEffect = true;
	/** Explosion sound volume; 4 = vanilla TNT, audible to 64 blocks. */
	public double crashVolume = 4.0;

	// ---- mob hits
	public boolean mobHits = true;
	/** Only a rider at least this fast (blocks/s) hits mobs. */
	public double mobHitMinSpeed = 4.0;
	/** Damage per block/s of speed, capped at mobDamageMax. */
	public double mobDamagePerSpeed = 0.5;
	public double mobDamageMax = 20.0;
	/** Knockback along the heading: base + perSpeed * speed, capped. */
	public double mobKnockbackBase = 0.4;
	public double mobKnockbackPerSpeed = 0.05;
	public double mobKnockbackMax = 2.5;
	/** A mob is hit at most once per this many ticks (vanilla's i-frame window is 10). */
	public int mobHitCooldownTicks = 10;
	/** Hit box reaches this far beyond one tick of travel in front of the bike (blocks). */
	public double mobHitReach = 1.0;
	/** Hit box widening either side of the rider (blocks). */
	public double mobHitLateral = 0.2;

	// ---- rider status freshness
	/** A rider whose status has not been updated for this many ticks counts as stopped (speed 0). */
	public int statusStaleTicks = 10;

	public int fallGraceTicks() {
		return (int) Math.round(fallGraceSeconds * 20.0);
	}

	public void sanitize() {
		frostRadius = clamp(frostRadius, 0, 16);
		frostLookaheadSeconds = finiteIn(frostLookaheadSeconds, 0, 5, 0.1);
		frostLookaheadMaxBlocks = clamp(frostLookaheadMaxBlocks, 0, 64);
		frostStripHalfWidth = clamp(frostStripHalfWidth, 0, 4);
		frostVerticalReach = clamp(frostVerticalReach, 0, 2);
		fallGraceSeconds = finiteIn(fallGraceSeconds, 0, 60, 2.0);
		maxBikeSpeed = finiteIn(maxBikeSpeed, 1, 200, 40.0);
		tooQuicklySlack = finiteIn(tooQuicklySlack, 1, 4, 1.25);
		tooQuicklyBurstPackets = clamp(tooQuicklyBurstPackets, 1, 40);
		movedWronglyTicks = finiteIn(movedWronglyTicks, 0, 4, 1.25);
		movedWronglyMaxBlocks = finiteIn(movedWronglyMaxBlocks, 0.25, 1.5, 1.5);
		floatingTicksMultiplier = finiteIn(floatingTicksMultiplier, 1, 20, 2.0);
		crashVolume = finiteIn(crashVolume, 0, 16, 4.0);
		mobHitMinSpeed = finiteIn(mobHitMinSpeed, 0, 200, 4.0);
		mobDamagePerSpeed = finiteIn(mobDamagePerSpeed, 0, 100, 0.5);
		mobDamageMax = finiteIn(mobDamageMax, 0, 1000, 20.0);
		mobKnockbackBase = finiteIn(mobKnockbackBase, 0, 10, 0.4);
		mobKnockbackPerSpeed = finiteIn(mobKnockbackPerSpeed, 0, 10, 0.05);
		mobKnockbackMax = finiteIn(mobKnockbackMax, 0, 10, 2.5);
		mobHitCooldownTicks = clamp(mobHitCooldownTicks, 1, 200);
		mobHitReach = finiteIn(mobHitReach, 0, 8, 1.0);
		mobHitLateral = finiteIn(mobHitLateral, 0, 2, 0.2);
		statusStaleTicks = clamp(statusStaleTicks, 1, 200);
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static double finiteIn(double v, double lo, double hi, double fallback) {
		if (!Double.isFinite(v)) {
			return fallback;
		}
		return Math.max(lo, Math.min(hi, v));
	}

	/**
	 * Reads the file against the current defaults ({@link ConfigDefaults}: never-touched keys follow changed defaults,
	 * overrides are logged), sanitizes it and rewrites it with every key. {@code config()} is null when the file is
	 * missing or does not parse; then nothing is written.
	 */
	public static ConfigDefaults.Loaded<RideServerConfig> read(Path file) {
		ConfigDefaults.Loaded<RideServerConfig> l = ConfigDefaults.read(file, GSON, RideServerConfig.class, new RideServerConfig(), LOG);
		if (l.config() != null) {
			l.config().sanitize();
			l.config().save(file);
		}
		return l;
	}

	/** {@link #read}, or the defaults: written out when the file is missing, in memory only when it does not parse. */
	public static RideServerConfig load(Path file) {
		ConfigDefaults.Loaded<RideServerConfig> l = read(file);
		if (l.config() != null) {
			return l.config();
		}
		RideServerConfig cfg = new RideServerConfig();
		cfg.sanitize();
		if (!l.existed()) {
			cfg.save(file);
			ConfigDefaults.writeSnapshot(file, GSON, new RideServerConfig(), LOG);
		}
		return cfg;
	}

	public void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, toJson(), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			LOG.warn("could not write {}: {}", file, e.getMessage());
		}
	}

	public String toJson() {
		return GSON.toJson(this);
	}

	public static RideServerConfig fromJson(String json) {
		RideServerConfig c = GSON.fromJson(json, RideServerConfig.class);
		if (c == null) {
			c = new RideServerConfig();
		}
		c.sanitize();
		return c;
	}
}

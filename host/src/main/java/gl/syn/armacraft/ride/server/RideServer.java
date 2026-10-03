package gl.syn.armacraft.ride.server;

import gl.syn.armacraft.ride.RideRegistry;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side ride features, driven by {@link RideRegistry}: frost walker with lookahead, no fall damage while riding
 * (plus a grace period after DISMOUNT/CRASH), relaxed movement checks (via mixin), crash explosion effect, mob hits.
 * Server thread only. Time base: {@link MinecraftServer#getTickCount()}.
 *
 * <p>Assumes the ride handler calls {@link RideRegistry#update} about once per tick per rider (the ride_state payload
 * is sent every client tick). Riders whose status has not changed for {@code statusStaleTicks} count as stopped
 * for the lookahead and mob hits; the frost disk still runs.
 */
public final class RideServer implements RideRegistry.Listener {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft/ride-server");
	private static volatile @Nullable RideServer instance;

	private static final class Rider {
		ServerPlayer player;
		RideRegistry.Status status;
		long lastUpdate;

		Rider(ServerPlayer player, RideRegistry.Status status, long lastUpdate) {
			this.player = player;
			this.status = status;
			this.lastUpdate = lastUpdate;
		}
	}

	/** Swapped whole by {@link #reloadConfig} (/arma reload); every use reads the field again. */
	private volatile RideServerConfig config;
	private final java.nio.file.@Nullable Path configFile;
	private final Map<UUID, Rider> riders = new LinkedHashMap<>();
	private final FallGuard fall = new FallGuard();
	private final HitCooldown hits = new HitCooldown();
	private long frozenTotal;
	private long mobHitsTotal;
	private long crashEffects;

	public RideServer(RideServerConfig config) {
		this(config, null);
	}

	public RideServer(RideServerConfig config, java.nio.file.@Nullable Path configFile) {
		this.config = config;
		this.configFile = configFile;
	}

	/**
	 * /arma reload: re-read config/armacraft-ride-server.json. Frost walker, fall damage, movement checks, crash effect
	 * and mob hits use the new values from the next tick (nothing is cached per rider). A file that does not parse
	 * changes nothing.
	 */
	public String reloadConfig() {
		if (configFile == null) {
			return "[arma] ride-server config has no file";
		}
		var l = RideServerConfig.read(configFile);
		if (l.config() == null) {
			if (l.existed()) {
				return "[arma] " + configFile.getFileName() + " NOT reloaded, running values kept: " + l.error();
			}
			config = RideServerConfig.load(configFile); // deleted: back to defaults, written out again
			return "[arma] " + configFile.getFileName() + " was missing: defaults written";
		}
		config = l.config();
		return "[arma] reloaded " + configFile.getFileName() + gl.syn.armacraft.config.ConfigDefaults.summary(l.changes());
	}

	public static @Nullable RideServer get() {
		return instance;
	}

	static void install(RideServer server) {
		instance = server;
	}

	public RideServerConfig config() {
		return config;
	}

	// ------------------------------------------------------------------ RideRegistry.Listener

	@Override
	public void onStart(ServerPlayer player, RideRegistry.Status status) {
		track(player, status);
	}

	@Override
	public void onUpdate(ServerPlayer player, RideRegistry.Status status) {
		track(player, status);
	}

	private void track(ServerPlayer player, RideRegistry.Status status) {
		long now = now(player);
		Rider r = riders.get(player.getUUID());
		if (r == null) {
			riders.put(player.getUUID(), new Rider(player, status, now));
		} else {
			r.player = player;
			r.status = status;
			r.lastUpdate = now;
		}
	}

	@Override
	public void onStop(ServerPlayer player, RideRegistry.StopReason reason, Vec3 at) {
		riders.remove(player.getUUID());
		if (reason == RideRegistry.StopReason.DISCONNECT) {
			fall.clear(player.getUUID());
			return;
		}
		fall.startGrace(player.getUUID(), now(player), config.fallGraceTicks());
		if (reason == RideRegistry.StopReason.CRASH && config.crashEffect) {
			CrashEffect.play(player.level(), at != null ? at : player.position(), (float) config.crashVolume);
			crashEffects++;
		}
	}

	// ------------------------------------------------------------------ tick

	public void tick(MinecraftServer server) {
		long now = server.getTickCount();
		if (!riders.isEmpty()) {
			// snapshot: hurting a mob or freezing a block may run code that stops a ride
			List<Rider> snapshot = new ArrayList<>(riders.values());
			for (Rider r : snapshot) {
				tickRider(server, r, now);
			}
		}
		if (now % 100 == 0) {
			fall.prune(now);
			hits.prune(now, config.mobHitCooldownTicks);
		}
	}

	private void tickRider(MinecraftServer server, Rider r, long now) {
		ServerPlayer p = r.player;
		if (p.isRemoved()) {
			// respawned or changed entity: follow the uuid, else drop (the stop was missed)
			ServerPlayer current = server.getPlayerList().getPlayer(p.getUUID());
			if (current == null || current.isRemoved()) {
				riders.remove(p.getUUID());
				return;
			}
			r.player = p = current;
		}
		Optional<RideRegistry.Status> st = RideRegistry.status(p);
		if (st.isEmpty()) {
			riders.remove(p.getUUID());
			return;
		}
		RideRegistry.Status status = st.get();
		if (p.isSpectator()) {
			return;
		}
		Direction h = status.heading();
		int hx = h == null || h.getAxis() == Direction.Axis.Y ? 0 : h.getStepX();
		int hz = h == null || h.getAxis() == Direction.Axis.Y ? 0 : h.getStepZ();
		double speed = now - r.lastUpdate > config.statusStaleTicks ? 0 : clampSpeed(status.speedBlocksPerSecond());
		ServerLevel level = p.level();
		if (config.frostWalker) {
			frozenTotal += FrostWalker.apply(level, p, hx, hz, speed, config);
		}
		if (config.mobHits) {
			mobHitsTotal += MobHits.apply(level, p, hx, hz, speed, now, hits, config);
		}
	}

	private double clampSpeed(double speed) {
		if (!Double.isFinite(speed) || speed < 0) {
			return 0;
		}
		return Math.min(speed, config.maxBikeSpeed);
	}

	private static long now(ServerPlayer player) {
		return player.level().getServer().getTickCount();
	}

	public void reset() {
		riders.clear();
		fall.reset();
		hits.reset();
	}

	// ------------------------------------------------------------------ queries (mixins, tests)

	public boolean cancelsFallDamage(ServerPlayer player) {
		if (!config.noFallDamage) {
			return false;
		}
		boolean riding = RideRegistry.isRiding(player);
		return FallGuard.cancel(true, riding, !riding && fall.inGrace(player.getUUID(), now(player)));
	}

	public int trackedRiders() {
		return riders.size();
	}

	public long frozenTotal() {
		return frozenTotal;
	}

	public long mobHitsTotal() {
		return mobHitsTotal;
	}

	public long crashEffects() {
		return crashEffects;
	}

	// ------------------------------------------------------------------ static entry points for mixins (null-safe)

	/** Player.causeFallDamage HEAD. */
	public static boolean shouldCancelFallDamage(ServerPlayer player) {
		RideServer s = instance;
		return s != null && s.cancelsFallDamage(player);
	}

	private static @Nullable RideServer relaxingFor(ServerPlayer player) {
		RideServer s = instance;
		if (s == null || !s.config.relaxMovementChecks || player == null || !RideRegistry.isRiding(player)) {
			return null;
		}
		return s;
	}

	/** "moved too quickly": metersPerTick (vanilla 100) for this player. */
	public static float tooQuicklyMetersPerTick(ServerPlayer player, float vanilla, int rawPackets) {
		RideServer s = relaxingFor(player);
		if (s == null) {
			return vanilla;
		}
		RideServerConfig c = s.config;
		return MovementAllowance.tooQuicklyMetersPerTick(vanilla, rawPackets, c.maxBikeSpeed, c.tooQuicklySlack, c.tooQuicklyBurstPackets);
	}

	/** "moved wrongly": squared error threshold (vanilla 0.0625) for this player. */
	public static double movedWronglyThresholdSqr(ServerPlayer player, double vanilla) {
		RideServer s = relaxingFor(player);
		if (s == null) {
			return vanilla;
		}
		RideServerConfig c = s.config;
		double speed = RideRegistry.status(player).map(RideRegistry.Status::speedBlocksPerSecond).orElse(0.0);
		return MovementAllowance.movedWronglyThresholdSqr(vanilla, s.clampSpeed(speed), c.maxBikeSpeed, c.movedWronglyTicks,
			c.movedWronglyMaxBlocks);
	}

	/** Floating kick limit (vanilla getMaximumFlyingTicks) for this player. */
	public static int maxFlyingTicks(ServerPlayer player, int vanilla) {
		RideServer s = relaxingFor(player);
		return s == null ? vanilla : MovementAllowance.maxFlyingTicks(vanilla, s.config.floatingTicksMultiplier);
	}

	static void logError(String what, Throwable t) {
		LOG.error("armacraft ride-server: {} failed", what, t);
	}
}

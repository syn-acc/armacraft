package gl.syn.armacraft.ride.server;

/**
 * Relaxed {@code ServerGamePacketListenerImpl} movement thresholds for riders. Pure; applied by
 * {@code ServerGamePacketListenerImplRideMixin} only while {@code RideRegistry.isRiding(player)}.
 *
 * <p>Vanilla 26.3 {@code handlePlayerPositionChange}:
 * <ul>
 *   <li>"moved too quickly": {@code movedDist - expectedDist > metersPerTick * deltaPackets} with movedDist the
 *       squared distance from the position at the start of the server tick, metersPerTick 100 (300 fall-flying),
 *       deltaPackets = move packets since the last server tick, reset to 1 when above 5. A bike at max speed
 *       (2 blocks/tick) trips it after a 6-packet catch-up burst (10.8 blocks &gt; 10); a walking player needs 36.
 *       Result: teleport back.</li>
 *   <li>"moved wrongly": after the server replays the move with its own collision, horizontal error squared
 *       {@code > 0.0625} (0.25 blocks) teleports back. Speed-independent in vanilla, but a bike's error is: a turn
 *       inside one tick (server resolves X/Z in a different order around a corner), cobweb/berry slow-down the
 *       client bike ignores.</li>
 *   <li>Floating: {@code aboveGroundTickCount > getMaximumFlyingTicks} (80 ticks at normal gravity) kicks.</li>
 * </ul>
 */
public final class MovementAllowance {

	/** Vanilla's packet-count reset: more than this many packets in one tick count as one. */
	public static final int VANILLA_MAX_PACKETS = 5;

	private MovementAllowance() {}

	/** Vanilla's deltaPackets after its reset rule. */
	public static int vanillaDeltaPackets(int rawPackets) {
		return rawPackets > VANILLA_MAX_PACKETS ? 1 : Math.max(1, rawPackets);
	}

	/**
	 * The {@code metersPerTick} to use for a rider so that the vanilla comparison
	 * {@code movedDist - expected > metersPerTick * deltaPackets} becomes
	 * {@code movedDist - expected > max(vanilla * dp, (perPacket * min(raw, burstCap))^2)}, perPacket =
	 * maxBikeSpeed / 20 * slack. Never below vanilla.
	 */
	public static float tooQuicklyMetersPerTick(float vanilla, int rawPackets, double maxBikeSpeed, double slack, int burstCap) {
		int dp = vanillaDeltaPackets(rawPackets);
		double perPacket = maxBikeSpeed / 20.0 * slack;
		double burst = perPacket * Math.min(Math.max(1, rawPackets), Math.max(1, burstCap));
		double total = Math.max((double) vanilla * dp, burst * burst);
		return (float) (total / dp);
	}

	/** The squared-distance threshold for "moved wrongly" for a rider at {@code speed} blocks/s. Never below vanilla. */
	public static double movedWronglyThresholdSqr(double vanilla, double speed, double maxBikeSpeed, double ticks, double maxBlocks) {
		double v = Math.max(0, Math.min(speed, maxBikeSpeed));
		if (!Double.isFinite(v)) {
			v = 0;
		}
		double tol = Math.min(maxBlocks, v / 20.0 * ticks);
		return Math.max(vanilla, tol * tol);
	}

	/** The floating-kick limit for a rider. */
	public static int maxFlyingTicks(int vanilla, double multiplier) {
		if (vanilla == Integer.MAX_VALUE || !(multiplier > 1)) {
			return vanilla;
		}
		return (int) Math.min(Integer.MAX_VALUE, Math.ceil(vanilla * multiplier));
	}
}

package gl.syn.armacraft.ride.server;

/** Mob-hit numbers: damage and knockback scaled by speed, and the box ahead of the bike. Pure. */
public final class MobHitMath {

	private MobHitMath() {}

	/** Damage for a hit at {@code speed} blocks/s; 0 below the minimum speed. */
	public static double damage(double speed, double minSpeed, double perSpeed, double max) {
		if (!(speed >= minSpeed) || !(speed > 0)) {
			return 0;
		}
		return Math.min(max, perSpeed * speed);
	}

	/** Knockback strength (LivingEntity.knockback power) along the heading. */
	public static double knockback(double speed, double base, double perSpeed, double max) {
		return Math.min(max, base + perSpeed * Math.max(0, speed));
	}

	/** How far the box reaches past the bike's front face: one tick of travel plus {@code extra}. */
	public static double reach(double speed, double extra) {
		return Math.max(0, speed) / 20.0 + extra;
	}

	/**
	 * The box ahead of a rider: from the rider's centre plane to {@code reach} past its front face along the
	 * heading, widened by {@code lateral} on both sides, same height as the rider.
	 *
	 * @param bb rider bounding box {minX, minY, minZ, maxX, maxY, maxZ}
	 * @return {minX, minY, minZ, maxX, maxY, maxZ}
	 */
	public static double[] aheadBox(double[] bb, int hx, int hz, double reach, double lateral) {
		double minX = bb[0];
		double minY = bb[1];
		double minZ = bb[2];
		double maxX = bb[3];
		double maxY = bb[4];
		double maxZ = bb[5];
		double cx = (minX + maxX) / 2;
		double cz = (minZ + maxZ) / 2;
		if (hx > 0) {
			minX = cx;
			maxX = maxX + reach;
		} else if (hx < 0) {
			maxX = cx;
			minX = minX - reach;
		} else {
			minX -= lateral;
			maxX += lateral;
		}
		if (hz > 0) {
			minZ = cz;
			maxZ = maxZ + reach;
		} else if (hz < 0) {
			maxZ = cz;
			minZ = minZ - reach;
		} else {
			minZ -= lateral;
			maxZ += lateral;
		}
		return new double[] {minX, minY, minZ, maxX, maxY, maxZ};
	}
}

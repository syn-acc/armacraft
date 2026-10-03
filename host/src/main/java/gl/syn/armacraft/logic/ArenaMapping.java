package gl.syn.armacraft.logic;

/**
 * Armagetron grid coordinates to Minecraft world coordinates (PROTOCOL.md "Host-side coordinate mapping").
 *
 * <pre>
 * mc_x = ax + x * s
 * mc_y = ay
 * mc_z = az - y * s          // Armagetron north (+y) = Minecraft north (-z)
 * yaw  = atan2(-dx, -dy)     // MC yaw: 0=+z(south), 90=-x(west), 180=-z(north)
 * </pre>
 *
 * Pure math, no Minecraft classes, so it can be unit tested directly.
 */
public record ArenaMapping(double ax, double ay, double az, double scale) {

	public ArenaMapping {
		if (!(scale > 0) || Double.isInfinite(scale)) {
			throw new IllegalArgumentException("scale must be > 0, got " + scale);
		}
	}

	public double mcX(double x) {
		return ax + x * scale;
	}

	public double mcY() {
		return ay;
	}

	public double mcZ(double y) {
		return az - y * scale;
	}

	/** Inverse of {@link #mcX}. */
	public double arenaX(double mcX) {
		return (mcX - ax) / scale;
	}

	/** Inverse of {@link #mcZ}. */
	public double arenaY(double mcZ) {
		return (az - mcZ) / scale;
	}

	/**
	 * Minecraft yaw in degrees for an Armagetron heading. Normalised to (-180, 180].
	 * A zero heading returns 0 (south) rather than NaN.
	 */
	public static float yaw(double dx, double dy) {
		if (dx == 0 && dy == 0) {
			return 0f;
		}
		double deg = Math.toDegrees(Math.atan2(-dx, -dy));
		// atan2(-0.0, -1) = -180; normalise so north is always +180.
		if (deg <= -180.0) {
			deg += 360.0;
		}
		return (float) deg;
	}

	/** Unit facing vector (x, z) in Minecraft space for a Minecraft yaw. */
	public static double[] facing(float yawDeg) {
		double r = Math.toRadians(yawDeg);
		return new double[] {-Math.sin(r), Math.cos(r)};
	}
}

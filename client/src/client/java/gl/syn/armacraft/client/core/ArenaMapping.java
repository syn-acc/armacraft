package gl.syn.armacraft.client.core;

/**
 * Armagetron grid coordinates to Minecraft world coordinates, identical to PROTOCOL.md
 * "Host-side coordinate mapping" (and host/.../logic/ArenaMapping):
 *
 * <pre>
 * mc_x = ax + x * s
 * mc_y = ay + z * s          // z = height above the arena floor (Armagetron units)
 * mc_z = az - y * s          // Armagetron north (+y) = Minecraft north (-z)
 * yaw  = atan2(-dx, -dy)     // MC yaw: 0=+z(south), 90=-x(west), 180=-z(north)
 * </pre>
 *
 * (x, y, z) -> (x, z, -y) is a proper rotation, so Armagetron geometry keeps its handedness and winding.
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

	public double mcY(double z) {
		return ay + z * scale;
	}

	public double mcZ(double y) {
		return az - y * scale;
	}

	public double arenaX(double mcX) {
		return (mcX - ax) / scale;
	}

	public double arenaY(double mcZ) {
		return (az - mcZ) / scale;
	}

	public double arenaZ(double mcY) {
		return (mcY - ay) / scale;
	}

	/** Minecraft yaw (degrees, (-180,180]) for an Armagetron heading; 0 for a zero heading. */
	public static float yaw(double dx, double dy) {
		if (dx == 0 && dy == 0) {
			return 0f;
		}
		double deg = Math.toDegrees(Math.atan2(-dx, -dy));
		if (deg <= -180.0) {
			deg += 360.0;
		}
		return (float) deg;
	}

	/**
	 * Minecraft pitch (degrees, positive looks down) for an Armagetron look vector whose horizontal part has
	 * length {@code horiz} and vertical part {@code rise}.
	 */
	public static float pitch(double horiz, double rise) {
		return (float) -Math.toDegrees(Math.atan2(rise, horiz));
	}
}

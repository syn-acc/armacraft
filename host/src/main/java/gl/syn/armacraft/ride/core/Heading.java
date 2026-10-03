package gl.syn.armacraft.ride.core;

/**
 * The four Armagetron axes (AXES 4) in Minecraft space. Index 0 north (-z), 1 east (+x), 2 south (+z),
 * 3 west (-x). A right turn is +1 (north -> east seen from above), a left turn -1, matching Armagetron's
 * {@code DoTurn(dir)} with +1 = right.
 */
public final class Heading {

	public static final int NORTH = 0;
	public static final int EAST = 1;
	public static final int SOUTH = 2;
	public static final int WEST = 3;

	private static final int[] DX = {0, 1, 0, -1};
	private static final int[] DZ = {-1, 0, 1, 0};

	private Heading() {}

	public static int dx(int h) {
		return DX[h & 3];
	}

	public static int dz(int h) {
		return DZ[h & 3];
	}

	/** Heading after a turn: dir +1 right, -1 left. */
	public static int turn(int h, int dir) {
		return (h + (dir > 0 ? 1 : -1)) & 3;
	}

	/** Minecraft yaw (degrees) looking along the heading: south 0, west 90, north 180, east -90. */
	public static float yaw(int h) {
		return switch (h & 3) {
			case NORTH -> 180f;
			case EAST -> -90f;
			case SOUTH -> 0f;
			default -> 90f;
		};
	}

	/** The heading closest to a Minecraft yaw ("facing snapped to the nearest cardinal"). */
	public static int fromYaw(float yaw) {
		double r = Math.toRadians(yaw);
		double fx = -Math.sin(r);
		double fz = Math.cos(r);
		if (Math.abs(fx) > Math.abs(fz)) {
			return fx > 0 ? EAST : WEST;
		}
		return fz > 0 ? SOUTH : NORTH;
	}

	public static String name(int h) {
		return switch (h & 3) {
			case NORTH -> "north";
			case EAST -> "east";
			case SOUTH -> "south";
			default -> "west";
		};
	}
}

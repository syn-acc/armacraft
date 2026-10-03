package gl.syn.armacraft.ride.core;

/**
 * Terrain classification for the cycle: what happens when the bike's box enters a column.
 *
 * <p>The bike stands on the highest collision top that is at most {@code stepHeight} above its feet (vanilla
 * step-up through {@code Entity.move} with the {@code step_height} attribute). It needs {@code bikeHeight}
 * of clear space above both its current and its new floor. A drop of up to {@code snapDown} is driven down
 * smoothly; deeper floors are fallen to under gravity.
 */
public final class Terrain {

	public enum Type {
		/** Same level. */
		FLAT,
		/** Up by at most the step height, with headroom: climbed without stopping. */
		STEP_UP,
		/** Down by at most {@code snapDown}: snapped down, no flying off. */
		STEP_DOWN,
		/** Still water surface (any of the above levels): driven on, frost walker freezes it. */
		WATER,
		/** Floor more than {@code snapDown} below (or none in range): drive off and fall. */
		DROP,
		/** Higher than a step, too little headroom, or lava: Armagetron wall, rubber applies. */
		WALL,
		/** Chunk not loaded: hold position, no rubber spent. */
		UNKNOWN
	}

	/** @param floorY the level the bike stands at after entering (feet y); for DROP/WALL the current feet y */
	public record Result(Type type, double floorY) {}

	public static final double EPS = 1e-3;

	private final double stepHeight;
	private final double bikeHeight;
	private final double snapDown;

	public Terrain(double stepHeight, double bikeHeight, double snapDown) {
		this.stepHeight = stepHeight;
		this.bikeHeight = bikeHeight;
		this.snapDown = snapDown;
	}

	public static Terrain standard() {
		return new Terrain(1.0, 1.8, 1.0);
	}

	public double stepHeight() {
		return stepHeight;
	}

	public double bikeHeight() {
		return bikeHeight;
	}

	public double snapDown() {
		return snapDown;
	}

	public Result classify(Column c, double feetY) {
		if (!c.loaded()) {
			return new Result(Type.UNKNOWN, feetY);
		}
		double floor = Double.NEGATIVE_INFINITY;
		Column.Kind floorKind = null;
		for (Column.Interval iv : c.intervals()) {
			if (iv.maxY() <= feetY + stepHeight + EPS && iv.maxY() > floor) {
				floor = iv.maxY();
				floorKind = iv.kind();
			}
		}
		boolean drop = !(floor >= feetY - snapDown - EPS);
		double standY = drop ? feetY : floor;
		// clear space needed while moving across: from the lower of the two levels to bikeHeight above the higher
		double bandBottom = Math.min(feetY, standY);
		double bandTop = Math.max(feetY, standY) + bikeHeight;
		for (Column.Interval iv : c.intervals()) {
			if (iv.maxY() <= standY + EPS || iv.maxY() <= bandBottom + EPS) {
				continue; // support or below
			}
			if (iv.minY() < bandTop - EPS) {
				return new Result(Type.WALL, feetY);
			}
		}
		if (floorKind == Column.Kind.LAVA) {
			return new Result(Type.WALL, feetY);
		}
		if (drop) {
			return new Result(Type.DROP, feetY);
		}
		if (floorKind == Column.Kind.WATER) {
			return new Result(Type.WATER, floor);
		}
		if (floor > feetY + EPS) {
			return new Result(Type.STEP_UP, floor);
		}
		if (floor < feetY - EPS) {
			return new Result(Type.STEP_DOWN, floor);
		}
		return new Result(Type.FLAT, floor);
	}
}

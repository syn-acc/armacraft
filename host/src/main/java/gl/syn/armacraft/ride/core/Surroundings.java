package gl.syn.armacraft.ride.core;

/**
 * What the sensors see for one tick (blocks). Trails are measured from the cycle's point (Armagetron: the cycle is a
 * point, walls are lines), terrain from the faces of the bike's narrow riding box.
 *
 * @param spaceAhead  distance to the first wall along the heading; +inf if none in range.
 *                    This is Armagetron's front sensor hit ({@code GetMaxSpaceAhead} before the stop offset).
 * @param wallLength  length of the wall ahead (feeds CYCLE_RUBBER_MINDISTANCE_RATIO); 1 for a block face
 * @param left        diagonal-back sensor to the left (Armagetron casts {@code dirDrive.Turn(-1, d)})
 * @param right       same to the right
 * @param hold        terrain ahead is not loaded: do not move and do not spend rubber this tick
 * @param frontKind   what the front sensor hit: Minecraft terrain gets {@link CycleParams#terrainRubberFactor}
 */
public record Surroundings(double spaceAhead, double wallLength, Side left, Side right, boolean hold, Kind frontKind) {

	/** Without the kind of the wall ahead (treated as a trail: exact Armagetron rubber). */
	public Surroundings(double spaceAhead, double wallLength, Side left, Side right, boolean hold) {
		this(spaceAhead, wallLength, left, right, hold, Kind.ENEMY);
	}

	public enum Kind { NONE, TERRAIN, SELF, ENEMY }

	/**
	 * One side sensor.
	 *
	 * @param clearance distance to the wall (blocks; from the point for trails, the side face for terrain), +inf if
	 *                  nothing within CYCLE_WALL_NEAR
	 * @param kind      what was hit: terrain counts as Armagetron's rim, own trail as SELF, other trails as ENEMY
	 */
	public record Side(double clearance, Kind kind) {
		public static final Side NONE = new Side(Double.POSITIVE_INFINITY, Kind.NONE);

		public boolean hit() {
			return kind != Kind.NONE && Double.isFinite(clearance);
		}
	}

	public static final Surroundings OPEN = new Surroundings(Double.POSITIVE_INFINITY, 1, Side.NONE, Side.NONE, false, Kind.NONE);

	public static Surroundings ahead(double space) {
		return new Surroundings(space, 1, Side.NONE, Side.NONE, false, Kind.ENEMY);
	}

	public static Surroundings terrainAhead(double space) {
		return new Surroundings(space, 1, Side.NONE, Side.NONE, false, Kind.TERRAIN);
	}

	public Surroundings withSides(Side l, Side r) {
		return new Surroundings(spaceAhead, wallLength, l, r, hold, frontKind);
	}
}

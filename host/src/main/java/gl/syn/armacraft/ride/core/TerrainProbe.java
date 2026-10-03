package gl.syn.armacraft.ride.core;

/**
 * Armagetron's sensors against Minecraft terrain. The front sensor walks block columns along the heading,
 * following steps up and down like the bike would, and reports the distance from the bike's front face to the
 * first {@link Terrain.Type#WALL}. The side sensors are Armagetron's diagonal-back rays used for wall
 * acceleration ({@code gCycleMovement::CalculateAcceleration}, {@code dirDrive.Turn(-1, d)}): a ray going back and
 * sideways at 45 degrees, whose hit parameter equals the lateral distance to a wall parallel to the heading.
 *
 * <p>Coordinates: block (bx, bz) covers [bx, bx+1) x [bz, bz+1). The bike is a box of {@code halfWidth} around
 * (x, z) with its feet at {@code feetY}.
 */
public final class TerrainProbe {

	/** Columns are looked up through this; implementations may cache per tick. */
	public interface ColumnSource {
		Column column(int bx, int bz);
	}

	/**
	 * @param distance   front face to the first wall (blocks), +inf if none within range
	 * @param hold       an unloaded column was reached before any wall
	 * @param first      classification of the first column ahead (diagnostics, tests)
	 * @param water      a water column lies within range ahead
	 * @param dropAt     distance to the first drop, +inf if none
	 * @param levels     the bike's feet height along the way (steps up and down followed, as far as was looked)
	 */
	public record Ahead(double distance, boolean hold, Terrain.Type first, boolean water, double dropAt, Levels levels) {}

	/**
	 * Feet height against driven distance: a step up is taken when the front face reaches the higher column, a step
	 * down when the back face has left the higher one.
	 */
	public static final class Levels implements Trail.Profile {
		private final double feetY;
		private double[] at = new double[8];
		private double[] level = new double[8];
		private int n;

		Levels(double feetY) {
			this.feetY = feetY;
		}

		void add(double dist, double y) {
			if (n == at.length) {
				at = java.util.Arrays.copyOf(at, n * 2);
				level = java.util.Arrays.copyOf(level, n * 2);
			}
			at[n] = dist;
			level[n] = y;
			n++;
		}

		@Override
		public double feetAt(double dist) {
			double y = feetY;
			for (int i = 0; i < n && at[i] <= dist + 1e-9; i++) {
				y = level[i];
			}
			return y;
		}

		public int steps() {
			return n;
		}
	}

	private TerrainProbe() {}

	public static Ahead ahead(ColumnSource src, Terrain terrain, double x, double z, double feetY, int heading, double halfWidth,
		double maxDist) {
		int hx = Heading.dx(heading);
		int hz = Heading.dz(heading);
		double s = x * hx + z * hz;
		double front = s + halfWidth;
		double lat = hx != 0 ? z : x;
		int lat0 = (int) Math.floor(lat - halfWidth + 1e-6);
		int lat1 = (int) Math.floor(lat + halfWidth - 1e-6);
		int n0 = (int) Math.ceil(front - 1e-6);
		double vy = feetY;
		Levels levels = new Levels(feetY);
		Terrain.Type first = null;
		boolean water = false;
		double dropAt = Double.POSITIVE_INFINITY;
		for (int n = n0; ; n++) {
			double dist = n - front;
			if (dist > maxDist) {
				break;
			}
			// forward coordinate n along a negative axis maps to block -n-1
			int along = (hx + hz) > 0 ? n : -n - 1;
			Column merged = null;
			for (int l = lat0; l <= lat1; l++) {
				Column c = hx != 0 ? src.column(along, l) : src.column(l, along);
				merged = merged == null ? c : Column.merge(merged, c);
			}
			Terrain.Result r = terrain.classify(merged, vy);
			if (first == null) {
				first = r.type();
			}
			switch (r.type()) {
				case WALL -> {
					return new Ahead(Math.max(0, dist), false, first, water, dropAt, levels);
				}
				case UNKNOWN -> {
					return new Ahead(Math.max(0, dist), true, first, water, dropAt, levels);
				}
				case DROP -> {
					// the bike leaves the ground here; anything beyond is met in flight by vanilla collision
					dropAt = Math.min(dropAt, Math.max(0, dist));
					return new Ahead(Double.POSITIVE_INFINITY, false, first, water, dropAt, levels);
				}
				default -> {
					water |= r.type() == Terrain.Type.WATER;
					if (Math.abs(r.floorY() - vy) > Terrain.EPS) {
						levels.add(Math.max(0, r.floorY() > vy ? dist : dist + 2 * halfWidth), r.floorY());
					}
					vy = r.floorY();
				}
			}
		}
		return new Ahead(Double.POSITIVE_INFINITY, false, first == null ? Terrain.Type.FLAT : first, water, dropAt, levels);
	}

	/**
	 * One diagonal-back side sensor.
	 *
	 * @param side +1 right, -1 left
	 * @return clearance from the bike's side face to a terrain wall, +inf if none within {@code near}
	 */
	public static double side(ColumnSource src, Terrain terrain, double x, double z, double feetY, int heading, int side,
		double halfWidth, double near) {
		int hx = Heading.dx(heading);
		int hz = Heading.dz(heading);
		int sh = Heading.turn(heading, side);
		int sx = Heading.dx(sh);
		int sz = Heading.dz(sh);
		double step = 0.125;
		int lastBx = Integer.MIN_VALUE;
		int lastBz = Integer.MIN_VALUE;
		for (double t = halfWidth; t <= near + halfWidth + 1e-9; t += step) {
			double px = x + (sx - hx) * t;
			double pz = z + (sz - hz) * t;
			int bx = (int) Math.floor(px);
			int bz = (int) Math.floor(pz);
			if (bx == lastBx && bz == lastBz) {
				continue;
			}
			lastBx = bx;
			lastBz = bz;
			Terrain.Result r = terrain.classify(src.column(bx, bz), feetY);
			if (r.type() == Terrain.Type.WALL) {
				// exact lateral distance from the bike's side face to the near face of this column
				double latBike = (sx != 0 ? x : z) * (sx + sz);
				double nearFace = sx + sz > 0 ? (sx != 0 ? bx : bz) : -((sx != 0 ? bx : bz) + 1);
				return Math.max(0, nearFace - latBike - halfWidth);
			}
		}
		return Double.POSITIVE_INFINITY;
	}
}

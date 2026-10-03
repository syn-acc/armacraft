package gl.syn.armacraft.ride.core;

import java.util.Arrays;

/**
 * A light trail: a polyline of feet positions along the driven path, so it follows terrain height. Each point
 * carries the turn counter ({@code seg}) it was laid under, so the owner can ignore the walls Armagetron ignores
 * for its own cycle: the wall being laid and the one before the last turn ({@code gCycle::EdgeIsDangerous},
 * gCycle.cpp:3367: {@code nw == currentWall || nw == lastWall}).
 *
 * <p>Collision is Armagetron's: the cycle is a point on its heading line and a wall is a zero-thickness line
 * (eSensor casts a ray from the cycle's position). A wall running along the heading any distance &gt; 0 to the side is
 * never ahead; only a real crossing is. In plan view a trail between two turns is one straight axis-aligned line; a
 * 1-block step only changes its height, so the join is the same wall.
 *
 * <p>Points have absolute indices ({@link #index}), so a viewer can receive only the new ones. The trail keeps at
 * most {@code maxLength} blocks behind its head (Armagetron CYCLE_WALLS_LENGTH) and, once its rider stops,
 * stays up for {@code stayUp} seconds (WALLS_STAY_UP_DELAY; negative: forever), then vanishes. A trail is lethal
 * exactly while it is visible.
 */
public final class Trail {

	private double[] xs = new double[64];
	private double[] ys = new double[64];
	private double[] zs = new double[64];
	private int[] segs = new int[64];
	private int start;
	private int size;
	/** Absolute index of the point at {@link #start}. */
	private long baseIndex;
	private double length;
	/** Diagnostics: piece index (start point) of the nearest hit found by the last {@link #ahead} call, -1 if none. */
	public int lastHit = -1;
	/**
	 * A wall running along the heading is ahead only if the cycle is on its very line: within this (blocks). Far below
	 * CYCLE_RUBBER_MINDISTANCE (0.001 units), so a cycle that stopped against a wall and turned rides alongside it.
	 */
	static final double ON_LINE = 1e-5;
	/** A crossing this far (blocks) behind the cycle's point is behind it, not ahead. */
	static final double BEHIND = 1e-9;
	/** A dead trail fades out over the last this many seconds of its stay-up time (it is solid, and lethal, until then). */
	static final double FADE_SECONDS = 1.0;
	/** NaN while the rider is riding; the time it stopped otherwise. */
	private double deadSince = Double.NaN;

	// ---- building

	/** Adds a point. Straight, level continuations replace the previous point instead of adding one. */
	public void append(double x, double y, double z, int seg) {
		if (size > 0) {
			int last = slot(size - 1);
			double dx = x - xs[last];
			double dy = y - ys[last];
			double dz = z - zs[last];
			double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (d < 1e-4) {
				return;
			}
			if (size > 1) {
				int prev = slot(size - 2);
				if (segs[last] == seg && collinear(prev, last, x, y, z)) {
					double old = dist(prev, last);
					xs[last] = x;
					ys[last] = y;
					zs[last] = z;
					length += dist(prev, last) - old;
					return;
				}
			}
			length += d;
		}
		ensure(size + 1);
		int i = slot(size);
		xs[i] = x;
		ys[i] = y;
		zs[i] = z;
		segs[i] = seg;
		size++;
	}

	private boolean collinear(int a, int b, double x, double y, double z) {
		if (Math.abs(ys[a] - ys[b]) > 1e-6 || Math.abs(y - ys[b]) > 1e-6) {
			return false;
		}
		double ux = xs[b] - xs[a];
		double uz = zs[b] - zs[a];
		double vx = x - xs[b];
		double vz = z - zs[b];
		double cross = ux * vz - uz * vx;
		double dot = ux * vx + uz * vz;
		double lu = Math.hypot(ux, uz);
		double lv = Math.hypot(vx, vz);
		return dot > 0 && Math.abs(cross) <= 1e-4 * lu * lv + 1e-9;
	}

	/** Drops the oldest points until the polyline (plus the head piece to (hx,hy,hz)) is at most maxLength long. */
	public void trim(double maxLength, double hx, double hy, double hz) {
		if (!(maxLength > 0) || size < 2) {
			return;
		}
		double head = size > 0 ? Math.sqrt(sq(hx - x(size - 1)) + sq(hy - y(size - 1)) + sq(hz - z(size - 1))) : 0;
		if (Double.isNaN(head)) {
			head = 0;
		}
		while (size >= 2 && length + head > maxLength) {
			double first = dist(slot(0), slot(1));
			double excess = length + head - maxLength;
			if (first <= excess + 1e-9) {
				length -= first;
				start = (start + 1) % xs.length;
				size--;
				baseIndex++;
			} else {
				// shorten the oldest piece in place
				int a = slot(0);
				int b = slot(1);
				double f = excess / first;
				xs[a] += (xs[b] - xs[a]) * f;
				ys[a] += (ys[b] - ys[a]) * f;
				zs[a] += (zs[b] - zs[a]) * f;
				length -= excess;
				break;
			}
		}
	}

	public void clear() {
		baseIndex += size;
		start = 0;
		size = 0;
		length = 0;
	}

	// ---- life cycle

	public void stop(double now) {
		if (Double.isNaN(deadSince)) {
			deadSince = now;
		}
	}

	public boolean riding() {
		return Double.isNaN(deadSince);
	}

	public double deadSince() {
		return deadSince;
	}

	/**
	 * Visibility 1..0: 1 while riding and for {@code stayUp} seconds after (Armagetron: the walls of a dead cycle stay
	 * up for WALLS_STAY_UP_DELAY, negative = forever, 0 = gone at once), fading out over the last second of that.
	 */
	public double alpha(double now, double stayUp) {
		return riding() ? 1 : alphaAfter(now - deadSince, stayUp);
	}

	/** Visibility of a dead cycle's walls {@code deadFor} seconds after it died. */
	public static double alphaAfter(double deadFor, double stayUp) {
		if (stayUp < 0) {
			return 1;
		}
		double left = stayUp - deadFor;
		if (!(left > 0)) {
			return 0;
		}
		return Math.min(1, left / Math.min(FADE_SECONDS, stayUp / 2));
	}

	/** Lethal exactly while visible. */
	public boolean lethal(double now, double stayUp) {
		return alpha(now, stayUp) > 0;
	}

	// ---- access

	public int size() {
		return size;
	}

	public double length() {
		return length;
	}

	public double x(int i) {
		return xs[slot(i)];
	}

	public double y(int i) {
		return ys[slot(i)];
	}

	public double z(int i) {
		return zs[slot(i)];
	}

	public int seg(int i) {
		return segs[slot(i)];
	}

	/** Absolute index of point i (stable across trims). */
	public long index(int i) {
		return baseIndex + i;
	}

	public long firstIndex() {
		return baseIndex;
	}

	public long endIndex() {
		return baseIndex + size;
	}

	/**
	 * Replaces points from absolute index {@code from} on with the given ones (viewer side of the sync). Points
	 * before {@code from} that are still held are kept; a gap resets the trail.
	 */
	public void receive(long from, double[] pts, int[] segIds) {
		int n = pts.length / 3;
		if (from > endIndex() || from < baseIndex) {
			// gap or rewrite of trimmed history: restart at from
			start = 0;
			size = 0;
			length = 0;
			baseIndex = from;
		} else {
			// drop held points at and after from (the last held point may have been replaced by a merge)
			while (endIndex() > from && size > 0) {
				if (size >= 2) {
					length -= dist(slot(size - 2), slot(size - 1));
				}
				size--;
			}
		}
		for (int k = 0; k < n; k++) {
			ensure(size + 1);
			int i = slot(size);
			xs[i] = pts[3 * k];
			ys[i] = pts[3 * k + 1];
			zs[i] = pts[3 * k + 2];
			segs[i] = segIds != null && k < segIds.length ? segIds[k] : 0;
			if (size > 0) {
				length += dist(slot(size - 1), i);
			}
			size++;
		}
	}

	/** Drops points before absolute index {@code from} (viewer side: the owner trimmed them). */
	public void dropBefore(long from) {
		while (baseIndex < from && size > 0) {
			if (size >= 2) {
				length -= dist(slot(0), slot(1));
			}
			start = (start + 1) % xs.length;
			size--;
			baseIndex++;
		}
		if (size == 0) {
			baseIndex = Math.max(baseIndex, from);
			start = 0;
		}
	}

	// ---- collision

	/**
	 * The cycle for trail collision: a point (x, z) with a vertical extent {@code [feetY - below, feetY + height]}.
	 * Trails block where their vertical extent {@code [min(ya,yb), max(ya,yb) + trailHeight]} overlaps it.
	 * {@code below} is the step the cycle can take down (a wall laid one step lower, at the foot of the step the
	 * cycle is about to drive down, is a wall for it; a bridge needs more room than that above a wall anyway).
	 */
	public record Point(double x, double z, double feetY, double height, double below) {
		public Point(double x, double z, double feetY, double height) {
			this(x, z, feetY, height, 0);
		}

		Point at(double feet) {
			return feet == feetY ? this : new Point(x, z, feet, height, below);
		}
	}

	/**
	 * The cycle's feet height along its path: {@code feetAt(d)} once it has driven {@code d} blocks on. A wall is
	 * met at the height the cycle has when it gets there, not the height it has now (stairs: the wall three steps
	 * down is on the cycle's level by the time it arrives).
	 */
	public interface Profile {
		double feetAt(double dist);
	}

	/** {@link #ahead(Point, int, double, double, int, double, double, double, Profile)} on level ground. */
	public double ahead(Point bike, int heading, double trailHeight, double maxDist, int ignoreFromSeg, double headX, double headY,
		double headZ) {
		return ahead(bike, heading, trailHeight, maxDist, ignoreFromSeg, headX, headY, headZ, null);
	}

	/**
	 * Distance from the cycle's point along its heading to the first piece of this trail the ray crosses, or +inf
	 * (Armagetron's front sensor, gCycleMovement::GetMaxSpaceAhead). Zero-thickness walls: a piece along the heading
	 * counts only if the cycle is on its line ({@link #ON_LINE}).
	 *
	 * @param ignoreFromSeg pieces whose seg is &gt;= this are ignored (owner: current seg - 1); Integer.MAX_VALUE for none
	 * @param headX         head piece end (the rider's current position) or NaN for none
	 * @param profile       the cycle's feet height along the way, or null for level ground at {@code bike.feetY()}
	 */
	public double ahead(Point bike, int heading, double trailHeight, double maxDist, int ignoreFromSeg, double headX, double headY,
		double headZ, Profile profile) {
		int hx = Heading.dx(heading);
		int hz = Heading.dz(heading);
		// lateral axis: right of heading
		int rx = -hz;
		int rz = hx;
		double s0 = bike.x() * hx + bike.z() * hz;
		double lat = bike.x() * rx + bike.z() * rz;
		double best = Double.POSITIVE_INFINITY;
		lastHit = -1;
		int pieces = size - 1 + (Double.isNaN(headX) || size == 0 ? 0 : 1);
		for (int k = 0; k < pieces; k++) {
			double ax = x(k);
			double ay = y(k);
			double az = z(k);
			double bx;
			double by;
			double bz;
			int seg;
			if (k + 1 < size) {
				bx = x(k + 1);
				by = y(k + 1);
				bz = z(k + 1);
				seg = seg(k + 1);
			} else {
				bx = headX;
				by = headY;
				bz = headZ;
				seg = seg(k);
			}
			if (seg >= ignoreFromSeg) {
				continue;
			}
			double sA = ax * hx + az * hz;
			double sB = bx * hx + bz * hz;
			double lA = ax * rx + az * rz - lat;
			double lB = bx * rx + bz * rz - lat;
			double sHit;
			if (Math.abs(lB - lA) <= ON_LINE) {
				// along the heading: beside the cycle, however close, unless the cycle is on its very line
				if (Math.abs(lA) > ON_LINE && Math.abs(lB) > ON_LINE) {
					continue;
				}
				if (Math.max(sA, sB) < s0 - BEHIND) {
					continue;
				}
				sHit = Math.max(s0, Math.min(sA, sB));
			} else {
				if ((lA > 0 && lB > 0) || (lA < 0 && lB < 0)) {
					continue; // both ends on one side of the ray's line
				}
				sHit = sA + (sB - sA) * (-lA / (lB - lA));
				if (sHit < s0 - BEHIND) {
					continue;
				}
			}
			double d = Math.max(0, sHit - s0);
			if (d >= best || d > maxDist) {
				continue;
			}
			if (!verticalOverlap(ay, by, trailHeight, profile == null ? bike : bike.at(profile.feetAt(d)))) {
				continue;
			}
			best = d;
			lastHit = k;
		}
		return best;
	}

	/**
	 * Diagonal-back side sensor against this trail (see {@link TerrainProbe#side}): distance from the cycle's point
	 * to a piece running along the heading on that side, or +inf.
	 */
	public double side(Point bike, int heading, int side, double trailHeight, double near, int ignoreFromSeg) {
		int hx = Heading.dx(heading);
		int hz = Heading.dz(heading);
		int sh = Heading.turn(heading, side);
		int sx = Heading.dx(sh);
		int sz = Heading.dz(sh);
		double s0 = bike.x() * hx + bike.z() * hz;
		double l0 = bike.x() * sx + bike.z() * sz;
		double best = Double.POSITIVE_INFINITY;
		for (int k = 0; k + 1 < size; k++) {
			if (seg(k + 1) >= ignoreFromSeg) {
				continue;
			}
			double ax = x(k);
			double az = z(k);
			double bx = x(k + 1);
			double bz = z(k + 1);
			if (!verticalOverlap(y(k), y(k + 1), trailHeight, bike)) {
				continue;
			}
			double sA = ax * hx + az * hz;
			double sB = bx * hx + bz * hz;
			double lA = ax * sx + az * sz;
			double lB = bx * sx + bz * sz;
			double len = Math.hypot(sB - sA, lB - lA);
			// parallel to the heading (Armagetron: |wallVec . dirDrive| > .9 |dirDrive|^2 for unit-ish edges)
			if (len < 1e-6 || Math.abs(sB - sA) < 0.9 * len) {
				continue;
			}
			double l = (lA + lB) / 2;
			double t = l - l0; // lateral distance on this side
			if (t <= 0 || t > near) {
				continue;
			}
			double sHit = s0 - t; // the ray reaches lateral t at t behind the cycle
			if (sHit < Math.min(sA, sB) - 1e-6 || sHit > Math.max(sA, sB) + 1e-6) {
				continue;
			}
			best = Math.min(best, t);
		}
		return best;
	}

	/** Is every piece axis-aligned in plan view (no bends)? Diagnostics and tests. */
	public boolean axisAligned(double eps) {
		for (int k = 0; k + 1 < size; k++) {
			if (Math.abs(x(k + 1) - x(k)) > eps && Math.abs(z(k + 1) - z(k)) > eps) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Server validation: does the move (ax,az)->(bx,bz) of a rider whose feet were at {@code feetY} cross a piece
	 * of this trail (head piece to (hx,hy,hz) included)?
	 */
	public boolean crossedBy(double ax, double az, double bx, double bz, double feetY, double height, double trailHeight,
		double headX, double headY, double headZ) {
		Point box = new Point(0, 0, feetY, height);
		int pieces = size - 1 + (Double.isNaN(headX) || size == 0 ? 0 : 1);
		for (int k = 0; k < pieces; k++) {
			double px = x(k);
			double py = y(k);
			double pz = z(k);
			double qx = k + 1 < size ? x(k + 1) : headX;
			double qy = k + 1 < size ? y(k + 1) : headY;
			double qz = k + 1 < size ? z(k + 1) : headZ;
			if (!verticalOverlap(py, qy, trailHeight, box)) {
				continue;
			}
			if (segmentsIntersect(ax, az, bx, bz, px, pz, qx, qz)) {
				return true;
			}
		}
		return false;
	}

	static boolean verticalOverlap(double ya, double yb, double trailHeight, Point bike) {
		double bottom = Math.min(ya, yb);
		double top = Math.max(ya, yb) + trailHeight;
		return top > bike.feetY() - bike.below() + 1e-3 && bottom < bike.feetY() + bike.height() - 1e-3;
	}

	/** Proper 2D segment intersection (touching endpoints count). */
	public static boolean segmentsIntersect(double ax, double az, double bx, double bz, double cx, double cz, double dx, double dz) {
		double d1 = cross(cx, cz, dx, dz, ax, az);
		double d2 = cross(cx, cz, dx, dz, bx, bz);
		double d3 = cross(ax, az, bx, bz, cx, cz);
		double d4 = cross(ax, az, bx, bz, dx, dz);
		if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
			return true;
		}
		return (d1 == 0 && onSegment(cx, cz, dx, dz, ax, az)) || (d2 == 0 && onSegment(cx, cz, dx, dz, bx, bz))
			|| (d3 == 0 && onSegment(ax, az, bx, bz, cx, cz)) || (d4 == 0 && onSegment(ax, az, bx, bz, dx, dz));
	}

	private static double cross(double ax, double az, double bx, double bz, double px, double pz) {
		return (bx - ax) * (pz - az) - (bz - az) * (px - ax);
	}

	private static boolean onSegment(double ax, double az, double bx, double bz, double px, double pz) {
		return Math.min(ax, bx) - 1e-9 <= px && px <= Math.max(ax, bx) + 1e-9 && Math.min(az, bz) - 1e-9 <= pz && pz <= Math.max(az, bz) + 1e-9;
	}

	/** Human-readable description of piece k (diagnostics). */
	public String describePiece(int k) {
		if (k < 0 || k + 1 > size) {
			return "none";
		}
		String b = k + 1 < size ? String.format("(%.2f,%.2f,%.2f) seg %d", x(k + 1), y(k + 1), z(k + 1), seg(k + 1)) : "head";
		return String.format("#%d (%.2f,%.2f,%.2f) seg %d -> %s", index(k), x(k), y(k), z(k), seg(k), b);
	}

	// ---- storage

	private int slot(int i) {
		return (start + i) % xs.length;
	}

	private double dist(int a, int b) {
		return Math.sqrt(sq(xs[b] - xs[a]) + sq(ys[b] - ys[a]) + sq(zs[b] - zs[a]));
	}

	private static double sq(double v) {
		return v * v;
	}

	private void ensure(int n) {
		if (n <= xs.length) {
			return;
		}
		int cap = Math.max(n, xs.length * 2);
		double[] nx = new double[cap];
		double[] ny = new double[cap];
		double[] nz = new double[cap];
		int[] ns = new int[cap];
		for (int i = 0; i < size; i++) {
			int s = slot(i);
			nx[i] = xs[s];
			ny[i] = ys[s];
			nz[i] = zs[s];
			ns[i] = segs[s];
		}
		xs = nx;
		ys = ny;
		zs = nz;
		segs = ns;
		start = 0;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("Trail[");
		for (int i = 0; i < Math.min(size, 8); i++) {
			sb.append(String.format("(%.2f,%.2f,%.2f s%d)", x(i), y(i), z(i), seg(i)));
		}
		if (size > 8) {
			sb.append("... ").append(size).append(" pts");
		}
		return sb.append(" len=").append(String.format("%.2f", length)).append(']').toString();
	}

	/** Copy of the points from absolute index {@code from} as xyz triples (sync sender side). */
	public double[] pointsFrom(long from) {
		int i0 = (int) Math.max(0, from - baseIndex);
		double[] out = new double[Math.max(0, size - i0) * 3];
		for (int i = i0, k = 0; i < size; i++, k++) {
			out[3 * k] = x(i);
			out[3 * k + 1] = y(i);
			out[3 * k + 2] = z(i);
		}
		return out;
	}

	public int[] segsFrom(long from) {
		int i0 = (int) Math.max(0, from - baseIndex);
		int[] out = new int[Math.max(0, size - i0)];
		for (int i = i0, k = 0; i < size; i++, k++) {
			out[k] = seg(i);
		}
		return out;
	}

	@SuppressWarnings("unused")
	private static String dump(double[] a) {
		return Arrays.toString(a);
	}
}

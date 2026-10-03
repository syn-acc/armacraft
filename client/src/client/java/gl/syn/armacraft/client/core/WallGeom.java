package gl.syn.armacraft.client.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 2D wall geometry helpers: ray casts for the lean sensors and per-cycle trail distances. */
public final class WallGeom {

	private WallGeom() {
	}

	/** A wall segment on the arena plane. */
	public record Seg(double x1, double y1, double x2, double y2) {
		public double length() {
			return Math.hypot(x2 - x1, y2 - y1);
		}
	}

	/**
	 * Smallest {@code t} in (minT, range] with {@code p + t*d} on a segment, else {@code range}.
	 * Like Armagetron's eSensor, {@code t} is measured in multiples of {@code d} (which need not be unit).
	 */
	public static double raycast(V2 p, V2 d, double range, Iterable<Seg> segs, double minT) {
		double best = range;
		for (Seg s : segs) {
			double ex = s.x2 - s.x1, ey = s.y2 - s.y1;
			double denom = d.x() * ey - d.y() * ex;
			if (Math.abs(denom) < 1e-12) {
				continue;   // parallel
			}
			double qx = s.x1 - p.x(), qy = s.y1 - p.y();
			double t = (qx * ey - qy * ex) / denom;
			double u = (qx * d.y() - qy * d.x()) / denom;
			if (u >= -1e-9 && u <= 1 + 1e-9 && t > minT && t < best) {
				best = t;
			}
		}
		return best;
	}

	/** Splits a wall into its solid pieces given {@code [from,to]} gap fractions (holes from explosions). */
	public static List<double[]> solidPieces(double[][] gaps) {
		List<double[]> pieces = new ArrayList<>();
		if (gaps == null || gaps.length == 0) {
			pieces.add(new double[] {0, 1});
			return pieces;
		}
		List<double[]> g = new ArrayList<>();
		for (double[] gap : gaps) {
			if (gap != null && gap.length >= 2) {
				double a = Math.max(0, Math.min(gap[0], gap[1]));
				double b = Math.min(1, Math.max(gap[0], gap[1]));
				if (b > a) {
					g.add(new double[] {a, b});
				}
			}
		}
		g.sort((x, y) -> Double.compare(x[0], y[0]));
		double cur = 0;
		for (double[] gap : g) {
			if (gap[0] > cur) {
				pieces.add(new double[] {cur, gap[0]});
			}
			cur = Math.max(cur, gap[1]);
		}
		if (cur < 1) {
			pieces.add(new double[] {cur, 1});
		}
		return pieces;
	}

	private static long key(double x, double y) {
		long kx = Math.round(x * 64);
		long ky = Math.round(y * 64);
		return (kx * 73856093L) ^ (ky * 19349663L);
	}

	/**
	 * Trail distance (Armagetron "Pos") at the start of each wall of one cycle. Walls chain end-to-start
	 * (x2,y2 of one wall is x1,y1 of the next); the chain root starts at distance 0. Matching uses 1/64 unit
	 * buckets plus an exact distance check, so it is O(n).
	 *
	 * @return start distance per index of {@code walls}
	 */
	public static double[] chainStarts(List<Seg> walls) {
		int n = walls.size();
		double[] start = new double[n];
		Map<Long, List<Integer>> byEnd = new HashMap<>();
		for (int i = 0; i < n; i++) {
			Seg s = walls.get(i);
			byEnd.computeIfAbsent(key(s.x2, s.y2), k -> new ArrayList<>()).add(i);
		}
		int[] pred = new int[n];
		for (int i = 0; i < n; i++) {
			pred[i] = -1;
			Seg s = walls.get(i);
			List<Integer> cands = byEnd.get(key(s.x1, s.y1));
			if (cands != null) {
				for (int j : cands) {
					if (j != i) {
						Seg o = walls.get(j);
						if (Math.abs(o.x2 - s.x1) < 1e-3 && Math.abs(o.y2 - s.y1) < 1e-3) {
							pred[i] = j;
							break;
						}
					}
				}
			}
		}
		boolean[] done = new boolean[n];
		for (int i = 0; i < n; i++) {
			resolve(i, walls, pred, start, done, 0);
		}
		return start;
	}

	private static double resolve(int i, List<Seg> walls, int[] pred, double[] start, boolean[] done, int depth) {
		if (done[i]) {
			return start[i];
		}
		double s = 0;
		if (pred[i] >= 0 && depth < 100_000) {
			int p = pred[i];
			pred[i] = -1;   // guard against malformed loops
			s = resolve(p, walls, pred, start, done, depth + 1) + walls.get(p).length();
			pred[i] = p;
		}
		start[i] = s;
		done[i] = true;
		return s;
	}
}

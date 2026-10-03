package gl.syn.armacraft.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Splits a wall segment into renderable pieces.
 *
 * <p>Two things cause splits:
 * <ul>
 *   <li>{@code gaps}: [from,to] fractions along the wall that are holes.</li>
 *   <li>A fixed grid of {@code maxPieceLen} along the wall, measured from the wall start. Long walls would
 *   otherwise be one entity whose origin may be outside a viewer's entity tracking / view range while its
 *   far end is right next to them. Using a grid anchored at the wall start keeps keys stable while the
 *   head wall grows: only the last cell changes length, and new cells appear as it crosses boundaries.</li>
 * </ul>
 *
 * Keys are {@code prefix:cell:sub}. All coordinates here are Armagetron grid units.
 */
public final class WallLayout {

	public record Gap(double from, double to) {}

	/** A solid piece of wall from (sx,sy) to (ex,ey) in grid units. */
	public record Piece(String key, double sx, double sy, double ex, double ey) {
		public double length() {
			return Math.hypot(ex - sx, ey - sy);
		}
	}

	/** Pieces shorter than this (grid units) are dropped. */
	public static final double MIN_PIECE = 1e-3;

	private WallLayout() {}

	public static List<Piece> pieces(String prefix, double x1, double y1, double x2, double y2, List<Gap> gaps, double maxPieceLen) {
		List<Piece> out = new ArrayList<>();
		double len = Math.hypot(x2 - x1, y2 - y1);
		if (!(len > MIN_PIECE) || !Double.isFinite(len)) {
			return out;
		}
		if (!(maxPieceLen > 0) || !Double.isFinite(maxPieceLen)) {
			maxPieceLen = len;
		}
		double ux = (x2 - x1) / len;
		double uy = (y2 - y1) / len;
		List<double[]> solid = solidIntervals(gaps); // fractions
		int cells = (int) Math.ceil(len / maxPieceLen - 1e-9);
		cells = Math.max(cells, 1);
		for (int k = 0; k < cells; k++) {
			double c0 = k * maxPieceLen;
			double c1 = Math.min(len, (k + 1) * maxPieceLen);
			int sub = 0;
			for (double[] s : solid) {
				double a = Math.max(c0, s[0] * len);
				double b = Math.min(c1, s[1] * len);
				if (b - a > MIN_PIECE) {
					out.add(new Piece(prefix + ":" + k + ":" + sub, x1 + ux * a, y1 + uy * a, x1 + ux * b, y1 + uy * b));
					sub++;
				}
			}
		}
		return out;
	}

	/** [0,1] minus the union of gaps, as sorted disjoint [from,to] fraction intervals. */
	public static List<double[]> solidIntervals(List<Gap> gaps) {
		List<double[]> holes = new ArrayList<>();
		if (gaps != null) {
			for (Gap g : gaps) {
				if (g == null || !Double.isFinite(g.from) || !Double.isFinite(g.to)) {
					continue;
				}
				double a = clamp01(Math.min(g.from, g.to));
				double b = clamp01(Math.max(g.from, g.to));
				if (b > a) {
					holes.add(new double[] {a, b});
				}
			}
		}
		holes.sort(Comparator.comparingDouble(h -> h[0]));
		List<double[]> solid = new ArrayList<>();
		double cursor = 0;
		for (double[] h : holes) {
			if (h[0] > cursor) {
				solid.add(new double[] {cursor, h[0]});
			}
			cursor = Math.max(cursor, h[1]);
		}
		if (cursor < 1) {
			solid.add(new double[] {cursor, 1});
		}
		return solid;
	}

	/**
	 * Display-entity transformation for one piece, in Minecraft block units, relative to an entity placed at
	 * the piece start. Local +z is rotated onto the piece direction; the box is centred across its thickness.
	 *
	 * @param angle      rotation about +Y in radians (JOML {@code rotationY}): maps (0,0,1) to (sin a, 0, cos a)
	 * @param tx         translation (already rotated, applied after rotation)
	 * @param sx         scale x = thickness, sy = height, sz = length
	 */
	public record Transform(float angle, float tx, float ty, float tz, float sx, float sy, float sz) {}

	/** Direction (mcDx, mcDz) of the piece in Minecraft space and its length in blocks. */
	public static Transform transform(double mcDx, double mcDz, double thickness, double height) {
		double len = Math.hypot(mcDx, mcDz);
		double ux = len > 0 ? mcDx / len : 0;
		double uz = len > 0 ? mcDz / len : 1;
		double angle = Math.atan2(ux, uz);
		// R_y(angle) * (-t/2, 0, 0) = (-t/2 * cos a, 0, t/2 * sin a) = (-t/2 * uz, 0, t/2 * ux)
		double tx = -thickness / 2.0 * uz;
		double tz = thickness / 2.0 * ux;
		return new Transform((float) angle, (float) tx, 0f, (float) tz, (float) thickness, (float) height, (float) len);
	}

	private static double clamp01(double v) {
		return Math.max(0, Math.min(1, v));
	}
}

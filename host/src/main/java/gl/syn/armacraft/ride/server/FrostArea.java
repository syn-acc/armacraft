package gl.syn.armacraft.ride.server;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which block columns the ride frost walker freezes: vanilla Frost Walker's disk around the rider plus a strip
 * ahead along the heading, so a fast bike finds ice already there. Pure geometry; the world side is
 * {@link FrostWalker}.
 *
 * <p>Vanilla 26.3 ({@code Enchantments.FROST_WALKER} -> {@code ReplaceDisk.apply}): the centre block is
 * {@code BlockPos.containing(position).offset(0, -1, 0)}, a column is in the disk when
 * {@code pos.distToCenterSqr(x, pos.y + 0.5, z) < radius^2} (strict, horizontal distance from the block centre to
 * the entity), radius = clamp(3 + (level - 1), 0, 16).
 */
public final class FrostArea {

	/** A block column. */
	public record Col(int x, int z) {}

	private FrostArea() {}

	/** Vanilla disk membership. */
	public static boolean inDisk(int bx, int bz, double x, double z, int radius) {
		double dx = bx + 0.5 - x;
		double dz = bz + 0.5 - z;
		return dx * dx + dz * dz < (double) radius * radius;
	}

	/** Strip length in blocks: speed x seconds, capped; 0 when not moving. */
	public static double lookaheadBlocks(double speedBlocksPerSecond, double seconds, int maxBlocks) {
		if (!(speedBlocksPerSecond > 0) || !(seconds > 0) || maxBlocks <= 0) {
			return 0;
		}
		return Math.min(maxBlocks, speedBlocksPerSecond * seconds);
	}

	/** The layer the bike rides on: vanilla's {@code BlockPos.containing(position).below()}. */
	public static int supportLayer(double feetY) {
		return (int) Math.floor(feetY) - 1;
	}

	/**
	 * Disk around (x, z) plus the strip ahead.
	 *
	 * @param hx        heading step x (-1, 0, 1), cardinal
	 * @param hz        heading step z (-1, 0, 1), cardinal
	 * @param lookahead strip length (blocks), from {@link #lookaheadBlocks}
	 * @param halfWidth strip rows either side of the rider's row
	 */
	public static Set<Col> columns(double x, double z, int hx, int hz, double lookahead, int radius, int halfWidth) {
		Set<Col> out = new LinkedHashSet<>();
		int cx = (int) Math.floor(x);
		int cz = (int) Math.floor(z);
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (inDisk(cx + dx, cz + dz, x, z, radius)) {
					out.add(new Col(cx + dx, cz + dz));
				}
			}
		}
		if (lookahead > 0 && (hx != 0 || hz != 0)) {
			// lateral axis of a cardinal heading
			int px = hz != 0 ? 1 : 0;
			int pz = hx != 0 ? 1 : 0;
			int n = (int) Math.ceil(lookahead);
			for (int i = 1; i <= n; i++) {
				for (int w = -halfWidth; w <= halfWidth; w++) {
					out.add(new Col(cx + hx * i + px * w, cz + hz * i + pz * w));
				}
			}
		}
		return out;
	}
}

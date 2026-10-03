package gl.syn.armacraft.client.ride;

import gl.syn.armacraft.ride.core.Heading;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Measures how a turn key press turns into a corner, in game (enable with {@link #enabled}):
 * <ul>
 *   <li>where the bike was drawn when the key went down (the last drawn frame, carried forward by the bike's speed to
 *   the key event's wall-clock time);</li>
 *   <li>where the physics put the corner, and how long after the key event the physics applied it;</li>
 *   <li>how long after the key event the drawn bike first left the old line (0.02 blocks sideways).</li>
 * </ul>
 * Presses and physics turns are matched in order (presses spaced wider than CYCLE_DELAY).
 */
public final class TurnTimingProbe {

	public static volatile boolean enabled;

	/** One press. Distances in blocks, times in ms; along = how far past the drawn position the corner is. */
	public static final class Rec {
		public final long pressNanos;
		public final double ex;
		public final double ez;
		public final int heading;
		public double along = Double.NaN;
		public double perp = Double.NaN;
		public double physMs = Double.NaN;
		public double visibleMs = Double.NaN;
		public double pressPhase = Double.NaN;

		Rec(long pressNanos, double ex, double ez, int heading, double pressPhase) {
			this.pressNanos = pressNanos;
			this.ex = ex;
			this.ez = ez;
			this.heading = heading;
			this.pressPhase = pressPhase;
		}

		boolean done() {
			return !Double.isNaN(along) && !Double.isNaN(visibleMs);
		}
	}

	private final List<Rec> open = new ArrayList<>();
	private final List<Rec> done = new ArrayList<>();
	private int nextPhysics;

	public synchronized void clear() {
		open.clear();
		done.clear();
		nextPhysics = 0;
	}

	/**
	 * @param framePos   the bike as drawn in the last frame
	 * @param frameNanos when that frame was drawn
	 * @param heading    the drawn heading in that frame
	 * @param speed      blocks/s
	 * @param phase      sub-tick phase of the press (0..1, the frame partial carried forward)
	 */
	public synchronized void onPress(long nanos, Vec3 framePos, long frameNanos, int heading, double speed, double phase) {
		if (!enabled || framePos == null) {
			return;
		}
		double dt = Math.max(0, (nanos - frameNanos) / 1e9);
		double ex = framePos.x + Heading.dx(heading) * speed * dt;
		double ez = framePos.z + Heading.dz(heading) * speed * dt;
		open.add(new Rec(nanos, ex, ez, heading, phase));
	}

	/** The physics applied a press as a turn now, at {@code corner}. */
	public synchronized void onPhysicsTurn(Vec3 corner, long nanos) {
		if (!enabled) {
			return;
		}
		Rec r = null;
		for (Rec x : open) {
			if (Double.isNaN(x.along)) {
				r = x;
				break;
			}
		}
		if (r == null) {
			return;
		}
		int hx = Heading.dx(r.heading);
		int hz = Heading.dz(r.heading);
		double dx = corner.x - r.ex;
		double dz = corner.z - r.ez;
		r.along = dx * hx + dz * hz;
		r.perp = Math.abs(dx * -hz + dz * hx);
		r.physMs = (nanos - r.pressNanos) / 1e6;
		finish(r);
	}

	/** A frame drew the bike at {@code pos}. */
	public synchronized void onFrame(Vec3 pos, long nanos) {
		if (!enabled) {
			return;
		}
		for (Rec r : new ArrayList<>(open)) {
			if (!Double.isNaN(r.visibleMs) || nanos <= r.pressNanos) {
				continue;
			}
			int hx = Heading.dx(r.heading);
			int hz = Heading.dz(r.heading);
			double side = Math.abs((pos.x - r.ex) * -hz + (pos.z - r.ez) * hx);
			if (side > 0.02) {
				r.visibleMs = (nanos - r.pressNanos) / 1e6;
				finish(r);
			}
		}
	}

	private void finish(Rec r) {
		if (r.done()) {
			open.remove(r);
			done.add(r);
		}
	}

	public synchronized List<Rec> results() {
		return new ArrayList<>(done);
	}

	/** "n, corner past the drawn position: mean/max/min, sideways max, physics ms mean/max, visible ms mean/max". */
	public static String summary(String label, List<Rec> rs) {
		if (rs.isEmpty()) {
			return label + ": no samples";
		}
		double aSum = 0;
		double aMax = Double.NEGATIVE_INFINITY;
		double aMin = Double.POSITIVE_INFINITY;
		double absMax = 0;
		double pMax = 0;
		double phSum = 0;
		double phMax = 0;
		double vSum = 0;
		double vMax = 0;
		for (Rec r : rs) {
			aSum += r.along;
			aMax = Math.max(aMax, r.along);
			aMin = Math.min(aMin, r.along);
			absMax = Math.max(absMax, Math.abs(r.along));
			pMax = Math.max(pMax, r.perp);
			phSum += r.physMs;
			phMax = Math.max(phMax, r.physMs);
			vSum += r.visibleMs;
			vMax = Math.max(vMax, r.visibleMs);
		}
		int n = rs.size();
		return String.format(java.util.Locale.ROOT,
			"%s: n=%d  corner vs drawn bike at the press: along mean %+.3f min %+.3f max %+.3f (|max| %.3f) sideways max %.3f blocks;"
				+ "  key->physics turn mean %.1f max %.1f ms;  key->visible mean %.1f max %.1f ms",
			label, n, aSum / n, aMin, aMax, absMax, pMax, phSum / n, phMax, vSum / n, vMax);
	}
}

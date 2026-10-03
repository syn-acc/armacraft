package gl.syn.armacraft.client.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-frame motion of one cycle from ~29 Hz guest data.
 *
 * <p>Authoritative state comes from {@code state} snapshots and exact {@code turn} events. Between them the
 * cycle is dead-reckoned in game time: {@code pos = base + dir * speed * (t - baseT)}, clamped to
 * {@link #maxExtrapolate}. A turn event re-bases immediately at its exact position ("snap"), so corners
 * are never cut. For the local player's own cycle, key presses add predicted turns (respecting CYCLE_DELAY)
 * that are dropped as the guest's turn events confirm them, or when they get too old.
 *
 * <p>{@link #path(double)} returns the polyline from the last snapshot position to the render position;
 * the renderer appends it to the cycle's head wall, so the trail grows and bends every frame too.
 */
public final class CycleTrack {

	/** A turn: position, new heading and game time. */
	public record Turn(double x, double y, double dx, double dy, double t) {}

	public double maxExtrapolate = 0.25;
	/** Drop unconfirmed predictions after this many seconds of game time. */
	public double predictionTimeout = 0.35;

	// authoritative base
	private double bx, by, bdx = 0, bdy = 1, bt = Double.NEGATIVE_INFINITY, speed;
	private boolean valid;
	// last snapshot position (start of the head-wall extension)
	private double sx, sy, st = Double.NEGATIVE_INFINITY;
	private final List<Turn> authTurns = new ArrayList<>();
	private final List<Turn> predicted = new ArrayList<>();
	private double lastTurnT = Double.NEGATIVE_INFINITY;

	public boolean valid() {
		return valid;
	}

	public double speed() {
		return speed;
	}

	public double baseT() {
		return bt;
	}

	public V2 snapshotPos() {
		return new V2(sx, sy);
	}

	public double snapshotT() {
		return st;
	}

	public List<Turn> predictedTurns() {
		return predicted;
	}

	public double lastTurnTime() {
		return lastTurnT;
	}

	/** Reset to a known state (spawn). */
	public void spawn(double x, double y, double dx, double dy, double speed, double t) {
		authTurns.clear();
		predicted.clear();
		valid = false;
		lastTurnT = Double.NEGATIVE_INFINITY;
		bt = Double.NEGATIVE_INFINITY;
		st = Double.NEGATIVE_INFINITY;
		snapshot(x, y, dx, dy, speed, t);
	}

	/** A full-snapshot sample of this cycle at game time {@code t}. */
	public void snapshot(double x, double y, double dx, double dy, double speed, double t) {
		this.speed = speed;
		if (!valid || t >= bt - 1e-9) {
			bx = x;
			by = y;
			if (dx != 0 || dy != 0) {
				bdx = dx;
				bdy = dy;
			}
			bt = t;
			valid = true;
		}
		if (!Double.isFinite(st) || t >= st - 1e-9) {
			sx = x;
			sy = y;
			st = t;
			authTurns.removeIf(turn -> turn.t <= st + 1e-9);
		}
		expirePredictions(t);
	}

	/**
	 * Exact turn reported by the guest. Re-bases immediately if it is newer than the base.
	 *
	 * @return the turn direction for animation: +1 right, -1 left, 0 unknown/straight
	 */
	public int turn(double x, double y, double dx, double dy, double t) {
		int d = turnSign(bdx, bdy, dx, dy);
		if (!valid || t >= bt - 1e-9) {
			bx = x;
			by = y;
			bdx = dx;
			bdy = dy;
			bt = t;
			valid = true;
		}
		if (t > st + 1e-9) {
			authTurns.add(new Turn(x, y, dx, dy, t));
		}
		lastTurnT = Math.max(lastTurnT, t);
		if (!predicted.isEmpty()) {
			predicted.remove(0);   // the oldest prediction is confirmed (or superseded) by this turn
		}
		return d;
	}

	/**
	 * Predict a local turn at render time {@code now}. The guest delays a turn until {@code cycleDelay}
	 * after the previous one, so the prediction does the same.
	 *
	 * @param dir +1 right, -1 left
	 */
	public Turn predict(int dir, double now, double cycleDelay) {
		if (!valid) {
			return null;
		}
		double lastT = lastTurnT;
		if (!predicted.isEmpty()) {
			lastT = Math.max(lastT, predicted.get(predicted.size() - 1).t);
		}
		double t = Math.max(now, lastT + cycleDelay);
		V2 p = positionNoPredictAfter(t);
		V2 h = headingAt(t);
		// right turn = clockwise in Armagetron's x-east/y-north plane (eAxis winding +1)
		double ndx = dir > 0 ? h.y() : -h.y();
		double ndy = dir > 0 ? -h.x() : h.x();
		Turn turn = new Turn(p.x(), p.y(), ndx, ndy, t);
		predicted.add(turn);
		return turn;
	}

	private void expirePredictions(double now) {
		predicted.removeIf(p -> now - p.t > predictionTimeout);
	}

	public void dropPredictions() {
		predicted.clear();
	}

	/**
	 * Base or the latest prediction that has started by time t. Unconfirmed predictions win even over a newer
	 * snapshot: such a snapshot was sent before the guest processed the turn key, and the confirming turn
	 * event (or the timeout) follows within a frame or two.
	 */
	private Turn baseAt(double t, boolean usePredictions) {
		Turn b = new Turn(bx, by, bdx, bdy, bt);
		if (usePredictions) {
			for (Turn p : predicted) {
				if (p.t <= t + 1e-9) {
					b = p;
				}
			}
		}
		return b;
	}

	private V2 extrapolate(Turn b, double t) {
		// cycles do not move before game time 0 (pre-round countdown)
		double dt = Math.max(t, 0) - Math.max(b.t, 0);
		if (!(dt > 0)) {
			dt = 0;
		}
		if (dt > maxExtrapolate) {
			dt = maxExtrapolate;
		}
		return new V2(b.x + b.dx * speed * dt, b.y + b.dy * speed * dt);
	}

	private V2 positionNoPredictAfter(double t) {
		return extrapolate(baseAt(t, true), t);
	}

	/** Render position at game time {@code t} (alive cycles; dead ones should not call this with t > death). */
	public V2 position(double t) {
		return extrapolate(baseAt(t, true), t);
	}

	/** Driving direction at game time {@code t} (unit, axis aligned in standard arenas). */
	public V2 headingAt(double t) {
		Turn b = baseAt(t, true);
		return new V2(b.dx, b.dy);
	}

	/** Position without extrapolation (dead cycles, frozen state). */
	public V2 basePos() {
		return new V2(bx, by);
	}

	/**
	 * Points from the last snapshot position to the render position at {@code t}: snapshot pos, any newer
	 * guest turns, any applicable predicted turns, then the render position. Always at least two points.
	 */
	public List<V2> path(double t) {
		List<V2> pts = new ArrayList<>();
		pts.add(new V2(sx, sy));
		double lastT = st;
		for (Turn a : authTurns) {
			if (a.t > lastT && a.t <= t + 1e-9) {
				pts.add(new V2(a.x, a.y));
				lastT = a.t;
			}
		}
		for (Turn p : predicted) {
			if (p.t > lastT && p.t <= t + 1e-9) {
				pts.add(new V2(p.x, p.y));
				lastT = p.t;
			}
		}
		pts.add(position(t));
		return pts;
	}

	/** +1 for a right (clockwise) turn from (ax,ay) to (bx,by), -1 for left, 0 otherwise. */
	public static int turnSign(double ax, double ay, double bx, double by) {
		double c = ax * by - ay * bx;
		if (c < -1e-6) {
			return 1;
		}
		if (c > 1e-6) {
			return -1;
		}
		return 0;
	}
}

package gl.syn.armacraft.logic;

import gl.syn.armacraft.bridge.Msg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshots arrive at up to 30 Hz but Minecraft samples them at 20 Hz, so the age of the sampled snapshot
 * jitters by up to one guest frame. Moving every alive cycle forward by speed * age (capped) removes that
 * jitter; head walls that end at their cycle's position are stretched by the same amount so they stay
 * attached.
 */
public final class Extrapolate {

	/** Max distance (grid units) between a wall end and its cycle for the wall to count as the head wall. */
	static final double HEAD_EPS = 0.75;

	private Extrapolate() {}

	public static Msg.State forward(Msg.State st, double seconds) {
		if (!(seconds > 0)) {
			return st;
		}
		Map<Long, double[]> shift = new HashMap<>();
		Map<Long, Msg.Cycle> raw = new HashMap<>();
		List<Msg.Cycle> cycles = new ArrayList<>(st.cycles().size());
		for (Msg.Cycle c : st.cycles()) {
			raw.put(c.id(), c);
			if (!c.alive() || !(c.speed() > 0)) {
				cycles.add(c);
				continue;
			}
			double n = Math.hypot(c.dx(), c.dy());
			if (!(n > 0)) {
				cycles.add(c);
				continue;
			}
			double d = c.speed() * seconds;
			double ox = c.dx() / n * d;
			double oy = c.dy() / n * d;
			shift.put(c.id(), new double[] {ox, oy});
			cycles.add(new Msg.Cycle(c.id(), c.name(), c.r(), c.g(), c.b(), c.x() + ox, c.y() + oy, c.dx(), c.dy(), c.speed(),
				c.alive(), c.brake(), c.rubber(), c.bridgeKey()));
		}
		List<Msg.Wall> walls = new ArrayList<>(st.walls().size());
		for (Msg.Wall w : st.walls()) {
			double[] s = shift.get(w.cycle());
			Msg.Cycle c = raw.get(w.cycle());
			if (s != null && c != null && Math.hypot(w.x2() - c.x(), w.y2() - c.y()) <= HEAD_EPS) {
				walls.add(new Msg.Wall(w.id(), w.cycle(), w.x1(), w.y1(), w.x2() + s[0], w.y2() + s[1], w.gaps()));
			} else {
				walls.add(w);
			}
		}
		return new Msg.State(st.t() + seconds, cycles, walls, st.receivedNanos());
	}
}

package gl.syn.armacraft.logic;

import gl.syn.armacraft.bridge.Msg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stable reconciliation keys for walls.
 *
 * <p>The real guest sends a freshly started head wall with {@code id: 0} until Armagetron assigns it a net id
 * (observed live). Keying by id alone makes every cycle's new head wall collide on key 0, so one entity gets
 * dragged between cycles. Rules:
 * <ul>
 *   <li>id &gt; 0: {@code w<id>}</li>
 *   <li>id &lt;= 0: {@code c<cycle>h} (the owning cycle's unnumbered head wall)</li>
 *   <li>a duplicate within one snapshot gets {@code #2}, {@code #3}... so nothing is dropped</li>
 *   <li>when a numbered wall appears with the same owner and start point as last snapshot's unnumbered head
 *       wall, report a rekey so the existing entities are reused instead of respawned</li>
 * </ul>
 */
public final class WallKeys {

	private static final double START_EPS = 1e-3;

	/**
	 * @param keys   one key per input wall, same order
	 * @param rekeys old key prefix to new key prefix (both end with ':'), to apply before reconciling
	 * @param heads  start point of each cycle's unnumbered head wall in this snapshot (feed into the next call)
	 */
	public record Result(List<String> keys, Map<String, String> rekeys, Map<Long, double[]> heads) {}

	private WallKeys() {}

	public static String headKey(long cycle) {
		return "c" + cycle + "h";
	}

	public static Result assign(List<Msg.Wall> walls, Map<Long, double[]> prevHeads) {
		List<String> keys = new ArrayList<>(walls.size());
		Map<String, String> rekeys = new LinkedHashMap<>();
		Map<Long, double[]> heads = new HashMap<>();
		Set<String> used = new HashSet<>();
		for (Msg.Wall w : walls) {
			String base = w.id() > 0 ? "w" + w.id() : headKey(w.cycle());
			String key = base;
			for (int n = 2; !used.add(key); n++) {
				key = base + "#" + n;
			}
			keys.add(key);
			if (w.id() <= 0) {
				heads.putIfAbsent(w.cycle(), new double[] {w.x1(), w.y1()});
			} else {
				double[] prev = prevHeads.get(w.cycle());
				if (prev != null && Math.abs(prev[0] - w.x1()) < START_EPS && Math.abs(prev[1] - w.y1()) < START_EPS) {
					rekeys.put(headKey(w.cycle()) + ":", key + ":");
				}
			}
		}
		return new Result(keys, rekeys, heads);
	}
}

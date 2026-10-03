package gl.syn.armacraft.logic;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Decides when to play a death effect so each death plays exactly once, whether the guest reports it by a
 * {@code death} event, by {@code alive:false} in a snapshot, or both in either order. Snapshots can lag the
 * event (seen live: event, then one more alive snapshot, then dead), so only a dead-to-alive transition or a
 * spawn event re-arms a cycle.
 */
public final class DeathTracker {

	private final Map<Long, Boolean> lastAlive = new HashMap<>();
	private final Set<Long> exploded = new HashSet<>();

	/** @return true if the effect should play now */
	public boolean onDeathEvent(long id) {
		return exploded.add(id);
	}

	/** @return true if this snapshot shows a death not yet played */
	public boolean onSnapshot(long id, boolean alive) {
		Boolean was = lastAlive.put(id, alive);
		if (alive) {
			if (Boolean.FALSE.equals(was)) {
				exploded.remove(id);
			}
			return false;
		}
		return Boolean.TRUE.equals(was) && exploded.add(id);
	}

	public void onSpawn(long id) {
		exploded.remove(id);
	}

	/** Forget cycles that are no longer in the snapshot. */
	public void retain(Set<Long> present) {
		lastAlive.keySet().retainAll(present);
	}

	public void reset() {
		lastAlive.clear();
		exploded.clear();
	}
}

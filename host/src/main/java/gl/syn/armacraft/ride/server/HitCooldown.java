package gl.syn.armacraft.ride.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-mob hit cooldown in server ticks (shared by all riders). Server thread only. */
public final class HitCooldown {

	private final Map<UUID, Long> lastHit = new HashMap<>();

	/** @return true (and records the hit) if {@code id} was not hit within the last {@code cooldown} ticks */
	public boolean tryHit(UUID id, long now, int cooldown) {
		Long last = lastHit.get(id);
		if (last != null && now - last < cooldown) {
			return false;
		}
		lastHit.put(id, now);
		return true;
	}

	public void prune(long now, int cooldown) {
		lastHit.values().removeIf(last -> now - last >= cooldown);
	}

	public int size() {
		return lastHit.size();
	}

	public void reset() {
		lastHit.clear();
	}
}

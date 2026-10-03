package gl.syn.armacraft.ride.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fall-damage grace after a ride ends: a DISMOUNT or CRASH mid-air starts a window (in server ticks) in which fall
 * damage is cancelled. Riding itself is checked by the caller. Server thread only.
 */
public final class FallGuard {

	private final Map<UUID, Long> graceUntil = new HashMap<>();

	/** Grace covers ticks {@code [now, now + ticks)}. */
	public void startGrace(UUID id, long now, int ticks) {
		if (ticks > 0) {
			graceUntil.put(id, now + ticks);
		} else {
			graceUntil.remove(id);
		}
	}

	public void clear(UUID id) {
		graceUntil.remove(id);
	}

	public boolean inGrace(UUID id, long now) {
		Long until = graceUntil.get(id);
		if (until == null) {
			return false;
		}
		if (now >= until) {
			graceUntil.remove(id);
			return false;
		}
		return true;
	}

	public void prune(long now) {
		graceUntil.values().removeIf(until -> now >= until);
	}

	public int size() {
		return graceUntil.size();
	}

	public void reset() {
		graceUntil.clear();
	}

	public static boolean cancel(boolean enabled, boolean riding, boolean inGrace) {
		return enabled && (riding || inGrace);
	}
}

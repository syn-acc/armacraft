package gl.syn.armacraft.client.core;

/**
 * Maps the local monotonic clock onto the guest's game time {@code t}.
 *
 * <p>Every timestamped message gives a sample {@code t - localReceiveTime}. Network and scheduling delay
 * only ever make a sample smaller, so the estimate tracks the running maximum (the least-delayed sample),
 * decaying slowly so clock drift or a guest stall cannot pin it too high. A sample far below the estimate
 * means game time jumped back (new round: Armagetron restarts {@code t} at a negative countdown), which
 * resets the estimate.
 */
public final class GameClock {

	/** How fast the estimate may fall towards fresh samples (seconds of game time per local second). */
	public static final double DECAY = 0.02;
	/** A sample this far below the estimate is a time jump, not jitter. */
	public static final double JUMP = 0.75;

	private double offset;
	private double lastLocal;
	private boolean valid;

	public void reset() {
		valid = false;
	}

	public boolean valid() {
		return valid;
	}

	/** Feed a message timestamp {@code t} that was received at local time {@code local} (seconds). */
	public void sample(double t, double local) {
		if (!Double.isFinite(t)) {
			return;
		}
		double s = t - local;
		if (!valid) {
			offset = s;
			lastLocal = local;
			valid = true;
			return;
		}
		double dl = Math.max(0, local - lastLocal);
		lastLocal = local;
		double decayed = offset - DECAY * dl;
		if (s < offset - JUMP) {
			offset = s;          // time went backwards: new round / restarted guest
		} else {
			offset = Math.max(s, decayed);
		}
	}

	/** Estimated guest game time at local time {@code local}. */
	public double gameTime(double local) {
		return valid ? local + offset : Double.NaN;
	}

	public double offset() {
		return offset;
	}
}

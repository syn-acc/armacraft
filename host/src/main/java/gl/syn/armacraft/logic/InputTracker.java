package gl.syn.armacraft.logic;

import java.util.ArrayList;
import java.util.List;

/**
 * Edge detection for the vanilla client's movement input packet.
 *
 * <p>A (left) and D (right) produce one turn per press (rising edge). Jump or sprint held maps to brake;
 * brake is a hold state, so both edges are reported.
 */
public final class InputTracker {

	public sealed interface Action permits Turn, Brake {}

	public record Turn(String dir) implements Action {}

	public record Brake(boolean on) implements Action {}

	private boolean left;
	private boolean right;
	private boolean brake;

	public List<Action> update(boolean curLeft, boolean curRight, boolean jump, boolean sprint) {
		List<Action> out = new ArrayList<>(2);
		if (curLeft && !left) {
			out.add(new Turn("left"));
		}
		if (curRight && !right) {
			out.add(new Turn("right"));
		}
		boolean curBrake = jump || sprint;
		if (curBrake != brake) {
			out.add(new Brake(curBrake));
		}
		left = curLeft;
		right = curRight;
		brake = curBrake;
		return out;
	}

	public boolean braking() {
		return brake;
	}

	public void reset() {
		left = right = brake = false;
	}
}

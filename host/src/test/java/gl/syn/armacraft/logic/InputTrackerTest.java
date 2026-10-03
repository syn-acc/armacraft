package gl.syn.armacraft.logic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InputTrackerTest {

	@Test
	void turnsOnRisingEdgeOnly() {
		InputTracker t = new InputTracker();
		assertEquals(List.of(new InputTracker.Turn("left")), t.update(true, false, false, false));
		assertEquals(List.of(), t.update(true, false, false, false)); // held
		assertEquals(List.of(), t.update(false, false, false, false)); // release
		assertEquals(List.of(new InputTracker.Turn("left")), t.update(true, false, false, false)); // press again
		assertEquals(List.of(new InputTracker.Turn("right")), t.update(true, true, false, false));
	}

	@Test
	void bothKeysSamePacket() {
		InputTracker t = new InputTracker();
		assertEquals(List.of(new InputTracker.Turn("left"), new InputTracker.Turn("right")), t.update(true, true, false, false));
	}

	@Test
	void brakeIsHoldStateFromJumpOrSprint() {
		InputTracker t = new InputTracker();
		assertEquals(List.of(new InputTracker.Brake(true)), t.update(false, false, true, false));
		assertEquals(List.of(), t.update(false, false, true, true));
		assertEquals(List.of(), t.update(false, false, false, true));
		assertEquals(List.of(new InputTracker.Brake(false)), t.update(false, false, false, false));
	}

	@Test
	void turnAndBrakeTogether() {
		InputTracker t = new InputTracker();
		assertEquals(List.of(new InputTracker.Turn("right"), new InputTracker.Brake(true)), t.update(false, true, false, true));
	}
}

package gl.syn.armacraft.client.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CycleTrackTest {

	@Test
	void extrapolatesAlongDirectionAndClamps() {
		CycleTrack t = new CycleTrack();
		t.maxExtrapolate = 0.25;
		t.snapshot(10, 5, 0, 1, 30, 2.0);
		V2 p = t.position(2.1);
		assertEquals(10, p.x(), 1e-9);
		assertEquals(5 + 3.0, p.y(), 1e-9);
		// clamped after maxExtrapolate seconds
		V2 far = t.position(5.0);
		assertEquals(5 + 30 * 0.25, far.y(), 1e-9);
		// never extrapolates backwards
		assertEquals(5, t.position(1.0).y(), 1e-9);
	}

	@Test
	void doesNotMoveBeforeGameTimeZero() {
		CycleTrack t = new CycleTrack();
		t.snapshot(0, 0, 1, 0, 20, -3.0);
		assertEquals(0, t.position(-2.9).x(), 1e-9);
		assertEquals(20 * 0.1, t.position(0.1).x(), 1e-9);
	}

	@Test
	void turnEventSnapsToExactCorner() {
		CycleTrack t = new CycleTrack();
		t.snapshot(0, 0, 1, 0, 10, 1.0);
		int d = t.turn(2.0, 0, 0, 1, 1.2);  // left turn (east -> north)
		assertEquals(-1, d);
		V2 p = t.position(1.3);
		assertEquals(2.0, p.x(), 1e-9);
		assertEquals(1.0, p.y(), 1e-9);
		List<V2> path = t.path(1.3);
		assertEquals(3, path.size());
		assertEquals(new V2(0, 0), path.get(0));
		assertEquals(new V2(2, 0), path.get(1));
		// the next snapshot supersedes the turn point
		t.snapshot(2.0, 1.5, 0, 1, 10, 1.35);
		assertEquals(2, t.path(1.35).size());
	}

	@Test
	void olderSnapshotDoesNotRewindATurn() {
		CycleTrack t = new CycleTrack();
		t.snapshot(0, 0, 1, 0, 10, 1.0);
		t.turn(2.0, 0, 0, -1, 1.2);   // right turn (east -> south)
		t.snapshot(1.9, 0, 1, 0, 10, 1.19);
		V2 p = t.position(1.25);
		assertEquals(2.0, p.x(), 1e-9);
		assertEquals(-0.5, p.y(), 1e-9);
	}

	@Test
	void turnSignConvention() {
		assertEquals(1, CycleTrack.turnSign(1, 0, 0, -1));   // east -> south is right (clockwise)
		assertEquals(-1, CycleTrack.turnSign(1, 0, 0, 1));   // east -> north is left
		assertEquals(0, CycleTrack.turnSign(1, 0, 1, 0));
	}

	@Test
	void predictedTurnAppliesImmediatelyAndRespectsCycleDelay() {
		CycleTrack t = new CycleTrack();
		t.snapshot(0, 0, 1, 0, 10, 1.0);
		CycleTrack.Turn p1 = t.predict(-1, 1.05, 0.1);   // left
		assertEquals(1.05, p1.t(), 1e-9);
		assertEquals(0.5, p1.x(), 1e-9);
		assertEquals(0, p1.dx(), 1e-9);
		assertEquals(1, p1.dy(), 1e-9);
		// second press 20 ms later is delayed to 0.1 s after the first, like the guest's CYCLE_DELAY
		CycleTrack.Turn p2 = t.predict(-1, 1.07, 0.1);
		assertEquals(1.15, p2.t(), 1e-9);
		assertEquals(0.5, p2.x(), 1e-9);
		assertEquals(1.0, p2.y(), 1e-9);
		assertEquals(-1, p2.dx(), 1e-9);
		// before the delayed turn the cycle still heads north
		V2 mid = t.position(1.10);
		assertEquals(0.5, mid.x(), 1e-9);
		assertEquals(0.5, mid.y(), 1e-9);
		// the guest confirms the first turn: prediction consumed, base snaps to the exact corner
		t.turn(0.52, 0, 0, 1, 1.052);
		assertEquals(1, t.predictedTurns().size());
		// unconfirmed predictions expire
		t.snapshot(0.52, 3, 0, 1, 10, 1.6);
		assertTrue(t.predictedTurns().isEmpty());
	}
}

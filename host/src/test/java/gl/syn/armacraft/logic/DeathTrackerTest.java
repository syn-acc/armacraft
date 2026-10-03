package gl.syn.armacraft.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathTrackerTest {

	@Test
	void eventThenLaggingAliveSnapshotThenDeadPlaysOnce() {
		// sequence observed against the real guest
		DeathTracker d = new DeathTracker();
		assertFalse(d.onSnapshot(7, true));
		assertTrue(d.onDeathEvent(7));
		assertFalse(d.onSnapshot(7, true));   // snapshot still says alive
		assertFalse(d.onSnapshot(7, false));  // now dead: already played
		assertFalse(d.onSnapshot(7, false));
	}

	@Test
	void snapshotOnlyGuestStillGetsTheEffect() {
		DeathTracker d = new DeathTracker();
		assertFalse(d.onSnapshot(7, true));
		assertTrue(d.onSnapshot(7, false));
		assertFalse(d.onDeathEvent(7)); // late event: no second effect
	}

	@Test
	void respawnRearms() {
		DeathTracker d = new DeathTracker();
		d.onSnapshot(7, true);
		assertTrue(d.onDeathEvent(7));
		d.onSnapshot(7, false);
		assertFalse(d.onSnapshot(7, true)); // dead -> alive: respawned
		assertTrue(d.onDeathEvent(7));
	}

	@Test
	void spawnEventRearmsAndFirstSightingOfDeadCycleIsSilent() {
		DeathTracker d = new DeathTracker();
		assertFalse(d.onSnapshot(9, false)); // joined mid-round, already dead
		assertTrue(d.onDeathEvent(9));
		d.onSpawn(9);
		assertTrue(d.onDeathEvent(9));
	}
}

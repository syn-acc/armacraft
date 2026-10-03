package gl.syn.armacraft.ride.server;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallGuardTest {

	@Test
	void graceCoversExactlyTheConfiguredTicks() {
		FallGuard g = new FallGuard();
		UUID id = UUID.randomUUID();
		g.startGrace(id, 1000, 40);
		assertTrue(g.inGrace(id, 1000));
		assertTrue(g.inGrace(id, 1039));
		assertFalse(g.inGrace(id, 1040));
		assertFalse(g.inGrace(id, 1039), "expired entries are dropped");
	}

	@Test
	void zeroGraceAndClear() {
		FallGuard g = new FallGuard();
		UUID id = UUID.randomUUID();
		g.startGrace(id, 0, 0);
		assertFalse(g.inGrace(id, 0));
		g.startGrace(id, 0, 40);
		g.clear(id);
		assertFalse(g.inGrace(id, 1));
	}

	@Test
	void cancelRule() {
		assertTrue(FallGuard.cancel(true, true, false));
		assertTrue(FallGuard.cancel(true, false, true));
		assertFalse(FallGuard.cancel(true, false, false));
		assertFalse(FallGuard.cancel(false, true, true));
	}

	@Test
	void prune() {
		FallGuard g = new FallGuard();
		g.startGrace(UUID.randomUUID(), 0, 10);
		g.startGrace(UUID.randomUUID(), 0, 100);
		g.prune(50);
		assertEquals(1, g.size());
	}

	@Test
	void configGraceTicks() {
		RideServerConfig c = new RideServerConfig();
		assertEquals(40, c.fallGraceTicks());
		c.fallGraceSeconds = 0.5;
		assertEquals(10, c.fallGraceTicks());
	}
}

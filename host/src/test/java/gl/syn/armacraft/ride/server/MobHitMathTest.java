package gl.syn.armacraft.ride.server;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobHitMathTest {

	@Test
	void damageScalesWithSpeedAboveMinimumAndCaps() {
		assertEquals(0, MobHitMath.damage(3.9, 4, 0.5, 20));
		assertEquals(2.0, MobHitMath.damage(4, 4, 0.5, 20), 1e-9);
		assertEquals(4.0, MobHitMath.damage(8, 4, 0.5, 20), 1e-9);
		assertEquals(9.0, MobHitMath.damage(18, 4, 0.5, 20), 1e-9);
		assertEquals(20.0, MobHitMath.damage(60, 4, 0.5, 20), 1e-9);
		assertEquals(0, MobHitMath.damage(Double.NaN, 4, 0.5, 20));
		assertEquals(0, MobHitMath.damage(0, 0, 0.5, 20));
	}

	@Test
	void damageIsMonotonicInSpeed() {
		double last = 0;
		for (double v = 0; v <= 50; v += 0.5) {
			double d = MobHitMath.damage(v, 4, 0.5, 20);
			assertTrue(d >= last);
			last = d;
		}
	}

	@Test
	void knockbackScalesAndCaps() {
		assertEquals(0.4, MobHitMath.knockback(0, 0.4, 0.05, 2.5), 1e-9);
		assertEquals(0.8, MobHitMath.knockback(8, 0.4, 0.05, 2.5), 1e-9);
		assertEquals(1.3, MobHitMath.knockback(18, 0.4, 0.05, 2.5), 1e-9);
		assertEquals(2.5, MobHitMath.knockback(100, 0.4, 0.05, 2.5), 1e-9);
		assertEquals(0.4, MobHitMath.knockback(-10, 0.4, 0.05, 2.5), 1e-9);
	}

	@Test
	void reachIsOneTickPlusExtra() {
		assertEquals(1.4, MobHitMath.reach(8, 1.0), 1e-9);
		assertEquals(2.0, MobHitMath.reach(20, 1.0), 1e-9);
		assertEquals(1.0, MobHitMath.reach(-3, 1.0), 1e-9);
	}

	@Test
	void aheadBoxPerHeading() {
		double[] bb = {9.7, 64, 4.7, 10.3, 65.8, 5.3}; // player 0.6 x 1.8 at (10, 64, 5)
		assertArrayEquals(new double[] {10.0, 64, 4.5, 11.8, 65.8, 5.5}, MobHitMath.aheadBox(bb, 1, 0, 1.5, 0.2), 1e-9);
		assertArrayEquals(new double[] {8.2, 64, 4.5, 10.0, 65.8, 5.5}, MobHitMath.aheadBox(bb, -1, 0, 1.5, 0.2), 1e-9);
		assertArrayEquals(new double[] {9.5, 64, 5.0, 10.5, 65.8, 6.8}, MobHitMath.aheadBox(bb, 0, 1, 1.5, 0.2), 1e-9);
		assertArrayEquals(new double[] {9.5, 64, 3.2, 10.5, 65.8, 5.0}, MobHitMath.aheadBox(bb, 0, -1, 1.5, 0.2), 1e-9);
	}

	@Test
	void cooldownAllowsOneHitPerWindowPerMob() {
		HitCooldown c = new HitCooldown();
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		assertTrue(c.tryHit(a, 100, 10));
		for (long t = 100; t < 110; t++) {
			assertFalse(c.tryHit(a, t, 10), "tick " + t);
		}
		assertTrue(c.tryHit(b, 105, 10), "other mob unaffected");
		assertTrue(c.tryHit(a, 110, 10));
		assertFalse(c.tryHit(a, 111, 10));
		// hits per 100 ticks of continuous contact = 10
		HitCooldown d = new HitCooldown();
		int hits = 0;
		for (long t = 0; t < 100; t++) {
			if (d.tryHit(a, t, 10)) {
				hits++;
			}
		}
		assertEquals(10, hits);
	}

	@Test
	void cooldownPruneDropsExpiredOnly() {
		HitCooldown c = new HitCooldown();
		c.tryHit(UUID.randomUUID(), 0, 10);
		c.tryHit(UUID.randomUUID(), 95, 10);
		c.prune(100, 10);
		assertEquals(1, c.size());
	}
}

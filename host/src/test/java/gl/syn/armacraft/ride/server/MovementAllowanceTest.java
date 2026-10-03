package gl.syn.armacraft.ride.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementAllowanceTest {

	private static final float VANILLA_SPEED = 100.0F;
	private static final double VANILLA_WRONG = 0.0625;

	/** Vanilla's comparison with the mixin's metersPerTick. */
	private static boolean tooQuickly(double movedDist, int raw, float metersPerTick) {
		return movedDist > metersPerTick * MovementAllowance.vanillaDeltaPackets(raw);
	}

	private static float ride(int raw) {
		return MovementAllowance.tooQuicklyMetersPerTick(VANILLA_SPEED, raw, 40, 1.25, 10);
	}

	@Test
	void vanillaDeltaPacketsResetAboveFive() {
		assertEquals(1, MovementAllowance.vanillaDeltaPackets(1));
		assertEquals(5, MovementAllowance.vanillaDeltaPackets(5));
		assertEquals(1, MovementAllowance.vanillaDeltaPackets(6));
		assertEquals(1, MovementAllowance.vanillaDeltaPackets(0));
	}

	@Test
	void vanillaRubberbandsAMaxSpeedBikeAfterSixPacketBurst() {
		double perTick = 36.0 / 20; // CYCLE_SPEED_MAX 2 x 18 blocks/s
		assertFalse(tooQuickly(Math.pow(perTick * 5, 2), 5, VANILLA_SPEED));
		assertTrue(tooQuickly(Math.pow(perTick * 6, 2), 6, VANILLA_SPEED));
	}

	@Test
	void rideAllowsCatchUpBurstsUpToTheCapAtMaxSpeed() {
		for (int raw = 1; raw <= 10; raw++) {
			// a bike at the 40 b/s bound catching up raw ticks in one server tick
			double honest = Math.pow(40.0 / 20 * raw, 2);
			assertFalse(tooQuickly(honest, raw, ride(raw)), "honest burst of " + raw);
			double total = ride(raw) * (double) MovementAllowance.vanillaDeltaPackets(raw);
			assertFalse(tooQuickly(total - 1e-2, raw, ride(raw)));
			assertTrue(tooQuickly(total + 1e-2, raw, ride(raw)));
		}
	}

	@Test
	void rideIsNeverLooserThanItsBoundAndNeverTighterThanVanilla() {
		for (int raw = 1; raw <= 40; raw++) {
			int dp = MovementAllowance.vanillaDeltaPackets(raw);
			double total = ride(raw) * (double) dp;
			assertTrue(total >= VANILLA_SPEED * dp - 1e-3, "never tighter than vanilla at " + raw);
			double cap = Math.max(VANILLA_SPEED * dp, Math.pow(2.5 * Math.min(raw, 10), 2));
			assertEquals(cap, total, 1e-2, "allowance at " + raw);
		}
		// beyond the burst cap the allowance stops growing: a 12-packet 2.6-block spam is still rejected
		assertTrue(tooQuickly(Math.pow(12 * 2.6, 2), 12, ride(12)));
	}

	@Test
	void singlePacketKeepsVanillaThreshold() {
		assertEquals(VANILLA_SPEED, ride(1), 1e-4);
	}

	@Test
	void movedWronglyScalesWithSpeedAndCaps() {
		assertEquals(VANILLA_WRONG, MovementAllowance.movedWronglyThresholdSqr(VANILLA_WRONG, 0, 40, 1.25, 1.5), 1e-12);
		assertEquals(VANILLA_WRONG, MovementAllowance.movedWronglyThresholdSqr(VANILLA_WRONG, 3, 40, 1.25, 1.5), 1e-12); // 0.1875 < 0.25
		assertEquals(Math.pow(1.25, 2), MovementAllowance.movedWronglyThresholdSqr(VANILLA_WRONG, 20, 40, 1.25, 1.5), 1e-12);
		assertEquals(Math.pow(1.125, 2), MovementAllowance.movedWronglyThresholdSqr(VANILLA_WRONG, 18, 40, 1.25, 1.5), 1e-12);
		assertEquals(Math.pow(1.5, 2), MovementAllowance.movedWronglyThresholdSqr(VANILLA_WRONG, 36, 40, 1.25, 1.5), 1e-12);
		// reported speed is clamped to the bound; garbage is zero
		assertEquals(Math.pow(1.5, 2), MovementAllowance.movedWronglyThresholdSqr(VANILLA_WRONG, 1e9, 40, 1.25, 1.5), 1e-12);
		assertEquals(VANILLA_WRONG, MovementAllowance.movedWronglyThresholdSqr(VANILLA_WRONG, Double.NaN, 40, 1.25, 1.5), 1e-12);
		assertEquals(VANILLA_WRONG, MovementAllowance.movedWronglyThresholdSqr(VANILLA_WRONG, -20, 40, 1.25, 1.5), 1e-12);
	}

	@Test
	void movedWronglyCapCannotSkipAFullBlock() {
		// passing a 1-block wall needs the target 1 block + bike width (0.6) past the near face
		double maxTol = Math.sqrt(MovementAllowance.movedWronglyThresholdSqr(VANILLA_WRONG, 1e9, 1e9, 100, 1.5));
		assertTrue(maxTol < 1.6);
	}

	@Test
	void floatingLimitMultipliedNotDisabled() {
		assertEquals(160, MovementAllowance.maxFlyingTicks(80, 2.0));
		assertEquals(80, MovementAllowance.maxFlyingTicks(80, 1.0));
		assertEquals(80, MovementAllowance.maxFlyingTicks(80, 0.5));
		assertEquals(Integer.MAX_VALUE, MovementAllowance.maxFlyingTicks(Integer.MAX_VALUE, 2.0));
		assertEquals(Integer.MAX_VALUE, MovementAllowance.maxFlyingTicks(Integer.MAX_VALUE - 1, 2.0));
	}
}

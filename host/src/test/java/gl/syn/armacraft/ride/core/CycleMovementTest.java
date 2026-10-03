package gl.syn.armacraft.ride.core;

import gl.syn.armacraft.ride.RideConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CycleMovementTest {

	static final double DT = 0.05;

	/** Stock Armagetron physics with CYCLE_RUBBER 15 (9 blocks), boost off: the reference for these tests. */
	static CycleParams params() {
		RideConfig c = new RideConfig();
		c.preset = "armagetron";
		c.overrides.put("CYCLE_RUBBER", 15.0);
		c.overrides.put("CYCLE_ACCEL_RIM", 1.0);
		c.boost.enabled = false;
		c.sanitize();
		return c.params();
	}

	/** Runs ticks against a fixed wall {@code wallAt} blocks ahead of the start (front face). */
	static double drive(CycleMovement m, int ticks, double wallAt, double[] moved) {
		for (int i = 0; i < ticks && !m.crashed(); i++) {
			double space = wallAt - moved[0];
			moved[0] += m.tick(DT, Surroundings.ahead(space)).distance();
		}
		return moved[0];
	}

	@Test
	void defaultsConvertArmagetronUnitsToBlocks() {
		CycleParams p = params();
		assertEquals(18.0, p.speed, 1e-9, "CYCLE_SPEED 30 u/s * 0.6");
		assertEquals(12.0, p.startSpeed, 1e-9);
		assertEquals(18.0, p.brake, 1e-9, "CYCLE_BRAKE 30 * 0.6");
		assertEquals(9.0, p.rubber, 1e-9, "CYCLE_RUBBER 15 * 0.6");
		assertEquals(3.6, p.accel, 1e-9, "CYCLE_ACCEL 10 * 0.6^2");
		assertEquals(1.2, p.accelOffset, 1e-9);
		assertEquals(3.6, p.wallNear, 1e-9);
		assertEquals(0.1, p.delay, 1e-12, "CYCLE_DELAY is time: unscaled");
		assertEquals(10.0, p.rubberTime, 1e-12);
		assertEquals(40.0, p.rubberSpeed, 1e-12, "CYCLE_RUBBER_SPEED is a rate: unscaled");
		assertEquals(36.0, p.maxSpeed(), 1e-9);
	}

	@Test
	void acceleratesFromStartSpeedToCycleSpeed() {
		CycleMovement m = new CycleMovement(params(), Heading.EAST);
		assertEquals(12.0, m.speed(), 1e-9);
		for (int i = 0; i < 20; i++) {
			m.tick(DT, Surroundings.OPEN);
		}
		// CYCLE_SPEED_DECAY_BELOW 5/s: after 1 s the gap 6 b/s has shrunk by about e^-5
		assertEquals(18.0, m.speed(), 0.1);
		assertTrue(m.speed() <= 18.0 + 1e-6, "approaches from below without overshoot: " + m.speed());
	}

	@Test
	void brakeSlowsAndDepletesThenRefills() {
		CycleMovement m = new CycleMovement(params(), Heading.EAST);
		m.setSpeed(18);
		m.setBraking(true);
		for (int i = 0; i < 6; i++) { // 0.3 s
			m.tick(DT, Surroundings.OPEN);
		}
		assertTrue(m.speed() < 16.0, "braking 0.3 s at 18 b/s^2 against decay should slow down: " + m.speed());
		assertEquals(0.7, m.brakeReservoir(), 0.02, "CYCLE_BRAKE_DEPLETE 1/s");
		for (int i = 0; i < 20; i++) { // 1 s more: reservoir runs dry at 1.0 s total
			m.tick(DT, Surroundings.OPEN);
		}
		assertEquals(0.0, m.brakeReservoir(), 1e-9);
		double slow = m.speed();
		assertTrue(slow >= 18 * 0.25 - 1e-6, "never below CYCLE_SPEED_MIN: " + slow);
		for (int i = 0; i < 10; i++) { // empty reservoir: no more braking, speed recovers
			m.tick(DT, Surroundings.OPEN);
		}
		assertTrue(m.speed() > slow, "empty brake has no effect any more");
		m.setBraking(false);
		for (int i = 0; i < 20; i++) {
			m.tick(DT, Surroundings.OPEN);
		}
		assertEquals(0.1, m.brakeReservoir(), 0.01, "CYCLE_BRAKE_REFILL 0.1/s");
	}

	@Test
	void turnDelayQueuesSecondTurnAndAppliesSpeedFactor() {
		CycleMovement m = new CycleMovement(params(), Heading.NORTH);
		m.setSpeed(18);
		m.tick(DT, Surroundings.OPEN);
		double before = m.speed();
		assertTrue(m.requestTurn(+1), "first turn is immediate");
		assertEquals(Heading.EAST, m.heading());
		assertEquals(before * 0.95, m.speed(), 1e-6, "CYCLE_TURN_SPEED_FACTOR");
		assertFalse(m.requestTurn(+1), "second turn within CYCLE_DELAY is queued");
		assertEquals(Heading.EAST, m.heading());
		assertEquals(1, m.pendingTurns());
		assertFalse(m.processPendingTurns());
		m.tick(DT, Surroundings.OPEN); // t = +0.05
		assertFalse(m.processPendingTurns(), "still within 0.1 s");
		m.tick(DT, Surroundings.OPEN); // t = +0.10
		assertTrue(m.processPendingTurns(), "queued turn runs once CYCLE_DELAY has passed");
		assertEquals(Heading.SOUTH, m.heading());
		assertEquals(0, m.pendingTurns());
	}

	@Test
	void turnMemoryCancelsOppositeTurnsBeyondIt() {
		CycleParams p = params();
		p.turnMemory = 1;
		CycleMovement m = new CycleMovement(p, Heading.NORTH);
		m.requestTurn(+1);  // immediate
		m.requestTurn(+1);  // queued (size 0 <= 1)
		m.requestTurn(+1);  // queued (size 1 <= 1)
		assertEquals(2, m.pendingTurns());
		m.requestTurn(-1);  // size 2 > 1 and opposite: cancels the last one
		assertEquals(1, m.pendingTurns());
	}

	@Test
	void rubberStopsTheBikeBeforeTheWallThenRunsOutAndCrashes() {
		CycleMovement m = new CycleMovement(params(), Heading.EAST);
		m.setSpeed(18);
		double[] moved = {0};
		double wall = 5.0;
		drive(m, 10, wall, moved); // 0.5 s: reaches the wall after ~0.28 s
		assertFalse(m.crashed());
		assertTrue(moved[0] < wall, "never passes the wall: " + moved[0]);
		assertTrue(moved[0] > wall - 0.05, "ends up pressed against it: " + moved[0]);
		double left = m.rubberLeft();
		assertTrue(left < 0.9 && left > 0, "rubber dropped while pressing: " + left);
		int ticks = 0;
		while (!m.crashed() && ticks < 100) {
			drive(m, 1, wall, moved);
			ticks++;
		}
		assertTrue(m.crashed(), "crashes once rubber is used up");
		assertEquals(0.0, m.rubberLeft(), 1e-9);
		// 9 blocks of rubber at 18 b/s: about half a second of pressing in total (0.2 s already spent)
		assertTrue(ticks >= 3 && ticks <= 12, "ticks pressing before the crash: " + ticks);
		assertTrue(moved[0] <= wall + 1e-6, "crash at the wall, not through it: " + moved[0]);
	}

	@Test
	void turningAwaySavesYouAndRubberRegenerates() {
		CycleMovement m = new CycleMovement(params(), Heading.EAST);
		m.setSpeed(18);
		double[] moved = {0};
		drive(m, 12, 4.0, moved);
		assertFalse(m.crashed());
		double used = m.rubberUsed();
		assertTrue(used > 1.0, "rubber was spent: " + used);
		assertTrue(m.requestTurn(-1));
		for (int i = 0; i < 20; i++) { // 1 s in the open
			m.tick(DT, Surroundings.OPEN);
		}
		assertFalse(m.crashed());
		// CYCLE_RUBBER_TIME 10 s: rubber /= (1 + ts/10) every substep -> about e^-0.1 per second
		assertEquals(used * Math.exp(-0.1), m.rubberUsed(), used * 0.01);
		assertTrue(m.rubberLeft() > 1 - used / 9.0, "rubber left went back up");
	}

	@Test
	void wallAccelerationFollowsArmagetronFormula() {
		CycleParams p = params();
		CycleMovement open = new CycleMovement(p, Heading.EAST);
		CycleMovement grind = new CycleMovement(p, Heading.EAST);
		open.setSpeed(18);
		grind.setSpeed(18);
		Surroundings side = Surroundings.OPEN.withSides(new Surroundings.Side(0.2, Surroundings.Kind.TERRAIN), Surroundings.Side.NONE);
		for (int i = 0; i < 20; i++) {
			open.tick(DT, Surroundings.OPEN);
			grind.tick(DT, side);
		}
		assertEquals(18.0, open.speed(), 1e-6);
		// equilibrium: (base - v) * decayAbove + accel*(1/(h+off) - 1/(near+off)) = 0, approached at rate decayAbove
		double wallAccel = p.accel * (1 / (0.2 + p.accelOffset) - 1 / (p.wallNear + p.accelOffset));
		double expected = 18 + wallAccel / p.decayAbove * (1 - Math.exp(-p.decayAbove * 1.0));
		assertEquals(expected, grind.speed(), 0.05, "grinding a wall at 0.2 blocks speeds up");
		assertTrue(grind.speed() > 18.5);
	}

	@Test
	void slingshotBetweenOwnWallAndTerrainAddsBothSides() {
		CycleParams p = params();
		CycleMovement one = new CycleMovement(p, Heading.EAST);
		CycleMovement two = new CycleMovement(p, Heading.EAST);
		one.setSpeed(18);
		two.setSpeed(18);
		Surroundings.Side wall = new Surroundings.Side(0.5, Surroundings.Kind.TERRAIN);
		Surroundings.Side self = new Surroundings.Side(0.5, Surroundings.Kind.SELF);
		for (int i = 0; i < 10; i++) {
			one.tick(DT, Surroundings.OPEN.withSides(wall, Surroundings.Side.NONE));
			two.tick(DT, Surroundings.OPEN.withSides(wall, self));
		}
		assertTrue(two.speed() - 18 > 1.9 * (one.speed() - 18), "two walls ~ double the gain");
	}

	@Test
	void speedIsCappedAtCycleSpeedMax() {
		CycleParams p = params();
		p.speedMax = 1.1;
		CycleMovement m = new CycleMovement(p, Heading.EAST);
		m.setSpeed(18);
		Surroundings.Side w = new Surroundings.Side(0.0, Surroundings.Kind.TERRAIN);
		for (int i = 0; i < 400; i++) {
			m.tick(DT, Surroundings.OPEN.withSides(w, w));
		}
		assertTrue(m.speed() <= 18 * 1.1 + 1e-6, "capped: " + m.speed());
		assertTrue(m.speed() > 18 * 1.05);
	}

	@Test
	void holdDoesNotMoveOrSpendRubber() {
		CycleMovement m = new CycleMovement(params(), Heading.EAST);
		CycleMovement.Step s = m.tick(DT, new Surroundings(0.0, 1, Surroundings.Side.NONE, Surroundings.Side.NONE, true));
		assertEquals(0, s.distance(), 1e-12);
		assertEquals(0, m.rubberUsed(), 1e-12);
	}

	@Test
	void distanceMatchesSpeedInTheOpen() {
		CycleMovement m = new CycleMovement(params(), Heading.EAST);
		m.setSpeed(18);
		double d = 0;
		for (int i = 0; i < 20; i++) {
			d += m.tick(DT, Surroundings.OPEN).distance();
		}
		assertEquals(18.0, d, 0.05);
	}

	@Test
	void terrainRubberFactorStretchesTheBudgetForTerrainOnly() {
		CycleParams p = params();
		p.terrainRubberFactor = 4.0;
		int trailTicks = ticksToCrash(new CycleMovement(p, Heading.EAST), false);
		int terrainTicks = ticksToCrash(new CycleMovement(p, Heading.EAST), true);
		// pressing time scales with the budget: 9 blocks at 18 b/s = 0.5 s vs 2 s
		assertTrue(terrainTicks > 3 * trailTicks, "terrain " + terrainTicks + " ticks vs trail " + trailTicks);
	}

	private static int ticksToCrash(CycleMovement m, boolean terrain) {
		m.setSpeed(18);
		double moved = 0;
		int ticks = 0;
		while (!m.crashed() && ticks < 400) {
			double space = 2.0 - moved;
			moved += m.tick(DT, terrain ? Surroundings.terrainAhead(space) : Surroundings.ahead(space)).distance();
			ticks++;
		}
		return ticks;
	}
}

package gl.syn.armacraft.ride.core;

import gl.syn.armacraft.ride.RideConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleBinaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tight maneuvers of a competitive Armagetron player against the cycle's own wall, with the default (RCL sumobar)
 * physics in Minecraft units, on flat ground and on a staircase that steps one block per block in both axes. The bike
 * is driven like the client drives it: 50 ms ticks, split at every key press and at every turn CYCLE_DELAY held
 * back; its feet follow the ground; the trail is laid at its feet.
 *
 * <p>What the real engine does with the same key scripts is in RideDiffTest's recordings (uturn, adjust, box,
 * spiral_in): survive, survive, die inside the box, die on the third wall.
 */
class ManeuverTest {

	static final double DT = 0.05;
	static final double BIKE_HEIGHT = 1.8;
	static final double TRAIL_HEIGHT = 1.0;
	static final DoubleBinaryOperator FLAT = (x, z) -> 64;
	/** One block up per block east and per block south: every block the bike drives is a step. */
	static final DoubleBinaryOperator STAIRS = (x, z) -> 64 + Math.floor(x) + Math.floor(z);
	/** One block up every third block, east and south. */
	static final DoubleBinaryOperator TERRACES = (x, z) -> 64 + Math.floor(x / 3) + Math.floor(z / 3);

	static CycleParams params() {
		RideConfig c = new RideConfig();
		c.boost.enabled = false;
		c.sanitize();
		return c.params();
	}

	/** One scripted ride. Presses: {time, dir}. */
	static final class Ride {
		final CycleMovement m;
		final Trail trail = new Trail();
		final DoubleBinaryOperator ground;
		final List<double[]> presses = new ArrayList<>();
		final List<double[]> corners = new ArrayList<>(); // x, z, time
		double x;
		double z;
		double y;
		double death = Double.NaN;
		int hitPiece = -1;
		double minOwnGap = Double.POSITIVE_INFINITY;
		private int next;

		Ride(DoubleBinaryOperator ground, double x, double z, int heading) {
			this.ground = ground;
			this.x = x;
			this.z = z;
			this.y = ground.applyAsDouble(x, z);
			m = new CycleMovement(params(), heading);
			trail.append(x, y, z, 0);
		}

		Ride press(double t, int... dirs) {
			for (int d : dirs) {
				presses.add(new double[] {t, d});
			}
			return this;
		}

		/** Runs until {@code tEnd} or the crash. */
		Ride run(double tEnd) {
			double tickEnd = 0;
			while (m.time() < tEnd - 1e-9 && !m.crashed()) {
				tickEnd += DT;
				while (!m.crashed()) {
					while (next < presses.size() && presses.get(next)[0] <= m.time() + 1e-9) {
						if (m.requestTurn((int) presses.get(next)[1])) {
							corners.add(new double[] {x, z, m.time()});
						}
						next++;
					}
					while (m.processPendingTurns()) {
						corners.add(new double[] {x, z, m.time()});
					}
					double now = m.time();
					if (now >= tickEnd - 1e-9) {
						break;
					}
					double until = tickEnd;
					if (next < presses.size()) {
						until = Math.min(until, Math.max(now, presses.get(next)[0]));
					}
					until = Math.min(until, m.nextDueTurnTime());
					if (until - now > 1e-9) {
						piece(until - now);
					}
				}
			}
			if (m.crashed() && Double.isNaN(death)) {
				death = m.time();
			}
			return this;
		}

		private void piece(double dt) {
			int h = m.heading();
			int hx = Heading.dx(h);
			int hz = Heading.dz(h);
			Trail.Point p = new Trail.Point(x, z, y, BIKE_HEIGHT, 1.0);
			final double x0 = x;
			final double z0 = z;
			double space = trail.ahead(p, h, TRAIL_HEIGHT, 50, m.turns() - 1, Double.NaN, 0, 0, d -> ground.applyAsDouble(x0 + hx * d, z0 + hz * d));
			int piece = trail.lastHit;
			double l = trail.side(p, h, -1, TRAIL_HEIGHT, m.params().wallNear, Integer.MAX_VALUE);
			double r = trail.side(p, h, +1, TRAIL_HEIGHT, m.params().wallNear, Integer.MAX_VALUE);
			if (m.turns() >= 2) {
				minOwnGap = Math.min(minOwnGap, Math.min(l, r));
			}
			Surroundings.Side left = Double.isFinite(l) ? new Surroundings.Side(l, Surroundings.Kind.SELF) : Surroundings.Side.NONE;
			Surroundings.Side right = Double.isFinite(r) ? new Surroundings.Side(r, Surroundings.Kind.SELF) : Surroundings.Side.NONE;
			CycleMovement.Step step = m.tick(dt, new Surroundings(space, 1, left, right, false, Surroundings.Kind.SELF));
			x += hx * step.distance();
			z += hz * step.distance();
			y = ground.applyAsDouble(x, z);
			trail.append(x, y, z, m.turns());
			if (step.crashed()) {
				death = m.time();
				hitPiece = piece;
			}
		}

		boolean alive() {
			return !m.crashed();
		}
	}

	static final DoubleBinaryOperator[] GROUNDS = {FLAT, STAIRS, TERRACES};
	static final String[] GROUND_NAMES = {"flat", "stairs", "terraces"};

	@Test
	void doubleBind180RidesBackAlongItsOwnWall() {
		for (int g = 0; g < GROUNDS.length; g++) {
			for (int dir = -1; dir <= 1; dir += 2) {
				// both keys at once: the first turns now, the second CYCLE_DELAY later, as close as the game allows
				Ride r = new Ride(GROUNDS[g], 100.3, 100.7, Heading.NORTH).press(1.0, dir, dir).run(3.0);
				String what = GROUND_NAMES[g] + " dir " + dir;
				assertTrue(r.alive(), what + ": died at " + r.death + " on piece " + r.hitPiece);
				assertEquals(Heading.SOUTH, r.m.heading(), what);
				assertEquals(2, r.corners.size(), what);
				assertEquals(r.m.params().delay, r.corners.get(1)[2] - r.corners.get(0)[2], 1e-9, what + ": spacing is CYCLE_DELAY");
				double gap = Math.abs(r.x - 100.3);
				assertTrue(gap > 1.0 && gap < 2.2, what + ": rides back " + gap + " blocks beside its wall");
				assertTrue(r.z > 100.7 + 10, what + ": past its start, " + r.z);
				assertTrue(r.trail.axisAligned(0), what + ": every piece is axis-aligned");
			}
		}
	}

	@Test
	void adjustTowardsAnOldWallEndsAHairBesideIt() {
		for (int g = 0; g < GROUNDS.length; g++) {
			// 180, back along the first wall, then right-left at once: one CYCLE_DELAY leg towards the old wall
			Ride r = new Ride(GROUNDS[g], 100.3, 100.7, Heading.NORTH).press(1.0, 1, 1).press(1.4, 1, -1).run(4.0);
			String what = GROUND_NAMES[g];
			assertTrue(r.alive(), what + ": died at " + r.death + " on piece " + r.hitPiece);
			assertEquals(Heading.SOUTH, r.m.heading(), what);
			assertEquals(4, r.corners.size(), what);
			double gap = Math.abs(r.x - 100.3);
			assertTrue(gap < 0.5, what + ": now " + gap + " blocks beside the old wall");
			assertTrue(r.minOwnGap < 0.5, what + ": rode alongside it at " + r.minOwnGap);
			assertTrue(r.z > 100.7 + 10, what + ": past the old wall's start, " + r.z);
			assertTrue(r.trail.axisAligned(0), what);
		}
	}

	@Test
	void adjustAwayAndBackNextToAnOldWall() {
		for (int g = 0; g < GROUNDS.length; g++) {
			Ride r = new Ride(GROUNDS[g], 100.3, 100.7, Heading.NORTH).press(1.0, 1, 1).press(1.3, -1, 1).press(1.6, 1, -1).run(4.0);
			String what = GROUND_NAMES[g];
			assertTrue(r.alive(), what + ": died at " + r.death + " on piece " + r.hitPiece);
			assertEquals(6, r.corners.size(), what);
			assertEquals(Heading.SOUTH, r.m.heading(), what);
			assertTrue(r.trail.axisAligned(0), what);
		}
	}

	@Test
	void grindingAnOldWallThenTurningAlongItSurvives() {
		for (int g = 0; g < GROUNDS.length; g++) {
			// 180, back along the first wall, then turn into it and only turn away 0.25 s later: rubber holds the bike a
			// hair in front of the wall, the turn puts the wall beside it
			Ride r = new Ride(GROUNDS[g], 100.3, 100.7, Heading.NORTH).press(1.0, 1, 1).press(1.4, 1).press(1.65, -1).run(3.0);
			String what = GROUND_NAMES[g];
			assertTrue(r.alive(), what + ": died at " + r.death + " on piece " + r.hitPiece);
			assertTrue(r.m.rubberUsed() > 0, what + ": used rubber on the wall");
			double gap = Math.abs(r.x - 100.3);
			assertTrue(gap > 0 && gap < 0.01, what + ": stopped " + gap + " in front of the wall (CYCLE_RUBBER_MINDISTANCE)");
			assertEquals(Heading.SOUTH, r.m.heading(), what);
			assertTrue(r.z > 100.7 + 5, what + ": rode on along the wall and past its start, " + r.z);
		}
	}

	@Test
	void tightestBoxDiesInsideOnTheSecondWall() {
		double flatDeath = Double.NaN;
		for (int g = 0; g < GROUNDS.length; g++) {
			// four lefts at once: north, west, south, east, north again a fraction of a block beside the first wall
			Ride r = new Ride(GROUNDS[g], 100.3, 100.7, Heading.NORTH).press(1.0, -1, -1, -1, -1).run(6.0);
			String what = GROUND_NAMES[g];
			assertFalse(r.alive(), what + ": boxed in, must die");
			assertEquals(4, r.corners.size(), what + ": all four turns made (not killed by the wall beside it)");
			assertEquals(Heading.NORTH, r.m.heading(), what);
			double beside = Math.abs(r.x - 100.3);
			assertTrue(beside > 0.01 && beside < 0.6, what + ": fourth leg " + beside + " beside the first wall");
			// the wall it dies on is the second leg (seg 1), at the first corner's z
			assertEquals(1, r.trail.seg(r.hitPiece + 1), what + ": died on the second wall");
			assertEquals(r.corners.get(0)[1], r.z, 0.01, what + ": at the second wall");
			if (g == 0) {
				flatDeath = r.death;
			} else {
				assertEquals(flatDeath, r.death, 1e-9, what + ": steps change nothing about when");
			}
		}
		assertTrue(flatDeath > 1.4, "rubber kept it alive in the box for a while: " + flatDeath);
	}

	@Test
	void boxSpiralInwardDiesOnTheThirdWall() {
		double flatDeath = Double.NaN;
		for (int g = 0; g < GROUNDS.length; g++) {
			Ride r = new Ride(GROUNDS[g], 100.3, 100.7, Heading.NORTH).press(1.0, -1).press(1.6, -1).press(2.1, -1).press(2.5, -1).press(2.8, -1)
				.run(8.0);
			String what = GROUND_NAMES[g];
			assertFalse(r.alive(), what + ": spiralled in, must die");
			assertEquals(5, r.corners.size(), what);
			assertEquals(2, r.trail.seg(r.hitPiece + 1), what + ": died on the third wall");
			assertTrue(r.trail.axisAligned(0), what);
			if (g == 0) {
				flatDeath = r.death;
			} else {
				assertEquals(flatDeath, r.death, 1e-9, what + ": steps change nothing about when");
			}
		}
	}

	@Test
	void zigZagUpAStaircaseNeverMeetsItsOwnJoins() {
		// the user's 18:32 crash: four turns after a step, back on the lane of the old wall's step join
		for (int g = 1; g < GROUNDS.length; g++) {
			Ride r = new Ride(GROUNDS[g], 100.3, 100.7, Heading.EAST);
			for (int i = 0; i < 12; i++) {
				r.press(0.5 + 0.35 * i, i % 2 == 0 ? -1 : 1, i % 2 == 0 ? 1 : -1);
			}
			r.run(6.0);
			assertTrue(r.alive(), GROUND_NAMES[g] + ": died at " + r.death + " on piece " + r.hitPiece);
			assertEquals(24, r.corners.size());
			assertTrue(r.trail.axisAligned(0));
		}
	}

	@Test
	void aWallAtTheFootOfAStepIsAWall() {
		// lay a wall east-west one level down, just past a step edge; then come at it from above: stepping down must not
		// carry the bike through it (it is met at the height the bike has when it gets there)
		DoubleBinaryOperator ledge = (x, z) -> z < 100 ? 65 : 64; // north of z = 100 is one block higher
		Trail wall = new Trail();
		wall.append(90, 64, 100.05, 0);
		wall.append(110, 64, 100.05, 0);
		Trail.Point onLedge = new Trail.Point(100.3, 99.4, 65, BIKE_HEIGHT, 1.0);
		assertEquals(0.65, wall.ahead(onLedge, Heading.SOUTH, TRAIL_HEIGHT, 50, Integer.MAX_VALUE, Double.NaN, 0, 0,
			d -> ledge.applyAsDouble(100.3, 99.4 + d)), 1e-9);
		// from two steps up it is still found: the bike will be on its level when it arrives
		DoubleBinaryOperator steps = (x, z) -> 64 + Math.max(0, Math.ceil(100 - z));
		Trail.Point twoUp = new Trail.Point(100.3, 98.4, 66, BIKE_HEIGHT, 1.0);
		assertEquals(1.65, wall.ahead(twoUp, Heading.SOUTH, TRAIL_HEIGHT, 50, Integer.MAX_VALUE, Double.NaN, 0, 0,
			d -> steps.applyAsDouble(100.3, 98.4 + d)), 1e-9);
		// a bridge three blocks above it is not on its level, there or anywhere
		Trail.Point bridge = new Trail.Point(100.3, 98.4, 67, BIKE_HEIGHT, 1.0);
		assertEquals(Double.POSITIVE_INFINITY, wall.ahead(bridge, Heading.SOUTH, TRAIL_HEIGHT, 50, Integer.MAX_VALUE, Double.NaN, 0, 0, d -> 67));
	}
}

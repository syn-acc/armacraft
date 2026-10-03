package gl.syn.armacraft.ride.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrailTest {

	static final double H = 1.0;

	@Test
	void straightLevelPointsMerge() {
		Trail t = new Trail();
		for (int i = 0; i <= 10; i++) {
			t.append(i, 64, 0.5, 0);
		}
		assertEquals(2, t.size(), "straight run is one piece");
		assertEquals(10.0, t.length(), 1e-9);
		t.append(10, 65, 0.5, 0); // step up: kept
		t.append(11, 65, 0.5, 0);
		assertEquals(4, t.size());
	}

	@Test
	void turnCornersAreKept() {
		Trail t = new Trail();
		t.append(0, 64, 0, 0);
		t.append(5, 64, 0, 0);
		t.append(5, 64, 5, 1);
		t.append(5, 64, 10, 1);
		assertEquals(3, t.size());
		assertEquals(1, t.seg(2));
	}

	@Test
	void trimKeepsNewestLength() {
		Trail t = new Trail();
		t.append(0, 64, 0, 0);
		t.append(10, 64, 0, 0);
		t.append(10, 64, 10, 1);
		t.trim(15, 10, 64, 10);
		assertEquals(15.0, t.length(), 1e-9);
		assertEquals(5.0, t.x(0), 1e-9, "oldest piece shortened");
		t.trim(5, 10, 64, 10);
		assertEquals(1L, t.firstIndex(), "first point dropped");
	}

	@Test
	void aheadFindsPerpendicularCrossingFromThePoint() {
		Trail t = new Trail(); // other rider's trail along z = 5, x from -10 to 10
		t.append(-10, 64, 5, 0);
		t.append(10, 64, 5, 0);
		Trail.Point bike = new Trail.Point(0.5, 0.5, 64, 1.8);
		// heading south (+z): the cycle is a point at z = 0.5, the wall a line at z = 5
		assertEquals(4.5, t.ahead(bike, Heading.SOUTH, H, 16, Integer.MAX_VALUE, Double.NaN, 0, 0), 1e-9);
		assertEquals(Double.POSITIVE_INFINITY, t.ahead(bike, Heading.NORTH, H, 16, Integer.MAX_VALUE, Double.NaN, 0, 0));
		// the wall ends at x = 10: its very end is still wall, a hair past it is free (zero-thickness, no margin)
		assertEquals(4.5, t.ahead(new Trail.Point(10, 0.5, 64, 1.8), Heading.SOUTH, H, 16, Integer.MAX_VALUE, Double.NaN, 0, 0), 1e-9);
		assertEquals(Double.POSITIVE_INFINITY,
			t.ahead(new Trail.Point(10.0001, 0.5, 64, 1.8), Heading.SOUTH, H, 16, Integer.MAX_VALUE, Double.NaN, 0, 0));
		// already past it: behind, not ahead
		assertEquals(Double.POSITIVE_INFINITY, t.ahead(new Trail.Point(0.5, 5.001, 64, 1.8), Heading.SOUTH, H, 16, Integer.MAX_VALUE, Double.NaN, 0, 0));
	}

	@Test
	void parallelWallIsNeverAheadHoweverClose() {
		Trail t = new Trail(); // a wall along x = 5 from z = 0 to z = 20
		t.append(5, 64, 0, 0);
		t.append(5, 64, 20, 0);
		for (double off : new double[] {1.8, 0.3, 0.05, 0.001, 0.0001}) {
			for (int side = -1; side <= 1; side += 2) {
				Trail.Point bike = new Trail.Point(5 + side * off, 10, 64, 1.8);
				assertEquals(Double.POSITIVE_INFINITY, t.ahead(bike, Heading.SOUTH, H, 50, Integer.MAX_VALUE, Double.NaN, 0, 0),
					"riding south " + off + " beside the wall");
				assertEquals(Double.POSITIVE_INFINITY, t.ahead(bike, Heading.NORTH, H, 50, Integer.MAX_VALUE, Double.NaN, 0, 0),
					"riding north " + off + " beside the wall");
			}
		}
		// on its very line: inside it (0), or running into its end from outside
		assertEquals(0.0, t.ahead(new Trail.Point(5, 10, 64, 1.8), Heading.SOUTH, H, 50, Integer.MAX_VALUE, Double.NaN, 0, 0), 1e-12);
		assertEquals(3.0, t.ahead(new Trail.Point(5, -3, 64, 1.8), Heading.SOUTH, H, 50, Integer.MAX_VALUE, Double.NaN, 0, 0), 1e-12);
		assertEquals(Double.POSITIVE_INFINITY, t.ahead(new Trail.Point(5, 21, 64, 1.8), Heading.SOUTH, H, 50, Integer.MAX_VALUE, Double.NaN, 0, 0),
			"its end is behind");
	}

	@Test
	void grindThenTurnRidesAlongTheWall() {
		// stopped by rubber CYCLE_RUBBER_MINDISTANCE (0.001 units = 0.0006 blocks) in front of a wall, then turned:
		// the wall is now beside the cycle, and turning back into it finds it 0.0006 ahead again
		Trail t = new Trail();
		t.append(5, 64, -20, 0);
		t.append(5, 64, 20, 0);
		Trail.Point bike = new Trail.Point(5 - 0.0006, 0, 64, 1.8);
		assertEquals(0.0006, t.ahead(bike, Heading.EAST, H, 50, Integer.MAX_VALUE, Double.NaN, 0, 0), 1e-12);
		assertEquals(Double.POSITIVE_INFINITY, t.ahead(bike, Heading.SOUTH, H, 50, Integer.MAX_VALUE, Double.NaN, 0, 0));
		assertEquals(Double.POSITIVE_INFINITY, t.ahead(bike, Heading.WEST, H, 50, Integer.MAX_VALUE, Double.NaN, 0, 0));
	}

	@Test
	void headPieceCountsForOtherRiders() {
		Trail t = new Trail();
		t.append(-10, 64, 5, 0);
		Trail.Point bike = new Trail.Point(0.5, 0.5, 64, 1.8);
		assertEquals(Double.POSITIVE_INFINITY, t.ahead(bike, Heading.SOUTH, H, 16, Integer.MAX_VALUE, Double.NaN, 0, 0));
		// the rider who lays it is now at x=3: the piece being laid crosses our line
		assertEquals(4.5, t.ahead(bike, Heading.SOUTH, H, 16, Integer.MAX_VALUE, 3, 64, 5), 1e-9);
	}

	@Test
	void ownFreshSegmentsAreIgnoredOlderOnesAreNot() {
		// a square loop: east, south, west, then north back across the first leg
		Trail t = new Trail();
		t.append(0.5, 64, 0.5, 0);
		t.append(10.5, 64, 0.5, 0);
		t.append(10.5, 64, 10.5, 1);
		t.append(0.75, 64, 10.5, 2);
		t.append(0.75, 64, 6.5, 3); // heading north now, seg 3
		Trail.Point bike = new Trail.Point(0.75, 6.5, 64, 1.8);
		// own rule: ignore seg >= current - 1 = 2; seg 0 (the first leg at z=0.5) is ahead
		double d = t.ahead(bike, Heading.NORTH, H, 16, 2, 0.75, 64, 6.5);
		assertEquals(6.0, d, 1e-9);
		// right after the second turn the corner leg (seg 2) itself must not count
		Trail.Point atCorner = new Trail.Point(0.75, 10.5, 64, 1.8);
		assertEquals(10.0, t.ahead(atCorner, Heading.NORTH, H, 16, 2, 0.75, 64, 10.5), 1e-9);
	}

	@Test
	void trailBelowABridgeDoesNotBlock() {
		Trail t = new Trail();
		t.append(-10, 64, 5, 0);
		t.append(10, 64, 5, 0);
		Trail.Point high = new Trail.Point(0.5, 0.5, 70, 1.8);
		assertEquals(Double.POSITIVE_INFINITY, t.ahead(high, Heading.SOUTH, H, 16, Integer.MAX_VALUE, Double.NaN, 0, 0));
		Trail.Point justAbove = new Trail.Point(0.5, 0.5, 65.0, 1.8); // trail top 65 == feet: no overlap
		assertEquals(Double.POSITIVE_INFINITY, t.ahead(justAbove, Heading.SOUTH, H, 16, Integer.MAX_VALUE, Double.NaN, 0, 0));
		Trail.Point overlapping = new Trail.Point(0.5, 0.5, 64.5, 1.8);
		assertEquals(4.5, t.ahead(overlapping, Heading.SOUTH, H, 16, Integer.MAX_VALUE, Double.NaN, 0, 0), 1e-9);
	}

	@Test
	void stepJoinIsTheSameStraightWall() {
		// the user's 18:32:47 crash: a wall along z = 53.5 that steps up one block (the join is the sloped piece)
		Trail t = new Trail();
		t.append(-2340.0, 65, 53.5, 142);
		t.append(-2344.42, 65, 53.5, 142);
		t.append(-2344.15 - 0.6, 66, 53.5, 142); // one physics piece across the step
		t.append(-2350.0, 66, 53.5, 142);
		assertTrue(t.axisAligned(1e-12), "a step changes the height, not the line");
		// four turns later, riding west a hair beside it, on either level, past the join: beside, never ahead
		for (double off : new double[] {0.3, 0.01, 0.0001}) {
			for (double y : new double[] {65, 66}) {
				Trail.Point bike = new Trail.Point(-2343.73, 53.5 - off, y, 1.8);
				assertEquals(Double.POSITIVE_INFINITY, t.ahead(bike, Heading.WEST, H, 50, 145, Double.NaN, 0, 0), "beside the join, " + off);
				bike = new Trail.Point(-2343.73, 53.5 + off, y, 1.8);
				assertEquals(Double.POSITIVE_INFINITY, t.ahead(bike, Heading.WEST, H, 50, 145, Double.NaN, 0, 0), "beside the join, " + off);
			}
		}
		// crossing it is a crossing of one line: the same distance below the join, on it and above it
		for (double x : new double[] {-2342.0, -2344.6, -2348.0}) {
			double y = x > -2344.42 ? 65 : 66;
			assertEquals(3.0, t.ahead(new Trail.Point(x, 50.5, y, 1.8), Heading.SOUTH, H, 50, 145, Double.NaN, 0, 0), 1e-9, "crossing at x " + x);
		}
	}

	@Test
	void sideSensorMeasuresFromThePoint() {
		Trail t = new Trail();
		t.append(-20, 64, 2.5, 0);
		t.append(20, 64, 2.5, 0);
		Trail.Point bike = new Trail.Point(0.5, 0.5, 64, 1.8);
		// heading east, right side is +z: the wall is 2 blocks from the point
		assertEquals(2.0, t.side(bike, Heading.EAST, +1, H, 3.6, Integer.MAX_VALUE), 1e-9);
		assertEquals(Double.POSITIVE_INFINITY, t.side(bike, Heading.EAST, -1, H, 3.6, Integer.MAX_VALUE));
		assertEquals(Double.POSITIVE_INFINITY, t.side(bike, Heading.SOUTH, -1, H, 3.6, Integer.MAX_VALUE), "perpendicular");
		assertEquals(0.01, t.side(new Trail.Point(0.5, 2.49, 64, 1.8), Heading.EAST, +1, H, 3.6, Integer.MAX_VALUE), 1e-9);
	}

	@Test
	void serverCrossingCheck() {
		Trail t = new Trail();
		t.append(-10, 64, 5, 0);
		t.append(10, 64, 5, 0);
		assertTrue(t.crossedBy(0, 4.5, 0, 5.5, 64, 1.8, H, Double.NaN, 0, 0));
		assertFalse(t.crossedBy(0, 4.5, 0, 4.9, 64, 1.8, H, Double.NaN, 0, 0), "stopped short");
		assertFalse(t.crossedBy(0, 4.5, 0, 5.5, 70, 1.8, H, Double.NaN, 0, 0), "on a bridge above");
		assertFalse(t.crossedBy(15, 4.5, 15, 5.5, 64, 1.8, H, Double.NaN, 0, 0), "past its end");
		assertTrue(t.crossedBy(15, 4.5, 15, 5.5, 64, 1.8, H, 20, 64, 5), "head piece being laid");
	}

	@Test
	void deadWallsStayUpThenVanish() {
		Trail t = new Trail();
		t.append(0, 64, 0, 0);
		t.append(5, 64, 0, 0);
		assertEquals(1.0, t.alpha(100, 8), 1e-12);
		assertTrue(t.lethal(100, 8));
		t.stop(100);
		// WALLS_STAY_UP_DELAY 8: solid (and lethal) for 8 s, fading out over the last one
		assertEquals(1.0, t.alpha(104, 8), 1e-12);
		assertEquals(1.0, t.alpha(107, 8), 1e-12);
		assertEquals(0.5, t.alpha(107.5, 8), 1e-12);
		assertTrue(t.lethal(107.9, 8));
		assertFalse(t.lethal(108.1, 8), "gone after the stay-up time: no wall, nothing drawn");
		assertEquals(0.0, t.alpha(108.1, 8), 1e-12);
		// 0: gone the moment the cycle dies; negative: forever
		assertFalse(t.lethal(100, 0));
		assertEquals(1.0, t.alpha(1e6, -1), 1e-12);
		assertTrue(t.lethal(1e6, -1));
	}

	@Test
	void syncReceiveReplacesProvisionalLastPoint() {
		Trail owner = new Trail();
		owner.append(0, 64, 0, 0);
		owner.append(1, 64, 0, 0);
		Trail viewer = new Trail();
		viewer.receive(owner.firstIndex(), owner.pointsFrom(owner.firstIndex()), owner.segsFrom(owner.firstIndex()));
		assertEquals(2, viewer.size());
		owner.append(5, 64, 0, 0); // merged into the last point in place
		long from = owner.endIndex() - 1;
		viewer.receive(from, owner.pointsFrom(from), owner.segsFrom(from));
		assertEquals(2, viewer.size());
		assertEquals(5.0, viewer.x(1), 1e-9);
		assertEquals(5.0, viewer.length(), 1e-9);
		owner.append(5, 64, 3, 1);
		from = owner.endIndex() - 2;
		viewer.receive(from, owner.pointsFrom(from), owner.segsFrom(from));
		assertEquals(3, viewer.size());
		viewer.dropBefore(1);
		assertEquals(2, viewer.size());
		assertEquals(1L, viewer.firstIndex());
	}

	@Test
	void tightUTurnRidesAlongsideItsOwnFirstWall() {
		// the user's 14:46 crash: deployed north next to a wall, stepped up, turned west into the wall (0.2 blocks of
		// room), turned again (south): now 0.2 blocks beside its own first wall (seg 0), which is lethal after two turns
		Trail t = new Trail();
		t.append(-677.50, 66, -35.5, 0);
		t.append(-677.50, 67, -36.14, 0);
		t.append(-677.50, 67, -38.0, 0);
		t.append(-677.70, 67, -38.0, 1);
		Trail.Point bike = new Trail.Point(-677.70, -37.9, 67, 1.8);
		assertEquals(Double.POSITIVE_INFINITY, t.ahead(bike, Heading.SOUTH, H, 16, 1, Double.NaN, 0, 0), "alongside, 0.2 to the side");
		// on its very line: a wall
		assertEquals(0.0, t.ahead(new Trail.Point(-677.50, -37.9, 67, 1.8), Heading.SOUTH, H, 16, 1, Double.NaN, 0, 0), 1e-9);
		// a crossing is measured from the point
		Trail cross = new Trail();
		cross.append(-680, 67, -35, 0);
		cross.append(-670, 67, -35, 0);
		assertEquals(2.9, cross.ahead(bike, Heading.SOUTH, H, 16, Integer.MAX_VALUE, Double.NaN, 0, 0), 1e-9);
	}
}

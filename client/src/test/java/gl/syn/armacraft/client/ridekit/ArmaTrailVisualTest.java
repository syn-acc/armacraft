package gl.syn.armacraft.client.ridekit;

import gl.syn.armacraft.client.ride.api.TrailVisual;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Ride trail polyline: terrain steps become level runs with a vertical join at the block edge; falls keep their slope. */
class ArmaTrailVisualTest {

	private static double[][] poly(double[] pts, Vec3 head) {
		return new ArmaTrailVisual(() -> 0.6).polyline(new TrailVisual.Ribbon(pts, pts.length / 3, head, 1.0, 0xff00ff, 1f, 0, 10));
	}

	@Test
	void stepUpIsSplitAtTheBlockEdge() {
		// the front of the rider box (x + 0.3) passes x = 1 during the last move
		double[][] p = poly(new double[] {0.2, 64, 0.5, 0.65, 64, 0.5, 1.55, 65, 0.5}, null);
		assertEquals(5, p.length);
		assertArrayEquals(new double[] {1.0, 64, 0.5}, p[2], 1e-9);
		assertArrayEquals(new double[] {1.0, 65, 0.5}, p[3], 1e-9, "vertical join at x = 1");
		assertArrayEquals(new double[] {1.55, 65, 0.5}, p[4], 1e-9);
	}

	/**
	 * Trail recorded in game (play-shaders-tex bench p5b, 18 b/s): stone strips one block high at x 0..2 and 8..10.
	 * The rider is lifted when its front reaches a strip, 0.07 blocks before the edge at x = 0: the wall must stay
	 * on the ground up to the block face instead of rising in mid-air half a block early (the "spikes" at steps).
	 */
	@Test
	void recordedStepsJoinAtTheBlockFaces() {
		double[][] p = poly(new double[] {-20.5, 151, 0.5, -0.967, 151, 0.5, -0.068, 152, 0.5, 2.631, 152, 0.5, 3.531, 151, 0.5,
			7.130, 151, 0.5, 8.030, 152, 0.5, 10.730, 152, 0.5, 11.630, 151, 0.5}, null);
		double[][] want = {{-20.5, 151}, {-0.967, 151}, {0, 151}, {0, 152}, {2.631, 152}, {3, 152}, {3, 151}, {3.531, 151},
			{7.130, 151}, {8, 151}, {8, 152}, {8.030, 152}, {10.730, 152}, {11, 152}, {11, 151}, {11.630, 151}};
		assertEquals(want.length, p.length);
		for (int i = 0; i < want.length; i++) {
			assertEquals(want[i][0], p[i][0], 1e-9, "x of point " + i);
			assertEquals(want[i][1], p[i][1], 1e-9, "y of point " + i);
		}
		for (int i = 1; i < p.length; i++) {
			assertTrue(p[i][0] >= p[i - 1][0] - 1e-9, "the wall never runs backwards: point " + i);
		}
		// the way back (heading -x) over a two block high stair at x 16..21 / 18..19
		double[][] q = poly(new double[] {22.726, 151, -7.5, 21.828, 152, -7.5, 20.929, 152, -7.5, 20.030, 153, -7.5, 18.231, 153, -7.5,
			17.332, 152, -7.5, 16.432, 152, -7.5, 15.532, 151, -7.5}, null);
		double[][] wantBack = {{22.726, 151}, {22, 151}, {22, 152}, {21.828, 152}, {20.929, 152}, {20, 152}, {20, 153}, {18.231, 153},
			{18, 153}, {18, 152}, {17.332, 152}, {16.432, 152}, {16, 152}, {16, 151}, {15.532, 151}};
		assertEquals(wantBack.length, q.length);
		for (int i = 0; i < wantBack.length; i++) {
			assertEquals(wantBack[i][0], q[i][0], 1e-9, "x of point " + i + " (back)");
			assertEquals(wantBack[i][1], q[i][1], 1e-9, "y of point " + i + " (back)");
		}
	}

	@Test
	void stepUpAheadOfTheNewestPointWaitsForTheTrailToPassTheEdge() {
		// lifted at x = -0.068, the block face is at x = 0: nothing recorded beyond it yet, so the join stays at the point
		double[][] p = poly(new double[] {-0.967, 151, 0.5, -0.068, 152, 0.5}, null);
		assertEquals(3, p.length);
		assertArrayEquals(new double[] {-0.068, 151, 0.5}, p[1], 1e-9);
		assertArrayEquals(new double[] {-0.068, 152, 0.5}, p[2], 1e-9);
		// a turn at the lifted point: the wall must not overshoot the corner
		double[][] t = poly(new double[] {-0.967, 151, 0.5, -0.068, 152, 0.5, -0.068, 152, 3.5}, null);
		assertArrayEquals(new double[] {-0.068, 151, 0.5}, t[1], 1e-9);
		assertArrayEquals(new double[] {-0.068, 152, 0.5}, t[2], 1e-9);
		// the head past the edge: the wall climbs at the edge
		double[][] h = poly(new double[] {-0.967, 151, 0.5, -0.068, 152, 0.5}, new Vec3(0.4, 152, 0.5));
		assertEquals(5, h.length);
		assertArrayEquals(new double[] {0, 151, 0.5}, h[2], 1e-9);
		assertArrayEquals(new double[] {0, 152, 0.5}, h[3], 1e-9);
		assertArrayEquals(new double[] {0.4, 152, 0.5}, h[4], 1e-9);
	}

	@Test
	void stepDownBehindThePreviousPointPullsItBackToTheEdge() {
		// the back of the box (x - 0.3) leaves the block at x = 3 between x = 3.2 and x = 4.1
		double[][] p = poly(new double[] {1.0, 152, 0.5, 3.2, 152, 0.5, 4.1, 151, 0.5}, null);
		assertEquals(4, p.length);
		assertArrayEquals(new double[] {3.0, 152, 0.5}, p[1], 1e-9, "no wall floating past the edge");
		assertArrayEquals(new double[] {3.0, 151, 0.5}, p[2], 1e-9);
		assertArrayEquals(new double[] {4.1, 151, 0.5}, p[3], 1e-9);
	}

	@Test
	void stepDownAlongZAndHeadIsAppended() {
		double[][] p = poly(new double[] {0.5, 70, 3.4, 0.5, 70, 2.6}, new Vec3(0.5, 69, 1.7));
		assertEquals(5, p.length);
		assertArrayEquals(new double[] {0.5, 70, 2.0}, p[2], 1e-9);
		assertArrayEquals(new double[] {0.5, 69, 2.0}, p[3], 1e-9, "vertical join at z = 2");
		assertArrayEquals(new double[] {0.5, 69, 1.7}, p[4], 1e-9, "head is the last point");
	}

	@Test
	void fallsKeepTheirSlopeAndLevelRunsAreUntouched() {
		double[][] fall = poly(new double[] {0.5, 80, 0.5, 1.4, 77.5, 0.5}, null);
		assertEquals(2, fall.length, "a 2.5 block drop over one tick is a fall, not a step");
		double[][] flat = poly(new double[] {0.5, 64, 0.5, 5.5, 64, 0.5, 5.5, 64, 9.5}, new Vec3(5.5, 64, 9.5));
		assertEquals(3, flat.length, "duplicate head point dropped");
	}

	@Test
	void newestPointAheadOfTheInterpolatedHeadIsCut() {
		// last recorded point (tick end) at x = 10.9, the rider drawn at x = 10.2: the wall must end at the rider
		double[][] p = poly(new double[] {0.5, 64, 0.5, 5.5, 64, 0.5, 10.9, 64, 0.5}, new Vec3(10.2, 64, 0.5));
		assertEquals(10.2, p[p.length - 1][0], 1e-9, "ends at the head");
		for (double[] q : p) {
			assertTrue(q[0] <= 10.2 + 1e-9, "nothing ahead of the head: " + q[0]);
		}
		// just after a turn: last point is the corner plus a bit of the new leg; the head still before the corner
		double[][] c = poly(new double[] {0.5, 64, 0.5, 8.5, 64, 0.5, 8.5, 64, 1.2}, new Vec3(8.1, 64, 0.5));
		assertEquals(8.1, c[c.length - 1][0], 1e-9);
		assertEquals(0.5, c[c.length - 1][2], 1e-9, "the corner is not drawn before the bike reaches it");
	}

	@Test
	void rideTrailsAreNeverLowered() {
		// Armagetron 0.4: CAMERA_VISIBILITY_LOWER_WALL lowers rim walls only; player walls (ride trails) stay full height
		assertFalse(ArmaTrailVisual.lowersTrailWalls(true, true), "camera active, CAMERA_VISIBILITY_LOWER_WALL 1");
		assertFalse(ArmaTrailVisual.lowersTrailWalls(true, false));
		assertFalse(ArmaTrailVisual.lowersTrailWalls(false, true));
	}

	@Test
	void wallTopRampsUpToTheHigherLevelOnTheLowSideOfAStep() {
		// 5 block run that climbs one block at its end: full height until 1 block before the face, then 45 degrees
		assertEquals(0, ArmaTrailVisual.rampExtra(0, 5, 0, 1, 1), 1e-9);
		assertEquals(0, ArmaTrailVisual.rampExtra(0.8, 5, 0, 1, 1), 1e-9);
		assertEquals(0.5, ArmaTrailVisual.rampExtra(0.9, 5, 0, 1, 1), 1e-9);
		assertEquals(1, ArmaTrailVisual.rampExtra(1, 5, 0, 1, 1), 1e-9, "at the face the wall reaches the top of the upper wall");
		assertArrayEquals(new double[] {0.8}, ArmaTrailVisual.rampCuts(5, 0, 1, 1), 1e-9);
		// came down one block at the start
		assertEquals(1, ArmaTrailVisual.rampExtra(0, 5, 1, 0, 1), 1e-9);
		assertEquals(0, ArmaTrailVisual.rampExtra(0.2, 5, 1, 0, 1), 1e-9);
		assertArrayEquals(new double[] {0.2}, ArmaTrailVisual.rampCuts(5, 1, 0, 1), 1e-9);
		// a one block wide trench: both ramps shortened to half a block, a V
		assertEquals(1, ArmaTrailVisual.rampExtra(0, 1, 1, 1, 1), 1e-9);
		assertEquals(0, ArmaTrailVisual.rampExtra(0.5, 1, 1, 1, 1), 1e-9);
		assertEquals(1, ArmaTrailVisual.rampExtra(1, 1, 1, 1, 1), 1e-9);
		assertArrayEquals(new double[] {0.5}, ArmaTrailVisual.rampCuts(1, 1, 1, 1), 1e-9);
		// render.trail_step_ramp 0: plain vertical joins
		assertEquals(0, ArmaTrailVisual.rampExtra(1, 5, 0, 1, 0), 1e-9);
		assertEquals(0, ArmaTrailVisual.rampCuts(5, 1, 1, 0).length);
	}

	@Test
	void stepWithoutEdgeCrossingJoinsHalfway() {
		// no block edge passed by the rider's front, none between the points: not a block step, join halfway
		double[][] p = poly(new double[] {0.1, 64, 0.5, 0.35, 64.5, 0.5}, null);
		assertEquals(4, p.length);
		assertEquals(0.225, p[1][0], 1e-9);
		assertEquals(64, p[1][1], 1e-9);
		assertEquals(64.5, p[2][1], 1e-9);
	}

	@Test
	void rampsAreSlicedSoTheStretchedTextureDoesNotKink() {
		// a 1 block ramp up at the end of a 5 block run: 8 slices, 7 cuts inside the last block, none elsewhere
		double[] up = ArmaTrailVisual.rampSubCuts(5, 0, 1, 1);
		assertEquals(7, up.length);
		for (double f : up) {
			assertTrue(f > 0.8 && f < 1, "inside the ramp: " + f);
		}
		// neighbouring slices differ in height by at most an eighth of the step
		double[] sorted = up.clone();
		java.util.Arrays.sort(sorted);
		double prev = ArmaTrailVisual.rampExtra(0.8, 5, 0, 1, 1);
		for (double f : sorted) {
			double e = ArmaTrailVisual.rampExtra(f, 5, 0, 1, 1);
			assertEquals(0.125, e - prev, 1e-9);
			prev = e;
		}
		// a ramp down at the start mirrors it
		double[] down = ArmaTrailVisual.rampSubCuts(5, 1, 0, 1);
		assertEquals(7, down.length);
		for (double f : down) {
			assertTrue(f > 0 && f < 0.2, "inside the ramp: " + f);
		}
		// both, shortened to fit a 1 block run: each half a block, 4 slices
		assertEquals(6, ArmaTrailVisual.rampSubCuts(1, 1, 1, 1).length);
		// no ramps: nothing to slice
		assertEquals(0, ArmaTrailVisual.rampSubCuts(5, 0, 0, 1).length);
		assertEquals(0, ArmaTrailVisual.rampSubCuts(5, 1, 1, 0).length);
	}
}

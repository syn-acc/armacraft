package gl.syn.armacraft.client.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArenaGeometryTest {

	@Test
	void chainStartsFollowTheTrail() {
		List<WallGeom.Seg> walls = List.of(
			new WallGeom.Seg(10, 0, 10, 5),   // second
			new WallGeom.Seg(0, 0, 10, 0),    // first
			new WallGeom.Seg(10, 5, 4, 5)     // third
		);
		double[] s = WallGeom.chainStarts(walls);
		assertEquals(10, s[0], 1e-9);
		assertEquals(0, s[1], 1e-9);
		assertEquals(15, s[2], 1e-9);
	}

	@Test
	void gapsSplitSolidPieces() {
		List<double[]> p = WallGeom.solidPieces(new double[][] {{0.7932, 0.9189}, {0, 0.1}});
		assertEquals(2, p.size());
		assertArrayEquals(new double[] {0.1, 0.7932}, p.get(0), 1e-9);
		assertArrayEquals(new double[] {0.9189, 1}, p.get(1), 1e-9);
		assertEquals(1, WallGeom.solidPieces(null).size());
		assertTrue(WallGeom.solidPieces(new double[][] {{0, 1}}).isEmpty());
	}

	@Test
	void raycastMeasuresInDirectionMultiples() {
		List<WallGeom.Seg> segs = List.of(new WallGeom.Seg(-5, 1, 5, 1));
		// diagonal (1,1): hits y=1 at t=1
		assertEquals(1.0, WallGeom.raycast(new V2(0, 0), new V2(1, 1), 4, segs, 1e-4), 1e-9);
		assertEquals(0.5, WallGeom.raycast(new V2(0, 0), new V2(1, 1), 0.5, segs, 1e-4), 1e-9);
	}

	@Test
	void leanAwayFromCloseWallAndOnTurns() {
		CycleAnim a = new CycleAnim();
		a.reset(new V2(0, 1));
		// wall close on the left: fl small -> lean right (positive skew)
		for (int i = 0; i < 120; i++) {
			a.step(1 / 60.0, new V2(0, 1), 30, true, 0.1, 1.0);
		}
		assertTrue(a.skew > 0, "skew " + a.skew);
		assertTrue(a.skew <= 0.5);

		CycleAnim b = new CycleAnim();
		b.reset(new V2(0, 1));
		b.turnImpulse(1);   // right turn
		b.step(1 / 60.0, new V2(1, 0), 30, true, 1, 1);
		assertTrue(b.skew > 0);
		// body direction swings towards the new heading but not instantly
		assertTrue(b.dir.x() > 0.5 && b.dir.y() > 0.1, "dir " + b.dir);
		for (int i = 0; i < 60; i++) {
			b.step(1 / 60.0, new V2(1, 0), 30, true, 1, 1);
		}
		assertEquals(1, b.dir.x(), 1e-3);
		assertEquals(0, b.skew, 0.02);
	}

	@Test
	void wheelRotationRollsWithDistance() {
		CycleAnim a = new CycleAnim();
		a.reset(new V2(1, 0));
		a.step(0.01, new V2(1, 0), 30, true, 1, 1);
		// small-angle: angle ~ 2*speed*dt/.43, rotate() turns clockwise
		double ang = Math.atan2(-a.rotFront.y(), a.rotFront.x());
		assertEquals(Math.atan(2 * 30 * 0.01 / .43), ang, 1e-9);
	}

	@Test
	void cycleBodyMatrixMatchesGlTransformChain() {
		// facing north, no skew: model x (forward) -> world +y, model 0 -> p - 1.5*.5*dir
		double[] m = ArenaMesher.bodyMatrix(new V2(10, 20), new V2(0, 1), 0);
		double wx = m[0] * 1.5 + m[3], wy = m[4] * 1.5 + m[7];
		assertEquals(10, wx, 1e-9);
		assertEquals(20, wy, 1e-9);
		// model +y (left) -> world -x when facing north
		assertEquals(-0.5, m[1], 1e-9);
		// model z up scaled by .5
		assertEquals(0.5, m[10], 1e-9);
		// a right lean (skew > 0) lifts the left side
		double[] lean = ArenaMesher.bodyMatrix(new V2(0, 0), new V2(0, 1), 0.5);
		assertTrue(lean[9] > 0, "model +y gets a +z component");
	}

	@Test
	void lightingSaturatesLitFacesAndDarkensUnderside() {
		double[] up = ArenaMesher.light(0, 0, 1, 2);
		assertArrayEquals(new double[] {1, 1, 1}, up, 1e-9);
		double[] down = ArenaMesher.light(0, 0, -1, 2);
		assertArrayEquals(new double[] {0.04, 0.04, 0.04}, down, 1e-9);
	}

	@Test
	void wallHeadCurvesIntoTheCycleAndFades() {
		ArenaMesher mesher = ArenaMesher.create();
		Mesh quads = new Mesh(), lines = new Mesh();
		// 20 units of straight trail ending at the cycle at (20, 0) heading east
		List<ArenaMesher.TrailSeg> trail = List.of(new ArenaMesher.TrailSeg(new WallGeom.Seg(0, 0, 20, 0), null));
		ArenaMesher.CycleView cv = new ArenaMesher.CycleView(1, 0, 0, true, Double.NaN, new V2(20, 0), new V2(1, 0), 0);
		mesher.cycleWalls(quads, lines, trail, cv, 10);
		// bulk part: one quad; head part (last BEG_LEN*SEGLEN = 5 units): 5 quads
		assertEquals((1 + 5) * 4, quads.vertexCount());
		float[] d = quads.data();
		// RenderBegin is emitted first (5 quads), then RenderNormal for the bulk (like gNetPlayerWall::RenderList)
		// last head quad, vertex 3, sits at rat = 1: x = .4*20 + .6*(20-1.5) = 19.1, alpha 0, colour whitened
		int last = 19 * Mesh.STRIDE;
		assertEquals(19.1, d[last], 1e-4);
		assertEquals(0, d[last + 8], 1e-6);
		assertEquals(2, d[last + 5], 1e-6);
		// head top at rat = 1 is h*hfunc(1) = .5
		assertEquals(0.5, d[18 * Mesh.STRIDE + 2], 1e-6);
		// head starts at rat = 0 exactly where the bulk ends (x = 15), full height, alpha 1
		assertEquals(15, d[0], 1e-6);
		assertEquals(1, d[Mesh.STRIDE + 2], 1e-6);
		assertEquals(1, d[8], 1e-6);
		// bulk quad uses u = pos/SEGLEN: 0 .. 15/2.5
		int bulk = 20 * Mesh.STRIDE;
		assertEquals(0, d[bulk + 3], 1e-6);
		assertEquals(6, d[bulk + 2 * Mesh.STRIDE + 3], 1e-6);
		// east-west walls get full intensity .7 + .3
		assertEquals(1, d[bulk + 5], 1e-6);
	}

	@Test
	void deadWallsCollapseAfterStayUpDelay() {
		ArenaMesher mesher = ArenaMesher.create();
		List<ArenaMesher.TrailSeg> trail = List.of(new ArenaMesher.TrailSeg(new WallGeom.Seg(0, 0, 0, 40), null));
		ArenaMesher.CycleView dead = new ArenaMesher.CycleView(0, 0, 1, false, 5.0, new V2(0, 40), new V2(0, 1), 0);
		Mesh q = new Mesh(), l = new Mesh();
		mesher.cycleWalls(q, l, trail, dead, 5.0 + 2.0 + 0.25);   // dt = 0.5
		assertFalse(q.isEmpty());
		// top of the bulk quad at hfrac = .5
		assertEquals(0.5, q.data()[Mesh.STRIDE + 2], 1e-6);
		Mesh q2 = new Mesh();
		mesher.cycleWalls(q2, new Mesh(), trail, dead, 5.0 + 2.0 + 0.6);
		// bulk wall gone (dt > 1); only the head remnant (alpha 1-rat^2) may remain
		for (int i = 0; i < q2.vertexCount(); i++) {
			assertTrue(q2.data()[i * Mesh.STRIDE + 1] > 30, "only head geometry near the end remains");
		}
	}

	@Test
	void rimWallTextureMapping() {
		Mesh q = new Mesh();
		ArenaMesher.rimWall(q, new WallGeom.Seg(0, 0, 125, 0), 50);
		float[] d = q.data();
		assertEquals(0, d[3], 1e-6);
		assertEquals(1, d[4], 1e-6);
		assertEquals(0.5, d[Mesh.STRIDE + 4], 1e-6);    // v = 1 - 50/100
		assertEquals(1.25, d[2 * Mesh.STRIDE + 3], 1e-6); // u = 125/100
		assertEquals(1, d[5], 1e-6);                     // east-west rim: intensity 1
		Mesh q2 = new Mesh();
		ArenaMesher.rimWall(q2, new WallGeom.Seg(0, 0, 0, 125), 50);
		assertEquals(0.7, q2.data()[5], 1e-6);
	}

	@Test
	void rimLowersWhenItBlocksTheViewAndRegrows() {
		ArenaMesher.RimLowering rl = new ArenaMesher.RimLowering();
		List<WallGeom.Seg> rim = List.of(new WallGeom.Seg(0, 0, 100, 0));
		double[] eye = {50, -30, 20.75};
		double[] target = {50, 10, 0};
		double[] h = rl.update(rim, 50, 0.016, eye, List.of(target));
		// object-to-camera line crosses y=0 at time = 10/40 = .25: limit .5*(.5*20.75*.25 + 1.5*.75)
		double limit = .5 * (.5 * 20.75 * .25 + 1.5 * .75);
		assertEquals(limit, h[0], 1e-9);
		double[] h2 = rl.update(rim, 50, 0.1, null, List.of());
		assertEquals(limit * 2 + 0.5, h2[0], 1e-9);
	}

	@Test
	void explosionRaysGrowAndFade() {
		Mesh l = new Mesh();
		ArenaMesher.explosion(l, 0, 0, 1, 0, 0, 0.5);
		assertEquals(40 * 2, l.vertexCount());
		assertEquals(1, l.data()[8], 1e-6);
		Mesh l2 = new Mesh();
		ArenaMesher.explosion(l2, 0, 0, 1, 0, 0, 1.5);
		assertEquals(0.49, l2.data()[8], 1e-6);
		Mesh l3 = new Mesh();
		ArenaMesher.explosion(l3, 0, 0, 1, 0, 0, 2.5);
		assertTrue(l3.isEmpty());
	}
}

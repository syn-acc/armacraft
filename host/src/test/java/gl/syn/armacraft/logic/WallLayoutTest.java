package gl.syn.armacraft.logic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WallLayoutTest {

	private static final double EPS = 1e-9;

	@Test
	void singlePieceWithoutGaps() {
		List<WallLayout.Piece> p = WallLayout.pieces("w1", 0, 0, 0, 10, List.of(), 32);
		assertEquals(1, p.size());
		assertEquals("w1:0:0", p.get(0).key());
		assertEquals(10, p.get(0).length(), EPS);
	}

	@Test
	void gapsSplitWall() {
		List<WallLayout.Piece> p = WallLayout.pieces("w", 0, 0, 0, 40, List.of(new WallLayout.Gap(0.25, 0.30)), 100);
		assertEquals(2, p.size());
		assertEquals(0, p.get(0).sy(), EPS);
		assertEquals(10, p.get(0).ey(), EPS);
		assertEquals(12, p.get(1).sy(), EPS);
		assertEquals(40, p.get(1).ey(), EPS);
	}

	@Test
	void overlappingAndUnsortedGapsMerge() {
		List<double[]> s = WallLayout.solidIntervals(List.of(
			new WallLayout.Gap(0.6, 0.8), new WallLayout.Gap(0.1, 0.3), new WallLayout.Gap(0.25, 0.4), new WallLayout.Gap(0.9, 0.7)));
		assertEquals(3, s.size());
		assertEquals(0.0, s.get(0)[0], EPS);
		assertEquals(0.1, s.get(0)[1], EPS);
		assertEquals(0.4, s.get(1)[0], EPS);
		assertEquals(0.6, s.get(1)[1], EPS);
		assertEquals(0.9, s.get(2)[0], EPS);
		assertEquals(1.0, s.get(2)[1], EPS);
	}

	@Test
	void fullGapLeavesNothingAndBadGapsAreIgnored() {
		assertTrue(WallLayout.pieces("w", 0, 0, 10, 0, List.of(new WallLayout.Gap(0, 1)), 32).isEmpty());
		assertEquals(1, WallLayout.pieces("w", 0, 0, 10, 0, List.of(new WallLayout.Gap(Double.NaN, 0.5)), 32).size());
		assertTrue(WallLayout.pieces("w", 5, 5, 5, 5, List.of(), 32).isEmpty());
	}

	@Test
	void longWallSplitsOnGridWithStableKeysWhileGrowing() {
		List<WallLayout.Piece> a = WallLayout.pieces("w9", 0, 0, 70, 0, List.of(), 32);
		assertEquals(List.of("w9:0:0", "w9:1:0", "w9:2:0"), a.stream().map(WallLayout.Piece::key).toList());
		assertEquals(32, a.get(0).length(), EPS);
		assertEquals(32, a.get(1).length(), EPS);
		assertEquals(6, a.get(2).length(), EPS);
		List<WallLayout.Piece> b = WallLayout.pieces("w9", 0, 0, 75, 0, List.of(), 32);
		assertEquals(a.get(0), b.get(0));
		assertEquals(a.get(1), b.get(1));
		assertEquals("w9:2:0", b.get(2).key());
		assertEquals(11, b.get(2).length(), EPS);
	}

	@Test
	void gridAndGapsCombine() {
		List<WallLayout.Piece> p = WallLayout.pieces("w", 0, 0, 64, 0, List.of(new WallLayout.Gap(30 / 64.0, 34 / 64.0)), 32);
		assertEquals(2, p.size());
		assertEquals("w:0:0", p.get(0).key());
		assertEquals(30, p.get(0).ex(), EPS);
		assertEquals("w:1:0", p.get(1).key());
		assertEquals(34, p.get(1).sx(), EPS);
	}

	@Test
	void transformRotatesLocalZOntoDirectionAndCentresThickness() {
		double[][] dirs = {{0, 5}, {5, 0}, {0, -5}, {-5, 0}, {3, 4}};
		for (double[] d : dirs) {
			WallLayout.Transform t = WallLayout.transform(d[0], d[1], 0.2, 3.0);
			double len = Math.hypot(d[0], d[1]);
			assertEquals(len, t.sz(), 1e-5);
			assertEquals(0.2, t.sx(), 1e-6);
			assertEquals(3.0, t.sy(), 1e-6);
			// JOML rotationY(a) maps (0,0,1) -> (sin a, 0, cos a)
			assertEquals(d[0] / len, Math.sin(t.angle()), 1e-6);
			assertEquals(d[1] / len, Math.cos(t.angle()), 1e-6);
			// box spans local x in [0, thickness]; its centre line T + R*(t/2,0,0) must sit on the wall line (origin)
			// R*(1,0,0) = (cos a, 0, -sin a)
			double cx = t.tx() + Math.cos(t.angle()) * 0.1;
			double cz = t.tz() - Math.sin(t.angle()) * 0.1;
			assertEquals(0, cx, 1e-6);
			assertEquals(0, cz, 1e-6);
		}
	}
}

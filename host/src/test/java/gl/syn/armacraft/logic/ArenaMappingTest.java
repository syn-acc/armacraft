package gl.syn.armacraft.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArenaMappingTest {

	private static final double EPS = 1e-9;

	@Test
	void mapsGridToWorldPerProtocol() {
		ArenaMapping m = new ArenaMapping(100, 64, -50, 2.0);
		assertEquals(100 + 10 * 2.0, m.mcX(10), EPS);
		assertEquals(64, m.mcY(), EPS);
		// north (+y) is -z
		assertEquals(-50 - 5 * 2.0, m.mcZ(5), EPS);
		assertEquals(-50 + 5 * 2.0, m.mcZ(-5), EPS);
	}

	@Test
	void inverseRoundTrips() {
		ArenaMapping m = new ArenaMapping(12.5, 70, -3.25, 0.75);
		for (double v : new double[] {-100, -1.5, 0, 3.3, 99}) {
			assertEquals(v, m.arenaX(m.mcX(v)), EPS);
			assertEquals(v, m.arenaY(m.mcZ(v)), EPS);
		}
	}

	@Test
	void yawForCardinalHeadings() {
		assertEquals(180f, ArenaMapping.yaw(0, 1), 1e-4);   // north -> -z
		assertEquals(0f, ArenaMapping.yaw(0, -1), 1e-4);    // south -> +z
		assertEquals(-90f, ArenaMapping.yaw(1, 0), 1e-4);   // east -> +x
		assertEquals(90f, ArenaMapping.yaw(-1, 0), 1e-4);   // west -> -x
		assertEquals(0f, ArenaMapping.yaw(0, 0), 1e-4);     // degenerate heading
	}

	@Test
	void yawFacingMatchesMappedHeading() {
		ArenaMapping m = new ArenaMapping(0, 0, 0, 1);
		double[][] headings = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}, {0.6, 0.8}, {-0.28, 0.96}};
		for (double[] h : headings) {
			float yaw = ArenaMapping.yaw(h[0], h[1]);
			double[] f = ArenaMapping.facing(yaw);
			double ex = m.mcX(h[0]) - m.mcX(0);
			double ez = m.mcZ(h[1]) - m.mcZ(0);
			double n = Math.hypot(ex, ez);
			assertArrayEquals(new double[] {ex / n, ez / n}, f, 1e-6, "heading " + h[0] + "," + h[1]);
		}
	}

	@Test
	void rejectsBadScale() {
		assertThrows(IllegalArgumentException.class, () -> new ArenaMapping(0, 0, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new ArenaMapping(0, 0, 0, -1));
		assertThrows(IllegalArgumentException.class, () -> new ArenaMapping(0, 0, 0, Double.NaN));
	}
}

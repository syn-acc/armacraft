package gl.syn.armacraft.ride.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrostAreaTest {

	private static final int[][] HEADINGS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}}; // N E S W

	@Test
	void vanillaDiskRadius3AtBlockCentreIs25Columns() {
		Set<FrostArea.Col> cols = FrostArea.columns(10.5, -4.5, 1, 0, 0, 3, 1);
		assertEquals(25, cols.size());
		// |dx|,|dz| <= 2 all in (2^2 + 2^2 = 8 < 9), any 3 out (9 is not < 9)
		assertTrue(cols.contains(new FrostArea.Col(12, -3)));
		assertTrue(cols.contains(new FrostArea.Col(8, -7)));
		assertFalse(cols.contains(new FrostArea.Col(13, -5)));
		assertFalse(cols.contains(new FrostArea.Col(10, -8)));
	}

	@Test
	void diskFollowsVanillaDistToCenterOffCentre() {
		// entity at the corner of a block: membership is from block centres to the exact position
		double x = 0.0;
		double z = 0.0;
		Set<FrostArea.Col> cols = FrostArea.columns(x, z, 0, 0, 0, 3, 1);
		for (int bx = -5; bx <= 5; bx++) {
			for (int bz = -5; bz <= 5; bz++) {
				double dx = bx + 0.5 - x;
				double dz = bz + 0.5 - z;
				assertEquals(dx * dx + dz * dz < 9, cols.contains(new FrostArea.Col(bx, bz)), bx + "," + bz);
			}
		}
	}

	@Test
	void radiusZeroAndStoppedFreezeNothing() {
		assertTrue(FrostArea.columns(0.5, 0.5, 1, 0, 0, 0, 1).isEmpty());
	}

	@ParameterizedTest
	@CsvSource({"0,  0", "8, 4", "20, 10", "36, 18", "40, 20", "100, 32"})
	void lookaheadIsSpeedTimesHalfSecondCapped(double speed, double expected) {
		assertEquals(expected, FrostArea.lookaheadBlocks(speed, 0.5, 32), 1e-9);
	}

	@Test
	void lookaheadZeroForBadInput() {
		assertEquals(0, FrostArea.lookaheadBlocks(-5, 0.5, 32));
		assertEquals(0, FrostArea.lookaheadBlocks(Double.NaN, 0.5, 32));
		assertEquals(0, FrostArea.lookaheadBlocks(20, 0, 32));
		assertEquals(0, FrostArea.lookaheadBlocks(20, 0.5, 0));
	}

	@ParameterizedTest
	@CsvSource({"4", "8", "20", "36"})
	void stripAheadForEveryHeading(double speed) {
		double x = 100.5;
		double z = -50.5;
		int cx = 100;
		int cz = -51;
		double l = FrostArea.lookaheadBlocks(speed, 0.5, 32);
		int n = (int) Math.ceil(l);
		for (int[] h : HEADINGS) {
			int hx = h[0];
			int hz = h[1];
			Set<FrostArea.Col> cols = FrostArea.columns(x, z, hx, hz, l, 3, 1);
			int px = hz != 0 ? 1 : 0;
			int pz = hx != 0 ? 1 : 0;
			for (int i = 1; i <= n; i++) {
				for (int w = -1; w <= 1; w++) {
					assertTrue(cols.contains(new FrostArea.Col(cx + hx * i + px * w, cz + hz * i + pz * w)),
						"speed " + speed + " heading " + hx + "," + hz + " i=" + i + " w=" + w);
				}
				// strip is 3 wide: row +-2 only where the disk reaches
				if (i > 2) {
					assertFalse(cols.contains(new FrostArea.Col(cx + hx * i + px * 2, cz + hz * i + pz * 2)));
				}
			}
			// nothing past the lookahead (beyond the disk)
			int past = Math.max(n + 1, 3);
			assertFalse(cols.contains(new FrostArea.Col(cx + hx * past, cz + hz * past)), "past " + past);
			// nothing extra behind: behind the rider is the disk only
			assertFalse(cols.contains(new FrostArea.Col(cx - hx * 3, cz - hz * 3)));
			assertTrue(cols.contains(new FrostArea.Col(cx - hx * 2, cz - hz * 2)));
			// count = disk + strip cells outside the disk
			int expected = 25;
			for (int i = 1; i <= n; i++) {
				for (int w = -1; w <= 1; w++) {
					if (!FrostArea.inDisk(cx + hx * i + px * w, cz + hz * i + pz * w, x, z, 3)) {
						expected++;
					}
				}
			}
			assertEquals(expected, cols.size(), "count for speed " + speed + " heading " + hx + "," + hz);
		}
	}

	@Test
	void bikeAt20NeverOutrunsTheIce() {
		// each tick the rider moves 1 block; the frozen strip always reaches >= 9 blocks ahead of the new position
		double speed = 20;
		double l = FrostArea.lookaheadBlocks(speed, 0.5, 32);
		assertEquals(10, l, 1e-9);
		Set<FrostArea.Col> frozen = new java.util.HashSet<>();
		double x = 0.5;
		for (int tick = 0; tick < 40; tick++) {
			frozen.addAll(FrostArea.columns(x, 0.5, 1, 0, l, 3, 1));
			x += speed / 20.0;
			int bx = (int) Math.floor(x);
			for (int k = 0; k < 9; k++) {
				assertTrue(frozen.contains(new FrostArea.Col(bx + k, 0)), "tick " + tick + " +" + k);
			}
		}
	}

	@Test
	void supportLayerIsVanillaBlockBelowFeet() {
		assertEquals(62, FrostArea.supportLayer(63.0));
		assertEquals(62, FrostArea.supportLayer(63.9));
		assertEquals(61, FrostArea.supportLayer(62.95));
		assertEquals(-2, FrostArea.supportLayer(-0.5));
	}
}

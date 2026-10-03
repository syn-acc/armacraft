package gl.syn.armacraft.logic;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorsTest {

	@Test
	void stableAndInRange() {
		UUID u = UUID.fromString("0f3a1b2c-3d4e-5f60-7182-93a4b5c6d7e8");
		assertArrayEquals(Colors.fromUuid(u), Colors.fromUuid(u));
		for (int i = 0; i < 200; i++) {
			double[] c = Colors.fromUuid(UUID.randomUUID());
			for (double v : c) {
				assertTrue(v >= 0 && v <= 1.0000001);
			}
			assertEquals(1.0, Math.max(c[0], Math.max(c[1], c[2])), 1e-9);
		}
	}

	@Test
	void hsvPrimaries() {
		assertArrayEquals(new double[] {1, 0, 0}, Colors.hsvToRgb(0, 1, 1), 1e-9);
		assertArrayEquals(new double[] {0, 1, 0}, Colors.hsvToRgb(120, 1, 1), 1e-9);
		assertArrayEquals(new double[] {0, 0, 1}, Colors.hsvToRgb(240, 1, 1), 1e-9);
		assertArrayEquals(new double[] {1, 0, 0}, Colors.hsvToRgb(360, 1, 1), 1e-9);
	}
}

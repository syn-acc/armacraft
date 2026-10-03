package gl.syn.armacraft.logic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DyePaletteTest {

	@ParameterizedTest(name = "{0},{1},{2} -> {3}")
	@CsvSource({
		"1.0, 0.0, 0.0, red",
		"0.0, 0.0, 1.0, blue",
		"0.0, 1.0, 0.0, lime",
		"1.0, 1.0, 0.0, yellow",
		"0.0, 1.0, 1.0, cyan",
		"1.0, 0.0, 1.0, magenta",
		"1.0, 0.5, 0.0, orange",
		"0.1, 0.6, 1.0, light_blue",
		"1.0, 1.0, 1.0, white",
		"0.0, 0.0, 0.0, black",
		"0.5, 0.5, 0.5, light_gray",
		"0.25, 0.25, 0.27, gray",
		"0.5, 0.0, 1.0, purple",
		"1.0, 0.45, 0.65, pink",
		"0.4, 0.25, 0.1, brown",
		"0.3, 0.38, 0.15, green",
	})
	void picksPerceptuallyClosestDye(double r, double g, double b, String expected) {
		assertEquals(expected, DyePalette.closest(r, g, b).name());
	}

	@Test
	void clampsOutOfRangeAndNaN() {
		assertEquals("red", DyePalette.closest(2.0, -1.0, Double.NaN).name());
	}

	@Test
	void packsRgb() {
		assertEquals(0xFF0000, DyePalette.packRgb(1, 0, 0));
		assertEquals(0x1A99FF, DyePalette.packRgb(0.1, 0.6, 1.0));
		assertEquals(0x000000, DyePalette.packRgb(-5, Double.NaN, 0));
		assertEquals(0xFFFFFF, DyePalette.packRgb(9, 9, 9));
	}

	@Test
	void everyDyeMapsToItself() {
		for (DyePalette.Dye d : DyePalette.DYES) {
			assertEquals(d.name(), DyePalette.closest(d.r() / 255.0, d.g() / 255.0, d.b() / 255.0).name());
		}
	}
}

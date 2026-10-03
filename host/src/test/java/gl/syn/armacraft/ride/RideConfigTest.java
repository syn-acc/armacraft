package gl.syn.armacraft.ride;

import gl.syn.armacraft.ride.core.ArmaSettings;
import gl.syn.armacraft.ride.core.CycleParams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RideConfigTest {

	@Test
	void rclIsTheDefaultPreset() {
		RideConfig c = new RideConfig();
		c.sanitize();
		assertEquals("rcl", c.preset);
		ArmaSettings s = c.settings();
		// rcl-sumobar.cfg: CYCLE_ACCEL 20, CYCLE_DELAY 0.1, CYCLE_RUBBER 5, CYCLE_RUBBER_WALL_SHRINK 1, CYCLE_SPEED 30, WALLS_LENGTH 400
		assertEquals(20.0, s.get("CYCLE_ACCEL"));
		assertEquals(5.0, s.get("CYCLE_RUBBER"));
		assertEquals(1.0, s.get("CYCLE_RUBBER_WALL_SHRINK"));
		assertEquals(400.0, s.get("WALLS_LENGTH"));
		assertEquals(-10.0, s.get("DOUBLEBIND_TIME"));
		// everything else stays Armagetron's
		assertEquals(10.0, s.get("CYCLE_RUBBER_TIME"));
		assertEquals(0.0, s.get("CYCLE_ACCEL_RIM"));
		CycleParams p = c.params();
		assertEquals(18.0, p.speed, 1e-9);
		assertEquals(3.0, p.rubber, 1e-9);
		assertEquals(20 * 0.36, p.accel, 1e-9);
		assertEquals(240.0, c.trailLengthBlocks(), 1e-9);
	}

	@Test
	void armagetronPresetIsStock() {
		RideConfig c = new RideConfig();
		c.preset = "armagetron";
		c.sanitize();
		ArmaSettings s = c.settings();
		assertEquals(10.0, s.get("CYCLE_ACCEL"));
		assertEquals(1.0, s.get("CYCLE_RUBBER"));
		assertEquals(-1.0, s.get("WALLS_LENGTH"));
		assertEquals(c.trailCapBlocks, c.trailLengthBlocks(), 1e-9);
	}

	@Test
	void overridesAndResolvedRoundTrip() {
		RideConfig c = new RideConfig();
		c.overrides.put("CYCLE_RUBBER", 8.0);
		c.overrides.put("NOT_A_SETTING", 1.0);
		c.sanitize();
		assertEquals(8.0, c.settings().get("CYCLE_RUBBER"));
		RideConfig sent = RideConfig.fromJson(c.toResolvedJson());
		assertEquals("resolved", sent.preset);
		assertEquals(c.settings().asMap(), sent.settings().asMap());
		assertEquals(c.params().rubber, sent.params().rubber, 1e-12);
	}

	@Test
	void cfgParserHandlesCommentsAndStrings() {
		ArmaSettings s = ArmaSettings.defaults().apply("# comment\nCYCLE_SPEED 25 # trailing\nMAP_FILE foo/bar.aamap.xml\n  CYCLE_DELAY\t0.05\nGARBAGE\n");
		assertEquals(25.0, s.get("CYCLE_SPEED"));
		assertEquals(0.05, s.get("CYCLE_DELAY"));
		assertFalse(s.set("MAP_FILE", 1));
		assertTrue(s.has("cycle_rubber"));
	}

	@Test
	void boostIsOptionalAndScaled() {
		RideConfig c = new RideConfig();
		c.sanitize();
		assertEquals(20 * 0.6, c.params().boostAccel, 1e-9);
		c.boost.enabled = false;
		assertEquals(0.0, c.params().boostAccel, 1e-12);
	}

	@Test
	void sumobarAndFortressPresets() {
		RideConfig sumo = new RideConfig();
		sumo.preset = "sumobar";
		sumo.sanitize();
		RideConfig rcl = new RideConfig();
		rcl.sanitize();
		assertEquals(rcl.settings().asMap(), sumo.settings().asMap(), "sumobar is rcl");
		RideConfig fort = new RideConfig();
		fort.preset = "fortress";
		fort.sanitize();
		ArmaSettings f = fort.settings();
		assertEquals(20.0, f.get("CYCLE_ACCEL"));
		assertEquals(5.0, f.get("CYCLE_RUBBER"));
		assertEquals(1.0, f.get("CYCLE_RUBBER_WALL_SHRINK"));
		assertEquals(400.0, f.get("WALLS_LENGTH"));
		assertEquals(30.0, f.get("CYCLE_SPEED"), "no CYCLE_SPEED line: stock 0.2.9");
		assertEquals(0.1, f.get("CYCLE_DELAY"), "no CYCLE_DELAY line: stock 0.2.9");
		assertTrue(RideConfig.PRESETS.containsAll(java.util.List.of("rcl", "sumobar", "fortress", "armagetron")));
	}

	@Test
	void presetSwitchReResolves() {
		RideConfig c = new RideConfig();
		c.sanitize();
		assertEquals(5.0, c.settings().get("CYCLE_RUBBER"));
		c.preset = "armagetron";
		c.sanitize();
		assertEquals(1.0, c.settings().get("CYCLE_RUBBER"), "the cached resolution is dropped");
	}
}

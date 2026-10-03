package gl.syn.armacraft.client.ridekit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Importing the rider's Armagetron settings: file format, precedence, player keys. */
class ArmaCamSettingsTest {

	@Test
	void parsesArmagetronConfigLines() {
		Map<String, String> m = ArmaCamSettings.parse(List.of(
			"CAMERA_CUSTOM_BACK    6         # how much the custom camera is moved back",
			"#CAMERA_CUSTOM_BACK           15 # commented out",
			"                 START_FOV_1 85",
			"CAMERA_GLANCE_MODE 2",
			"CAMERA_GLANCE_MODE 0            # later line wins",
			"KEYBOARD\tSCANCODE_Q\tPLAYER_BIND\tGLANCE_LEFT\t1",
			"",
			"   # only a comment"));
		assertEquals("6", m.get("CAMERA_CUSTOM_BACK"));
		assertEquals("85", m.get("START_FOV_1"));
		assertEquals("0", m.get("CAMERA_GLANCE_MODE"));
	}

	@Test
	void profileOverridesInstallAndConfigOverridesBoth() {
		Map<String, String> install = Map.of("CAMERA_CUSTOM_TURN_SPEED", "4", "CAMERA_CUSTOM_BACK", "6", "START_FOV_1", "90");
		Map<String, String> profile = Map.of("START_FOV_1", "85", "START_FOV_2", "70", "CAMERA_CUSTOM_BACK", "8");
		ArmaCamSettings.Resolved r = ArmaCamSettings.resolve(install, profile, Map.of("CAMERA_CUSTOM_TURN_SPEED", "10"), 1, "I", "P");
		assertEquals(85, r.fov(), "player 1 key from the profile");
		assertEquals(8, r.camera().back, "profile over install");
		assertEquals(10, r.camera().turnSpeed, "our config over both");
		assertTrue(r.sources().get("CAMERA_CUSTOM_TURN_SPEED").contains("armacraft_client.json"));
		assertTrue(r.sources().get("START_FOV").startsWith("profile"));
		assertEquals(70, ArmaCamSettings.resolve(install, profile, Map.of(), 2, "I", "P").fov(), "player 2");
	}

	@Test
	void missingFilesFallBackToTheZ5693Setup() {
		ArmaCamSettings.Resolved r = ArmaCamSettings.resolve(Map.of(), Map.of(), Map.of(), 1, "-", "-");
		var c = r.camera();
		assertEquals(6, c.back);
		assertEquals(4, c.rise);
		assertEquals(.5, c.backFromSpeed);
		assertEquals(.4, c.riseFromSpeed);
		assertEquals(-.58, c.pitch);
		assertEquals(0, c.zoom);
		assertEquals(4, c.turnSpeed);
		assertEquals(4, c.turnSpeed180);
		assertEquals(0, c.glanceMode);
		assertFalse(c.glanceStacking);
		assertTrue(c.glanceReturn);
		assertEquals(.99, c.glanceReturnStop);
		assertEquals(-.5, c.glanceSnap);
		assertEquals(4 * Math.PI, c.glanceAngularVelocity, 1e-12, "not in settings_visual.cfg: eCamera.cpp default");
		assertEquals(85, r.fov());
		assertTrue(r.lowerWall());
		assertTrue(r.center());
		assertFalse(r.wobble());
	}

	@Test
	void booleansAcceptArmagetronForms() {
		assertTrue(ArmaCamSettings.bool(Map.of("K", "1"), "K", false));
		assertFalse(ArmaCamSettings.bool(Map.of("K", "0"), "K", true));
		assertTrue(ArmaCamSettings.bool(Map.of("K", "true"), "K", false));
		assertTrue(ArmaCamSettings.bool(Map.of(), "K", true));
	}
}

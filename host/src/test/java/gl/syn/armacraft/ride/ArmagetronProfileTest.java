package gl.syn.armacraft.ride;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ArmagetronProfileTest {

	/** The shape of an Armagetron Experimental user_3_1_utf8.cfg (synthetic: binds, colour and name only). */
	static final String CFG = String.join("\n",
		"                 ALLOW_CAM_1_1 1",
		"                   COLOR_B_1 2",
		"                   COLOR_G_1 0",
		"                   COLOR_R_1 11",
		"                    KEYBOARD SCANCODE_A\tPLAYER_BIND\tGLANCE_LEFT\t1",
		"KEYBOARD\tSCANCODE_D\tPLAYER_BIND\tCYCLE_TURN_LEFT\t1",
		"KEYBOARD\tSCANCODE_E\tPLAYER_BIND\tCYCLE_TURN_LEFT\t1",
		"KEYBOARD\tSCANCODE_F\tPLAYER_BIND\tCYCLE_TURN_LEFT\t1",
		"KEYBOARD\tSCANCODE_I\tPLAYER_BIND\tCYCLE_TURN_RIGHT\t1",
		"KEYBOARD\tSCANCODE_J\tPLAYER_BIND\tCYCLE_TURN_RIGHT\t1",
		"KEYBOARD\tSCANCODE_K\tPLAYER_BIND\tCYCLE_TURN_RIGHT\t1",
		"KEYBOARD\tSCANCODE_L\tPLAYER_BIND\tCYCLE_TURN_RIGHT\t1",
		"KEYBOARD\tSCANCODE_O\tPLAYER_BIND\tCYCLE_TURN_RIGHT\t1",
		"KEYBOARD\tSCANCODE_P\tPLAYER_BIND\tGLANCE_RIGHT\t1",
		"KEYBOARD\tSCANCODE_Q\tPLAYER_BIND\tGLANCE_LEFT\t1",
		"KEYBOARD\tSCANCODE_S\tPLAYER_BIND\tCYCLE_TURN_LEFT\t1",
		"KEYBOARD\tSCANCODE_V\tPLAYER_BIND\tGLANCE_BACK\t1",
		"KEYBOARD\tSCANCODE_W\tPLAYER_BIND\tCYCLE_TURN_LEFT\t1",
		"KEYBOARD\tSCANCODE_1\tPLAYER_BIND\tINSTANT_CHAT_1\t1",
		"KEYBOARD\tSCANCODE_RETURN\tPLAYER_BIND\tCHAT\t1",
		"KEYBOARD\tSCANCODE_ESCAPE\tPLAYER_BIND\tINGAME_MENU\t0",
		"KEYBOARD\tSCANCODE_SPACE\tPLAYER_BIND\tCYCLE_BRAKE_TOGGLE\t1",
		"KEYBOARD\tSCANCODE_;\tPLAYER_BIND\tGLANCE_RIGHT\t1",
		"KEYBOARD\tSCANCODE_X\tPLAYER_BIND\tCYCLE_TURN_LEFT\t2",
		"                    PASSWORD 1 2 3 \tmd5||\tsomeone",
		"                    PLAYER_1 syn",
		"                    PLAYER_2 other\\ name");

	@Test
	void readsPlayerOneBindsColourAndName() {
		ArmagetronProfile p = ArmagetronProfile.parse(CFG);
		assertEquals(List.of("key.keyboard.d", "key.keyboard.e", "key.keyboard.f", "key.keyboard.s", "key.keyboard.w"), p.binds.get("turn_left"),
			"player 2's X is not player 1's");
		assertEquals(List.of("key.keyboard.i", "key.keyboard.j", "key.keyboard.k", "key.keyboard.l", "key.keyboard.o"), p.binds.get("turn_right"));
		assertEquals(List.of("key.keyboard.a", "key.keyboard.q"), p.binds.get("glance_left"));
		assertEquals(List.of("key.keyboard.p", "key.keyboard.semicolon"), p.binds.get("glance_right"));
		assertEquals(List.of("key.keyboard.v"), p.binds.get("glance_back"));
		assertEquals(List.of("key.keyboard.space"), p.binds.get("brake_toggle"));
		assertNull(p.binds.get("brake"));
		assertArrayEquals(new double[] {11 / 15.0, 0, 2 / 15.0}, p.color, 1e-12);
		assertEquals("syn", p.name);
	}

	@Test
	void configWinsOverProfileWhichWinsOverBuiltins() {
		ArmagetronProfile p = ArmagetronProfile.parse(CFG);
		RideConfig c = new RideConfig();
		c.sanitize();
		RideConfig.Keys k = c.effectiveKeys(p);
		assertEquals(p.binds.get("turn_left"), k.turnLeft, "profile");
		assertEquals(List.of("key.keyboard.down"), k.brake, "built-in: Armagetron has no hold brake bound");
		assertEquals(List.of("key.keyboard.left.shift"), k.accelerate, "built-in boost");
		assertEquals(List.of("key.keyboard.backspace"), k.exit, "built-in exit");
		c.keys.turnLeft = List.of("key.keyboard.z");
		assertEquals(List.of("key.keyboard.z"), c.effectiveKeys(p).turnLeft, "this file wins");
		assertEquals(0xBB0022, c.riderRgb(p), "COLOR 11/0/2 of 15");
		assertEquals("syn", c.riderNameOr(p));
		c.riderColor = new double[] {0, 1, 0};
		c.riderName = "x";
		assertEquals(0x00FF00, c.riderRgb(p));
		assertEquals("x", c.riderNameOr(p));
		assertEquals(-1, new RideConfig().riderRgb(null), "no profile, no colour: the server derives one");
	}

	@Test
	void builtinsAreTheUsersBindsWithoutAProfile() {
		RideConfig c = new RideConfig();
		c.sanitize();
		RideConfig.Keys k = c.effectiveKeys(null);
		assertEquals(List.of("key.keyboard.d", "key.keyboard.e", "key.keyboard.f", "key.keyboard.s", "key.keyboard.w", "key.keyboard.left"), k.turnLeft);
		assertEquals(List.of("key.keyboard.v"), k.glanceBack);
		assertEquals(List.of("key.keyboard.space"), k.brakeToggle);
	}

	@Test
	void previousDefaultKeysAreMigrated() {
		String old = "{\"keys\":{\"turn_left\":[\"key.keyboard.w\",\"key.keyboard.e\",\"key.keyboard.f\",\"key.keyboard.left\"],"
			+ "\"turn_right\":[\"key.keyboard.j\",\"key.keyboard.i\",\"key.keyboard.o\",\"key.keyboard.right\"],"
			+ "\"brake\":[\"key.keyboard.space\",\"key.keyboard.down\"],\"accelerate\":[\"key.keyboard.v\"],"
			+ "\"glance_left\":[\"key.keyboard.q\"],\"glance_right\":[\"key.keyboard.p\"],\"dismount\":[\"key.keyboard.left.shift\"]}}";
		RideConfig c = RideConfig.fromJson(old);
		assertNull(c.keys.turnLeft, "an untouched old default file follows the new binds");
		String custom = old.replace("\"key.keyboard.q\"", "\"key.keyboard.z\"");
		assertEquals(List.of("key.keyboard.z"), RideConfig.fromJson(custom).keys.glanceLeft, "a customised file is kept");
	}

	@Test
	void scancodeNames() {
		assertEquals("key.keyboard.semicolon", ArmagetronProfile.keyName("SCANCODE_;"));
		assertEquals("key.keyboard.enter", ArmagetronProfile.keyName("SCANCODE_RETURN"));
		assertEquals("key.keyboard.keypad.5", ArmagetronProfile.keyName("SCANCODE_KEYPAD_5"));
		assertEquals("key.keyboard.f13", ArmagetronProfile.keyName("SCANCODE_F13"));
		assertNull(ArmagetronProfile.keyName("SCANCODE_WHATEVER"));
	}
}

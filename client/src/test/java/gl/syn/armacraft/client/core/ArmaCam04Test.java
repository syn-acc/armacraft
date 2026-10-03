package gl.syn.armacraft.client.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Armagetron 0.4 custom camera port (eCamera.cpp trunk) with the user's z5693 settings. */
class ArmaCam04Test {

	private static final V2 NORTH = new V2(0, 1), EAST = new V2(1, 0), WEST = new V2(-1, 0), SOUTH = new V2(0, -1);

	/** settings_visual.cfg of Armagetron Experimental 0.4.0_alpha_z5693. */
	static ArmaCam04.Settings z5693() {
		ArmaCam04.Settings s = new ArmaCam04.Settings();
		s.back = 6;
		s.rise = 4;
		s.backFromSpeed = .5;
		s.riseFromSpeed = .4;
		s.pitch = -.58;
		s.zoom = 0;
		s.turnSpeed = 4;
		s.turnSpeed180 = 4;
		s.glanceMode = 0;
		s.glanceStacking = false;
		s.glanceReturn = true;
		s.glanceReturnStop = .99;
		s.glanceSnap = -.5;
		return s;
	}

	private static ArmaCam04 cam(double speed) {
		ArmaCam04 c = new ArmaCam04();
		c.settings = z5693();
		c.reset(new V2(0, 0), NORTH, ArmaCam04.CYCLE_CAM_Z, speed);
		return c;
	}

	private static double angle(V2 a, V2 b) {
		return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, a.dot(b)))));
	}

	@Test
	void speedDependentBackAndRise() {
		ArmaCam04 c = cam(0);
		V2 p = new V2(0, 0);
		for (int i = 0; i < 1200; i++) { // 20 s at 60 fps, 30 u/s north
			p = p.add(NORTH.mul(30 / 60.0));
			c.step(1 / 60.0, 100, p, ArmaCam04.CYCLE_CAM_Z, NORTH, NORTH, NORTH, 30);
		}
		assertEquals(30, c.centerSpeedSmooth(), 1e-6);
		assertEquals(21, c.customBack(), 1e-6, "6 + .5 * 30");
		assertEquals(16, c.customRise(), 1e-6, "4 + .4 * 30");
		assertEquals(21, p.sub(c.pos).norm(), 1e-6, "eye 21 units behind");
		assertEquals(16.75, c.z, 1e-6, "eye 16 units above CamZ .75");
		assertEquals(-.58, c.rise, 1e-12);
		// SMOOTH_SPEED 1: css = (css + v ts) / (1 + ts) -> 1 - e^-t from rest
		ArmaCam04 d = cam(0);
		for (int i = 0; i < 60; i++) {
			d.step(1 / 60.0, 100, new V2(0, 0), .75, NORTH, NORTH, NORTH, 30);
		}
		assertEquals(30 * (1 - Math.exp(-1)), d.centerSpeedSmooth(), 0.15, "about 63 % after one second");
	}

	@Test
	void turnSpeed4FollowsTheClosedForm() {
		// a 90 degree turn of the (body) direction: d(theta)/dt = -4 sin(theta) -> tan(theta/2) = tan(45) e^(-4t)
		ArmaCam04 c = cam(30);
		double t = 0;
		double[] at = {0.25, 0.5, 1.0};
		int k = 0;
		for (int i = 1; i <= 600 && k < at.length; i++) {
			c.step(1 / 600.0, 100, new V2(0, 0), .75, EAST, EAST, NORTH, 30);
			t = i / 600.0;
			if (Math.abs(t - at[k]) < 1e-9) {
				double expect = Math.toDegrees(2 * Math.atan(Math.exp(-4 * t)));
				assertEquals(expect, angle(c.dir, EAST), 0.5, "view-to-heading angle at " + t + " s");
				k++;
			}
		}
		assertEquals(3, k);
	}

	@Test
	void reversalUsesTurnSpeed180TowardsTheLastDirection() {
		ArmaCam04 c = cam(30);
		// U-turn: north -> south (two quick turns via east); the view swings through east, not straight through
		c.step(1 / 60.0, 100, new V2(0, 0), .75, SOUTH, SOUTH, EAST, 30);
		assertTrue(c.dir.x() > 0.05, "swings towards the last direction (east): " + c.dir);
	}

	@Test
	void glanceLeftRotatesAtFourPiPerSecondThenReturns() {
		ArmaCam04 c = cam(30);
		c.step(1 / 600.0, 100, new V2(0, 0), .75, NORTH, NORTH, NORTH, 30);
		c.glance(ArmaCam04.GLANCE_LEFT, true);
		assertTrue(c.glancing());
		for (int i = 0; i < 60; i++) { // 0.1 s: 4 pi * 0.1 = 72 degrees
			c.step(1 / 600.0, 100, new V2(0, 0), .75, NORTH, NORTH, NORTH, 30);
		}
		assertEquals(72, angle(c.dir, NORTH), 1.5, "mode 0: constant angular velocity");
		assertTrue(c.dir.x() < 0, "left of north is west");
		for (int i = 0; i < 60; i++) {
			c.step(1 / 600.0, 100, new V2(0, 0), .75, NORTH, NORTH, NORTH, 30);
		}
		assertEquals(0, angle(c.dir, WEST), 1e-6, "arrives exactly");
		c.glance(ArmaCam04.GLANCE_LEFT, false);
		assertTrue(c.glancing(), "release starts the return glance");
		int steps = 0;
		while (c.glancing() && steps < 1000) {
			c.step(1 / 600.0, 100, new V2(0, 0), .75, NORTH, NORTH, NORTH, 30);
			steps++;
		}
		assertFalse(c.glancing(), "return glance ends");
		assertTrue(angle(c.dir, NORTH) <= Math.toDegrees(Math.acos(.99)) + 0.5, "stopped within RETURN_STOP (cos .99 = 8.1 deg): " + angle(c.dir, NORTH));
		assertTrue(steps > 60 && steps < 100, "return takes ~(90-8)/720 s = 0.11 s: " + steps + " steps of 1/600");
	}

	@Test
	void glanceBackSnapsAndReleaseSnapsForward() {
		ArmaCam04 c = cam(30);
		c.step(1 / 60.0, 100, new V2(0, 0), .75, NORTH, NORTH, NORTH, 30);
		c.glance(ArmaCam04.GLANCE_BACK, true);
		c.step(1 / 60.0, 100, new V2(0, 0), .75, NORTH, NORTH, NORTH, 30);
		assertEquals(0, angle(c.dir, SOUTH), 1e-6, "cos -1 < CAMERA_GLANCE_SNAP -.5: instant");
		c.glance(ArmaCam04.GLANCE_BACK, false); // includes the zero timestep of eCamera 880
		assertEquals(0, angle(c.dir, NORTH), 1e-6, "return glance snaps forward in the zero step");
		assertFalse(c.glancing(), "and stops at once (cos 1 >= .99)");
	}

	@Test
	void aTurnCancelsTheReturnGlance() {
		ArmaCam04 c = cam(30);
		c.step(1 / 60.0, 100, new V2(0, 0), .75, NORTH, NORTH, NORTH, 30);
		c.glance(ArmaCam04.GLANCE_RIGHT, true);
		for (int i = 0; i < 10; i++) {
			c.step(1 / 60.0, 100, new V2(0, 0), .75, NORTH, NORTH, NORTH, 30);
		}
		c.glance(ArmaCam04.GLANCE_RIGHT, false);
		assertTrue(c.glancing());
		c.step(1 / 60.0, 100, new V2(0, 0), .75, WEST, WEST, NORTH, 30); // the cycle turns left
		assertFalse(c.glancing(), "1970-1973: a turn removes the return request");
	}

	@Test
	void newestGlanceWinsAndReleasingItFallsBackToTheHeldOne() {
		ArmaCam04 c = cam(30);
		c.step(1 / 60.0, 100, new V2(0, 0), .75, NORTH, NORTH, NORTH, 30);
		c.glance(ArmaCam04.GLANCE_LEFT, true);
		c.glance(ArmaCam04.GLANCE_RIGHT, true);
		for (int i = 0; i < 30; i++) {
			c.step(1 / 60.0, 100, new V2(0, 0), .75, NORTH, NORTH, NORTH, 30);
		}
		assertEquals(0, angle(c.dir, EAST), 1e-6);
		c.glance(ArmaCam04.GLANCE_RIGHT, false);
		for (int i = 0; i < 30; i++) {
			c.step(1 / 60.0, 100, new V2(0, 0), .75, NORTH, NORTH, NORTH, 30);
		}
		assertEquals(0, angle(c.dir, WEST), 1e-6, "left is still held");
	}

	@Test
	void zeroTimestepChangesNothingWithoutGlances() {
		ArmaCam04 c = cam(30);
		c.step(1 / 60.0, 100, new V2(0, 0), .75, EAST, EAST, NORTH, 30);
		V2 pos = c.pos;
		V2 dir = c.dir;
		double z = c.z;
		for (int i = 0; i < 100; i++) {
			c.step(0, 100, new V2(0, 0), .75, EAST, EAST, NORTH, 30);
		}
		assertEquals(0, pos.dist(c.pos), 1e-12);
		assertEquals(0, dir.dist(c.dir), 1e-12);
		assertEquals(z, c.z, 1e-12);
	}
}

package gl.syn.armacraft.client.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CameraAndMappingTest {

	@Test
	void mappingMatchesProtocol() {
		ArenaMapping m = new ArenaMapping(100, 64, -50, 2);
		assertEquals(100 + 20, m.mcX(10), 1e-9);
		assertEquals(-50 - 10, m.mcZ(5), 1e-9);
		assertEquals(64 + 2 * 0.75, m.mcY(0.75), 1e-9);
		assertEquals(10, m.arenaX(m.mcX(10)), 1e-9);
		assertEquals(5, m.arenaY(m.mcZ(5)), 1e-9);
		assertEquals(180f, ArenaMapping.yaw(0, 1), 1e-4);   // north
		assertEquals(0f, ArenaMapping.yaw(0, -1), 1e-4);    // south
		assertEquals(-90f, ArenaMapping.yaw(1, 0), 1e-4);   // east
		assertEquals(90f, ArenaMapping.yaw(-1, 0), 1e-4);   // west
		assertEquals(34.99f, ArenaMapping.pitch(1, -0.7), 0.01f);
		assertThrows(IllegalArgumentException.class, () -> new ArenaMapping(0, 0, 0, 0));
	}

	@Test
	void customCameraSitsBackAndAboveLookingDown() {
		ArmaCam cam = new ArmaCam();
		V2 pos = new V2(50, 40);
		V2 dir = new V2(0, 1);
		cam.reset(pos, dir);
		for (int i = 0; i < 120; i++) {
			cam.step(1 / 60.0, 10, pos, dir, dir, ArmaCam.CYCLE_CAM_Z);
		}
		assertEquals(50, cam.eyeX, 1e-6);
		assertEquals(40 - 30, cam.eyeY, 1e-6);
		assertEquals(0.75 + 20, cam.eyeZ, 1e-9);
		assertEquals(0, cam.lookX, 1e-6);
		assertEquals(1, cam.lookY, 1e-6);
		assertEquals(-0.7, cam.lookRise, 1e-9);
	}

	@Test
	void customCameraChasesTurnsAtTurnSpeed() {
		ArmaCam cam = new ArmaCam();
		V2 pos = new V2(0, 0);
		cam.reset(pos, new V2(0, 1));
		V2 east = new V2(1, 0);
		cam.step(1 / 60.0, 10, pos, east, new V2(0, 1), .75);
		// newdir = dir + cycleDir*40*ts -> angle atan2(1, 40/60)
		double expectedAngle = Math.atan2(1, 40 / 60.0);
		assertEquals(Math.cos(expectedAngle), cam.lookX, 1e-9);
		for (int i = 0; i < 60; i++) {
			cam.step(1 / 60.0, 10, pos, east, new V2(0, 1), .75);
		}
		assertEquals(1, cam.lookX, 1e-3);
	}

	@Test
	void preRoundZoomScalesBackAndRise() {
		ArmaCam cam = new ArmaCam();
		V2 pos = new V2(0, 0);
		cam.reset(pos, new V2(1, 0));
		cam.step(0.01, -2, pos, new V2(1, 0), new V2(1, 0), .75);
		double zoom = Math.exp(0.5 * -2);
		assertEquals(-30 * zoom, cam.eyeX, 1e-6);
		assertEquals(.75 + 20 * zoom, cam.eyeZ, 1e-9);
	}

	@Test
	void inCycleCameraAndGlance() {
		ArmaCam cam = new ArmaCam();
		cam.mode = ArmaCam.Mode.IN;
		V2 pos = new V2(3, 4);
		cam.reset(pos, new V2(1, 0));
		cam.step(1 / 60.0, 5, pos, new V2(1, 0), new V2(1, 0), .75);
		assertEquals(3, cam.eyeX, 1e-9);
		assertEquals(4, cam.eyeY, 1e-9);
		assertEquals(.75, cam.eyeZ, 1e-9);
		cam.glanceBack = true;
		cam.step(1 / 60.0, 5, pos, new V2(1, 0), new V2(1, 0), .75);
		assertEquals(-1, cam.lookX, 1e-9);
	}

	@Test
	void armagetronFovIsHorizontalWithWidescreenCorrection() {
		// 90 deg at 16:9 -> vertical 2*atan(1/1.5)
		assertEquals(Math.toDegrees(2 * Math.atan(1 / 1.5)), ArmaCam.verticalFov(90, 16 / 9.0), 1e-9);
		// 4:3 -> plain horizontal 90 deg
		assertEquals(Math.toDegrees(2 * Math.atan(0.75)), ArmaCam.verticalFov(90, 4 / 3.0), 1e-9);
	}

	@Test
	void gameClockTracksLeastDelayedSampleAndRoundResets() {
		GameClock c = new GameClock();
		c.sample(10.0, 100.0);          // delay unknown
		c.sample(10.034, 100.040);      // 6 ms later than expected: ignored
		assertEquals(10.0 - 100.0, c.offset(), 1e-3);
		c.sample(10.1, 100.095);        // less delayed: offset rises
		assertEquals(10.1 - 100.095, c.offset(), 1e-9);
		c.sample(-4.0, 101.0);          // new round
		assertEquals(-4.0 - 101.0, c.offset(), 1e-9);
		assertEquals(-3.5, c.gameTime(101.5), 1e-9);
	}
}

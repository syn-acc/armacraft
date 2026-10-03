package gl.syn.armacraft.client.kit;

import gl.syn.armacraft.client.core.V2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The bridge-free pieces ride mode reuses. */
class KitTest {

	@Test
	void yawHeadingRoundTrip() {
		for (float yaw : new float[] {0, 90, 180, -90, 45}) {
			V2 h = BikeAnimator.headingFromYaw(yaw);
			float back = BikeAnimator.yawFromHeading(h);
			assertEquals(0, Math.abs(Math.IEEEremainder(back - yaw, 360)), 1e-3, "yaw " + yaw);
		}
		// MC yaw 180 faces north (-Z) = Armagetron +y
		V2 north = BikeAnimator.headingFromYaw(180);
		assertEquals(0, north.x(), 1e-9);
		assertEquals(1, north.y(), 1e-9);
	}

	@Test
	void chaseCamSitsBehindTargetInMinecraftSpace() {
		ChaseCam cam = new ChaseCam();
		Vec3 target = new Vec3(100, 64, -20);
		cam.reset(target, 0);   // facing south (+Z)
		for (int i = 0; i < 120; i++) {
			cam.update(1 / 60.0, target, 0, 0, 30, 2.0, Double.POSITIVE_INFINITY);
		}
		// 30 units back = 60 blocks north of the target, 20.75 units up = 41.5 blocks
		assertEquals(100, cam.eye().x, 1e-6);
		assertEquals(-20 - 60, cam.eye().z, 1e-6);
		assertEquals(64 + 41.5, cam.eye().y, 1e-6);
		assertEquals(0, cam.yaw(), 1e-3);
		assertEquals(34.99, cam.pitch(), 0.01);
		cam.setMode(ChaseCam.Mode.IN_CYCLE);
		cam.update(1 / 60.0, target, 0, 0, 30, 2.0, Double.POSITIVE_INFINITY);
		assertEquals(-20, cam.eye().z, 1e-6);
		assertEquals(64 + 1.5, cam.eye().y, 1e-6);
	}

	@Test
	void bikeAnimatorLeansIntoTurnsAndRollsWheels() {
		BikeAnimator a = new BikeAnimator();
		a.reset(180);
		a.step(1 / 60.0, 180, 30, true, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
		double w0 = a.rearWheelAngle();
		assertTrue(w0 > 0, "forward roll positive");
		a.step(1 / 60.0, 90, 30, true, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);   // north -> west: left turn
		assertTrue(a.skew() < 0, "left turn leans left: " + a.skew());
		float body = a.bodyYaw();
		assertTrue(body > 90 && body < 180, "body swings between old and new heading: " + body);
	}

	@Test
	void soundMixMatchesArmagetronLaw() {
		Vec3 eye = new Vec3(0, 20.75, 30);   // 30 back, 20.75 up, looking north
		Vec3 src = new Vec3(0, 0.75, 0);
		double level = ArmaSoundMix.level(eye, 180, src, 1, true, false);
		double dist = Math.sqrt(30 * 30 + 20 * 20);
		assertEquals(.9 * .5 / dist, level, 1e-9);
		assertEquals(.2, ArmaSoundMix.level(eye, 180, src, 1, true, true), 1e-12);
		assertEquals(.4, ArmaSoundMix.level(src, 180, src, 1, false, false), 1e-12);
	}

	/** Window shapes the HUD has to fit: the user's 810x1440 portrait recording window, 4:3, 16:10, 16:9 and ultrawide. */
	private static final float[][] WINDOWS = {{810, 1440}, {1024, 768}, {1280, 800}, {1600, 900}, {1920, 1080}, {2560, 1080}};

	@Test
	void screenAreaIsFourByThreeAtTheBottomOfAnyWindow() {
		for (float[] wh : WINDOWS) {
			float[] v = ArmaScreen.viewport(wh[0], wh[1]);
			String at = wh[0] + "x" + wh[1];
			assertEquals(4.0 / 3.0, v[0] / v[1], 1e-4, "x and y units in 4:3 proportion " + at);
			assertTrue(2 * v[0] <= wh[0] + 1e-3 && 2 * v[1] <= wh[1] + 1e-3, "inside the window " + at);
			assertEquals(wh[0] / 2, v[2], 1e-4, "centred " + at);
			assertEquals(wh[1], v[3] + v[1], 1e-3, "stands on the bottom edge " + at);
		}
		// 4:3 or wider: as before, the full window height
		assertArrayEquals(new float[] {600, 450, 800, 450}, ArmaScreen.viewport(1600, 900), 1e-3f);
		// portrait: as wide as the window
		assertArrayEquals(new float[] {405, 303.75f, 405, 1440 - 303.75f}, ArmaScreen.viewport(810, 1440), 1e-3f);
	}

	@Test
	void rideGaugeRowIsOneLineAndFitsEveryWindow() {
		for (float[] wh : WINDOWS) {
			String at = wh[0] + "x" + wh[1];
			float[] v = ArmaScreen.viewport(wh[0], wh[1]);
			double windowHalf = (wh[0] / 2) / v[0];
			ArmaGauges.RideRow row = ArmaGauges.rideRow(windowHalf);
			double[] xs = {row.rubberX(), row.speedX(), row.brakesX(), row.boostX()};
			// one row: Rubber Used, Speed, Brakes, Boost left to right with Armagetron's spacing for its gauge size
			for (int i = 1; i < xs.length; i++) {
				assertEquals(ArmaGauges.SPACING / ArmaGauges.SIZE, (xs[i] - xs[i - 1]) / row.size(), 1e-9, "spacing " + i + " " + at);
			}
			// nothing off the sides or under the bottom edge
			for (double x : xs) {
				double[] e = ArmaGauges.meterExtent(x, row.size());
				assertTrue(e[0] >= -windowHalf && e[1] <= windowHalf, "gauge at " + x + " inside the window " + at + ": " + e[0] + " .. " + e[1]);
			}
			assertTrue(row.y() - .24 * row.size() >= -1, "labels above the bottom edge " + at);
			assertTrue(row.size() >= 0.9 * ArmaGauges.SIZE, "close to Armagetron's size " + at + ": " + row.size());
			// centred: Armagetron's three about the middle while Boost comes and goes, or all four when Boost is always there
			if (row.boostAlways()) {
				assertEquals(0, xs[0] + xs[3], 1e-12, "four centred " + at);
				assertEquals(0, xs[1] + xs[2], 1e-12, "four centred " + at);
			} else {
				assertEquals(0, xs[1], 1e-12, "Speed in the middle " + at);
				assertEquals(0, xs[0] + xs[2], 1e-12, "Rubber Used and Brakes mirrored " + at);
			}
		}
		// 16:9 and wider: exactly Armagetron's row, Boost appended and shown only while in use
		assertEquals(new ArmaGauges.RideRow(.175, -.9, -.48, 0, .48, .96, false), ArmaGauges.rideRow((1600 / 2.0) / 600));
		assertFalse(ArmaGauges.rideRow((2560 / 2.0) / 720).boostAlways());
		// the 810x1440 recording window, 4:3 and 16:10: four centred, always whole
		assertTrue(ArmaGauges.rideRow(1.0).boostAlways());
		assertTrue(ArmaGauges.rideRow(1.2).boostAlways());
		assertEquals(.175, ArmaGauges.rideRow(1.2).size(), 1e-12, "16:10 keeps Armagetron's size");
	}
}

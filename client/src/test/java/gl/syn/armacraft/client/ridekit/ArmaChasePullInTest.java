package gl.syn.armacraft.client.ridekit;

import gl.syn.armacraft.client.ClientConfig;
import gl.syn.armacraft.client.ride.api.ChaseCamera;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The camera's obstacle pull-in: 1-block steps must never move the camera in (the 0.1.6 bug: a ray started inside the
 * step ahead and the camera snapped onto the bike), real occlusion must be sustained before it reacts, and it eases.
 */
class ArmaChasePullInTest {

	/** Block world from a column height function (x only): rays march through it like Minecraft's block clip. */
	static ArmaChaseCamera.World voxels(DoubleUnaryOperator ground) {
		return new ArmaChaseCamera.World() {
			@Override
			public double groundY(double x, double z) {
				return ground.applyAsDouble(Math.floor(x));
			}

			@Override
			public double clearFraction(Vec3 pivot, Vec3 want) {
				return ArmaChaseCamera.fanClear(pivot, want, (from, to) -> {
					Vec3 d = to.subtract(from);
					double len = d.length();
					int n = (int) Math.ceil(len / 0.01);
					for (int i = 0; i <= n; i++) {
						Vec3 p = from.add(d.scale(i / (double) n));
						if (p.y < ground.applyAsDouble(Math.floor(p.x))) {
							return len * i / n;
						}
					}
					return Double.NaN;
				});
			}
		};
	}

	/** Ten 1-block steps up (3 blocks each) from x = 20, a plateau, ten down from x = 60. */
	static double stairs(double bx) {
		if (bx < 20) {
			return 64;
		}
		if (bx < 50) {
			return 64 + Math.floor((bx - 20) / 3) + 1;
		}
		if (bx < 60) {
			return 74;
		}
		if (bx < 90) {
			return 74 - Math.floor((bx - 60) / 3) - 1 + 1;
		}
		return 64;
	}

	private static ChaseCamera.Target east(double x, double y) {
		return new ChaseCamera.Target(new Vec3(x, y, 0.5), -90f, -90f, 10, 18, -90f, 20f, 0.6, 10, false, false, false);
	}

	private static ClientConfig.Ride cfg(String pullIn) {
		ClientConfig.Ride c = new ClientConfig.Ride();
		c.pullIn = pullIn;
		return c;
	}

	/** Rides the staircase with the drawn height easing after the real ground (as the host does); returns {flat, min, max} distance. */
	private static double[] rideStairs(String mode) {
		ClientConfig.Ride c = cfg(mode);
		ArmaChaseCamera cam = new ArmaChaseCamera(() -> c, voxels(ArmaChasePullInTest::stairs), () -> 16 / 9.0);
		double dt = 1 / 120.0;
		double yDraw = 64;
		cam.reset(east(0, 64));
		double flat = Double.NaN, min = 1e9, max = 0;
		for (double x = 0; x < 110; x += 18 * dt) {
			// the player box (0.6 wide) climbs when its front edge reaches the step; the drawn height eases after it
			double real = Math.max(stairs(Math.floor(x)), stairs(Math.floor(x + 0.3)));
			yDraw += (real - yDraw) * (1 - Math.exp(-dt / 0.08));
			cam.update(dt, east(x, yDraw));
			assertEquals(1.0, cam.pull(), 1e-9, mode + ": pulled in at x " + x);
			double d = cam.eye().distanceTo(new Vec3(x, yDraw, 0.5));
			if (x > 12 && x < 19) {
				flat = d;
			}
			if (x > 19 && x < 100) {
				min = Math.min(min, d);
				max = Math.max(max, d);
			}
			double groundUnderEye = stairs(Math.floor(cam.eye().x));
			assertTrue(cam.eye().y > groundUnderEye + 0.5, mode + ": eye in the ground at x " + x);
		}
		return new double[] {flat, min, max};
	}

	@Test
	void oneBlockStepsNeverPullTheCameraIn() {
		for (String mode : new String[] {"on", "tall_only"}) {
			double[] r = rideStairs(mode);
			System.out.printf("stairs %s: flat %.2f, min %.2f, max %.2f blocks%n", mode, r[0], r[1], r[2]);
			assertTrue(r[1] > r[0] * 0.80, mode + ": distance never drops more than 20 % (min " + r[1] + " of " + r[0] + ")");
			assertTrue(r[2] < r[0] * 1.25, mode + ": distance never grows more than 25 % (max " + r[2] + " of " + r[0] + ")");
		}
	}

	/** One step of {@code dy} blocks at x = 20 after 8 s on the flat (smoothed speed converged); returns {flat, min, max} distance. */
	private static double[] rideOneStep(int dy) {
		ClientConfig.Ride c = cfg("tall_only");
		DoubleUnaryOperator ground = bx -> bx >= 20 ? 64 + dy : 64;
		ArmaChaseCamera cam = new ArmaChaseCamera(() -> c, voxels(ground), () -> 16 / 9.0);
		double dt = 1 / 120.0;
		double yDraw = 64;
		cam.reset(east(-130, 64));
		double flat = Double.NaN, min = 1e9, max = 0;
		for (double x = -130; x < 90; x += 18 * dt) {
			double real = dy > 0 ? Math.max(ground.applyAsDouble(Math.floor(x)), ground.applyAsDouble(Math.floor(x + 0.3)))
				: Math.max(ground.applyAsDouble(Math.floor(x)), ground.applyAsDouble(Math.floor(x - 0.3)));
			yDraw += (real - yDraw) * (1 - Math.exp(-dt / 0.08));
			cam.update(dt, east(x, yDraw));
			assertEquals(1.0, cam.pull(), 1e-9);
			double d = cam.eye().distanceTo(new Vec3(x, yDraw, 0.5));
			if (x < 19) {
				flat = d;
			} else {
				min = Math.min(min, d);
				max = Math.max(max, d);
			}
		}
		return new double[] {flat, min, max};
	}

	@Test
	void aSingleStepChangesTheDistanceByAFewPercentAtMost() {
		double[] up = rideOneStep(1);
		double[] down = rideOneStep(-1);
		System.out.printf("one step up: flat %.2f, min %.2f, max %.2f; one step down: flat %.2f, min %.2f, max %.2f blocks%n", up[0], up[1], up[2], down[0],
			down[1], down[2]);
		for (double[] r : new double[][] {up, down}) {
			assertTrue(r[1] > r[0] * 0.95, "never more than 5 % closer (min " + r[1] + " of " + r[0] + ")");
			assertTrue(r[2] < r[0] * 1.05, "never more than 5 % farther (max " + r[2] + " of " + r[0] + ")");
		}
	}

	@Test
	void aStepRightAheadDoesNotBlockTheSightLine() {
		// the 0.1.6 trigger: bike 0.04 before a 1-block step, camera behind and above
		ArmaChaseCamera.World w = voxels(bx -> bx >= 20 ? 65 : 64);
		Vec3 pivot = new Vec3(19.96, 64 + ArmaChaseCamera.BIKE_TOP_UNITS + 0.25, 0.5);
		assertEquals(1.0, w.clearFraction(pivot, new Vec3(19.96 - 12.6, 64 + 9.6, 0.5)), 1e-9);
	}

	/** A world whose sight line is blocked (free fraction 0.3) while {@code blocked[0]}. */
	private static ArmaChaseCamera.World switchable(boolean[] blocked) {
		return new ArmaChaseCamera.World() {
			@Override
			public double clearFraction(Vec3 pivot, Vec3 want) {
				return blocked[0] ? 0.3 : 1;
			}

			@Override
			public double groundY(double x, double z) {
				return 64;
			}
		};
	}

	@Test
	void sustainedOcclusionEasesInAfterAQuarterSecondAndBackOut() {
		boolean[] blocked = {false};
		ClientConfig.Ride c = cfg("on");
		ArmaChaseCamera cam = new ArmaChaseCamera(() -> c, switchable(blocked), () -> 16 / 9.0);
		double dt = 1 / 120.0;
		cam.reset(east(0, 64));
		double x = 0;
		for (int i = 0; i < 120; i++) {
			cam.update(dt, east(x += 18 * dt, 64));
		}
		blocked[0] = true;
		double prev = cam.pull();
		for (int i = 0; i < 29; i++) { // 0.24 s: still waiting
			cam.update(dt, east(x += 18 * dt, 64));
			assertEquals(1.0, cam.pull(), 1e-9, "no reaction before " + ArmaChaseCamera.SUSTAIN + " s");
		}
		double maxStep = 0;
		for (int i = 0; i < 240; i++) {
			cam.update(dt, east(x += 18 * dt, 64));
			maxStep = Math.max(maxStep, prev - cam.pull());
			prev = cam.pull();
		}
		assertEquals(0.3, cam.pull(), 0.01, "arrived at the clear fraction after 2 s");
		assertTrue(maxStep < 0.02, "eased, never a snap: largest change in one frame " + maxStep);
		blocked[0] = false;
		for (int i = 0; i < 480; i++) {
			cam.update(dt, east(x += 18 * dt, 64));
		}
		assertEquals(1.0, cam.pull(), 0.01, "back out when the view clears");
	}

	@Test
	void briefOcclusionAndOffModeNeverMoveTheCamera() {
		boolean[] blocked = {false};
		ClientConfig.Ride c = cfg("on");
		ArmaChaseCamera cam = new ArmaChaseCamera(() -> c, switchable(blocked), () -> 16 / 9.0);
		double dt = 1 / 120.0;
		cam.reset(east(0, 64));
		double x = 0;
		for (int k = 0; k < 6; k++) { // six 0.2 s occlusions (tree trunks going past), 0.3 s apart
			blocked[0] = true;
			for (int i = 0; i < 24; i++) {
				cam.update(dt, east(x += 18 * dt, 64));
				assertEquals(1.0, cam.pull(), 1e-9);
			}
			blocked[0] = false;
			for (int i = 0; i < 36; i++) {
				cam.update(dt, east(x += 18 * dt, 64));
				assertEquals(1.0, cam.pull(), 1e-9);
			}
		}
		ClientConfig.Ride off = cfg("off");
		ArmaChaseCamera cam2 = new ArmaChaseCamera(() -> off, switchable(new boolean[] {true}), () -> 16 / 9.0);
		cam2.reset(east(0, 64));
		for (int i = 0; i < 600; i++) {
			cam2.update(dt, east(i * 18 * dt, 64));
			assertEquals(1.0, cam2.pull(), 1e-9, "off never pulls in");
		}
	}

	@Test
	void pausedFramesDoNotAdvanceTheOcclusionTimer() {
		boolean[] blocked = {true};
		ClientConfig.Ride c = cfg("on");
		ArmaChaseCamera cam = new ArmaChaseCamera(() -> c, switchable(blocked), () -> 16 / 9.0);
		cam.reset(east(0, 64));
		for (int i = 0; i < 1000; i++) {
			cam.update(0, east(0, 64));
		}
		assertEquals(1.0, cam.pull(), 1e-9);
	}
}

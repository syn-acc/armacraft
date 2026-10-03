package gl.syn.armacraft.client.ridekit;

import gl.syn.armacraft.client.ClientConfig;
import gl.syn.armacraft.client.ride.api.ChaseCamera;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The chase camera on the host's game-time dt: paused = still, frame-rate independent, Armagetron turn swing. */
class ArmaChaseCameraTest {

	private static final ClientConfig.Ride CFG = new ClientConfig.Ride();

	/** Open ground at a given height everywhere (heightmap only, nothing to pull in for). */
	static ArmaChaseCamera.World flat(java.util.function.DoubleBinaryOperator ground) {
		return new ArmaChaseCamera.World() {
			@Override
			public double clearFraction(Vec3 pivot, Vec3 want) {
				return 1;
			}

			@Override
			public double groundY(double x, double z) {
				return ground.applyAsDouble(x, z);
			}
		};
	}

	private static ArmaChaseCamera cam() {
		return new ArmaChaseCamera(() -> CFG, flat((x, z) -> 64), () -> 16 / 9.0);
	}

	/** Bike driving north (MC yaw 180) at 18 blocks/s, turned right to east (yaw -90) at t = 0. */
	private static ChaseCamera.Target target(double t, double y) {
		Vec3 pos = new Vec3(18 * t, y, 0);
		return new ChaseCamera.Target(pos, -90f, 180f, t, 18, -90f, 20f, 0.6, 5 + t, false, false, false);
	}

	private static ChaseCamera.Target north(double z) {
		return new ChaseCamera.Target(new Vec3(0, 64, z), 180f, 180f, 10, 18, 180f, 20f, 0.6, 10, false, false, false);
	}

	@Test
	void pausedFramesHoldTheCameraExactlyStill() {
		ArmaChaseCamera c = cam();
		c.reset(north(0));
		c.update(0.05, target(0.05, 64)); // mid-swing after a turn, and mid height step
		c.update(0.05, target(0.10, 65));
		Vec3 eye = c.eye();
		float yaw = c.yaw();
		float pitch = c.pitch();
		for (int i = 0; i < 500; i++) {
			c.update(0, target(0.10, 65)); // paused: game time stands still, target interpolation frozen
		}
		assertEquals(0, eye.distanceTo(c.eye()), 1e-9, "eye still (only float renormalisation noise)");
		assertEquals(yaw, c.yaw(), 1e-4f);
		assertEquals(pitch, c.pitch(), 1e-4f);
	}

	@Test
	void resultDoesNotDependOnFrameRate() {
		double total = 0.5;
		ArmaChaseCamera slow = cam();
		ArmaChaseCamera fast = cam();
		slow.reset(north(0));
		fast.reset(north(0));
		for (int i = 1; i <= 15; i++) {
			slow.update(total / 15, target(total * i / 15, 64 + (i > 5 ? 1 : 0) * 0));
		}
		for (int i = 1; i <= 240; i++) {
			fast.update(total / 240, target(total * i / 240, 64));
		}
		// 30 fps vs 480 fps, mid-swing after a turn. Armagetron integrates the turn with an explicit Euler step
		// (eCamera.cpp 2105: newdir = dir + cycleDir * turnSpeed * ts), so the swing itself is slightly frame-rate
		// dependent, as in Armagetron; everything added on top integrates exactly. Bound: about a degree, which is
		// ~0.25 block at the 12.6-block camera distance of 30 u/s.
		double eyeDiff = slow.eye().distanceTo(fast.eye());
		double yawDiff = Math.abs(net.minecraft.util.Mth.wrapDegrees(slow.yaw() - fast.yaw()));
		System.out.println("30 vs 480 fps mid-swing: eye " + eyeDiff + " blocks, yaw " + yawDiff + " deg");
		assertTrue(eyeDiff < 0.35, "eye " + eyeDiff);
		assertTrue(yawDiff < 1.5, "yaw " + yawDiff);
	}

	@Test
	void viewSwingsIntoTheNewHeadingLikeArmagetron() {
		ArmaChaseCamera c = cam();
		c.reset(north(0));
		c.update(1 / 60.0, target(1 / 60.0, 64));
		float early = c.yaw();
		assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(early - 180f)) < 45, "starts close to the old heading: " + early);
		for (int i = 2; i <= 120; i++) {
			c.update(1 / 60.0, target(i / 60.0, 64));
		}
		assertEquals(-90f, c.yaw(), 1.0f, "settled on the new heading after 2 s (turn speed 25)");
	}

	@Test
	void heightStepIsEasedNotSnapped() {
		ArmaChaseCamera c = cam();
		c.reset(north(0));
		for (int i = 1; i <= 30; i++) {
			c.update(1 / 60.0, north(-0.3 * i));
		}
		double before = c.eye().y;
		c.update(1 / 60.0, new ChaseCamera.Target(new Vec3(0, 65, -9.3), 180f, 180f, 10, 18, 180f, 20f, 0.6, 10, false, false, false));
		double jump = c.eye().y - before;
		assertTrue(jump >= 0 && jump < 0.05, "one frame after a 1-block step the eye moved only " + jump);
		for (int i = 0; i < 240; i++) {
			c.update(1 / 60.0, new ChaseCamera.Target(new Vec3(0, 65, -9.3 - 0.3 * i), 180f, 180f, 10, 18, 180f, 20f, 0.6, 10, false, false, false));
		}
		assertEquals(before + 1, c.eye().y, 0.02, "fully caught up after 4 s");
	}

	@Test
	void liftsOverAHillBehindInsteadOfDivingIntoIt() {
		// riding north (-z) down a slope: ground rises 1 block per 2 blocks behind the bike (+z)
		ArmaChaseCamera c = new ArmaChaseCamera(() -> CFG, flat((x, z) -> 64 + Math.max(0, Math.floor(z / 2))), () -> 16 / 9.0);
		c.reset(north(0));
		for (int i = 0; i < 240; i++) {
			c.update(1 / 60.0, north(0));
		}
		double ground = 64 + Math.floor(c.eye().z / 2);
		assertTrue(c.eye().y > ground + 1.0, "eye " + c.eye().y + " must stay above the slope (" + ground + ")");
		// and the bike stays on screen: the view pitch points at it within the FOV
		double dy = 64.45 - c.eye().y;
		double dz = 0 - c.eye().z;
		double toBike = -Math.toDegrees(Math.atan2(dy, Math.abs(dz)));
		assertEquals(c.pitch(), toBike, 25, "bike within the vertical field of view");
	}

	@Test
	void springIsExactAndContinuous() {
		double[] a = ArmaChaseCamera.spring(0, 0, 1, 3, 0.1);
		double[] b = ArmaChaseCamera.spring(0, 0, 1, 3, 0.05);
		b = ArmaChaseCamera.spring(b[0], b[1], 1, 3, 0.05);
		assertEquals(a[0], b[0], 1e-12, "same result for one step or two half steps");
		assertEquals(a[1], b[1], 1e-12);
		double[] z = ArmaChaseCamera.spring(0.3, 0.7, 1, 3, 0);
		assertEquals(0.3, z[0]);
		assertEquals(0.7, z[1], "dt 0 (paused) changes nothing");
	}

	@Test
	void deathCamHint() {
		assertEquals("turn to ride again (auto in 3 s)", ArmaRideHud.deadHint(2.2));
		assertEquals("turn to ride again (auto in 0 s)", ArmaRideHud.deadHint(-1));
		assertEquals("turn to ride again", ArmaRideHud.deadHint(Double.NaN));
	}

	@Test
	void wallWarningUrgency() {
		assertEquals(0, ArmaRideHud.urgency(0.5, 0.5), 1e-9);
		assertEquals(0.5, ArmaRideHud.urgency(0.25, 0.5), 1e-9);
		assertEquals(1, ArmaRideHud.urgency(0, 0.5), 1e-9);
		assertEquals(0, ArmaRideHud.urgency(Double.POSITIVE_INFINITY, 0.5), 1e-9);
		assertEquals(0.5, ArmaRideHud.urgency(0.25, Double.NaN), 1e-9, "unknown threshold: host default 0.5 s");
	}
}

package gl.syn.armacraft.client.core;

/**
 * Armagetron's camera, replicating {@code eCamera::Timestep} / {@code eCamera::Render}
 * (src/engine/eCamera.cpp, v0.2.9.3.0) for the two modes a rider uses:
 *
 * <ul>
 *   <li>{@link Mode#CUSTOM} ("custom camera", Armagetron's default START_CAM): the view direction chases the
 *       cycle's direction at CAMERA_CUSTOM_TURN_SPEED (40/s, with CAMERA_CUSTOM_TURN_SPEED_180 for U-turns);
 *       the eye sits CAMERA_CUSTOM_BACK=30 behind and CAMERA_CUSTOM_RISE=20 above the cycle and looks down
 *       with rise CAMERA_CUSTOM_PITCH=-0.7. Before the round starts (game time &lt; 0) back and rise are
 *       scaled by {@code exp(CAMERA_CUSTOM_ZOOM * t)}, the pre-round zoom-in.</li>
 *   <li>{@link Mode#IN} (in-cycle camera): eye at the cycle position, height CamZ (cycle z = .75), direction
 *       chasing the cycle at CAMERA_IN_TURN_SPEED=40/s, rise kept by the user (default 0), clamped +-2.</li>
 * </ul>
 * Glancing (left/right/back) follows the same code: {@code glanceSmooth} integrates at GLANCE_SPEED=20 and
 * the view rotates by {@code (1,g).Turn(1,g)}.
 *
 * <p>All values are Armagetron units; the caller maps the result to Minecraft.
 */
public final class ArmaCam {

	public enum Mode { CUSTOM, IN }

	// eCamera.cpp defaults (s_custom*, s_inTurnSpeed) and gCycleMovement.cpp z=.75
	public double customBack = 30;
	public double customRise = 20;
	public double customPitch = -0.7;
	public double customZoom = 0.5;
	public double customTurnSpeed = 40;
	public double customTurnSpeed180 = 2;
	public double inTurnSpeed = 40;
	public static final double GLANCE_SPEED = 20;
	public static final double CYCLE_CAM_Z = .75;

	public Mode mode = Mode.CUSTOM;

	// camera state (Armagetron eCamera fields)
	public V2 pos = new V2(0, 0);
	public V2 dir = new V2(1, 0);
	public double z = 10;
	public double rise = 0;
	public double inRise = 0;
	private double glanceSmooth;
	public boolean glanceLeft, glanceRight, glanceBack;
	private V2 glanceDir = new V2(1, 0);
	private boolean initialized;

	/** Output of the last step: eye position (x, y, z) and look vector (lx, ly, rise) with |(lx,ly)| = 1. */
	public double eyeX, eyeY, eyeZ, lookX, lookY, lookRise;

	public void reset(V2 centerPos, V2 centerDir) {
		pos = centerPos;
		dir = centerDir.normSq() > 0 ? centerDir.normalized() : new V2(1, 0);
		z = 10;
		rise = 0;
		glanceSmooth = 0;
		initialized = true;
	}

	public boolean initialized() {
		return initialized;
	}

	public void switchMode() {
		mode = mode == Mode.CUSTOM ? Mode.IN : Mode.CUSTOM;
		if (mode == Mode.IN) {
			rise = inRise;
		}
	}

	/**
	 * Advance by {@code ts} seconds of game time.
	 *
	 * @param gameTime  guest game time (negative before the round starts)
	 * @param centerPos predicted position of the watched cycle
	 * @param cycleDir  the watched cycle's (smoothed) direction, Armagetron {@code Direction()}
	 * @param lastDir   its previous driving direction, Armagetron {@code LastDirection()}, for U-turns
	 * @param centerZ   watched object's camera height (cycle: .75)
	 */
	public void step(double ts, double gameTime, V2 centerPos, V2 cycleDir, V2 lastDir, double centerZ) {
		if (!initialized) {
			reset(centerPos, cycleDir);
		}
		if (!(ts >= 0) || !Double.isFinite(ts)) {
			ts = 0;
		}
		if (ts > 0.25) {
			ts = 0.25;
		}

		// glance smoothing
		if (glanceLeft) {
			glanceSmooth += GLANCE_SPEED * ts;
		}
		if (glanceRight) {
			glanceSmooth -= GLANCE_SPEED * ts;
		}
		glanceSmooth /= 1 + GLANCE_SPEED * ts;
		glanceDir = new V2(1, glanceSmooth).turn(1, glanceSmooth).normalized();
		if (glanceBack) {
			glanceDir = glanceDir.mul(-1);
		}

		double turnSpeed = mode == Mode.IN ? inTurnSpeed : customTurnSpeed;
		V2 newdir = dir.add(cycleDir.mul(turnSpeed * ts));
		double wrongDirection = -cycleDir.dot(newdir);
		if (wrongDirection > 0) {
			V2 normedLastDir = lastDir;
			double wrongWrong = -cycleDir.dot(normedLastDir);
			if (wrongWrong > 0) {
				normedLastDir = normedLastDir.add(cycleDir.mul(wrongWrong / cycleDir.normSq()));
				double n = normedLastDir.normSq();
				if (n > 0) {
					normedLastDir = normedLastDir.mul(1 / Math.sqrt(n));
				}
			}
			newdir = newdir.add(normedLastDir.mul(wrongDirection * ts * turnSpeed * customTurnSpeed180));
		}
		if (newdir.normSq() < 1e-12) {
			newdir = cycleDir;
		}
		newdir = newdir.normalized();

		V2 newpos;
		double newz;
		double newrise;
		if (mode == Mode.CUSTOM) {
			double zoom = gameTime > 0 || !Double.isFinite(gameTime) ? 1 : Math.exp(customZoom * gameTime);
			newpos = centerPos.sub(newdir.mul(customBack * zoom));
			newz = centerZ + customRise * zoom;
			newrise = customPitch;
		} else {
			newpos = centerPos;
			newz = centerZ;
			newrise = Math.max(-2, Math.min(2, inRise));
		}
		pos = newpos;
		dir = newdir;
		z = newz;
		rise = newrise;

		// eCamera::Render: glance around the watched object, clamp height
		if (z > 400) {
			z = 300;
		}
		if (z < 0) {
			z = 0;
		}
		V2 eye = mode == Mode.CUSTOM ? pos.sub(centerPos).turn(glanceDir).add(centerPos) : pos;
		V2 look = dir.turn(glanceDir);
		eyeX = eye.x();
		eyeY = eye.y();
		eyeZ = z;
		lookX = look.x();
		lookY = look.y();
		lookRise = rise;
	}

	/** Armagetron START_FOV is horizontal with a widescreen correction (rViewport::Perspective). Returns vertical degrees. */
	public static double verticalFov(double fovDeg, double aspect) {
		double ensureVertical = Math.max(aspect / 1.5, 1.0);
		double xmul = ensureVertical * Math.tan(Math.toRadians(fovDeg) / 2);
		double ymul = xmul / aspect;
		return Math.toDegrees(2 * Math.atan(ymul));
	}
}

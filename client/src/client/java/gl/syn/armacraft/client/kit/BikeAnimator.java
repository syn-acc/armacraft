package gl.syn.armacraft.client.kit;

import gl.syn.armacraft.client.core.CycleAnim;
import gl.syn.armacraft.client.core.V2;

/**
 * Armagetron's cycle animation (gCycle::TimestepCore: body direction swing, lean/"skew", wheel roll) driven in
 * Minecraft terms. Feed it the driving heading and speed every frame; read back what {@link BikeRenderer} needs.
 * No bridge types: arena mode feeds it from guest snapshots, ride mode from the local player.
 *
 * <p>Units: speed and clearances are Armagetron units (blocks / blocks-per-unit).
 */
public final class BikeAnimator {

	private final CycleAnim anim = new CycleAnim();
	private V2 lastDrive;

	/** Minecraft yaw (degrees) to Armagetron heading: MC forward (-sin, cos) in (x, z), Armagetron y = -z. */
	public static V2 headingFromYaw(float yawDeg) {
		double r = Math.toRadians(yawDeg);
		return new V2(-Math.sin(r), -Math.cos(r));
	}

	public static float yawFromHeading(V2 h) {
		return gl.syn.armacraft.client.core.ArenaMapping.yaw(h.x(), h.y());
	}

	public void reset(float yawDeg) {
		V2 h = headingFromYaw(yawDeg);
		anim.reset(h);
		lastDrive = h;
	}

	/** Turn impulse (gCycle: {@code skewDot += 4*d}). +1 right, -1 left. Called automatically when the drive yaw changes in {@link #step}. */
	public void turn(int dir) {
		anim.turnImpulse(dir);
	}

	/**
	 * @param dt         seconds
	 * @param driveYaw   driving direction (Minecraft yaw, degrees)
	 * @param speed      Armagetron units per second (wheel roll and body swing rate)
	 * @param alive      lean physics on
	 * @param leftClear  free distance 45 degrees front-left, in multiples of that diagonal (clear: >= 0.25 or +inf)
	 * @param rightClear same front-right
	 */
	public void step(double dt, float driveYaw, double speed, boolean alive, double leftClear, double rightClear) {
		V2 drive = headingFromYaw(driveYaw);
		step(dt, drive, speed, alive, leftClear, rightClear);
	}

	/** Same with an Armagetron heading vector (x east, y north). */
	public void step(double dt, V2 drive, double speed, boolean alive, double leftClear, double rightClear) {
		step(dt, drive, speed, speed, alive, leftClear, rightClear);
	}

	/** {@code speed} Armagetron units/s (body swing), {@code wheelSpeed} model units/s (wheel roll). */
	public void step(double dt, V2 drive, double speed, double wheelSpeed, boolean alive, double leftClear, double rightClear) {
		if (lastDrive == null) {
			anim.reset(drive);
			lastDrive = drive;
		}
		if (Math.abs(lastDrive.x() - drive.x()) > 1e-6 || Math.abs(lastDrive.y() - drive.y()) > 1e-6) {
			anim.turnImpulse(gl.syn.armacraft.client.core.CycleTrack.turnSign(lastDrive.x(), lastDrive.y(), drive.x(), drive.y()));
			lastDrive = drive;
		}
		anim.step(dt, drive, speed, wheelSpeed, alive, Double.isFinite(leftClear) ? leftClear : 1, Double.isFinite(rightClear) ? rightClear : 1);
	}

	/** Visible body yaw (Minecraft degrees): Armagetron's smoothed {@code dir}, swings into turns. */
	public float bodyYaw() {
		return yawFromHeading(anim.dir);
	}

	public V2 bodyHeading() {
		return anim.dir;
	}

	/** Lean, -0.5..0.5 (positive leans right). */
	public double skew() {
		return anim.skew;
	}

	/** Rolling angle of the front wheel, radians (forward roll positive). */
	public double frontWheelAngle() {
		return -Math.atan2(anim.rotFront.y(), anim.rotFront.x());
	}

	public double rearWheelAngle() {
		return -Math.atan2(anim.rotRear.y(), anim.rotRear.x());
	}

	/** True on frames where Armagetron would spawn grind sparks (and play the spark sound). */
	public boolean sparked() {
		return anim.sparked;
	}

	/** Direct access for arena mode. */
	public CycleAnim raw() {
		return anim;
	}
}

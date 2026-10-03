package gl.syn.armacraft.client.kit;

import gl.syn.armacraft.client.core.ArenaMapping;
import gl.syn.armacraft.client.core.ArmaCam;
import gl.syn.armacraft.client.core.V2;
import net.minecraft.world.phys.Vec3;

/**
 * Armagetron's custom (chase) camera and in-cycle camera for any target in Minecraft space. Wraps
 * {@link ArmaCam} (eCamera::Timestep replica) with a local Armagetron frame: x = X/s, y = -Z/s, height measured
 * from the target's Y. No bridge types: ride mode feeds the local bike, arena mode the watched cycle.
 *
 * <p>Per frame: {@link #update}, then apply {@link #eye()}, {@link #yaw()}, {@link #pitch()} and
 * {@link #verticalFov(double)} to the Minecraft camera.
 */
public final class ChaseCam {

	public enum Mode { CHASE, IN_CYCLE }

	private final ArmaCam cam = new ArmaCam();
	private double speedSmooth;
	private Vec3 eye = Vec3.ZERO;
	private float yaw, pitch;
	/** CAMERA_CUSTOM_BACK_FROMSPEED / CAMERA_CUSTOM_RISE_FROMSPEED (Armagetron default 0). */
	public double backFromSpeed = 0, riseFromSpeed = 0;
	/** START_FOV, horizontal with Armagetron's widescreen correction. */
	public double fovDeg = 90;
	private double baseBack = 30, baseRise = 20;

	public ArmaCam raw() {
		return cam;
	}

	public Mode mode() {
		return cam.mode == ArmaCam.Mode.IN ? Mode.IN_CYCLE : Mode.CHASE;
	}

	public void setMode(Mode m) {
		cam.mode = m == Mode.IN_CYCLE ? ArmaCam.Mode.IN : ArmaCam.Mode.CUSTOM;
	}

	public void toggleMode() {
		cam.switchMode();
	}

	/** Armagetron defaults (eCamera.cpp): back 30, rise 20, pitch -0.7 (rise per unit horizontal), turn speed 40/s, U-turn factor 2. */
	public void configure(double back, double rise, double pitchRise, double zoom, double turnSpeed, double turnSpeed180, double inTurnSpeed) {
		baseBack = back;
		baseRise = rise;
		cam.customPitch = pitchRise;
		cam.customZoom = zoom;
		cam.customTurnSpeed = turnSpeed;
		cam.customTurnSpeed180 = turnSpeed180;
		cam.inTurnSpeed = inTurnSpeed;
	}

	public void glance(boolean left, boolean right, boolean back) {
		cam.glanceLeft = left;
		cam.glanceRight = right;
		cam.glanceBack = back;
	}

	public void reset(Vec3 target, float headingYaw) {
		cam.reset(new V2(target.x, -target.z), BikeAnimator.headingFromYaw(headingYaw));
	}

	/**
	 * @param dt            seconds since the last update
	 * @param target        Minecraft position of the bike (ground point at its front, like {@link BikeRenderer.BikePose#pos()})
	 * @param headingYaw    visible heading (Minecraft yaw), Armagetron chases the smoothed body direction
	 * @param lastDriveYaw  previous driving direction (Minecraft yaw), used for the U-turn swing; same as heading if unknown
	 * @param speed         Armagetron units per second (only matters if *FromSpeed settings are non-zero)
	 * @param blocksPerUnit Minecraft blocks per Armagetron unit
	 * @param roundTime     game time; negative values give the pre-round zoom-in, pass +inf outside rounds
	 */
	public void update(double dt, Vec3 target, float headingYaw, float lastDriveYaw, double speed, double blocksPerUnit, double roundTime) {
		double s = blocksPerUnit > 0 ? blocksPerUnit : 1;
		// eCamera::Timestep: centerSpeedSmooth = (css + speed*ts*SMOOTH_SPEED)/(1 + ts*SMOOTH_SPEED), SMOOTH_SPEED 1
		speedSmooth = (speedSmooth + speed * dt) / (1 + dt);
		cam.customBack = baseBack + speedSmooth * backFromSpeed;
		cam.customRise = baseRise + speedSmooth * riseFromSpeed;
		// local frame: origin under the target so heights are relative to the bike's ground
		ArenaMapping local = new ArenaMapping(0, target.y, 0, s);
		V2 center = new V2(local.arenaX(target.x), local.arenaY(target.z));
		cam.step(dt, roundTime, center, BikeAnimator.headingFromYaw(headingYaw), BikeAnimator.headingFromYaw(lastDriveYaw), ArmaCam.CYCLE_CAM_Z);
		eye = new Vec3(local.mcX(cam.eyeX), local.mcY(cam.eyeZ), local.mcZ(cam.eyeY));
		yaw = ArenaMapping.yaw(cam.lookX, cam.lookY);
		pitch = ArenaMapping.pitch(1, cam.lookRise);
	}

	public Vec3 eye() {
		return eye;
	}

	public float yaw() {
		return yaw;
	}

	public float pitch() {
		return pitch;
	}

	/** Armagetron eye in its own (target-relative) units: {x, y, z, lookX, lookY}, for sound mixing. */
	public double[] armaEye(double blocksPerUnit, Vec3 origin) {
		double s = blocksPerUnit > 0 ? blocksPerUnit : 1;
		return new double[] {(eye.x - origin.x) / s, -(eye.z - origin.z) / s, (eye.y - origin.y) / s, cam.lookX, cam.lookY};
	}

	public float verticalFov(double aspect) {
		return (float) ArmaCam.verticalFov(fovDeg, aspect);
	}
}

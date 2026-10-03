package gl.syn.armacraft.client.core;

/**
 * Armagetron Advanced 0.4 (trunk, as in 0.4.0_alpha_z5693) custom camera, ported from src/engine/eCamera.cpp at the
 * last trunk commit touching it (ca00114e, 2024-01-18). Line numbers below refer to that file.
 *
 * <p>Covers the CAMERA_CUSTOM path of {@code eCamera::Timestep} with no user camera control
 * ({@code userCameraControl == 0}, so the blend at 2467-2478 takes the automatic position as is), CAMWOBBLE off
 * ({@code newpos = CenterPos()}, 2095-2099) and the glance request system (input 846-881, commit 2403-2457,
 * {@code nextDirIfGlancing} 332-375). CAMCENTER only changes CAMERA_IN (2099 turns the custom camera
 * unconditionally); SMART_GLANCE_CUSTOM is commented out in 0.4 (84, 2318-2348); AUTO_INCAM is smart-camera only.
 *
 * <p>Units are Armagetron's (grid units, seconds). Positions are the arena plane (x east, y north); heights {@code z}
 * are above the floor. Pure Java (no Minecraft types) so it is unit tested directly.
 */
public final class ArmaCam04 {

	/** Settings, Armagetron names in comments. Defaults are the eCamera.cpp compiled-in values (248, 276-283, 309). */
	public static final class Settings {
		public double back = 30;               // CAMERA_CUSTOM_BACK
		public double rise = 20;               // CAMERA_CUSTOM_RISE
		public double backFromSpeed = 0;       // CAMERA_CUSTOM_BACK_FROMSPEED
		public double riseFromSpeed = 0;       // CAMERA_CUSTOM_RISE_FROMSPEED
		public double pitch = -.7;             // CAMERA_CUSTOM_PITCH
		public double zoom = .5;               // CAMERA_CUSTOM_ZOOM
		public double turnSpeed = 40;          // CAMERA_CUSTOM_TURN_SPEED
		public double turnSpeed180 = 2;        // CAMERA_CUSTOM_TURN_SPEED_180
		public int glanceMode = 2;             // CAMERA_GLANCE_MODE
		public double glanceAngularVelocity = 4 * Math.PI;      // CAMERA_GLANCE_ANGULAR_VELOCITY
		public double glanceAngularVelocityBonus = 12;          // CAMERA_GLANCE_ANGULAR_VELOCITY_BONUS
		public boolean glanceReturn = true;    // CAMERA_GLANCE_RETURN
		public boolean glanceStacking = false; // CAMERA_GLANCE_STACKING
		public double glanceReturnStop = .99;  // CAMERA_GLANCE_RETURN_STOP
		public double glanceSnap = -.5;        // CAMERA_GLANCE_SNAP
	}

	public static final int GLANCE_FORWARD = 0, GLANCE_BACK = 1, GLANCE_RIGHT = 2, GLANCE_LEFT = 3;
	/** uGlanceAction relative directions (196-199): forward (1,0), back (-1,0), right (0,-1), left (0,1). */
	private static final V2[] REL = {new V2(1, 0), new V2(-1, 0), new V2(0, -1), new V2(0, 1)};
	/** CenterCamZ() of a cycle: eGameObject::CamZ() returns z, which is .75 for a cycle. */
	public static final double CYCLE_CAM_Z = .75;
	private static final double EPS = 1e-6;

	public Settings settings = new Settings();

	// eCamera state
	public V2 pos = new V2(0, 0);
	public V2 dir = new V2(1, 0);
	public double z = 10;
	public double rise = -1;
	private double centerSpeedSmooth;
	private V2 centerPosLast;
	private V2 centerDirLast = new V2(1, 0);
	private boolean initialized;

	// glance requests: tListItem list, head = active (most recent); returnRequest points into it (eCamera.h 112-114)
	private final V2[] requestDir = new V2[4];
	private final java.util.ArrayDeque<Integer> active = new java.util.ArrayDeque<>();
	private Integer returnRequest;

	// last step inputs, for the zero timestep after a glance release (880)
	private V2 lastCenter = new V2(0, 0), lastCamDir = new V2(1, 0), stepDrive = new V2(1, 0), stepLastDrive = new V2(1, 0);
	private double lastCenterZ = CYCLE_CAM_Z, lastSpeed, lastGameTime = Double.POSITIVE_INFINITY;

	public boolean initialized() {
		return initialized;
	}

	/** Speed-dependent back (1920): {@code CAMERA_CUSTOM_BACK + centerSpeedSmooth * CAMERA_CUSTOM_BACK_FROMSPEED}. */
	public double customBack() {
		return settings.back + centerSpeedSmooth * settings.backFromSpeed;
	}

	/** Speed-dependent rise (1921). */
	public double customRise() {
		return settings.rise + centerSpeedSmooth * settings.riseFromSpeed;
	}

	public double centerSpeedSmooth() {
		return centerSpeedSmooth;
	}

	/** True while a glance (or the return from one) controls the camera. */
	public boolean glancing() {
		return !active.isEmpty();
	}

	/** Camera reset behind the object (constructor and SetCenter: centerSpeedSmooth = Speed(), 562-566). */
	public void reset(V2 centerPos, V2 camDir, double centerZ, double speed) {
		centerSpeedSmooth = speed;
		dir = camDir.normSq() > 0 ? camDir.normalized() : new V2(1, 0);
		pos = centerPos.sub(dir.mul(customBack()));
		z = centerZ + customRise();
		rise = settings.pitch;
		centerPosLast = centerPos;
		centerDirLast = dir;
		active.clear();
		returnRequest = null;
		initialized = true;
	}

	/** grid->GetDirection(grid->DirectionWinding(d)): the nearest of the four axis directions. */
	static V2 gridDir(V2 d) {
		return Math.abs(d.x()) >= Math.abs(d.y()) ? new V2(Math.signum(d.x()) == 0 ? 1 : Math.signum(d.x()), 0) : new V2(0, Math.signum(d.y()));
	}

	/** Glance key edge (846-881). {@code down} press, otherwise release. */
	public void glance(int which, boolean down) {
		if (down) {
			if (returnRequest != null) { // cancel any returning from glance (851-854)
				active.remove(returnRequest);
				returnRequest = null;
			}
			V2 baseDir = settings.glanceStacking ? gridDir(dir) : gridDir(stepDrive); // 857-865
			requestDir[which] = baseDir.turn(REL[which]);
			active.remove(which);
			active.addFirst(which); // Insert(activeGlanceRequest): newest is active
		} else {
			active.remove(which);
			if (settings.glanceReturn && active.isEmpty() && which != GLANCE_FORWARD) { // 873-878
				returnRequest = GLANCE_FORWARD;
				requestDir[GLANCE_FORWARD] = gridDir(stepDrive);
				active.remove(GLANCE_FORWARD);
				active.addFirst(GLANCE_FORWARD);
			}
			if (initialized) {
				step(0, lastGameTime, lastCenter, lastCenterZ, lastCamDir, stepDrive, stepLastDrive, lastSpeed);
			} // 880: Timestep(0)
		}
	}

	/** {@code nextDirIfGlancing} (332-375). */
	V2 nextDirIfGlancing(V2 d, V2 target, double ts) {
		if (d.dot(target) < settings.glanceSnap) {
			return target;
		}
		double cross = -d.cross(target); // eCoord a*b = -a.x*b.y + a.y*b.x
		switch (settings.glanceMode) {
			case 0: {
				double arc = settings.glanceAngularVelocity * ts;
				if (Math.abs(cross) < arc && d.dot(target) > 0) {
					return target;
				}
				return d.turn(1, cross > 0 ? -arc : arc).normalized();
			}
			case 1: {
				double arc = Math.acos(Math.max(-1, Math.min(1, d.dot(target)))) * ts * settings.glanceAngularVelocity / (Math.PI / 2);
				if (cross > 0) {
					arc = -arc;
				}
				return d.turn(Math.cos(arc), Math.sin(arc));
			}
			case 2: {
				double arc = settings.glanceAngularVelocity * ts;
				V2 nd;
				if (d.dot(target) > 0) {
					nd = d.add(target.mul(arc));
				} else if (cross > 0) {
					nd = d.turn(1, -arc * settings.glanceAngularVelocityBonus);
				} else {
					nd = d.turn(1, arc * settings.glanceAngularVelocityBonus);
				}
				return nd.normalized();
			}
			default:
				return target;
		}
	}

	/**
	 * One {@code eCamera::Timestep} of the custom camera.
	 *
	 * @param ts        seconds
	 * @param gameTime  game time (pre-round zoom when negative); +inf outside rounds
	 * @param center    {@code CenterPos()}: the cycle's (predicted) position
	 * @param centerZ   {@code CenterCamZ()}: .75 for a cycle
	 * @param camDir    {@code CenterCamDir()}: the cycle's animated body direction (gCycle::CamDir returns dir)
	 * @param drive     {@code CenterDir()}: the driving direction (dirDrive)
	 * @param lastDrive {@code LastDirection()}: the driving direction before the last turn
	 * @param speed     the cycle's speed, units/s
	 */
	public void step(double ts, double gameTime, V2 center, double centerZ, V2 camDir, V2 drive, V2 lastDrive, double speed) {
		if (!(ts >= 0) || !Double.isFinite(ts)) {
			ts = 0;
		}
		if (!initialized) {
			reset(center, camDir, centerZ, speed);
		}
		this.lastCenter = center;
		this.lastCenterZ = centerZ;
		this.lastCamDir = camDir;
		this.stepDrive = drive;
		this.stepLastDrive = lastDrive;
		this.lastSpeed = speed;
		this.lastGameTime = gameTime;
		Settings s = settings;

		// 1903-1924: parameters from the smoothed speed of the previous step
		double customBack = customBack();
		double customRise = customRise();
		double customPitch = s.pitch;

		// 1970-1973: the cycle turned: cancel a return glance
		if (returnRequest != null && centerDirLast.sub(drive).normSq() > EPS * EPS) {
			active.remove(returnRequest);
			returnRequest = null;
		}
		// 1975-1979: centerDirLast follows only sideways changes
		if (Math.abs(-centerDirLast.cross(drive)) > .01) {
			centerDirLast = drive;
		}
		// 1997: SMOOTH_SPEED 1
		centerSpeedSmooth = (centerSpeedSmooth + speed * ts) / (1 + ts);
		if (centerPosLast == null) {
			centerPosLast = center;
		}

		// 2089-2124: custom camera direction chases the cycle's (body) direction
		V2 newpos = center; // CAMWOBBLE off
		V2 cycleDir = camDir;
		V2 newdir = dir.add(cycleDir.mul(s.turnSpeed * ts));
		double wrongDirection = -cycleDir.dot(newdir);
		if (wrongDirection > 0) {
			V2 normedLastDir = lastDrive;
			double wrongWrongDirection = -cycleDir.dot(normedLastDir);
			if (wrongWrongDirection > 0) {
				normedLastDir = normedLastDir.add(cycleDir.mul(wrongWrongDirection / cycleDir.normSq()));
				double n = normedLastDir.normSq();
				if (n > 0) {
					normedLastDir = normedLastDir.mul(1 / Math.sqrt(n));
				}
			}
			newdir = newdir.add(normedLastDir.mul(wrongDirection * ts * s.turnSpeed * s.turnSpeed180));
		}
		// 2192-2200
		double zoom = gameTime > 0 ? 1 : Math.exp(s.zoom * gameTime);
		newdir = newdir.normSq() > 0 ? newdir.normalized() : cycleDir;
		newpos = newpos.sub(newdir.mul(customBack * zoom));
		double newrise = customPitch;
		double newz = centerZ + customRise * zoom;

		if (!active.isEmpty()) {
			// 2403-2456: glance, not internal: rotate about the reconstructed focal point
			if (rise >= 0) {
				rise = -1;
			}
			double c = 5 * ts;
			V2 focusTarget = center;
			V2 focusTargetLast = centerPosLast;
			double focusZ = newpos.sub(focusTarget).norm() * newrise + newz;
			V2 focus = dir.mul((focusZ - z) / rise).add(pos);
			focus = focus.mul(1 - c).add(focusTargetLast.mul(c));
			V2 t = focus.sub(pos);
			double tnorm = t.norm();
			t = tnorm > 0 ? t.mul(1 / tnorm) : dir;
			t = nextDirIfGlancing(t, requestDir[active.peekFirst()], ts);
			if (returnRequest != null && t.dot(drive) >= s.glanceReturnStop) {
				active.remove(returnRequest);
				returnRequest = null;
			}
			focus = focus.add(focusTarget).sub(focusTargetLast);
			tnorm = tnorm * (1 - c) + newpos.sub(focusTarget).norm() * c;
			pos = focus.sub(t.mul(tnorm));
			dir = t;
			z = z * (1 - c) + newz * c;
			rise = (focusZ - z) / tnorm;
		} else {
			// 2467-2478 with userCameraControl == 0
			pos = newpos;
			dir = newdir;
			z = newz;
			rise = newrise;
		}
		dir = dir.normalized(); // 2493
		centerPosLast = center; // 2495
	}
}

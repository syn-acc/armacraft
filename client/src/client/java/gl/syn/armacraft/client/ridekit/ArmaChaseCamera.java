package gl.syn.armacraft.client.ridekit;

import gl.syn.armacraft.client.ClientConfig;
import gl.syn.armacraft.client.core.ArenaMapping;
import gl.syn.armacraft.client.core.ArmaCam;
import gl.syn.armacraft.client.core.ArmaCam04;
import gl.syn.armacraft.client.core.CycleAnim;
import gl.syn.armacraft.client.core.V2;
import gl.syn.armacraft.client.kit.BikeAnimator;
import gl.syn.armacraft.client.ride.api.ChaseCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Supplier;

/**
 * Ride mode's camera: Armagetron 0.4's custom camera ({@link ArmaCam04}, eCamera.cpp trunk) with the rider's own
 * Armagetron settings ({@link ArmaCamSettings}): speed-dependent CAMERA_CUSTOM_BACK/RISE, the view chasing the
 * cycle's animated body direction at CAMERA_CUSTOM_TURN_SPEED (TURN_SPEED_180 for reversals), CAMERA_CUSTOM_PITCH,
 * START_FOV with Armagetron's widescreen handling, and 0.4's glance requests (left/right/back held; mode, snap,
 * return and return stop). The body direction is integrated here with gCycle's swing (gCycle.cpp 3046-3053) so the
 * camera does not depend on the bike renderer's frame order. Everything is evaluated per rendered frame from the
 * interpolated target. {@code dt} is the host's game-time frame delta (ticks + partial tick): every
 * smoothing here integrates over it, so a paused or frozen game (dt 0) holds the camera exactly still, and the
 * result does not depend on the frame rate. Only an obstacle pull-in reacts without dt (instant in).
 *
 * <p>Minecraft additions, all continuous in position and velocity (critically damped springs):
 * <ul>
 *   <li>height: the camera base follows the bike's (host-eased) height on a spring;</li>
 *   <li>lift: over hills the eye rises until the sight line from the bike to the eye and the eye itself clear the
 *       ground under them (heightmap, no leaves), and the pitch tilts by the same angle so the bike stays put on
 *       screen; lifting replaces most terrain pull-ins, which used to snap the camera in and out on slopes;</li>
 *   <li>mouse look orbits the camera around the bike (yaw offset from the heading, pitch offset from the pitch at
 *       deploy; an untouched mouse gives the pure Armagetron view);</li>
 *   <li>obstacles the lift does not cover (trees, overhangs, tall walls), {@code ride.pull_in}: when something has
 *       hidden the bike (the sight line from above its drawn top) for a quarter second, ease in along bike -> eye
 *       on a spring, and ease back out when the view clears. 1-block steps never trigger it. "tall_only" tests the
 *       sight line from 2.25 blocks above the bike's ground, "off" never moves in.</li>
 * </ul>
 */
public final class ArmaChaseCamera implements ChaseCamera {

	/**
	 * Height above the ground under the bike of the point the obstacle rays start from and the pull-in converges to
	 * (blocks). Measured from max(bike y, ground): the host eases step-ups render-side, so while climbing the drawn
	 * bike is up to a block inside the step, and a ray starting there hits the step at once (a full snap-in).
	 */
	private static final double PIVOT_UP = 1.0;
	/** Height of the drawn cycle in Armagetron units (cycle_body.mod at gCycle's scale .5: top at .773). */
	static final double BIKE_TOP_UNITS = 0.78;
	/** Obstacle rays start this far above the bike's drawn top ("on"), or at least this high above its ground ("tall_only"). */
	private static final double TOP_MARGIN = 0.25;
	private static final double TALL_UP = 2.25;
	/** Obstacle rays start this far along the sight line, clear of the bike's immediate surroundings (blocks). */
	private static final double RAY_SKIP = 0.6;
	/** An obstacle must hide the bike for this long (game seconds) before the camera moves in. */
	static final double SUSTAIN = 0.25;
	/** Spring stiffness of the pull-in (1/s): eased, never a snap. */
	private static final double PULL_IN_RATE = 8;
	/** Distance kept from a hit surface (blocks); the ray fan is this wide too. */
	private static final double MARGIN = 0.3;
	/** Lift: clearance of the bike-to-eye sight line over the ground, and of the eye itself (blocks). */
	private static final double SIGHT_CLEAR = 0.5;
	private static final double EYE_CLEAR = 1.2;
	/** Lift: at most this much above the normal eye height; ground higher than the bike by more is an obstacle. */
	private static final double LIFT_MAX = 8;
	private static final double GROUND_MAX_ABOVE = 10;

	private final Supplier<ClientConfig.Ride> cfg;
	private final ArmaCam04 cam = new ArmaCam04();
	/** gCycle's animated body direction (CamDir), stepped on the same game-time dt. */
	private final CycleAnim body = new CycleAnim();
	private final boolean[] glanceHeld = new boolean[4];
	/** Production: the imported settings; tests and replays: the fallback values (never the user's files). */
	private Supplier<ArmaCamSettings.Resolved> settings = ArmaCamSettings::fallback;
	/** Units of the last update: Armagetron eye relative to the base, and the view (for sound and the trail lowering). */
	private double eyeZUnits = 10;
	private Vec3 lastBike = Vec3.ZERO;
	private double lastUpdateT = Double.NaN;
	private double baseY = Double.NaN;
	private double baseV;
	private double lift;
	private double liftV;
	private double pull = 1;
	private double pullV;
	private double occludedFor;
	private double heldFree = 1;
	private float pitchRef;
	private Vec3 eye = Vec3.ZERO;
	private float yaw;
	private float pitch;
	private float fov = Float.NaN;
	private final RideTrace trace = new RideTrace(4096);
	/** Diagnostics: which blocks pulled the camera in, by block id. */
	public static final java.util.Map<String, Integer> PULL_HITS = new java.util.concurrent.ConcurrentHashMap<>();
	/** Test recording of every update ({dt, target fields...}), or null. */
	public static volatile java.util.List<double[]> RECORD;
	/** Test instrumentation: cameras fed every reset and update of the live one (same ride, other settings), or null. */
	public static volatile java.util.List<ArmaChaseCamera> SHADOWS;
	private static boolean countHits = true;
	private boolean shadow;

	/** What the camera asks of the world. */
	interface World {
		/** Largest clear fraction (0..1) of the segment pivot -> want. */
		double clearFraction(Vec3 pivot, Vec3 want);

		/** Standing height of the column containing (x, z): top of the highest non-leaf motion-blocking block; NaN if unknown. */
		double groundY(double x, double z);
	}

	private final World world;
	private final java.util.function.DoubleSupplier aspect;

	public ArmaChaseCamera(Supplier<ClientConfig.Ride> cfg) {
		this(cfg, new McWorld(), ArmaChaseCamera::windowAspect);
		this.settings = ArmaCamSettings::current;
	}

	/** Tests and replays: no Minecraft instance needed. */
	ArmaChaseCamera(Supplier<ClientConfig.Ride> cfg, World world, java.util.function.DoubleSupplier aspect) {
		this.cfg = cfg;
		this.world = world;
		this.aspect = aspect;
	}

	public RideTrace trace() {
		return trace;
	}

	/** Test instrumentation: a camera on the same world and settings with another {@code ride.pull_in}, for {@link #SHADOWS}. */
	public ArmaChaseCamera shadow(String pullIn) {
		ClientConfig.Ride c = cfg.get().copy();
		c.pullIn = pullIn;
		ArmaChaseCamera s = new ArmaChaseCamera(() -> c, world, aspect);
		s.settings = settings;
		s.shadow = true;
		return s;
	}

	private void shadows(java.util.function.Consumer<ArmaChaseCamera> f) {
		java.util.List<ArmaChaseCamera> list = SHADOWS;
		if (list == null || shadow) {
			return;
		}
		countHits = false;
		try {
			list.forEach(f);
		} finally {
			countHits = true;
		}
	}

	/** Test metric: a block's collision shape lies between the eye and the middle of the drawn bike. */
	public static boolean hidden(Vec3 eye, Vec3 bike) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.level == null || mc.player == null) {
			return false;
		}
		Vec3 mid = bike.add(0, BIKE_TOP_UNITS * ArmaRideVisuals.visualScale() * 0.5, 0);
		HitResult hit = mc.level.clip(new ClipContext(eye, mid, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
		return hit.getType() != HitResult.Type.MISS && hit.getLocation().distanceTo(mid) > 0.5;
	}

	/** Test metric: the eye is inside a block with a collision shape. */
	public static boolean inBlock(Vec3 eye) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.level == null) {
			return false;
		}
		net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(eye);
		return !mc.level.getBlockState(at).getCollisionShape(mc.level, at).isEmpty();
	}

	/** Tests: settings without the files. */
	void settings(Supplier<ArmaCamSettings.Resolved> s) {
		this.settings = s;
	}

	public ArmaCam04 armaCam() {
		return cam;
	}

	/** Camera height above the bike's ground in Armagetron units (CAMERA_VISIBILITY_LOWER_WALL's zLimit). */
	public double eyeZUnits() {
		return eyeZUnits;
	}

	public boolean lowerWall() {
		return settings.get().lowerWall();
	}

	/** The bike position of the last update (the object the camera watches). */
	public Vec3 bike() {
		return lastBike;
	}

	/** Updated within the last half second of game time (riding with this camera). */
	public boolean active() {
		double now = ArmaBikeVisual.gameSeconds();
		return Double.isFinite(lastUpdateT) && Double.isFinite(now) && now - lastUpdateT < 0.5 && now >= lastUpdateT;
	}

	private void configure() {
		cam.settings = settings.get().camera();
	}

	private static V2 unitsPos(Vec3 p, double s) {
		return new V2(p.x / s, -p.z / s);
	}

	@Override
	public void reset(Target t) {
		configure();
		double s = t.scale() > 0 ? t.scale() : 1;
		V2 drive = BikeAnimator.headingFromYaw(t.headingYaw());
		body.reset(drive);
		java.util.Arrays.fill(glanceHeld, false);
		cam.reset(unitsPos(t.pos(), s), drive, ArmaCam04.CYCLE_CAM_Z, t.speed() / s);
		baseY = t.pos().y;
		baseV = 0;
		lift = 0;
		liftV = 0;
		pull = 1;
		pullV = 0;
		occludedFor = 0;
		heldFree = 1;
		pitchRef = t.lookPitch();
		place(0, t);
		shadows(sh -> sh.reset(t));
	}

	@Override
	public void update(double dt, Target t) {
		if (!Double.isFinite(dt) || dt < 0) {
			dt = 0;
		}
		dt = Math.min(dt, 0.25);
		java.util.List<double[]> rec = RECORD;
		if (rec != null && !shadow) {
			rec.add(new double[] {dt, t.pos().x, t.pos().y, t.pos().z, t.headingYaw(), t.lastHeadingYaw(), t.sinceTurn(), t.speed(), t.lookYaw(),
				t.lookPitch(), t.scale(), t.rideTime(), t.glanceLeft() ? 1 : 0, t.glanceRight() ? 1 : 0});
		}
		if (Double.isNaN(baseY)) {
			reset(t);
			return;
		}
		configure();
		// glance key edges (eCamera 846-881): left, right, back
		glanceEdge(ArmaCam04.GLANCE_LEFT, t.glanceLeft());
		glanceEdge(ArmaCam04.GLANCE_RIGHT, t.glanceRight());
		glanceEdge(ArmaCam04.GLANCE_BACK, glanceBack(t));
		place(dt, t);
		double step = dt;
		shadows(sh -> sh.update(step, t));
	}

	/**
	 * Critically damped spring towards {@code target} over {@code dt}, exact for a constant target: position and
	 * velocity stay continuous, so a change of target never jerks the view. Returns {pos, vel}.
	 */
	static double[] spring(double pos, double vel, double target, double omega, double dt) {
		if (!(dt > 0) || !(omega > 0)) {
			return new double[] {pos, vel};
		}
		double e = pos - target;
		double k = vel + omega * e;
		double ex = Math.exp(-omega * dt);
		return new double[] {target + (e + k * dt) * ex, (vel - omega * k * dt) * ex};
	}

	private void place(double dt, Target t) {
		ClientConfig.Ride c = cfg.get();
		double s = t.scale() > 0 ? t.scale() : 1;
		ArmaRideVisuals.physicsScale(s);
		double y = t.pos().y;
		boolean lifting = !"follow".equals(c.heightMode);
		double gap = Math.abs(y - baseY);
		if (lifting) {
			// falls catch up faster, still continuously
			double[] b = spring(baseY, baseV, y, c.heightFollow * (1 + 0.5 * gap), dt);
			baseY = b[0];
			baseV = b[1];
		} else {
			// 0.1.0: exponential follow
			baseY += (y - baseY) * (1 - Math.exp(-dt * c.heightFollow * (1 + gap * gap)));
		}
		Vec3 base = new Vec3(t.pos().x, baseY, t.pos().z);
		// Armagetron 0.4 custom camera in Armagetron units; heights relative to the (smoothed) ground under the bike
		double speedUnits = t.speed() / s;
		V2 drive = BikeAnimator.headingFromYaw(t.headingYaw());
		V2 lastDrive = BikeAnimator.headingFromYaw(t.lastHeadingYaw());
		body.step(dt, drive, speedUnits, true, 1, 1);
		cam.step(dt, Double.POSITIVE_INFINITY, unitsPos(t.pos(), s), ArmaCam04.CYCLE_CAM_Z, body.dir, drive, lastDrive, speedUnits);
		eyeZUnits = cam.z;
		lastBike = t.pos();
		lastUpdateT = ArmaBikeVisual.gameSeconds();
		Vec3 rel = new Vec3(cam.pos.x() * s - t.pos().x, cam.z * s, -cam.pos.y() * s - t.pos().z);
		float camYaw = ArenaMapping.yaw(cam.dir.x(), cam.dir.y());
		float camPitch = ArenaMapping.pitch(1, cam.rise);

		if (c.mouseOrbit) {
			// orbit: yaw offset of the free mouse look from the heading (the host turns the player's yaw with the bike)
			float yawOff = Mth.wrapDegrees(t.lookYaw() - t.headingYaw());
			double a = Math.toRadians(yawOff);
			double cs = Math.cos(a);
			double sn = Math.sin(a);
			rel = new Vec3(rel.x * cs - rel.z * sn, rel.y, rel.x * sn + rel.z * cs);
			camYaw += yawOff;
			// pitch offset from the pitch at deploy raises/lowers the eye on its sphere around the bike
			float pitchOff = Mth.clamp(t.lookPitch() - pitchRef, -40f, 50f);
			if (pitchOff != 0) {
				double h = Math.hypot(rel.x, rel.z);
				double dist = rel.length();
				double elev = Math.atan2(rel.y, h) + Math.toRadians(pitchOff);
				elev = Mth.clamp(elev, Math.toRadians(-10), Math.toRadians(85));
				double nh = dist * Math.cos(elev);
				double f = h > 1e-6 ? nh / h : 0;
				rel = new Vec3(rel.x * f, dist * Math.sin(elev), rel.z * f);
				camPitch = Mth.clamp(camPitch + pitchOff, -60f, 89f);
			}
		}

		if (lifting) {
			double want = requiredLift(t.pos(), baseY + rel.y, rel);
			double[] l = spring(lift, liftV, want, want > lift ? c.heightFollow * 3 : c.heightFollow, dt);
			lift = Math.max(0, l[0]);
			liftV = l[1];
			if (lift > 1e-6) {
				// keep the bike where it was on screen: tilt by the lift's angle (smoothed quantities only: no bobbing)
				double h = Math.max(0.5, Math.hypot(rel.x, rel.z));
				double off = rel.y + (baseY - y);
				camPitch += (float) Math.toDegrees(Math.atan2(off + lift, h) - Math.atan2(off, h));
				rel = rel.add(0, lift, 0);
			}
		}

		// Obstacles the lift does not cover (trees, overhangs, tall walls). The sight line is tested from above the
		// bike's drawn top (so 1-block steps, which never hide the bike, cannot trigger it), the camera only moves in
		// when the bike has been hidden for SUSTAIN seconds, and it eases in and out on springs.
		double under = world.groundY(t.pos().x, t.pos().z);
		double footY = Double.isFinite(under) && under <= y + 1.5 ? Math.max(y, under) : y;
		double top = BIKE_TOP_UNITS * ArmaRideVisuals.visualScale();
		boolean tall = "tall_only".equals(c.pullIn);
		Vec3 pivot = new Vec3(t.pos().x, footY + (tall ? Math.max(TALL_UP, top + TOP_MARGIN) : top + TOP_MARGIN), t.pos().z);
		Vec3 wantEye = base.add(rel);
		double len = wantEye.distanceTo(pivot);
		double target = 1;
		if (!"off".equals(c.pullIn) && len > 1e-6) {
			double free = world.clearFraction(pivot, wantEye);
			if (free < 0.999) {
				occludedFor = Math.min(SUSTAIN + 0.25, occludedFor + dt);
				heldFree = free;
			} else {
				occludedFor = Math.max(0, occludedFor - 2 * dt);
			}
			if (occludedFor >= SUSTAIN) {
				target = Math.max(heldFree, Math.min(1, c.pullInMin / len));
			}
		} else {
			occludedFor = 0;
		}
		double[] pl = spring(pull, pullV, target, target < pull ? PULL_IN_RATE : c.pullOutRate, dt);
		pull = Mth.clamp(pl[0], 0, 1);
		pullV = pl[1];
		eye = pivot.add(wantEye.subtract(pivot).scale(pull));
		// pulled in: turn the view onto the bike (from close up a small sight-line offset is a large angle)
		double w = Mth.clamp((1 - pull) * 1.5, 0, 1);
		if (w > 0) {
			Vec3 to = new Vec3(t.pos().x, y + top * 0.5, t.pos().z).subtract(eye);
			float aimYaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
			float aimPitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z)));
			camYaw += (float) (Mth.wrapDegrees(aimYaw - camYaw) * w);
			camPitch += (float) ((aimPitch - camPitch) * w);
		}
		yaw = camYaw;
		pitch = camPitch;
		double fovH = settings.get().fov();
		fov = fovH > 0 ? (float) ArmaCam.verticalFov(fovH, aspect.getAsDouble()) : Float.NaN;
		trace.add(System.nanoTime(), eye, t.pos(), yaw, pitch, pull, lift);
	}

	/** How far the eye must rise above {@code eyeY} so the bike-to-eye sight line and the eye clear the ground. */
	private double requiredLift(Vec3 bike, double eyeY, Vec3 rel) {
		double under = world.groundY(bike.x, bike.z);
		double pivotY = (Double.isFinite(under) && under <= bike.y + 1.5 ? Math.max(bike.y, under) : bike.y) + PIVOT_UP;
		double need = 0;
		for (double f : new double[] {0.3, 0.5, 0.7, 0.85, 1.0}) {
			double g = world.groundY(bike.x + rel.x * f, bike.z + rel.z * f);
			if (!Double.isFinite(g) || g > bike.y + GROUND_MAX_ABOVE || g < bike.y - 64) {
				continue; // unknown, a cliff or overhang (the pull-in handles it), or a chasm
			}
			double clear = f >= 1 ? EYE_CLEAR : SIGHT_CLEAR;
			// sight line pivot -> eye at fraction f must pass above g + clear
			double minEye = pivotY + (g + clear - pivotY) / f;
			need = Math.max(need, minEye - eyeY);
		}
		return Math.min(LIFT_MAX, need);
	}

	/** Production world: block collision rays and the MOTION_BLOCKING_NO_LEAVES heightmap of the client level. */
	private static final class McWorld implements World {
		@Override
		public double clearFraction(Vec3 pivot, Vec3 want) {
			return ArmaChaseCamera.clearFraction(pivot, want);
		}

		@Override
		public double groundY(double x, double z) {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.level == null) {
				return Double.NaN;
			}
			int bx = Mth.floor(x);
			int bz = Mth.floor(z);
			if (!mc.level.hasChunk(bx >> 4, bz >> 4)) {
				return Double.NaN;
			}
			return mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
		}
	}

	/** One block ray: distance from {@code from} to the first hit on the way to {@code to}, or NaN if clear. */
	interface RayCast {
		double hit(Vec3 from, Vec3 to);
	}

	/**
	 * Largest fraction of pivot -> want (0..1) that keeps the view clear. All rays start at one point, RAY_SKIP along
	 * the sight line from the pivot: nothing beside, below or ahead of the bike can start a ray inside a block (a
	 * 1-block step in front of the bike did exactly that with the old fan and snapped the camera onto the bike).
	 * The fan is at the eye end only (centre and eight corners MARGIN out), so a clear result also keeps the near
	 * plane out of blocks.
	 */
	static double fanClear(Vec3 pivot, Vec3 want, RayCast cast) {
		Vec3 d = want.subtract(pivot);
		double len = d.length();
		if (len < RAY_SKIP + 1e-3) {
			return 1;
		}
		Vec3 from = pivot.add(d.scale(RAY_SKIP / len));
		double best = 1;
		double o = MARGIN;
		double[][] fan = {{0, 0, 0}, {o, o, o}, {o, o, -o}, {o, -o, o}, {o, -o, -o}, {-o, o, o}, {-o, o, -o}, {-o, -o, o}, {-o, -o, -o}};
		for (double[] f : fan) {
			double h = cast.hit(from, want.add(f[0], f[1], f[2]));
			if (!Double.isNaN(h)) {
				best = Math.min(best, Math.max(0, (RAY_SKIP + h - MARGIN) / len));
			}
		}
		return best;
	}

	static double clearFraction(Vec3 pivot, Vec3 want) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.level == null || mc.player == null) {
			return 1;
		}
		// collision shapes: terrain, trunks and leaves block the view, grass and flowers do not
		return fanClear(pivot, want, (from, to) -> {
			HitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
			if (hit.getType() == HitResult.Type.MISS) {
				return Double.NaN;
			}
			if (countHits && hit instanceof net.minecraft.world.phys.BlockHitResult bh) {
				PULL_HITS.merge(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(bh.getBlockPos()).getBlock()).getPath(), 1,
					Integer::sum);
			}
			return hit.getLocation().distanceTo(from);
		});
	}

	private static double windowAspect() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) {
			return 16 / 9.0;
		}
		return mc.getWindow().getWidth() / (double) Math.max(1, mc.getWindow().getHeight());
	}

	private void glanceEdge(int which, boolean down) {
		if (down != glanceHeld[which]) {
			glanceHeld[which] = down;
			cam.glance(which, down);
		}
	}

	/** Target.glanceBack() when the host's api has it (additive field), else false. */
	private static final java.lang.reflect.Method GLANCE_BACK = findGlanceBack();

	private static java.lang.reflect.Method findGlanceBack() {
		for (var rc : Target.class.getRecordComponents()) {
			if (rc.getName().equals("glanceBack")) {
				return rc.getAccessor();
			}
		}
		return null;
	}

	static boolean glanceBack(Target t) {
		if (GLANCE_BACK == null) {
			return false;
		}
		try {
			return (Boolean) GLANCE_BACK.invoke(t);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	/** Current pull-in fraction (1 = not pulled in). */
	public double pull() {
		return pull;
	}

	@Override
	public void onTurn(int dir) {
		ArmaRideVisuals.ENGINE.turned();
	}

	@Override
	public Vec3 eye() {
		return eye;
	}

	@Override
	public float yaw() {
		return yaw;
	}

	@Override
	public float pitch() {
		return pitch;
	}

	@Override
	public float fov() {
		return fov;
	}
}

package gl.syn.armacraft.client.ridekit;

import com.mojang.blaze3d.vertex.PoseStack;
import gl.syn.armacraft.client.ride.api.BikeVisual;
import gl.syn.armacraft.client.kit.BikeAnimator;
import gl.syn.armacraft.client.kit.BikeRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.Vec3;

/**
 * Ride mode's bike: Armagetron's own light cycle (cycle_body/front/rear.mod, cycle textures tinted to the rider
 * colour) via {@link BikeRenderer}.
 *
 * <p>The local bike runs Armagetron's cycle animation (gCycle::TimestepCore through {@link BikeAnimator}) once per
 * rendered frame on the game-time clock (ticks + partial tick; stands still while paused): the body swings into the new heading, leans with the turn impulse and
 * the wheels roll with speed, all continuous between ticks. Its input is the cardinal {@code headingYaw}; the host's
 * own eased {@code yawDeg}/{@code lean} are not used for it. Remote bikes have no per-bike identity in the API, so
 * they are drawn stateless from the pose (host yaw and lean, wheels from {@code wheelPhase}).
 *
 * <p>Position: the pose's feet position is Armagetron's cycle point, where the trail attaches; the body extends
 * about one unit behind it, as in Armagetron.
 */
public final class ArmaBikeVisual implements BikeVisual {

	/** RideClient: {@code wheelPhase += blocksDriven / 0.38}. */
	private static final double HOST_WHEEL_RADIUS_BLOCKS = 0.38;
	private static final double ARMA_FRONT_RADIUS = .43, ARMA_REAR_RADIUS = .73;

	private final BikeAnimator local = new BikeAnimator();
	private final BikeGlow glow = new BikeGlow();
	private double lastLocalT = Double.NaN;
	private float lastHeading = Float.NaN;

	/**
	 * Game time in seconds: client level ticks plus the partial tick, the clock the host interpolates positions and
	 * the camera dt with. Stops while the game is paused or frozen ({@code /tick freeze}), so the animation does too.
	 */
	static double gameSeconds() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.level == null) {
			return Double.NaN;
		}
		return (mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)) / 20.0;
	}

	/** Diagnostics: submits seen, and the last local pose. */
	public static long submits;
	public static volatile Pose lastLocal;

	@Override
	public void submit(SubmitNodeCollector out, PoseStack poseStack, Vec3 cam, Pose p) {
		submits++;
		if (p.local()) {
			lastLocal = p;
			ArmaRideVisuals.visualScale(p.scale());
		}
		if (!(p.alpha() > 0.02f)) {
			return;
		}
		double s = p.scale() > 0 ? p.scale() : 1;
		float yaw;
		double skew;
		double front;
		double rear;
		if (p.local()) {
			double now = gameSeconds();
			double dt = Double.isNaN(now) || Double.isNaN(lastLocalT) ? 1 : now - lastLocalT;
			lastLocalT = now;
			if (!(dt >= 0) || dt > 0.5 || Float.isNaN(lastHeading)) {
				local.reset(p.headingYaw()); // new ride, world change or time jump: no swing from a stale heading
				dt = 0;
			}
			lastHeading = p.headingYaw();
			// body swing at the Armagetron speed (physics scale), wheel roll at the model's scale so the wheels do not
			// slip; sensors clear (lean comes from the turn impulse only)
			double phys = ArmaRideVisuals.physicsScale();
			local.step(Math.min(dt, 0.1), gl.syn.armacraft.client.kit.BikeAnimator.headingFromYaw(p.headingYaw()), p.speed() / (phys > 0 ? phys : s),
				p.speed() / s, true, 1, 1);
			yaw = local.bodyYaw();
			skew = local.skew();
			front = local.frontWheelAngle();
			rear = local.rearWheelAngle();
		} else {
			yaw = p.yawDeg();
			skew = Math.max(-0.5, Math.min(0.5, p.lean() * 0.5));
			double units = p.wheelPhase() * HOST_WHEEL_RADIUS_BLOCKS / s;
			// gCycle rotates the wheels by 2*distance/radius
			front = 2 * units / ARMA_FRONT_RADIUS;
			rear = 2 * units / ARMA_REAR_RADIUS;
		}
		int rgb = p.rgb();
		float r = ((rgb >> 16) & 0xFF) / 255f;
		float g = ((rgb >> 8) & 0xFF) / 255f;
		float b = (rgb & 0xFF) / 255f;
		BikeRenderer.get().submit(out, poseStack, cam, new BikeRenderer.BikePose(p.pos(), yaw, skew, front, rear, r, g, b, s));
		var c = ArmaRideVisuals.cfg();
		if (c.light && c.lightIntensity > 0) {
			// light centre about the body's middle and half a block up: the body extends ~1 unit behind the cycle point
			double yr = Math.toRadians(yaw);
			double back = 0.5 * s;
			Vec3 at = p.pos().add(Math.sin(yr) * back, 0.5, -Math.cos(yr) * back);
			glow.submit(out, poseStack, cam, p.rgb(), new BikeGlow.Light(at, c.lightRadius, c.lightIntensity * p.alpha()));
		}
	}

	/** Visible body yaw of the local bike after the last frame (diagnostics). */
	public float localBodyYaw() {
		return local.bodyYaw();
	}
}

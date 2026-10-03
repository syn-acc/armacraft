package gl.syn.armacraft.client.ride.render;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Cycle mode after a crash: the camera pulls up and back from the crash point over about a second and looks down at
 * it, like Armagetron's camera lingering on a fresh explosion. Mouse look turns it around the crash point, and its
 * heading is the direction the next bike deploys in.
 */
public final class DeathCam {

	private Vec3 eye = Vec3.ZERO;
	private float yaw;
	private float pitch;

	/**
	 * @param center    crash point (or the player, before the first ride)
	 * @param lookYaw   the player's free mouse-look yaw
	 * @param lookPitch the player's mouse-look pitch
	 * @param since     seconds since the crash
	 */
	public void update(Vec3 center, float lookYaw, float lookPitch, double since) {
		double k = 1 - Math.exp(-Math.max(0, since) / 0.35);
		double dist = 5 + 6 * k;
		double rise = 2 + 4 * k;
		yaw = lookYaw;
		double yr = Math.toRadians(yaw);
		double fx = -Math.sin(yr);
		double fz = Math.cos(yr);
		Vec3 pivot = center.add(0, 0.8, 0);
		Vec3 want = pivot.add(-fx * dist, rise, -fz * dist);
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null && mc.player != null) {
			HitResult hit = mc.level.clip(new ClipContext(pivot, want, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
			if (hit.getType() != HitResult.Type.MISS) {
				Vec3 at = hit.getLocation();
				want = at.add(pivot.subtract(at).normalize().scale(0.3));
			}
		}
		eye = want;
		Vec3 d = pivot.subtract(eye);
		float lookDown = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
		pitch = Math.max(-30f, Math.min(80f, lookDown + Math.max(-20f, Math.min(20f, lookPitch - 20f)) * 0.25f));
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
}

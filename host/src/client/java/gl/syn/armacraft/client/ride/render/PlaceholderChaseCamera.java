package gl.syn.armacraft.client.ride.render;

import gl.syn.armacraft.client.ride.api.ChaseCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Placeholder chase cam: orbits behind and above the bike along the player's free mouse look, pulls back with
 * speed, swings smoothly through turns (the rider's yaw jumps 90 degrees at a turn; the offset eases it out),
 * glances like Armagetron (eCamera.cpp:1638-1649: {@code glanceSmooth += 20 ts} while held, {@code /= 1 + 20 ts}
 * every frame, direction turned twice by (1, glanceSmooth): up to 90 degrees), and pulls in when terrain is between
 * bike and camera (in at once, out slowly, so leaves and trunks do not make it jump).
 */
public final class PlaceholderChaseCamera implements ChaseCamera {

	private static final double GLANCE_SPEED = 20;
	/** Critically damped springs (no velocity jumps): turn swing and vertical follow. */
	private static final double TURN_OMEGA = 9.0;
	private static final double Y_OMEGA = 10.0;

	private double yawOffset;
	private double yawVel;
	private double camY = Double.NaN;
	private double camYVel;
	private double glanceSmooth;
	private double smoothDist = Double.NaN;
	private double clipDist = Double.NaN;
	private Vec3 eye = Vec3.ZERO;
	private float yaw;
	private float pitch;

	@Override
	public void reset(Target t) {
		yawOffset = 0;
		yawVel = 0;
		camY = t.pos().y;
		camYVel = 0;
		glanceSmooth = 0;
		smoothDist = desiredDistance(t.speed());
		clipDist = Double.NaN;
		place(t, 0);
	}

	@Override
	public void onTurn(int dir) {
		yawOffset -= 90.0 * dir;
	}

	@Override
	public void update(double dt, Target t) {
		dt = Math.max(0, Math.min(0.25, dt));
		// sub-step the springs so large frame gaps stay stable
		int n = Math.max(1, (int) Math.ceil(dt / 0.004));
		double h = dt / n;
		if (Double.isNaN(camY)) {
			camY = t.pos().y;
		}
		for (int i = 0; i < n; i++) {
			yawVel += (-TURN_OMEGA * TURN_OMEGA * yawOffset - 2 * TURN_OMEGA * yawVel) * h;
			yawOffset += yawVel * h;
			camYVel += (Y_OMEGA * Y_OMEGA * (t.pos().y - camY) - 2 * Y_OMEGA * camYVel) * h;
			camY += camYVel * h;
		}
		if (Math.abs(t.pos().y - camY) > 4) {
			camY = t.pos().y; // teleport or long fall: do not lag behind
			camYVel = 0;
		}
		if (t.glanceLeft()) {
			glanceSmooth += GLANCE_SPEED * dt;
		}
		if (t.glanceRight()) {
			glanceSmooth -= GLANCE_SPEED * dt;
		}
		glanceSmooth /= (1 + GLANCE_SPEED * dt);
		double want = desiredDistance(t.speed());
		smoothDist = Double.isNaN(smoothDist) ? want : smoothDist + (want - smoothDist) * (1 - Math.exp(-dt * 2.0));
		place(t, dt);
	}

	private static double desiredDistance(double speed) {
		return 4.5 + Math.min(4.0, speed * 0.12);
	}

	/** Armagetron's glance direction (1, g) turned by (1, g), as an angle in degrees (left glance positive). */
	private double glanceDegrees() {
		double g = glanceSmooth;
		return Math.toDegrees(Math.atan2(2 * g, 1 - g * g));
	}

	private void place(Target t, double dt) {
		// Minecraft yaw grows clockwise seen from above; a left glance turns the view counter-clockwise
		// GLANCE_BACK flips the view (eCamera.cpp:1647-1648: glanceDir_ *= -1)
		yaw = (float) (t.lookYaw() + yawOffset - glanceDegrees() + (t.glanceBack() ? 180 : 0));
		pitch = Math.max(-25f, Math.min(75f, t.lookPitch() + 8f));
		double yr = Math.toRadians(yaw);
		double pr = Math.toRadians(pitch);
		double fx = -Math.sin(yr) * Math.cos(pr);
		double fy = -Math.sin(pr);
		double fz = Math.cos(yr) * Math.cos(pr);
		double py = Double.isNaN(camY) ? t.pos().y : camY;
		Vec3 pivot = new Vec3(t.pos().x, py + 1.6, t.pos().z);
		double dist = Double.isNaN(smoothDist) ? desiredDistance(t.speed()) : smoothDist;
		Vec3 dir = new Vec3(-fx, -fy + 0.4 / dist, -fz);
		double free = dist;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null && mc.player != null) {
			HitResult hit = mc.level.clip(new ClipContext(pivot, pivot.add(dir.scale(dist)), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE,
				mc.player));
			if (hit.getType() != HitResult.Type.MISS) {
				free = Math.max(0.5, hit.getLocation().distanceTo(pivot) / dir.length() - 0.25);
			}
		}
		if (Double.isNaN(clipDist) || free < clipDist) {
			clipDist = free;
		} else {
			clipDist += (free - clipDist) * (1 - Math.exp(-dt * 3.0));
		}
		eye = pivot.add(dir.scale(Math.min(dist, clipDist)));
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
}

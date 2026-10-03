package gl.syn.armacraft.client.ride.render;

import com.mojang.blaze3d.vertex.PoseStack;
import gl.syn.armacraft.client.ride.api.BikeVisual;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.Vec3;

/**
 * Placeholder light cycle made of boxes: dark body, rider-coloured stripe and canopy, two wheels whose spokes turn
 * with {@link BikeVisual.Pose#wheelPhase}. About 0.7 wide, 1.1 tall, 2.2 long, centred on the rider's position.
 */
public final class PlaceholderBike implements BikeVisual {

	private final Quads quads = new Quads(true);

	@Override
	public void submit(SubmitNodeCollector out, PoseStack poseStack, Vec3 cam, Pose bike) {
		quads.clear();
		double yaw = Math.toRadians(bike.yawDeg());
		double fx = -Math.sin(yaw);
		double fz = Math.cos(yaw);
		double rx = -fz;
		double rz = fx;
		float r = ((bike.rgb() >> 16) & 0xFF) / 255f;
		float g = ((bike.rgb() >> 8) & 0xFF) / 255f;
		float b = (bike.rgb() & 0xFF) / 255f;
		float a = bike.alpha();
		double ox = bike.pos().x - cam.x;
		double oy = bike.pos().y - cam.y;
		double oz = bike.pos().z - cam.z;
		// lean: shift the upper parts sideways
		double lean = Math.max(-1, Math.min(1, bike.lean())) * 0.18;

		// wheels: dark discs approximated by boxes, plus a coloured hub stripe that rotates
		for (int w = -1; w <= 1; w += 2) {
			double wx = ox + fx * 0.72 * w;
			double wz = oz + fz * 0.72 * w;
			quads.box(wx, oy + 0.38, wz, fx, fz, 0.38, 0.14, 0.38, 0.08f, 0.08f, 0.1f, a);
			double ph = bike.wheelPhase() + (w > 0 ? 0 : 0.8);
			double sx = Math.cos(ph);
			double sy = Math.sin(ph);
			quads.box(wx + fx * sx * 0.2 + rx * 0.15, oy + 0.38 + sy * 0.2, wz + fz * sx * 0.2 + rz * 0.15, fx, fz, 0.06, 0.02, 0.06, r, g, b, a);
			quads.box(wx - fx * sx * 0.2 - rx * 0.15, oy + 0.38 - sy * 0.2, wz - fz * sx * 0.2 - rz * 0.15, fx, fz, 0.06, 0.02, 0.06, r, g, b, a);
		}
		// body
		quads.box(ox + rx * lean, oy + 0.62, oz + rz * lean, fx, fz, 0.85, 0.24, 0.2, 0.12f, 0.12f, 0.16f, a);
		// glowing stripe along the body
		quads.box(ox + rx * lean, oy + 0.62, oz + rz * lean, fx, fz, 0.9, 0.26, 0.05, r, g, b, a);
		// canopy
		quads.box(ox + fx * 0.15 + rx * lean * 1.5, oy + 0.92, oz + fz * 0.15 + rz * lean * 1.5, fx, fz, 0.45, 0.2, 0.12, r * 0.7f, g * 0.7f, b * 0.7f, a);
		// tail light
		quads.box(ox - fx * 0.95 + rx * lean, oy + 0.7, oz - fz * 0.95 + rz * lean, fx, fz, 0.04, 0.2, 0.06, 1f, 0.15f, 0.1f, a);
		quads.submit(out, poseStack);
	}
}

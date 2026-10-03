package gl.syn.armacraft.client.ride.render;

import com.mojang.blaze3d.vertex.PoseStack;
import gl.syn.armacraft.client.ride.api.TrailVisual;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.Vec3;

/**
 * Placeholder light trail: a translucent ribbon in the rider's colour standing on the driven path, with a brighter
 * band along its top edge. The oldest {@code fadeLength} blocks fade out towards the tail.
 */
public final class PlaceholderTrail implements TrailVisual {

	private final Quads quads = new Quads(false);

	@Override
	public void submit(SubmitNodeCollector out, PoseStack poseStack, Vec3 cam, Ribbon t) {
		int n = t.count();
		boolean head = t.head() != null;
		if (n < 1 || (n < 2 && !head) || t.alpha() <= 0) {
			return;
		}
		quads.clear();
		float r = ((t.rgb() >> 16) & 0xFF) / 255f;
		float g = ((t.rgb() >> 8) & 0xFF) / 255f;
		float b = (t.rgb() & 0xFF) / 255f;
		double h = t.height();
		double[] p = t.points();
		double walked = 0;
		int pieces = n - 1 + (head ? 1 : 0);
		for (int k = 0; k < pieces; k++) {
			double ax = p[3 * k] - cam.x;
			double ay = p[3 * k + 1] - cam.y;
			double az = p[3 * k + 2] - cam.z;
			double bx;
			double by;
			double bz;
			if (k + 1 < n) {
				bx = p[3 * (k + 1)] - cam.x;
				by = p[3 * (k + 1) + 1] - cam.y;
				bz = p[3 * (k + 1) + 2] - cam.z;
			} else {
				bx = t.head().x - cam.x;
				by = t.head().y - cam.y;
				bz = t.head().z - cam.z;
			}
			double len = Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
			float fa = fade(walked, t) * t.alpha();
			float fb = fade(walked + len, t) * t.alpha();
			walked += len;
			if (fa <= 0 && fb <= 0) {
				continue;
			}
			// body
			strip(ax, ay + 0.02, az, bx, by + 0.02, bz, h * 0.85, r, g, b, fa * 0.45f, fb * 0.45f);
			// bright top band
			strip(ax, ay + h * 0.85, az, bx, by + h * 0.85, bz, h * 0.15, lift(r), lift(g), lift(b), fa * 0.9f, fb * 0.9f);
		}
		quads.submit(out, poseStack);
	}

	private void strip(double ax, double ay, double az, double bx, double by, double bz, double hh, float r, float g, float b, float a0, float a1) {
		quads.vertex(ax, ay, az, r, g, b, a0);
		quads.vertex(bx, by, bz, r, g, b, a1);
		quads.vertex(bx, by + hh, bz, r, g, b, a1);
		quads.vertex(ax, ay + hh, az, r, g, b, a0);
		quads.vertex(ax, ay + hh, az, r, g, b, a0);
		quads.vertex(bx, by + hh, bz, r, g, b, a1);
		quads.vertex(bx, by, bz, r, g, b, a1);
		quads.vertex(ax, ay, az, r, g, b, a0);
	}

	private static float lift(float c) {
		return 0.5f + c * 0.5f;
	}

	/** walked = distance from the tail. */
	private static float fade(double walked, Ribbon t) {
		if (!(t.fadeLength() > 0)) {
			return 1f;
		}
		return (float) Math.max(0, Math.min(1, walked / t.fadeLength()));
	}
}

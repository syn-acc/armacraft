package gl.syn.armacraft.client.ride.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;

import java.util.Arrays;

/**
 * Coloured, full-bright quads in camera-relative coordinates, submitted as custom geometry with a plain white
 * texture through Minecraft's translucent emissive entity render type. Placeholder-grade: no lighting, no model.
 */
public final class Quads {

	public static final Identifier WHITE = Identifier.fromNamespaceAndPath("armacraft", "textures/ride/white.png");
	private static final int STRIDE = 7; // x y z r g b a

	private float[] data = new float[STRIDE * 64];
	private int count;

	private final boolean opaque;

	/** @param opaque solid pass (bike bodies) instead of translucent emissive (trails) */
	public Quads(boolean opaque) {
		this.opaque = opaque;
	}

	public RenderType renderType() {
		return opaque ? RenderTypes.entitySolid(WHITE) : RenderTypes.entityTranslucentEmissive(WHITE);
	}

	public void clear() {
		count = 0;
	}

	public boolean isEmpty() {
		return count == 0;
	}

	public void vertex(double x, double y, double z, float r, float g, float b, float a) {
		if ((count + 1) * STRIDE > data.length) {
			data = Arrays.copyOf(data, data.length * 2);
		}
		int o = count * STRIDE;
		data[o] = (float) x;
		data[o + 1] = (float) y;
		data[o + 2] = (float) z;
		data[o + 3] = r;
		data[o + 4] = g;
		data[o + 5] = b;
		data[o + 6] = a;
		count++;
	}

	/** One quad, both windings (visible from both sides). */
	public void quad2(double[] p0, double[] p1, double[] p2, double[] p3, float r, float g, float b, float a) {
		quad(p0, p1, p2, p3, r, g, b, a);
		quad(p3, p2, p1, p0, r, g, b, a);
	}

	public void quad(double[] p0, double[] p1, double[] p2, double[] p3, float r, float g, float b, float a) {
		vertex(p0[0], p0[1], p0[2], r, g, b, a);
		vertex(p1[0], p1[1], p1[2], r, g, b, a);
		vertex(p2[0], p2[1], p2[2], r, g, b, a);
		vertex(p3[0], p3[1], p3[2], r, g, b, a);
	}

	/**
	 * An oriented box: centre (cx,cy,cz) camera-relative, half extents along the forward (fx,fz) and right
	 * (rx,rz) unit vectors and up. Faces are shaded 1.0 top, 0.8 sides, 0.6 front/back, 0.5 bottom.
	 */
	public void box(double cx, double cy, double cz, double fx, double fz, double hf, double hr, double hu, float r, float g, float b, float a) {
		double rx = -fz;
		double rz = fx;
		double[][] v = new double[8][];
		int i = 0;
		for (int sy = -1; sy <= 1; sy += 2) {
			for (int sf = -1; sf <= 1; sf += 2) {
				for (int sr = -1; sr <= 1; sr += 2) {
					v[i++] = new double[] {cx + fx * hf * sf + rx * hr * sr, cy + hu * sy, cz + fz * hf * sf + rz * hr * sr};
				}
			}
		}
		// index = (sy+1)/2*4 + (sf+1)/2*2 + (sr+1)/2
		face(v[4], v[5], v[7], v[6], r, g, b, a, 1.0f);   // top
		face(v[2], v[3], v[1], v[0], r, g, b, a, 0.5f);   // bottom
		face(v[6], v[7], v[3], v[2], r, g, b, a, 0.6f);   // front
		face(v[1], v[5], v[4], v[0], r, g, b, a, 0.6f);   // back
		face(v[3], v[7], v[5], v[1], r, g, b, a, 0.8f);   // right
		face(v[0], v[4], v[6], v[2], r, g, b, a, 0.8f);   // left
	}

	private void face(double[] p0, double[] p1, double[] p2, double[] p3, float r, float g, float b, float a, float shade) {
		quad2(p0, p1, p2, p3, r * shade, g * shade, b * shade, a);
	}

	public void submit(SubmitNodeCollector out, PoseStack poseStack) {
		if (count == 0) {
			return;
		}
		final float[] d = Arrays.copyOf(data, count * STRIDE);
		final int n = count;
		out.submitCustomGeometry(poseStack, renderType(), (pose, buf) -> emit(pose, buf, d, n));
	}

	private static void emit(PoseStack.Pose pose, VertexConsumer buf, float[] d, int n) {
		for (int i = 0; i < n; i++) {
			int o = i * STRIDE;
			buf.addVertex(pose, d[o], d[o + 1], d[o + 2])
				.setColor(d[o + 3], d[o + 4], d[o + 5], d[o + 6])
				.setUv(0.5f, 0.5f)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(LightCoordsUtil.FULL_BRIGHT)
				.setNormal(pose, 0f, 1f, 0f);
		}
	}
}

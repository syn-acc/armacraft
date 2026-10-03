package gl.syn.armacraft.client.core;

import java.util.Arrays;

/**
 * Growable vertex buffer in Armagetron coordinates (x east, y north, z up). Quads are 4 consecutive
 * vertices; triangles are stored as degenerate quads (third vertex repeated) because Minecraft's entity
 * pipelines draw quads. Lines are 2 consecutive vertices (u/v unused).
 *
 * <p>Per vertex: x y z u v r g b a nx ny nz (12 floats).
 */
public final class Mesh {
	public static final int STRIDE = 12;
	private float[] data = new float[STRIDE * 64];
	private int count;

	public void clear() {
		count = 0;
	}

	public int vertexCount() {
		return count;
	}

	public float[] data() {
		return data;
	}

	public boolean isEmpty() {
		return count == 0;
	}

	public void vertex(double x, double y, double z, double u, double v, double r, double g, double b, double a, double nx, double ny, double nz) {
		if ((count + 1) * STRIDE > data.length) {
			data = Arrays.copyOf(data, data.length * 2);
		}
		int o = count * STRIDE;
		data[o] = (float) x;
		data[o + 1] = (float) y;
		data[o + 2] = (float) z;
		data[o + 3] = (float) u;
		data[o + 4] = (float) v;
		data[o + 5] = (float) r;
		data[o + 6] = (float) g;
		data[o + 7] = (float) b;
		data[o + 8] = (float) a;
		data[o + 9] = (float) nx;
		data[o + 10] = (float) ny;
		data[o + 11] = (float) nz;
		count++;
	}

	public void vertex(double x, double y, double z, double u, double v, double r, double g, double b, double a) {
		vertex(x, y, z, u, v, r, g, b, a, 0, 0, 1);
	}

	public void line(double x1, double y1, double z1, double x2, double y2, double z2, double r, double g, double b, double a) {
		double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len <= 1e-9) {
			return;
		}
		dx /= len;
		dy /= len;
		dz /= len;
		vertex(x1, y1, z1, 0, 0, r, g, b, a, dx, dy, dz);
		vertex(x2, y2, z2, 0, 0, r, g, b, a, dx, dy, dz);
	}
}

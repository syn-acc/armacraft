package gl.syn.armacraft.client.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for Armagetron's old ".mod" model format, replicating {@code rModel::Load} (src/render/rModel.cpp,
 * v0.2.9.3.0, "old loader" branch):
 *
 * <ul>
 *   <li>The C++ loader loops {@code in >> c} reading one non-blank character, then
 *       {@code 'v'}: {@code in >> num >> x >> y >> z} stores vertex {@code num} (1-based in the shipped files;
 *       index 0 stays (0,0,0) and is unused), {@code 'f'}: {@code in >> A >> B >> C} adds a triangle,
 *       anything else is skipped one character at a time.</li>
 *   <li>Texture coordinates are a planar side projection over all {@code v} lines:
 *       {@code u = (x - xmin)/(xmax - xmin)}, {@code v = (zmax - z)/(zmax - zmin)}.</li>
 *   <li>Normals: per face {@code (B-A) x (C-A)} normalised, summed into its corners, then normalised.</li>
 * </ul>
 * Coordinates are Armagetron model space: x forward, y left, z up.
 */
public final class ModModel {

	public final float[] vx, vy, vz;   // positions, indexed by vertex number
	public final float[] nx, ny, nz;   // smooth normals
	public final float[] tu, tv;       // texture coordinates
	public final int[] faces;          // 3 vertex numbers per triangle

	private ModModel(float[] vx, float[] vy, float[] vz, float[] nx, float[] ny, float[] nz, float[] tu, float[] tv, int[] faces) {
		this.vx = vx;
		this.vy = vy;
		this.vz = vz;
		this.nx = nx;
		this.ny = ny;
		this.nz = nz;
		this.tu = tu;
		this.tv = tv;
		this.faces = faces;
	}

	public int vertexCount() {
		return vx.length;
	}

	public int faceCount() {
		return faces.length / 3;
	}

	public static ModModel parse(InputStream in) throws IOException {
		return parse(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
	}

	/** Character-level reader mimicking C++ istream extraction (skips whitespace before each value). */
	private static final class Cursor {
		final String s;
		int i;

		Cursor(String s) {
			this.s = s;
		}

		void skipWs() {
			while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
				i++;
			}
		}

		boolean eof() {
			skipWs();
			return i >= s.length();
		}

		char nextChar() {
			skipWs();
			return s.charAt(i++);
		}

		double nextNumber() throws IOException {
			skipWs();
			int start = i;
			while (i < s.length()) {
				char c = s.charAt(i);
				if (Character.isDigit(c) || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
					i++;
				} else {
					break;
				}
			}
			if (start == i) {
				throw new IOException("expected a number at offset " + start);
			}
			try {
				return Double.parseDouble(s.substring(start, i));
			} catch (NumberFormatException e) {
				throw new IOException("bad number '" + s.substring(start, i) + "' at offset " + start);
			}
		}
	}

	public static ModModel parse(String text) throws IOException {
		Cursor in = new Cursor(text);
		List<double[]> verts = new ArrayList<>();
		List<int[]> faces = new ArrayList<>();
		double xmax = -1e32, xmin = 1e32, zmax = -1e32, zmin = 1e32;

		while (!in.eof()) {
			char c = in.nextChar();
			if (c == 'v') {
				int num = (int) in.nextNumber();
				double x = in.nextNumber();
				double y = in.nextNumber();
				double z = in.nextNumber();
				if (num < 0 || num > 1_000_000) {
					throw new IOException("bad vertex number " + num);
				}
				xmax = Math.max(xmax, x);
				xmin = Math.min(xmin, x);
				zmax = Math.max(zmax, z);
				zmin = Math.min(zmin, z);
				while (verts.size() <= num) {
					verts.add(null);
				}
				verts.set(num, new double[] {x, y, z});
			} else if (c == 'f') {
				int a = (int) in.nextNumber();
				int b = (int) in.nextNumber();
				int d = (int) in.nextNumber();
				faces.add(new int[] {a, b, d});
			}
		}
		if (verts.isEmpty() || faces.isEmpty()) {
			throw new IOException(".mod data has no vertices or faces");
		}

		int count = verts.size();
		float[] vx = new float[count], vy = new float[count], vz = new float[count];
		for (int i = 0; i < count; i++) {
			double[] v = verts.get(i);
			if (v != null) {
				vx[i] = (float) v[0];
				vy[i] = (float) v[1];
				vz[i] = (float) v[2];
			}
		}
		float[] tu = new float[count], tv = new float[count];
		double dx = xmax - xmin, dz = zmax - zmin;
		for (int i = 0; i < count; i++) {
			tu[i] = (float) ((vx[i] - xmin) / dx);
			tv[i] = (float) ((zmax - vz[i]) / dz);
		}
		int[] f = new int[faces.size() * 3];
		for (int i = 0; i < faces.size(); i++) {
			int[] t = faces.get(i);
			for (int j = 0; j < 3; j++) {
				if (t[j] < 0 || t[j] >= count || verts.get(t[j]) == null) {
					throw new IOException("face " + i + " references missing vertex " + t[j]);
				}
				f[i * 3 + j] = t[j];
			}
		}
		double[] snx = new double[count], sny = new double[count], snz = new double[count];
		for (int i = faces.size() - 1; i >= 0; i--) {
			int a = f[i * 3], b = f[i * 3 + 1], c = f[i * 3 + 2];
			double x1 = vx[b] - vx[a], y1 = vy[b] - vy[a], z1 = vz[b] - vz[a];
			double x2 = vx[c] - vx[a], y2 = vy[c] - vy[a], z2 = vz[c] - vz[a];
			double nx = y1 * z2 - z1 * y2;
			double ny = z1 * x2 - x1 * z2;
			double nz = x1 * y2 - y1 * x2;
			double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len > 0) {
				nx /= len;
				ny /= len;
				nz /= len;
			}
			for (int j = 2; j >= 0; j--) {
				int v = f[i * 3 + j];
				snx[v] += nx;
				sny[v] += ny;
				snz[v] += nz;
			}
		}
		float[] nx = new float[count], ny = new float[count], nz = new float[count];
		for (int i = 0; i < count; i++) {
			double len = Math.sqrt(snx[i] * snx[i] + sny[i] * sny[i] + snz[i] * snz[i]);
			if (len > 0) {
				nx[i] = (float) (snx[i] / len);
				ny[i] = (float) (sny[i] / len);
				nz[i] = (float) (snz[i] / len);
			}
		}
		return new ModModel(vx, vy, vz, nx, ny, nz, tu, tv, f);
	}

	/** Axis-aligned bounds over all face vertices: {xmin,ymin,zmin,xmax,ymax,zmax}. */
	public double[] bounds() {
		double[] b = {1e32, 1e32, 1e32, -1e32, -1e32, -1e32};
		for (int v : faces) {
			b[0] = Math.min(b[0], vx[v]);
			b[1] = Math.min(b[1], vy[v]);
			b[2] = Math.min(b[2], vz[v]);
			b[3] = Math.max(b[3], vx[v]);
			b[4] = Math.max(b[4], vy[v]);
			b[5] = Math.max(b[5], vz[v]);
		}
		return b;
	}
}

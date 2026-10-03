package gl.syn.armacraft.client.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds Armagetron geometry (Armagetron coordinates) for one frame. Every constant and formula here is taken
 * from the v0.2.9.3.0 sources named in each method; nothing is restyled.
 */
public final class ArenaMesher {

	// gWall.cpp
	public static final double SEGLEN = 2.5;          // #define SEGLEN 2.5 (texture repeat along the trail)
	public static final double BEG_LEN = 2;           // #define gBEG_LEN 2 (head effect length, texture units)
	public static final double CYCLE_LEN = 1.5;       // #define gCYCLE_LEN 1.5
	public static final double WALL_H = 1;            // REAL h=1 in RenderNormal/RenderBegin
	private static final int SEGS = 5;                // #define segs 5

	// gWall.cpp gWallRim / eAdvWall
	public static final double RIM_STRETCH_X = 100;   // RIM_WALL_STRETCH_X
	public static final double RIM_STRETCH_Y = 100;   // RIM_WALL_STRETCH_Y

	// gCycle.cpp gCycle::Render lights (glLightfv, positions are directions in world space)
	private static final double[] LIGHT_A_DIR = norm3(320, 240, 200);
	private static final double[] LIGHT_B_DIR = norm3(-240, -100, 200);
	private static final double[] LIGHT_A = {1, .7, .7};
	private static final double[] LIGHT_B = {.7, .7, 1};
	/** GL default global ambient (0.2) times default material ambient (0.2). */
	private static final double AMBIENT = 0.04;

	/** CYCLE_WALLS_STAY_UP_DELAY as used by the guest (gGame.cpp multiplayer default 2.0). */
	public double wallsStayUpDelay = 2.0;

	private ArenaMesher() {
	}

	public static ArenaMesher create() {
		return new ArenaMesher();
	}

	private static double[] norm3(double x, double y, double z) {
		double l = Math.sqrt(x * x + y * y + z * z);
		return new double[] {x / l, y / l, z / l};
	}

	// ------------------------------------------------------------------------------------------------ cycle

	/** Row-major 3x4 affine matrix helpers (world = M * v). */
	static double[] identity() {
		return new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
	}

	static double[] mul(double[] a, double[] b) {
		double[] r = new double[12];
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 4; j++) {
				double s = a[i * 4] * b[j] + a[i * 4 + 1] * b[4 + j] + a[i * 4 + 2] * b[8 + j];
				if (j == 3) {
					s += a[i * 4 + 3];
				}
				r[i * 4 + j] = s;
			}
		}
		return r;
	}

	static double[] translate(double x, double y, double z) {
		return new double[] {1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z};
	}

	static double[] scale(double s) {
		return new double[] {s, 0, 0, 0, 0, s, 0, 0, 0, 0, s, 0};
	}

	/** GL matrix given as columns c0, c1, c2 (glMultMatrixf with column-major arrays). */
	static double[] columns(double[] c0, double[] c1, double[] c2) {
		return new double[] {c0[0], c1[0], c2[0], 0, c0[1], c1[1], c2[1], 0, c0[2], c1[2], c2[2], 0};
	}

	/**
	 * Model matrix of the cycle body as in gCycle::Render:
	 * {@code glTranslatef(p.x,p.y,0); glScalef(.5,.5,.5); glMultMatrixf(dir); glTranslatef(-1.5,0,0); glMultMatrixf(sk)}.
	 */
	public static double[] bodyMatrix(V2 p, V2 dir, double skew) {
		V2 ske = new V2(1, skew).normalized();
		double[] m = translate(p.x(), p.y(), 0);
		m = mul(m, scale(.5));
		m = mul(m, columns(new double[] {dir.x(), dir.y(), 0}, new double[] {-dir.y(), dir.x(), 0}, new double[] {0, 0, 1}));
		m = mul(m, translate(-1.5, 0, 0));
		m = mul(m, columns(new double[] {1, 0, 0}, new double[] {0, ske.x(), ske.y()}, new double[] {0, -ske.y(), ske.x()}));
		return m;
	}

	/** Wheel model matrix: body * glTranslatef(off) * rotation about the model y axis by {@code rot}. */
	public static double[] wheelMatrix(double[] body, double ox, double oz, V2 rot) {
		double[] m = mul(body, translate(ox, 0, oz));
		return mul(m, columns(new double[] {rot.x(), 0, rot.y()}, new double[] {0, 1, 0}, new double[] {-rot.y(), 0, rot.x()}));
	}

	/**
	 * Emits one .mod part. Per-vertex colour reproduces the fixed-function lighting Armagetron sets up for
	 * cycles: two directional lights (diffuse = specular = LIGHT_A/LIGHT_B), white material diffuse and
	 * specular, shininess 0 (so the specular term is the full light colour on any lit vertex), default
	 * ambient, no GL_NORMALIZE (normals scaled x2 by the .5 model scale). Texture modulates the result.
	 */
	public static void emitModel(Mesh out, ModModel m, double[] mat) {
		// normal matrix = rotation part (uniform scale removed, re-applied as the x2 of missing GL_NORMALIZE)
		double sc = Math.cbrt(Math.abs(det3(mat)));
		if (sc <= 0) {
			sc = 1;
		}
		int[] f = m.faces;
		for (int i = 0; i < f.length; i += 3) {
			for (int k = 0; k < 4; k++) {
				int v = f[i + Math.min(k, 2)];
				double x = m.vx[v], y = m.vy[v], z = m.vz[v];
				double wx = mat[0] * x + mat[1] * y + mat[2] * z + mat[3];
				double wy = mat[4] * x + mat[5] * y + mat[6] * z + mat[7];
				double wz = mat[8] * x + mat[9] * y + mat[10] * z + mat[11];
				double nx = (mat[0] * m.nx[v] + mat[1] * m.ny[v] + mat[2] * m.nz[v]) / sc;
				double ny = (mat[4] * m.nx[v] + mat[5] * m.ny[v] + mat[6] * m.nz[v]) / sc;
				double nz = (mat[8] * m.nx[v] + mat[9] * m.ny[v] + mat[10] * m.nz[v]) / sc;
				double[] c = light(nx, ny, nz, 2.0);
				out.vertex(wx, wy, wz, m.tu[v], m.tv[v], c[0], c[1], c[2], 1, nx, ny, nz);
			}
		}
	}

	private static double det3(double[] m) {
		return m[0] * (m[5] * m[10] - m[6] * m[9]) - m[1] * (m[4] * m[10] - m[6] * m[8]) + m[2] * (m[4] * m[9] - m[5] * m[8]);
	}

	/** Fixed-function vertex colour for a unit normal; {@code normalScale} = length of the GL eye normal. */
	public static double[] light(double nx, double ny, double nz, double normalScale) {
		double r = AMBIENT, g = AMBIENT, b = AMBIENT;
		double da = (nx * LIGHT_A_DIR[0] + ny * LIGHT_A_DIR[1] + nz * LIGHT_A_DIR[2]) * normalScale;
		double db = (nx * LIGHT_B_DIR[0] + ny * LIGHT_B_DIR[1] + nz * LIGHT_B_DIR[2]) * normalScale;
		if (da > 0) {
			r += LIGHT_A[0] * da + LIGHT_A[0];
			g += LIGHT_A[1] * da + LIGHT_A[1];
			b += LIGHT_A[2] * da + LIGHT_A[2];
		}
		if (db > 0) {
			r += LIGHT_B[0] * db + LIGHT_B[0];
			g += LIGHT_B[1] * db + LIGHT_B[1];
			b += LIGHT_B[2] * db + LIGHT_B[2];
		}
		return new double[] {Math.min(1, r), Math.min(1, g), Math.min(1, b)};
	}

	/** Full cycle: body into {@code body}, both wheels into {@code wheels} (gCycle::Render offsets). */
	public static void cycle(Mesh body, Mesh wheels, ModModel bodyModel, ModModel front, ModModel rear, V2 p, V2 dir, double skew, V2 rotFront, V2 rotRear) {
		double[] bm = bodyMatrix(p, dir, skew);
		emitModel(body, bodyModel, bm);
		emitModel(wheels, rear, wheelMatrix(bm, 0, .73, rotRear));
		emitModel(wheels, front, wheelMatrix(bm, 1.84, .43, rotFront));
	}

	// ------------------------------------------------------------------------------------------------ walls

	/** What the wall renderer needs to know about the owning cycle. */
	public record CycleView(float r, float g, float b, boolean alive, double deathTime, V2 renderPos, V2 dir, double skew) {}

	/** A trail piece with its solid fractions. */
	public record TrailSeg(WallGeom.Seg seg, double[][] gaps) {}

	static double hfunc(double x) {
		return 1 - (x * x) / 2;
	}

	static double cfunc(double x) {
		return x * x;
	}

	static double afunc(double x) {
		return 1 - x * x;
	}

	static double sfunc(double x) {
		return x * x;
	}

	static double xfunc(double x) {
		return (x * .2 + x * x) / 2;
	}

	/**
	 * gNetPlayerWall::RenderList for every wall of one cycle: bulk walls via RenderNormal, the last
	 * {@link #BEG_LEN} texture units before the cycle via RenderBegin (curving into the cycle, shrinking,
	 * whitening and fading), the dying-wall collapse after CYCLE_WALLS_STAY_UP_DELAY, and the coloured top
	 * edge lines.
	 *
	 * @param trail    the cycle's walls in any order (they are chained by matching endpoints)
	 * @param gameTime current render game time
	 */
	public void cycleWalls(Mesh quads, Mesh lines, List<TrailSeg> trail, CycleView cv, double gameTime) {
		if (trail.isEmpty()) {
			return;
		}
		List<WallGeom.Seg> segs = new ArrayList<>(trail.size());
		for (TrailSeg t : trail) {
			segs.add(t.seg());
		}
		double[] starts = WallGeom.chainStarts(segs);
		double total = 0;
		for (int i = 0; i < segs.size(); i++) {
			total = Math.max(total, starts[i] + segs.get(i).length());
		}
		double time = total / SEGLEN;
		if (!cv.alive() && Double.isFinite(cv.deathTime())) {
			time += Math.max(0, gameTime - cv.deathTime());
		}

		for (int i = 0; i < segs.size(); i++) {
			WallGeom.Seg s = segs.get(i);
			double len = s.length();
			if (len <= 0) {
				continue;
			}
			V2 P1 = new V2(s.x1(), s.y1());
			V2 P2 = new V2(s.x2(), s.y2());
			double vx = s.x2() - s.x1(), vy = s.y2() - s.y1();
			double xs = vx * vx, ys = vy * vy;
			double intensity = .7 + .3 * xs / (xs + ys);
			double r = cv.r() * intensity, g = cv.g() * intensity, b = cv.b() * intensity;
			double a = 1;
			for (double[] piece : WallGeom.solidPieces(trail.get(i).gaps())) {
				double pa = starts[i] + piece[0] * len;
				double pe = starts[i] + piece[1] * len;
				V2 p1 = V2.lerp(P1, P2, piece[0]);
				V2 p2 = V2.lerp(P1, P2, piece[1]);
				double ta = pa / SEGLEN;
				double te = pe / SEGLEN;
				if (te + BEG_LEN <= time) {
					renderNormal(quads, lines, p1, p2, ta, te, r, g, b, a, cv, gameTime);
				} else if (ta + BEG_LEN >= time) {
					renderBegin(quads, lines, p1, p2, ta, te, 1 + (ta - time) / BEG_LEN, 1 + (te - time) / BEG_LEN, r, g, b, a, cv, gameTime);
				} else {
					double denom = te - ta;
					if (denom <= 0) {
						continue;
					}
					double sfrac = ((time - BEG_LEN) - ta) / denom;
					V2 pm = V2.lerp(p1, p2, sfrac);
					renderBegin(quads, lines, pm, p2, ta + (te - ta) * sfrac, te, 0, 1 + (te - time) / BEG_LEN, r, g, b, a, cv, gameTime);
					renderNormal(quads, lines, p1, pm, ta, ta + (te - ta) * sfrac, r, g, b, a, cv, gameTime);
				}
			}
		}
	}

	private void renderNormal(Mesh quads, Mesh lines, V2 p1, V2 p2, double ta, double te, double r, double g, double b, double a, CycleView cv, double gameTime) {
		double hfrac = 1;
		if (!cv.alive() && wallsStayUpDelay >= 0 && Double.isFinite(cv.deathTime())) {
			double dt = (gameTime - cv.deathTime() - wallsStayUpDelay) * 2;
			if (dt > 1) {
				return;
			}
			if (dt >= 0) {
				double ca = .5 / (dt + .5);
				double alpha = Math.min(1, 1 - dt);
				hfrac = 1 - dt;
				r += ca;
				g += ca;
				b += ca;
				a *= alpha;
			}
		}
		double h = WALL_H;
		if (hfrac > 0) {
			lines.line(p1.x(), p1.y(), h * hfrac, p2.x(), p2.y(), h * hfrac, r, g, b, a);
			quads.vertex(p1.x(), p1.y(), 0, ta, hfrac, r, g, b, 1);
			quads.vertex(p1.x(), p1.y(), h * hfrac, ta, 0, r, g, b, 1);
			quads.vertex(p2.x(), p2.y(), h * hfrac, te, 0, r, g, b, 1);
			quads.vertex(p2.x(), p2.y(), 0, te, hfrac, r, g, b, 1);
		}
	}

	private void renderBegin(Mesh quads, Mesh lines, V2 p1, V2 pp2, double ta, double te, double ra, double re, double r, double g, double b, double a, CycleView cv, double gameTime) {
		double hfrac = 1;
		V2 p2 = pp2;
		if (re > 1) {
			if (re > 2 || re <= ra) {
				return;
			}
			double ratio = (1 - ra) / (re - ra);
			p2 = V2.lerp(p1, pp2, ratio);
			te = ta + (te - ta) * ratio;
			re = 1;
		}
		if (!cv.alive() && Double.isFinite(cv.deathTime())) {
			double dt = (gameTime - cv.deathTime() - wallsStayUpDelay) * 2;
			if (dt > 1) {
				dt = 1;
			}
			if (dt > 0) {
				double ca = .5 / (dt + .5);
				hfrac = 1 - dt;
				r += ca;
				g += ca;
				b += ca;
			}
		}
		double h = WALL_H;
		V2 ppos = cv.renderPos().sub(cv.dir().mul(CYCLE_LEN));
		double skew = cv.skew();
		V2 dir = cv.dir();
		if (!ppos.finite() || !p1.finite() || !p2.finite() || !Double.isFinite(skew)) {
			return;
		}
		double[] xs = new double[SEGS + 1], ys = new double[SEGS + 1], tx = new double[SEGS + 1], ty = new double[SEGS + 1], th = new double[SEGS + 1];
		double[] cs = new double[SEGS + 1], as = new double[SEGS + 1], us = new double[SEGS + 1];
		for (int i = 0; i <= SEGS; i++) {
			double frag = i / (double) SEGS;
			double rat = ra + frag * (re - ra);
			double xf = xfunc(rat);
			double x = (p1.x() + frag * (p2.x() - p1.x())) * (1 - xf) + ppos.x() * xf;
			double y = (p1.y() + frag * (p2.y() - p1.y())) * (1 - xf) + ppos.y() * xf;
			double H = h * hfrac * hfunc(rat);
			xs[i] = x;
			ys[i] = y;
			tx[i] = x + H * skew * sfunc(rat) * dir.y();
			ty[i] = y - H * skew * sfunc(rat) * dir.x();
			th[i] = H;
			cs[i] = cfunc(rat);
			as[i] = a * afunc(rat);
			us[i] = ta + (te - ta) * frag;
		}
		if (hfrac > 0) {
			for (int i = 0; i < SEGS; i++) {
				// line strip with per-vertex alpha; split into segments, alpha averaged per segment end
				lines.vertex(tx[i], ty[i], th[i], 0, 0, r, g, b, a * afunc(ra + (i / (double) SEGS) * (re - ra)), tx[i + 1] - tx[i], ty[i + 1] - ty[i], th[i + 1] - th[i]);
				lines.vertex(tx[i + 1], ty[i + 1], th[i + 1], 0, 0, r, g, b, a * afunc(ra + ((i + 1) / (double) SEGS) * (re - ra)), tx[i + 1] - tx[i], ty[i + 1] - ty[i], th[i + 1] - th[i]);
			}
		}
		for (int i = 0; i < SEGS; i++) {
			int j = i + 1;
			quads.vertex(xs[i], ys[i], 0, us[i], hfrac, r + cs[i], g + cs[i], b + cs[i], as[i]);
			quads.vertex(tx[i], ty[i], th[i], us[i], 0, r + cs[i], g + cs[i], b + cs[i], as[i]);
			quads.vertex(tx[j], ty[j], th[j], us[j], 0, r + cs[j], g + cs[j], b + cs[j], as[j]);
			quads.vertex(xs[j], ys[j], 0, us[j], hfrac, r + cs[j], g + cs[j], b + cs[j], as[j]);
		}
	}

	// ------------------------------------------------------------------------------------------------ rim

	/**
	 * gWallRim::RenderReal (gWall.cpp): textured quad, {@code u = (x+y)/RIM_WALL_STRETCH_X} per endpoint
	 * (wrapped by its integer part), {@code v = 1} at the floor and {@code 1 - h/RIM_WALL_STRETCH_Y} at the
	 * top, grey intensity {@code .7 + .3*dx^2/(dx^2+dy^2)}.
	 */
	public static void rimWall(Mesh quads, WallGeom.Seg s, double h) {
		double tBeg = (s.x1() + s.y1()) / RIM_STRETCH_X;
		double tEnd = (s.x2() + s.y2()) / RIM_STRETCH_X;
		double offset = tBeg > tEnd ? -Math.floor(tEnd) : -Math.floor(tBeg);
		tBeg += offset;
		tEnd += offset;
		double vx = s.x1() - s.x2(), vy = s.y1() - s.y2();
		double xs = vx * vx, ys = vy * vy;
		double intensity = .7 + .3 * xs / (xs + ys + 1e-30);
		double vTop = 1 - h / RIM_STRETCH_Y;
		quads.vertex(s.x1(), s.y1(), 0, tBeg, 1, intensity, intensity, intensity, 1);
		quads.vertex(s.x1(), s.y1(), h, tBeg, vTop, intensity, intensity, intensity, 1);
		quads.vertex(s.x2(), s.y2(), h, tEnd, vTop, intensity, intensity, intensity, 1);
		quads.vertex(s.x2(), s.y2(), 0, tEnd, 1, intensity, intensity, intensity, 1);
	}

	/**
	 * Rim walls that would block the camera's view of the watched cycle are lowered and grow back, like
	 * eCameraSensor::PassEdge + gWallRim::OnBlocksCamera + the regrowth at the end of gWallRim::RenderReal
	 * ({@code renderHeight *= 1+10*ts; renderHeight += 5*ts}). A wall crossed at fraction {@code time} of the
	 * way from the object to the camera may be {@code .5*(.5*camZ*time + objectZ*(1-time))} high
	 * ({@code objectZ = 2 * CamZ = 1.5}); walls lower than half that do not count as blockers. Only rim walls
	 * react (eWall::OnBlocksCamera is empty for player walls).
	 */
	public static final class RimLowering {
		public static final double OBJECT_Z = 1.5;
		private double[] heights = new double[0];

		public double[] update(List<WallGeom.Seg> rim, double fullHeight, double ts, double[] eye, List<double[]> targets) {
			if (heights.length != rim.size()) {
				heights = new double[rim.size()];
				java.util.Arrays.fill(heights, fullHeight);
			}
			for (int i = 0; i < rim.size(); i++) {
				double rh = heights[i];
				if (ts > 0) {
					if (rh < .25) {
						rh = .25;
					}
					rh *= 1 + 10 * ts;
					rh += 5 * ts;
				}
				if (rh > fullHeight) {
					rh = fullHeight;
				}
				if (eye != null) {
					for (double[] t : targets) {
						double limit = heightLimit(rim.get(i), eye, t);
						if (rh > limit * .5 && limit < rh) {
							rh = Math.max(.25, limit);
						}
					}
				}
				heights[i] = rh;
			}
			return heights;
		}

		/** Allowed wall height where the object-to-camera line crosses the wall, or +inf if it does not cross. */
		static double heightLimit(WallGeom.Seg s, double[] eye, double[] target) {
			double dx = eye[0] - target[0], dy = eye[1] - target[1];
			double ex = s.x2() - s.x1(), ey = s.y2() - s.y1();
			double denom = dx * ey - dy * ex;
			if (Math.abs(denom) < 1e-12) {
				return Double.POSITIVE_INFINITY;
			}
			double qx = s.x1() - target[0], qy = s.y1() - target[1];
			double time = (qx * ey - qy * ex) / denom;    // 0 at the object, 1 at the camera
			double u = (qx * dy - qy * dx) / denom;
			if (time <= 0 || time >= 1 || u < 0 || u > 1) {
				return Double.POSITIVE_INFINITY;
			}
			return .5 * (.5 * eye[2] * time + OBJECT_Z * (1 - time));
		}

		public void reset() {
			heights = new double[0];
		}
	}

	// ------------------------------------------------------------------------------------------------ explosion

	/** gExplosion.cpp init_exp: 9 fixed rays plus 31 random ones (fak = 7), normalised. */
	public static final double[][] EXPLOSION_RAYS = initRays();

	private static double[][] initRays() {
		List<double[]> v = new ArrayList<>();
		v.add(new double[] {0, 0, 1});
		v.add(new double[] {0, 1, 1});
		v.add(new double[] {0, -1, 1});
		v.add(new double[] {1, 0, 1});
		v.add(new double[] {-1, 0, 1});
		v.add(new double[] {1, 1, 1});
		v.add(new double[] {-1, 1, 1});
		v.add(new double[] {1, -1, 1});
		v.add(new double[] {-1, -1, 1});
		final double fak = 7;
		Random rnd = new Random(0x41524D41L);   // tRandomizer is seeded per run; any fixed spread will do
		for (int j = v.size(); j < 40; j++) {
			v.add(new double[] {fak * (rnd.nextDouble() - .5), fak * (rnd.nextDouble() - .5), 1});
		}
		double[][] out = new double[v.size()][];
		for (int i = 0; i < v.size(); i++) {
			double[] e = v.get(i);
			double l = Math.sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2]);
			out[i] = new double[] {e[0] / l, e[1] / l, e[2] / l};
		}
		return out;
	}

	/**
	 * gExplosion::Render: lines from {@code e*vec} to {@code a1*vec} with {@code a1 = (age+.01)*100},
	 * {@code e = max(0, a1-1)*100}, colour = cycle colour, alpha = {@code clamp(2 - a1, 0, 1)}.
	 */
	public static void explosion(Mesh lines, double x, double y, double r, double g, double b, double age) {
		double a1 = age + .01;
		double e = a1 - 1;
		if (e < 0) {
			e = 0;
		}
		double fade = 2 - a1;
		if (fade <= 0) {
			return;
		}
		if (fade > 1) {
			fade = 1;
		}
		a1 *= 100;
		e *= 100;
		for (double[] v : EXPLOSION_RAYS) {
			lines.line(x + a1 * v[0], y + a1 * v[1], a1 * v[2], x + e * v[0], y + e * v[1], e * v[2], r, g, b, fade);
		}
	}
}

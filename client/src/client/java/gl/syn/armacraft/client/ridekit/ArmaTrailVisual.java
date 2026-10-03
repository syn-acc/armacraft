package gl.syn.armacraft.client.ridekit;

import com.mojang.blaze3d.vertex.PoseStack;
import gl.syn.armacraft.client.ArenaClient;
import gl.syn.armacraft.client.ClientConfig;
import gl.syn.armacraft.client.core.ArenaMesher;
import gl.syn.armacraft.client.core.Mesh;
import gl.syn.armacraft.client.kit.BikeRenderer;
import gl.syn.armacraft.client.render.ArmaRenderTypes;
import gl.syn.armacraft.client.render.ArmaSubmit;
import gl.syn.armacraft.client.render.ArmaTextures;
import gl.syn.armacraft.client.ride.api.TrailVisual;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

/**
 * Ride mode's light trail with Armagetron's wall look (gNetPlayerWall::RenderList / RenderNormal / RenderBegin):
 * dir_wall texture repeating every SEGLEN (2.5) units, brightness .7 + .3 cos^2 of the wall direction, rider colour,
 * coloured top edge, and the head section (the last BEG_LEN texture units) curving into the bike, shrinking to half
 * height, whitening and fading out like Armagetron's growing wall end.
 *
 * <p>Minecraft additions: the ribbon follows the trail's 3D points (feet level, terrain height). A step (a height
 * change between two neighbouring points that is a terrain step, not a fall) is drawn as two level walls joined
 * vertically at the block edge where the ground changes, so walls step up and down with the terrain instead of
 * slanting through blocks; falls keep their slope. The rider is a box, not a point: it is lifted when its front
 * reaches the higher block and comes down when its back has left it, so the block edge lies up to
 * {@link #RIDER_HALF_WIDTH} ahead of the first raised point, or behind the last one (see {@link #stepJoin}).
 * On the low side of a step the wall top ramps up to the higher level ({@code render.trail_step_ramp}, see
 * {@link #rampExtra}), so the wall also covers the block face it climbs and the two levels form one surface.
 * Texture coordinates are anchored in world space, so the pattern does not slide when the tail is trimmed. The
 * oldest {@code fadeLength} blocks fade towards the tail.
 *
 * <p>Ride trails are player walls and are always drawn at full height: Armagetron 0.4 lowers only rim walls for
 * CAMERA_VISIBILITY_LOWER_WALL (gWallRim::OnBlocksCamera, gWall.cpp 504-526; player walls inherit the no-op at
 * eWall.cpp 319), so the setting does not apply here (see {@link #lowersTrailWalls}). Arena rim walls keep it
 * (ArenaRenderer / ArenaMesher).
 */
public final class ArmaTrailVisual implements TrailVisual {

	/** A height change this small or larger over a horizontal run this short is a terrain step (blocks). */
	private static final double STEP_MAX_RISE = 1.3;
	private static final double STEP_MAX_RUN = 2.5;
	/**
	 * Half the width of the rider's collision box (the vanilla player's 0.6 wide box). Entity.move steps the box up
	 * when its front face meets the block and lets it down when its back face has left the block, so recorded trail
	 * points change height this far before an up edge and this far after a down edge.
	 */
	static final double RIDER_HALF_WIDTH = 0.3;
	/** Subdivisions of the head section (Armagetron: segs 5). */
	private static final int HEAD_SEGS = 8;

	/** Physics blocks per unit of the ride (kept for the constructor contract; the trail itself uses the ribbon height). */
	private final java.util.function.DoubleSupplier blocksPerUnit;
	private final BikeGlow glow = new BikeGlow();
	// scratch polyline (render thread only)
	private double[] px = new double[256];
	private double[] py = new double[256];
	private double[] pz = new double[256];
	private int n;
	// an up step whose block edge lies ahead of the newest point: index of the join's lower point, the edge, the heading
	private int pendJoin = -1;
	private double pendX, pendZ, pendDx, pendDz;

	public ArmaTrailVisual(java.util.function.DoubleSupplier blocksPerUnit) {
		this.blocksPerUnit = blocksPerUnit;
	}

	@Override
	public void submit(SubmitNodeCollector out, PoseStack poseStack, Vec3 cam, Ribbon trail) {
		if (trail.count() < 1 || !(trail.alpha() > 0.003f) || !(trail.height() > 0)) {
			return;
		}
		// visual scale: Armagetron walls are 1 unit tall, so the ribbon height is the blocks per unit the trail (and the
		// bike, BikeVisual.Pose.scale) is drawn at. Head length, texture repeat and CYCLE_LEN use it.
		double s = trail.height();
		buildPolyline(trail);
		if (n < 2) {
			return;
		}
		ClientConfig cfg = ArenaClient.get() != null ? ArenaClient.get().config() : new ClientConfig();
		ArmaTextures.ensureBase(cfg.render.wallBodyAlpha, cfg.render.wallBodyBrightness);

		double segLen = ArenaMesher.SEGLEN * s;               // texture repeat, blocks
		double begLen = ArenaMesher.BEG_LEN * ArenaMesher.SEGLEN * s; // head section, blocks
		double h = trail.height();
		float r0 = ((trail.rgb() >> 16) & 0xFF) / 255f;
		float g0 = ((trail.rgb() >> 8) & 0xFF) / 255f;
		float b0 = (trail.rgb() & 0xFF) / 255f;

		// distance along the polyline (3D) from the tail, and from the head
		double[] dist = new double[n];
		for (int i = 1; i < n; i++) {
			dist[i] = dist[i - 1] + Math.sqrt(sq(px[i] - px[i - 1]) + sq(py[i] - py[i - 1]) + sq(pz[i] - pz[i - 1]));
		}
		double total = dist[n - 1];
		boolean head = trail.head() != null;
		// head direction (horizontal) and Armagetron's ppos = cycle position - dir * CYCLE_LEN
		double hdx = 0;
		double hdz = 0;
		for (int i = n - 1; i > 0 && hdx == 0 && hdz == 0; i--) {
			double dx = px[i] - px[i - 1];
			double dz = pz[i] - pz[i - 1];
			double l = Math.hypot(dx, dz);
			if (l > 1e-6) {
				hdx = dx / l;
				hdz = dz / l;
			}
		}
		double ppx = px[n - 1] - hdx * ArenaMesher.CYCLE_LEN * s;
		double ppz = pz[n - 1] - hdz * ArenaMesher.CYCLE_LEN * s;

		Mesh quads = new Mesh();
		Mesh lines = new Mesh();
		double ox = px[n - 1];
		double oy = py[n - 1];
		double oz = pz[n - 1];
		double ramp = cfg.render.trailStepRamp;
		for (int i = 0; i + 1 < n; i++) {
			double hx = px[i + 1] - px[i];
			double hz = pz[i + 1] - pz[i];
			double run = Math.hypot(hx, hz);
			double len = dist[i + 1] - dist[i];
			if (len <= 1e-6) {
				continue;
			}
			if (ramp > 0 && run <= 1e-6 && stepJoinAt(i)) {
				continue; // the ramps of the lower run already carry the top edge across this join
			}
			double intensity = run > 1e-6 ? .7 + .3 * (hx * hx) / (run * run) : 1;
			double hSeg = h; // never lowered: see lowersTrailWalls
			// this run is the low side of a step down at its start and/or a step up at its end: ramp the top there
			double dropAtStart = ramp > 0 && run > 1e-6 && i >= 1 && stepJoinAt(i - 1) && py[i - 1] > py[i] ? py[i - 1] - py[i] : 0;
			double riseAtEnd = ramp > 0 && run > 1e-6 && i + 2 < n && stepJoinAt(i + 1) && py[i + 2] > py[i + 1] ? py[i + 2] - py[i + 1] : 0;
			// head section: subdivide so the curve is smooth
			double fromHeadA = total - dist[i];
			double fromHeadB = total - dist[i + 1];
			int sub = head && fromHeadB < begLen ? Math.max(1, (int) Math.ceil(HEAD_SEGS * Math.min(len, fromHeadA - Math.max(0, fromHeadB)) / begLen)) : 1;
			double[] cuts = cuts(sub, rampCuts(len, dropAtStart, riseAtEnd, ramp), rampSubCuts(len, dropAtStart, riseAtEnd, ramp));
			for (int k = 0; k + 1 < cuts.length; k++) {
				double fa = cuts[k];
				double fb = cuts[k + 1];
				double[] va = vertexAt(i, fa, dist, total, head, begLen, hSeg, ppx, ppz, trail);
				double[] vb = vertexAt(i, fb, dist, total, head, begLen, hSeg, ppx, ppz, trail);
				va[3] += rampExtra(fa, len, dropAtStart, riseAtEnd, ramp);
				vb[3] += rampExtra(fb, len, dropAtStart, riseAtEnd, ramp);
				// va/vb: x, y(bottom), z, top height, whitening, alpha
				float ra = clamp(r0 * intensity + va[4]);
				float ga = clamp(g0 * intensity + va[4]);
				float ba = clamp(b0 * intensity + va[4]);
				float rb = clamp(r0 * intensity + vb[4]);
				float gb = clamp(g0 * intensity + vb[4]);
				float bb = clamp(b0 * intensity + vb[4]);
				double ua = (va[0] + va[2]) / segLen;
				double ub = (vb[0] + vb[2]) / segLen;
				if (run > 1e-6) {
					quad(quads, va, ox, oy, oz, ua, ra, ga, ba, vb, ub, rb, gb, bb);
				}
				// coloured top edge (vertical joins at steps included)
				lines.line(va[0] - ox, -(va[2] - oz), va[1] + va[3] - oy, vb[0] - ox, -(vb[2] - oz), vb[1] + vb[3] - oy,
					r0 * intensity, g0 * intensity, b0 * intensity, Math.min(va[5], vb[5]));
			}
		}
		if (head && ArmaRideVisuals.cfg().trailLight && ArmaRideVisuals.cfg().light) {
			trailGlow(out, poseStack, cam, dist, total, trail);
		}
		Vec3 origin = new Vec3(ox, oy, oz);
		BikeRenderer.submitMesh(out, poseStack, ArmaRenderTypes.wall(), quads, origin, 1, cam);
		if (!lines.isEmpty()) {
			final float[] d = java.util.Arrays.copyOf(lines.data(), lines.vertexCount() * Mesh.STRIDE);
			final int count = lines.vertexCount();
			final double lx = ox - cam.x;
			final double ly = oy - cam.y;
			final double lz = oz - cam.z;
			final float width = BikeRenderer.screenLineWidth(cfg.render.lineWidth);
			ArmaSubmit.blended(out, poseStack, RenderTypes.linesTranslucent(), (pose, buf) -> BikeRenderer.emitLines(pose, buf, d, count, lx, ly, lz, 1, width));
		}
	}

	/** Faint light along the newest blocks of the trail (the bike's own pool covers the head itself). */
	private void trailGlow(SubmitNodeCollector out, PoseStack poseStack, Vec3 cam, double[] dist, double total, Ribbon trail) {
		var c = ArmaRideVisuals.cfg();
		java.util.List<BikeGlow.Light> lights = new java.util.ArrayList<>();
		for (double back = 2; back <= 8; back += 2) {
			double want = total - back;
			if (want < 0) {
				break;
			}
			for (int i = 0; i + 1 < n; i++) {
				if (dist[i + 1] >= want) {
					double f = dist[i + 1] > dist[i] ? (want - dist[i]) / (dist[i + 1] - dist[i]) : 0;
					Vec3 p = new Vec3(px[i] + (px[i + 1] - px[i]) * f, py[i] + (py[i + 1] - py[i]) * f + trail.height() * 0.5, pz[i] + (pz[i + 1] - pz[i]) * f);
					lights.add(new BikeGlow.Light(p, 2.2, c.lightIntensity * 0.3 * (1 - back / 10) * trail.alpha()));
					break;
				}
			}
		}
		if (!lights.isEmpty()) {
			glow.submit(out, poseStack, cam, trail.rgb(), lights.toArray(new BikeGlow.Light[0]));
		}
	}

	/** One wall column at fraction f of polyline segment i: {x, y, z, wallHeight, whitening, alpha}. */
	private double[] vertexAt(int i, double f, double[] dist, double total, boolean head, double begLen, double h, double ppx, double ppz,
		Ribbon trail) {
		double x = px[i] + (px[i + 1] - px[i]) * f;
		double y = py[i] + (py[i + 1] - py[i]) * f;
		double z = pz[i] + (pz[i + 1] - pz[i]) * f;
		double d = dist[i] + (dist[i + 1] - dist[i]) * f;
		double alpha = trail.alpha();
		if (trail.fadeLength() > 1e-6) {
			alpha *= Math.max(0, Math.min(1, d / trail.fadeLength()));
		}
		double height = h;
		double white = 0;
		double fromHead = total - d;
		if (head && fromHead < begLen) {
			// RenderBegin: rat 0 at the start of the head section, 1 at the bike
			double rat = 1 - fromHead / begLen;
			double xf = (rat * .2 + rat * rat) / 2;          // xfunc
			x = x * (1 - xf) + ppx * xf;
			z = z * (1 - xf) + ppz * xf;
			height = h * (1 - rat * rat / 2);                 // hfunc
			white = rat * rat;                                // cfunc
			alpha *= 1 - rat * rat;                           // afunc
		}
		return new double[] {x, y, z, height, white, alpha};
	}

	/** Polyline segment i is the vertical join of a terrain step (same place, a step's height apart). */
	private boolean stepJoinAt(int i) {
		return i >= 0 && i + 1 < n && Math.hypot(px[i + 1] - px[i], pz[i + 1] - pz[i]) <= 1e-6
			&& Math.abs(py[i + 1] - py[i]) > 1e-3 && Math.abs(py[i + 1] - py[i]) <= STEP_MAX_RISE;
	}

	/**
	 * Extra wall height at fraction f of a level run of length {@code len} that is the low side of a step: the top
	 * starts {@code dropAtStart} higher (the level the trail came down from) and ends {@code riseAtEnd} higher (the
	 * level it climbs to), each falling off linearly over {@code ramp} blocks per block of step. Two ramps that do not
	 * fit in the run are shortened in proportion.
	 */
	static double rampExtra(double f, double len, double dropAtStart, double riseAtEnd, double ramp) {
		if (!(ramp > 0) || !(len > 1e-9)) {
			return 0;
		}
		double ld = dropAtStart * ramp;
		double lu = riseAtEnd * ramp;
		if (ld + lu > len) {
			double k = len / (ld + lu);
			ld *= k;
			lu *= k;
		}
		double extra = 0;
		if (ld > 1e-9) {
			extra += dropAtStart * Math.max(0, 1 - f * len / ld);
		}
		if (lu > 1e-9) {
			extra += riseAtEnd * Math.max(0, 1 - (1 - f) * len / lu);
		}
		return extra;
	}

	/** Fractions of the run where a ramp ends (the wall top has a corner there), inside (0, 1). */
	static double[] rampCuts(double len, double dropAtStart, double riseAtEnd, double ramp) {
		if (!(ramp > 0) || !(len > 1e-9) || (dropAtStart <= 0 && riseAtEnd <= 0)) {
			return new double[0];
		}
		double ld = dropAtStart * ramp;
		double lu = riseAtEnd * ramp;
		if (ld + lu > len) {
			double k = len / (ld + lu);
			ld *= k;
			lu *= k;
		}
		double a = ld / len;
		double b = 1 - lu / len;
		boolean hasA = ld > 1e-9 && a < 1 - 1e-9;
		boolean hasB = lu > 1e-9 && b > 1e-9 && (!hasA || b > a + 1e-9);
		return hasA && hasB ? new double[] {a, b} : hasA ? new double[] {a} : hasB ? new double[] {b} : new double[0];
	}

	/**
	 * Fractions of the run inside its ramps, {@link #RAMP_SEGS} slices per block of ramp. A ramp drawn as one quad is
	 * a trapezoid whose texture is stretched over a different height at each end; the GPU draws it as two triangles
	 * that each stretch the texture linearly, so the pattern (dir_wall's diagonal bolt and its top line) kinks along
	 * the quad's diagonal: a sawtooth at every step. Thin slices are nearly rectangles, so the stretch is smooth.
	 */
	static double[] rampSubCuts(double len, double dropAtStart, double riseAtEnd, double ramp) {
		if (!(ramp > 0) || !(len > 1e-9) || (dropAtStart <= 0 && riseAtEnd <= 0)) {
			return new double[0];
		}
		double ld = Math.max(0, dropAtStart) * ramp;
		double lu = Math.max(0, riseAtEnd) * ramp;
		if (ld + lu > len) {
			double k = len / (ld + lu);
			ld *= k;
			lu *= k;
		}
		int nd = ld > 1e-9 ? rampSlices(ld) : 0;
		int nu = lu > 1e-9 ? rampSlices(lu) : 0;
		double[] out = new double[Math.max(0, nd - 1) + Math.max(0, nu - 1)];
		int m = 0;
		for (int j = 1; j < nd; j++) {
			out[m++] = j * ld / (nd * len);
		}
		for (int j = 1; j < nu; j++) {
			out[m++] = 1 - j * lu / (nu * len);
		}
		return out;
	}

	/** Slices per block of ramp, and the most one ramp gets. */
	private static final int RAMP_SEGS = 8, RAMP_SEGS_MAX = 12;

	private static int rampSlices(double rampLength) {
		return Math.max(2, Math.min(RAMP_SEGS_MAX, (int) Math.ceil(RAMP_SEGS * rampLength - 1e-9)));
	}

	/** 0, 1, the {@code sub} equal subdivisions and the extra fractions, sorted and without duplicates. */
	private static double[] cuts(int sub, double[] extra, double[] extra2) {
		double[] all = new double[sub + 1 + extra.length + extra2.length];
		for (int k = 0; k <= sub; k++) {
			all[k] = k / (double) sub;
		}
		System.arraycopy(extra, 0, all, sub + 1, extra.length);
		System.arraycopy(extra2, 0, all, sub + 1 + extra.length, extra2.length);
		java.util.Arrays.sort(all);
		int m = 1;
		for (int k = 1; k < all.length; k++) {
			if (all[k] - all[m - 1] > 1e-9) {
				all[m++] = all[k];
			}
		}
		return m == all.length ? all : java.util.Arrays.copyOf(all, m);
	}

	/** Mesh space is Armagetron-like (x east, y = -z, z up) relative to the origin, so BikeRenderer.emitQuads maps it back. */
	private static void quad(Mesh m, double[] a, double ox, double oy, double oz, double ua, float ra, float ga, float ba,
		double[] b, double ub, float rb, float gb, float bb) {
		m.vertex(a[0] - ox, -(a[2] - oz), a[1] - oy, ua, 1, ra, ga, ba, a[5]);
		m.vertex(a[0] - ox, -(a[2] - oz), a[1] + a[3] - oy, ua, 0, ra, ga, ba, a[5]);
		m.vertex(b[0] - ox, -(b[2] - oz), b[1] + b[3] - oy, ub, 0, rb, gb, bb, b[5]);
		m.vertex(b[0] - ox, -(b[2] - oz), b[1] - oy, ub, 1, rb, gb, bb, b[5]);
	}

	/** The polyline {@link #submit} draws for this ribbon, as {x, y, z} rows (tests). */
	double[][] polyline(Ribbon t) {
		buildPolyline(t);
		double[][] out = new double[n][];
		for (int i = 0; i < n; i++) {
			out[i] = new double[] {px[i], py[i], pz[i]};
		}
		return out;
	}

	/** Trail points + head, with terrain steps split into level runs joined vertically at the block edge. */
	private void buildPolyline(Ribbon t) {
		n = 0;
		pendJoin = -1;
		double[] p = t.points();
		int count = Math.min(t.count(), p.length / 3);
		for (int i = 0; i < count; i++) {
			add(p[3 * i], p[3 * i + 1], p[3 * i + 2]);
		}
		Vec3 head = t.head();
		if (head != null) {
			cutAtHead(head);
			add(head.x, head.y, head.z);
		}
	}

	/**
	 * The host records trail points at each tick's end position, but the rider (and {@code head}) is drawn at the
	 * interpolated position, up to one tick behind. Without this the wall would run out to the newest point, ahead of
	 * the bike, and back. Cut the polyline at the head's projection onto the nearest of the last few segments
	 * (horizontal distance), dropping everything after it.
	 */
	private void cutAtHead(Vec3 head) {
		if (n < 2) {
			return;
		}
		int best = -1;
		double bestD = Double.MAX_VALUE;
		double bestF = 0;
		for (int i = n - 2; i >= Math.max(0, n - 5); i--) {
			double dx = px[i + 1] - px[i], dz = pz[i + 1] - pz[i];
			double l2 = dx * dx + dz * dz;
			double f = l2 < 1e-12 ? 0 : ((head.x - px[i]) * dx + (head.z - pz[i]) * dz) / l2;
			f = Math.max(0, Math.min(1, f));
			double cx = px[i] + dx * f, cz = pz[i] + dz * f;
			double d = (head.x - cx) * (head.x - cx) + (head.z - cz) * (head.z - cz);
			if (d < bestD - 1e-9) {
				bestD = d;
				best = i;
				bestF = f;
			}
		}
		if (best < 0 || bestD > 1.0) {
			return; // head not on the recent trail (e.g. teleported): keep the polyline
		}
		// keep points 0..best, then the projection point (if it is not the segment start)
		double fx = px[best] + (px[best + 1] - px[best]) * bestF;
		double fy = py[best] + (py[best + 1] - py[best]) * bestF;
		double fz = pz[best] + (pz[best + 1] - pz[best]) * bestF;
		n = best + 1;
		if (bestF > 1e-6) {
			push(fx, fy, fz);
		}
	}

	private void add(double x, double y, double z) {
		resolvePendingJoin(x, z);
		if (n > 0) {
			double lx = px[n - 1];
			double ly = py[n - 1];
			double lz = pz[n - 1];
			double dx = x - lx;
			double dz = z - lz;
			double run = Math.hypot(dx, dz);
			double rise = y - ly;
			if (run < 1e-6 && Math.abs(rise) < 1e-6) {
				return; // duplicate
			}
			if (Math.abs(rise) > 1e-3 && Math.abs(rise) <= STEP_MAX_RISE && run > 1e-3 && run <= STEP_MAX_RUN) {
				double f = stepJoin(lx, lz, x, z, rise > 0);
				if (f > 1) {
					// up step, the block edge is ahead of this point: join here for now, and move the join out to the
					// edge once the trail is seen to continue past it (resolvePendingJoin); a turn here keeps it
					push(x, ly, z);
					pendJoin = n - 1;
					pendX = lx + dx * f;
					pendZ = lz + dz * f;
					pendDx = dx / run;
					pendDz = dz / run;
				} else if (f < 0) {
					// down step, the block edge is behind the previous point: pull that point back to the edge if the
					// level run it ends reaches back past the edge, else join at it
					double ex = lx + dx * f;
					double ez = lz + dz * f;
					if (n >= 2 && Math.abs(py[n - 2] - ly) < 1e-6 && (ex - px[n - 2]) * dx + (ez - pz[n - 2]) * dz > 1e-9
						&& Math.abs((lx - px[n - 2]) * dz - (lz - pz[n - 2]) * dx) < 1e-6 * run) {
						px[n - 1] = ex;
						pz[n - 1] = ez;
						push(ex, y, ez);
					} else {
						push(lx, y, lz);
					}
				} else {
					double mx = lx + dx * f;
					double mz = lz + dz * f;
					push(mx, ly, mz);
					push(mx, y, mz);
				}
			}
		}
		push(x, y, z);
	}

	/** The next point after an up step whose edge was still ahead: if the trail carried on past the edge, join there. */
	private void resolvePendingJoin(double x, double z) {
		int j = pendJoin;
		pendJoin = -1;
		if (j < 0 || j + 1 >= n) {
			return; // none, or cut off at the head
		}
		double ax = x - px[j];
		double az = z - pz[j];
		double along = ax * pendDx + az * pendDz;
		double side = Math.abs(ax * pendDz - az * pendDx);
		double edge = (pendX - px[j]) * pendDx + (pendZ - pz[j]) * pendDz;
		if (side < 1e-6 && along >= edge - 1e-9) {
			px[j] = pendX;
			pz[j] = pendZ;
			px[j + 1] = pendX;
			pz[j + 1] = pendZ;
		}
	}

	/**
	 * Where the ground changes for a step between two neighbouring trail points, as a fraction of the way from the
	 * first to the second: the block edge crossed by the rider's front (up step) or back (down step) during that
	 * move. Below 0 or above 1 when that edge lies outside the two points (at most {@link #RIDER_HALF_WIDTH} away).
	 * Without such an edge (not a block step): the first block edge between the points, or halfway.
	 */
	static double stepJoin(double x1, double z1, double x2, double z2, boolean up) {
		double best = Double.NaN;
		double bestDist = Double.MAX_VALUE;
		double[][] axes = {{x1, x2}, {z1, z2}};
		for (double[] a : axes) {
			double d = a[1] - a[0];
			if (Math.abs(d) < 1e-6) {
				continue;
			}
			// the face that decides: front for up, back for down, in the direction of travel
			double shift = (up ? RIDER_HALF_WIDTH : -RIDER_HALF_WIDTH) * Math.signum(d);
			double lo = Math.min(a[0], a[1]) + shift;
			double hi = Math.max(a[0], a[1]) + shift;
			// edges the face passed during the move: (from, to] in travel order
			for (double e = d > 0 ? Math.floor(lo) + 1 : Math.ceil(hi) - 1; d > 0 ? e <= hi + 1e-9 : e >= lo - 1e-9; e += d > 0 ? 1 : -1) {
				double mid = Math.abs((e - shift - a[0]) / d - 0.5);
				if (mid < bestDist) {
					bestDist = mid;
					best = (e - a[0]) / d;
				}
			}
		}
		return Double.isNaN(best) ? edgeCrossing(x1, z1, x2, z2) : best;
	}

	private static double edgeCrossing(double x1, double z1, double x2, double z2) {
		double best = 0.5;
		double bestDist = Double.MAX_VALUE;
		double[][] axes = {{x1, x2}, {z1, z2}};
		for (double[] a : axes) {
			double lo = Math.min(a[0], a[1]);
			double hi = Math.max(a[0], a[1]);
			double edge = Math.floor(lo) + 1;
			if (edge > lo && edge < hi && hi - lo > 1e-6) {
				double f = (edge - a[0]) / (a[1] - a[0]);
				if (Math.abs(f - 0.5) < bestDist) {
					bestDist = Math.abs(f - 0.5);
					best = f;
				}
			}
		}
		return best;
	}

	private void push(double x, double y, double z) {
		if (n == px.length) {
			px = java.util.Arrays.copyOf(px, n * 2);
			py = java.util.Arrays.copyOf(py, n * 2);
			pz = java.util.Arrays.copyOf(pz, n * 2);
		}
		px[n] = x;
		py[n] = y;
		pz[n] = z;
		n++;
	}

	/**
	 * Whether ride trail walls are lowered where they cross the bike-to-camera sight line. Never: in Armagetron 0.4
	 * CAMERA_VISIBILITY_LOWER_WALL lowers rim walls only (player walls are never lowered), whatever the camera
	 * setting and whether the ride camera is active. Arena rim walls are lowered by the arena renderer.
	 */
	static boolean lowersTrailWalls(boolean rideCameraActive, boolean cameraVisibilityLowerWall) {
		return false;
	}

	private static double sq(double v) {
		return v * v;
	}

	private static float clamp(double v) {
		return (float) Math.max(0, Math.min(1, v));
	}
}

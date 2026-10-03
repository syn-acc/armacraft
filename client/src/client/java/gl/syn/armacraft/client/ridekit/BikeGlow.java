package gl.syn.armacraft.client.ridekit;

import com.mojang.blaze3d.vertex.PoseStack;
import gl.syn.armacraft.client.core.Mesh;
import gl.syn.armacraft.client.kit.BikeRenderer;
import gl.syn.armacraft.client.render.ArmaRenderTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;


/**
 * The light a light cycle gives off (NOT Armagetron, which has no world to light): a soft additive pool in the
 * rider's colour on the terrain around one or more light points. Geometry follows the blocks: the top face of each
 * surface block in range, plus the exposed sides of steps, each lifted a hair off the surface. Brightness is per
 * vertex, {@code I * (1 - (d/R)^2)^2} from the 3D distance to the light, times how much the face looks at the light,
 * so it fades smoothly to zero at the radius and nothing pops in or out. Stronger at night than in daylight.
 *
 * <p>Cost: block shapes come from a per-block cache (primitive map, no boxing) that survives the light moving and
 * changing height; each frame re-reads only 6 columns of the area from the world (round robin), so placed or broken
 * blocks show up within a fraction of a second and there is never a bulk rebuild. The mesh (tens of quads) is
 * rebuilt per frame. No lighting engine or chunk re-meshing involved.
 */
public final class BikeGlow {

	private static final double LIFT = 0.015;
	/** Columns searched from this far above to this far below the light's block. */
	private static final int UP = 2;
	private static final int DOWN = 5;

	/** Per-block cache shared by all lights: collision shape top (0..1.5) of the block, NaN if no collision, -1 unknown. */
	private static final it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap SHAPE_TOP = new it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap();
	private static Level cacheLevel;
	private static final int VERIFY_PER_FRAME = 6;
	private int verifyCursor;
	private int cacheY;
	private double dayFactor = Double.NaN;
	private double lastT = Double.NaN;

	/** Diagnostics: CPU time spent building and submitting light pools. */
	public static long statNanos, statCalls, statMaxNanos, statQuads;

	public static String stats() {
		return String.format(java.util.Locale.ROOT, "%d calls, %.1f us avg, %.1f us max, %.0f quads avg", statCalls,
			statCalls == 0 ? 0 : statNanos / 1e3 / statCalls, statMaxNanos / 1e3, statCalls == 0 ? 0 : statQuads / (double) statCalls);
	}

	public static void resetStats() {
		statNanos = statCalls = statMaxNanos = statQuads = 0;
	}

	/** One light: position (world), radius (blocks), intensity (0..~1). */
	public record Light(Vec3 pos, double radius, double intensity) {}

	public void submit(SubmitNodeCollector out, PoseStack poseStack, Vec3 cam, int rgb, Light... lights) {
		long t0 = System.nanoTime();
		int quads = build(out, poseStack, cam, rgb, lights);
		long dt = System.nanoTime() - t0;
		statNanos += dt;
		statCalls++;
		statMaxNanos = Math.max(statMaxNanos, dt);
		statQuads += quads;
	}

	private int build(SubmitNodeCollector out, PoseStack poseStack, Vec3 cam, int rgb, Light... lights) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.level == null || lights.length == 0) {
			return 0;
		}
		Level level = mc.level;
		Vec3 c0 = lights[0].pos();
		double night = nightFactor(level, BlockPos.containing(c0));
		// the rider colour, 30 % towards white: saturated light reads better on dark terrain
		float r = 0.7f * ((rgb >> 16) & 0xFF) / 255f + 0.3f;
		float g = 0.7f * ((rgb >> 8) & 0xFF) / 255f + 0.3f;
		float b = 0.7f * (rgb & 0xFF) / 255f + 0.3f;

		// columns covered by any light
		double maxR = 0;
		double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (Light l : lights) {
			maxR = Math.max(maxR, l.radius());
			minX = Math.min(minX, l.pos().x - l.radius());
			minZ = Math.min(minZ, l.pos().z - l.radius());
			maxX = Math.max(maxX, l.pos().x + l.radius());
			maxZ = Math.max(maxZ, l.pos().z + l.radius());
		}
		refreshCache(level, BlockPos.containing(c0));
		// keep the cache honest: re-read a few columns of this area from the world every frame
		int w = (int) Math.floor(maxX) - (int) Math.floor(minX) + 3;
		int h = (int) Math.floor(maxZ) - (int) Math.floor(minZ) + 3;
		for (int k = 0; k < VERIFY_PER_FRAME && w > 0 && h > 0; k++) {
			int idx = Math.floorMod(verifyCursor++, w * h);
			verify(level, (int) Math.floor(minX) - 1 + idx % w, (int) Math.floor(minZ) - 1 + idx / w);
		}
		Mesh mesh = new Mesh();
		double ox = c0.x, oy = c0.y, oz = c0.z;
		for (int x = (int) Math.floor(minX); x <= (int) Math.floor(maxX); x++) {
			for (int z = (int) Math.floor(minZ); z <= (int) Math.floor(maxZ); z++) {
				double top = top(level, x, z);
				if (Double.isNaN(top)) {
					continue;
				}
				double y = top + LIFT;
				// top face
				double i00 = light(lights, x, y, z, 0, 1, 0) * night;
				double i10 = light(lights, x + 1, y, z, 0, 1, 0) * night;
				double i11 = light(lights, x + 1, y, z + 1, 0, 1, 0) * night;
				double i01 = light(lights, x, y, z + 1, 0, 1, 0) * night;
				if (i00 + i10 + i11 + i01 > 1e-3) {
					quad(mesh, ox, oy, oz, r, g, b, x, y, z, i00, x + 1, y, z, i10, x + 1, y, z + 1, i11, x, y, z + 1, i01);
				}
				// exposed sides towards lower neighbours (step faces)
				for (Direction d : Direction.Plane.HORIZONTAL) {
					double nTop = top(level, x + d.getStepX(), z + d.getStepZ());
					double low = Double.isNaN(nTop) ? top - 1 : nTop;
					if (low >= top - 1e-3) {
						continue;
					}
					low = Math.max(low, top - 3);
					double fx = d.getStepX() > 0 ? x + 1 + LIFT : d.getStepX() < 0 ? x - LIFT : 0;
					double fz = d.getStepZ() > 0 ? z + 1 + LIFT : d.getStepZ() < 0 ? z - LIFT : 0;
					double ax, az, bx, bz;
					if (d.getStepX() != 0) {
						ax = fx;
						bx = fx;
						az = z;
						bz = z + 1;
					} else {
						az = fz;
						bz = fz;
						ax = x;
						bx = x + 1;
					}
					double nx = d.getStepX(), nz = d.getStepZ();
					double ia = light(lights, ax, top, az, nx, 0, nz) * night;
					double ib = light(lights, bx, top, bz, nx, 0, nz) * night;
					double ja = light(lights, ax, low, az, nx, 0, nz) * night;
					double jb = light(lights, bx, low, bz, nx, 0, nz) * night;
					if (ia + ib + ja + jb > 1e-3) {
						quad(mesh, ox, oy, oz, r, g, b, ax, top, az, ia, bx, top, bz, ib, bx, low, bz, jb, ax, low, az, ja);
					}
				}
			}
		}
		if (!mesh.isEmpty()) {
			BikeRenderer.submitMesh(out, poseStack, ArmaRenderTypes.glow(), mesh, c0, 1, cam);
		}
		return mesh.vertexCount() / 4;
	}

	/** Sum of the lights at a point on a face with normal n. */
	private static double light(Light[] lights, double x, double y, double z, double nx, double ny, double nz) {
		double sum = 0;
		for (Light l : lights) {
			double dx = l.pos().x - x, dy = l.pos().y - y, dz = l.pos().z - z;
			double d2 = dx * dx + dy * dy + dz * dz;
			double r2 = l.radius() * l.radius();
			if (d2 >= r2) {
				continue;
			}
			double fall = 1 - d2 / r2;
			fall *= fall;
			double d = Math.sqrt(d2);
			// Lambert-ish: faces looking at the light get more, never zero right next to it
			double facing = d < 1e-6 ? 1 : Math.max(0, (dx * nx + dy * ny + dz * nz) / d);
			sum += l.intensity() * fall * (0.35 + 0.65 * facing);
		}
		return Math.min(1.5, sum);
	}

	private static void quad(Mesh m, double ox, double oy, double oz, float r, float g, float b, double x1, double y1, double z1, double i1, double x2,
		double y2, double z2, double i2, double x3, double y3, double z3, double i3, double x4, double y4, double z4, double i4) {
		vertex(m, ox, oy, oz, r, g, b, x1, y1, z1, i1);
		vertex(m, ox, oy, oz, r, g, b, x2, y2, z2, i2);
		vertex(m, ox, oy, oz, r, g, b, x3, y3, z3, i3);
		vertex(m, ox, oy, oz, r, g, b, x4, y4, z4, i4);
	}

	/** Mesh space is Armagetron-like (x, -z, up) relative to the origin; additive, so colour = rgb * intensity. */
	private static void vertex(Mesh m, double ox, double oy, double oz, float r, float g, float b, double x, double y, double z, double i) {
		m.vertex(x - ox, -(z - oz), y - oy, 0.5, 0.5, r * i, g * i, b * i, 1);
	}

	/** 1 in the dark .. 0.3 in full daylight, eased over about a second of game time (no flicker at light edges). */
	private double nightFactor(Level level, BlockPos at) {
		double raw = level.getMaxLocalRawBrightness(at) / 15.0;
		double want = 1 - 0.7 * raw;
		double t = ArmaBikeVisual.gameSeconds();
		double dt = Double.isFinite(t) && Double.isFinite(lastT) ? Math.max(0, Math.min(0.25, t - lastT)) : 0;
		lastT = t;
		if (Double.isNaN(dayFactor)) {
			dayFactor = want;
		} else {
			dayFactor += (want - dayFactor) * (1 - Math.exp(-dt * 1.5));
		}
		return dayFactor;
	}

	private void refreshCache(Level level, BlockPos ref) {
		cacheY = ref.getY();
		if (cacheLevel != level || SHAPE_TOP.size() > 200_000) {
			SHAPE_TOP.clear();
			SHAPE_TOP.defaultReturnValue(-1f);
			cacheLevel = level;
		}
	}

	private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

	/** Collision shape top of one block (cached), NaN if it has no collision. */
	private float shapeTop(Level level, int x, int y, int z) {
		long k = BlockPos.asLong(x, y, z);
		float v = SHAPE_TOP.get(k);
		if (v != -1f) {
			return v;
		}
		return read(level, x, y, z, k);
	}

	private float read(Level level, int x, int y, int z, long k) {
		scratch.set(x, y, z);
		BlockState st = level.getBlockState(scratch);
		VoxelShape shape = st.getCollisionShape(level, scratch);
		float v = shape.isEmpty() ? Float.NaN : (float) shape.max(Direction.Axis.Y);
		SHAPE_TOP.put(k, v);
		return v;
	}

	/** Re-read one column's blocks in the search window from the world. */
	private void verify(Level level, int x, int z) {
		for (int y = cacheY + UP + 1; y >= cacheY - DOWN; y--) {
			read(level, x, y, z, BlockPos.asLong(x, y, z));
		}
	}

	/** Top of the walkable surface in this column near the light's height, or NaN. */
	private double top(Level level, int x, int z) {
		for (int y = cacheY + UP; y >= cacheY - DOWN; y--) {
			float t = shapeTop(level, x, y, z);
			if (Float.isNaN(t)) {
				continue;
			}
			return Float.isNaN(shapeTop(level, x, y + 1, z)) ? y + t : Double.NaN;
		}
		return Double.NaN;
	}
}

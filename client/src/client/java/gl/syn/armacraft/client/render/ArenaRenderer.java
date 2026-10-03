package gl.syn.armacraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import gl.syn.armacraft.client.ArenaClient;
import gl.syn.armacraft.client.ClientConfig;
import gl.syn.armacraft.client.core.ArenaMapping;
import gl.syn.armacraft.client.core.ArenaMesher;
import gl.syn.armacraft.client.core.ArenaModel;
import gl.syn.armacraft.client.core.Mesh;
import gl.syn.armacraft.client.core.ModModel;
import gl.syn.armacraft.client.core.V2;
import gl.syn.armacraft.client.core.WallGeom;
import gl.syn.armacraft.client.kit.BikeAnimator;
import gl.syn.armacraft.client.kit.BikeRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the arena every frame from the dead-reckoned model and submits it as custom geometry
 * (Fabric LevelRenderEvents.COLLECT_SUBMITS). Geometry is computed in Armagetron coordinates by
 * {@link ArenaMesher} and mapped to camera-relative Minecraft coordinates here.
 */
public final class ArenaRenderer {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft_client/render");

	private final ArenaClient client;
	private final ArenaMesher mesher = ArenaMesher.create();
	private final ArenaMesher.RimLowering rimLowering = new ArenaMesher.RimLowering();
	private double lastRimLocal = Double.NaN;

	// reused per frame
	private final Mesh floor = new Mesh();
	private final Mesh rim = new Mesh();
	private final Mesh walls = new Mesh();
	private final Mesh lines = new Mesh();

	public ArenaRenderer(ArenaClient client) {
		this.client = client;
	}

	public void collect(LevelRenderContext ctx) {
		if (!client.active()) {
			return;
		}
		PoseStack poseStack = ctx.poseStack();
		if (poseStack == null) {
			poseStack = new PoseStack();
		}
		SubmitNodeCollector out = ctx.submitNodeCollector();
		ClientConfig cfg = client.config();
		ArenaModel model = client.model();
		ArenaMapping map = client.mapping();
		Vec3 cam = ctx.levelState().cameraRenderState.pos;
		double t = client.gameTime();
		if (!Double.isFinite(t)) {
			return;
		}

		ArmaTextures.ensureBase(cfg.render.wallBodyAlpha, cfg.render.wallBodyBrightness);

		floor.clear();
		rim.clear();
		walls.clear();
		lines.clear();

		// ---- floor grid over the arena bounds (rFLOOR_TWOTEXTURE, GRID_SIZE 1, FLOOR_RED/GREEN/BLUE)
		double[] b = model.bounds();
		if (cfg.render.floor && b != null) {
			double gain = cfg.render.floorGain;
			double r = .15 * gain, g = .3 * gain, bl = .15 * gain;
			double z = cfg.render.floorLift / map.scale();
			// tile so texture coordinates stay small and quads stay inside the near/far planes
			double tile = 32;
			for (double x0 = b[0]; x0 < b[2] - 1e-9; x0 += tile) {
				double x1 = Math.min(b[2], x0 + tile);
				for (double y0 = b[1]; y0 < b[3] - 1e-9; y0 += tile) {
					double y1 = Math.min(b[3], y0 + tile);
					double u0 = x0 - Math.floor(x0), v0 = y0 - Math.floor(y0);
					floor.vertex(x0, y0, z, u0, v0, r, g, bl, 1);
					floor.vertex(x0, y1, z, u0, v0 + (y1 - y0), r, g, bl, 1);
					floor.vertex(x1, y1, z, u0 + (x1 - x0), v0 + (y1 - y0), r, g, bl, 1);
					floor.vertex(x1, y0, z, u0 + (x1 - x0), v0, r, g, bl, 1);
				}
			}
		}

		// ---- rim walls, lowered where they would hide the watched cycle
		double now = ArenaClient.localNow();
		double rts = Double.isFinite(lastRimLocal) ? Math.min(0.25, now - lastRimLocal) : 0;
		lastRimLocal = now;
		double[] eye = client.camera().armaEye();
		List<double[]> targets = new ArrayList<>();
		ArenaModel.Cycle watched = client.camera().watchedId() != null ? model.cycle(client.camera().watchedId()) : null;
		if (watched != null) {
			V2 p = client.renderPos(watched);
			targets.add(new double[] {p.x(), p.y(), 0});
			V2 d = watched.anim.dir;
			targets.add(new double[] {p.x() + d.x() * watched.speed, p.y() + d.y() * watched.speed, 0});
		}
		double[] heights = rimLowering.update(model.rim(), cfg.render.rimHeight, rts, eye, targets);
		for (int i = 0; i < model.rim().size(); i++) {
			ArenaMesher.rimWall(rim, model.rim().get(i), heights[i]);
		}

		// ---- player walls, grouped per cycle; the head wall is extended to the dead-reckoned position
		mesher.wallsStayUpDelay = cfg.render.wallsStayUpDelay;
		Map<Integer, List<ArenaMesher.TrailSeg>> trails = new HashMap<>();
		for (ArenaModel.Wall w : model.walls()) {
			trails.computeIfAbsent(w.cycle(), k -> new ArrayList<>()).add(new ArenaMesher.TrailSeg(new WallGeom.Seg(w.x1(), w.y1(), w.x2(), w.y2()), w.gaps()));
		}
		for (ArenaModel.Cycle c : model.cycles()) {
			List<ArenaMesher.TrailSeg> trail = trails.computeIfAbsent(c.id, k -> new ArrayList<>());
			if (c.alive && t > 0) {
				List<V2> path = c.track.path(t);
				for (int i = 0; i + 1 < path.size(); i++) {
					V2 a = path.get(i), e = path.get(i + 1);
					if (a.dist(e) > 1e-6) {
						trail.add(new ArenaMesher.TrailSeg(new WallGeom.Seg(a.x(), a.y(), e.x(), e.y()), null));
					}
				}
			}
		}
		for (Map.Entry<Integer, List<ArenaMesher.TrailSeg>> e : trails.entrySet()) {
			ArenaModel.Cycle c = model.cycle(e.getKey());
			ArenaMesher.CycleView cv;
			if (c != null) {
				cv = new ArenaMesher.CycleView(c.r, c.g, c.b, c.alive, c.deathTime, client.renderPos(c), c.anim.dir, c.anim.skew);
			} else {
				cv = new ArenaMesher.CycleView(1, 1, 1, false, t - 100, new V2(0, 0), new V2(1, 0), 0);
			}
			mesher.cycleWalls(walls, lines, e.getValue(), cv, t);
		}

		// ---- cycles (gCycle::Render draws only alive cycles), through the shared bike renderer
		List<BikeRenderer.BikePose> bikes = new ArrayList<>();
		for (ArenaModel.Cycle c : model.cycles()) {
			if (!c.alive) {
				continue;
			}
			// eCamera::Render: the watched object is not drawn when the eye is within 1 unit of it (in-cycle camera)
			V2 p = client.renderPos(c);
			if (eye != null && watched == c && Math.hypot(p.x() - eye[0], p.y() - eye[1]) <= 1 && Math.abs(eye[2] - .75) <= 1) {
				continue;
			}
			double[] mc = client.toMc(p.x(), p.y(), 0);
			bikes.add(new BikeRenderer.BikePose(new Vec3(mc[0], mc[1], mc[2]), BikeAnimator.yawFromHeading(c.anim.dir), c.anim.skew,
				-Math.atan2(c.anim.rotFront.y(), c.anim.rotFront.x()), -Math.atan2(c.anim.rotRear.y(), c.anim.rotRear.x()),
				c.r, c.g, c.b, map.scale()));
		}

		// ---- explosions
		for (ArenaModel.Explosion x : model.explosions()) {
			ArenaMesher.explosion(lines, x.x(), x.y(), x.r(), x.g(), x.b(), t - x.t());
		}

		// ---- submit
		Emit emit = new Emit(map, cam, (float) cfg.render.lineWidth);
		if (!floor.isEmpty()) {
			submit(out, poseStack, ArmaRenderTypes.floor(), floor, emit, false);
		}
		if (!rim.isEmpty()) {
			submit(out, poseStack, ArmaRenderTypes.rim(), rim, emit, false);
		}
		for (BikeRenderer.BikePose bike : bikes) {
			BikeRenderer.get().submit(out, poseStack, cam, bike);
		}
		if (!walls.isEmpty()) {
			submit(out, poseStack, ArmaRenderTypes.wall(), walls, emit, false);
		}
		if (!lines.isEmpty()) {
			submit(out, poseStack, ArmaRenderTypes.lines(), lines, emit, true);
		}
	}

	/** Snapshot the mesh (the callback runs after this method returns) and submit it. */
	private static void submit(SubmitNodeCollector out, PoseStack poseStack, RenderType type, Mesh mesh, Emit emit, boolean isLines) {
		final float[] data = java.util.Arrays.copyOf(mesh.data(), mesh.vertexCount() * Mesh.STRIDE);
		final int count = mesh.vertexCount();
		SubmitNodeCollector.CustomGeometryRenderer renderer = (pose, buffer) -> {
			if (isLines) {
				emit.lines(pose, buffer, data, count);
			} else {
				emit.quads(pose, buffer, data, count);
			}
		};
		// blended types (walls, floor grid, lines) go after translucent terrain: see ArmaSubmit
		ArmaSubmit.auto(out, poseStack, type, renderer);
	}

	/** Armagetron (x, y, z) to camera-relative Minecraft (x, z, -y) * scale. */
	private record Emit(ArenaMapping map, Vec3 cam, float lineWidth) {
		void quads(PoseStack.Pose pose, VertexConsumer buf, float[] d, int count) {
			BikeRenderer.emitQuads(pose, buf, d, count, map.ax() - cam.x, map.ay() - cam.y, map.az() - cam.z, map.scale());
		}

		void lines(PoseStack.Pose pose, VertexConsumer buf, float[] d, int count) {
			BikeRenderer.emitLines(pose, buf, d, count, map.ax() - cam.x, map.ay() - cam.y, map.az() - cam.z, map.scale(), lineWidth);
		}
	}
}

package gl.syn.armacraft.client.kit;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import gl.syn.armacraft.client.core.ArenaMesher;
import gl.syn.armacraft.client.core.Mesh;
import gl.syn.armacraft.client.core.ModModel;
import gl.syn.armacraft.client.core.V2;
import gl.syn.armacraft.client.render.ArmaRenderTypes;
import gl.syn.armacraft.client.render.ArmaSubmit;
import gl.syn.armacraft.client.render.ArmaTextures;
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

/**
 * Draws Armagetron's light cycle (models/cycle_body.mod + cycle_front.mod + cycle_rear.mod, textures/cycle_body.png
 * and cycle_wheel.png tinted to the rider colour) at any Minecraft position, exactly as gCycle::Render builds it:
 * scale .5, body offset -1.5 along the heading, lean about the forward axis, rear wheel at (0,0,.73), front at
 * (1.84,0,.43), Armagetron's two-light fixed-function shading baked into vertex colours. No bridge types.
 *
 * <p>Call {@link #submit} from a submit phase, e.g. Fabric {@code LevelRenderEvents.COLLECT_SUBMITS}.
 */
public final class BikeRenderer {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft_client/bike");
	private static BikeRenderer instance;

	/**
	 * One bike to draw.
	 *
	 * @param pos        Minecraft position of Armagetron's cycle point: on the ground, at the front of the bike; the
	 *                   body extends about {@link #LENGTH_BEHIND} units behind it and {@link #LENGTH_AHEAD} ahead
	 * @param yawDeg     visible body yaw (Minecraft degrees), e.g. {@link BikeAnimator#bodyYaw()}
	 * @param skew       lean -0.5..0.5 (positive leans right), e.g. {@link BikeAnimator#skew()}
	 * @param frontWheel front wheel roll angle, radians
	 * @param rearWheel  rear wheel roll angle, radians
	 * @param r          rider colour 0..1 (fills the transparent parts of the textures)
	 * @param scale      blocks per Armagetron unit (arena mapping scale; 1 in the host default)
	 */
	public record BikePose(Vec3 pos, float yawDeg, double skew, double frontWheel, double rearWheel, float r, float g, float b, double scale) {}

	/** Model extent along the heading, Armagetron units (body x -0.508..2.049 at scale .5, offset -1.5). */
	public static final double LENGTH_BEHIND = 1.0, LENGTH_AHEAD = 0.28, HEIGHT = 0.78;

	private ModModel body, front, rear;
	private boolean loaded;

	public static synchronized BikeRenderer get() {
		if (instance == null) {
			instance = new BikeRenderer();
		}
		return instance;
	}

	private void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			body = model("cycle_body.mod");
			front = model("cycle_front.mod");
			rear = model("cycle_rear.mod");
		} catch (IOException e) {
			LOG.error("could not load Armagetron cycle models", e);
		}
	}

	private static ModModel model(String name) throws IOException {
		Identifier id = Identifier.fromNamespaceAndPath(ArmaTextures.NS, "models/arma/" + name);
		var res = Minecraft.getInstance().getResourceManager().getResource(id);
		if (res.isEmpty()) {
			throw new IOException("missing " + id);
		}
		try (InputStream in = res.get().open()) {
			return ModModel.parse(in);
		}
	}

	/** Submit one bike. {@code cameraPos} is the render camera position (geometry is camera-relative). */
	public void submit(SubmitNodeCollector out, PoseStack poseStack, Vec3 cameraPos, BikePose bike) {
		load();
		if (body == null) {
			return;
		}
		Identifier[] tex = ArmaTextures.cycle(bike.r(), bike.g(), bike.b());
		if (tex == null) {
			return;
		}
		Mesh bm = new Mesh(), wm = new Mesh();
		V2 dir = BikeAnimator.headingFromYaw(bike.yawDeg());
		V2 rotFront = new V2(Math.cos(bike.frontWheel()), -Math.sin(bike.frontWheel()));
		V2 rotRear = new V2(Math.cos(bike.rearWheel()), -Math.sin(bike.rearWheel()));
		ArenaMesher.cycle(bm, wm, body, front, rear, V2.ZERO, dir, bike.skew(), rotFront, rotRear);
		submitMesh(out, poseStack, ArmaRenderTypes.model(tex[0]), bm, bike.pos(), bike.scale(), cameraPos);
		submitMesh(out, poseStack, ArmaRenderTypes.model(tex[1]), wm, bike.pos(), bike.scale(), cameraPos);
	}

	/** Submit an Armagetron-coordinate quad mesh whose origin sits at {@code origin} (Minecraft). */
	public static void submitMesh(SubmitNodeCollector out, PoseStack poseStack, RenderType type, Mesh mesh, Vec3 origin, double scale, Vec3 cameraPos) {
		if (mesh.isEmpty()) {
			return;
		}
		final float[] d = java.util.Arrays.copyOf(mesh.data(), mesh.vertexCount() * Mesh.STRIDE);
		final int count = mesh.vertexCount();
		final double ox = origin.x - cameraPos.x, oy = origin.y - cameraPos.y, oz = origin.z - cameraPos.z;
		// blended types (walls, light pools) go after translucent terrain: see ArmaSubmit
		ArmaSubmit.auto(out, poseStack, type, (pose, buf) -> emitQuads(pose, buf, d, count, ox, oy, oz, scale));
	}

	/** Armagetron (x, y, z) to camera-relative Minecraft (x, z, -y) * scale + offset. */
	public static void emitQuads(PoseStack.Pose pose, VertexConsumer buf, float[] d, int count, double ox, double oy, double oz, double s) {
		for (int i = 0; i < count; i++) {
			int o = i * Mesh.STRIDE;
			buf.addVertex(pose, (float) (ox + d[o] * s), (float) (oy + d[o + 2] * s), (float) (oz - d[o + 1] * s))
				.setColor(c(d[o + 5]), c(d[o + 6]), c(d[o + 7]), c(d[o + 8]))
				.setUv(d[o + 3], d[o + 4])
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(LightCoordsUtil.FULL_BRIGHT)
				.setNormal(pose, d[o + 9], d[o + 11], -d[o + 10]);
		}
	}

	/**
	 * Line width in framebuffer pixels for a configured width at 720 lines: grows with the window, so an edge-on wall's
	 * top edge keeps the same apparent thickness at 1440p as at 720p (never below the configured width).
	 */
	public static float screenLineWidth(double configured) {
		Minecraft mc = Minecraft.getInstance();
		double h = mc == null ? 720 : mc.getWindow().getHeight();
		return (float) (configured * Math.max(1, h / 720.0));
	}

	/** Lines end this far in front of the camera (blocks); Minecraft's near plane is 0.05. */
	private static final double LINE_NEAR = 0.1;

	/**
	 * Line pairs (2 vertices each). Minecraft widens lines in the vertex shader in screen space, which breaks for a
	 * segment whose end lies behind the camera: such a line (e.g. a long straight trail edge running under the chase
	 * camera) vanished. Each segment is clipped to the camera's near side first, like GL's own line clipping.
	 */
	public static void emitLines(PoseStack.Pose pose, VertexConsumer buf, float[] d, int count, double ox, double oy, double oz, double s, float width) {
		double fx = 0, fy = 0, fz = 0;
		boolean clip = false;
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.gameRenderer != null) {
			org.joml.Vector3fc f = mc.gameRenderer.mainCamera().forwardVector();
			fx = f.x();
			fy = f.y();
			fz = f.z();
			clip = true;
		}
		for (int i = 0; i + 1 < count; i += 2) {
			int a = i * Mesh.STRIDE;
			int b = a + Mesh.STRIDE;
			double ax = ox + d[a] * s, ay = oy + d[a + 2] * s, az = oz - d[a + 1] * s;
			double bx = ox + d[b] * s, by = oy + d[b + 2] * s, bz = oz - d[b + 1] * s;
			if (clip) {
				double da = ax * fx + ay * fy + az * fz - LINE_NEAR;
				double db = bx * fx + by * fy + bz * fz - LINE_NEAR;
				if (da < 0 && db < 0) {
					continue;
				}
				if (da < 0) {
					double t = da / (da - db);
					ax += (bx - ax) * t;
					ay += (by - ay) * t;
					az += (bz - az) * t;
				} else if (db < 0) {
					double t = db / (db - da);
					bx += (ax - bx) * t;
					by += (ay - by) * t;
					bz += (az - bz) * t;
				}
			}
			buf.addVertex(pose, (float) ax, (float) ay, (float) az)
				.setColor(c(d[a + 5]), c(d[a + 6]), c(d[a + 7]), c(d[a + 8]))
				.setNormal(pose, d[a + 9], d[a + 11], -d[a + 10])
				.setLineWidth(width);
			buf.addVertex(pose, (float) bx, (float) by, (float) bz)
				.setColor(c(d[b + 5]), c(d[b + 6]), c(d[b + 7]), c(d[b + 8]))
				.setNormal(pose, d[b + 9], d[b + 11], -d[b + 10])
				.setLineWidth(width);
		}
	}

	private static float c(float v) {
		return v < 0 ? 0 : (v > 1 ? 1 : v);
	}
}

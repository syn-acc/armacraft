package gl.syn.armacraft.client.render;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Render types for Armagetron geometry. All use Minecraft's entity shader with EMISSIVE + NO_OVERLAY +
 * NO_CARDINAL_LIGHTING (the "eyes" combination), so the vertex colour we compute is exactly what is shaded:
 * Armagetron's own fixed-function lighting for cycles, plain colours for walls, no Minecraft lightmap.
 */
public final class ArmaRenderTypes {

	private ArmaRenderTypes() {
	}

	private static Identifier loc(String path) {
		return Identifier.fromNamespaceAndPath("armacraft_client", path);
	}

	/**
	 * Alpha cutout of the opaque pipelines. Vanilla: fragments below it are discarded (the cycle and rim textures are
	 * opaque, so nothing is). Iris: its API picks the pack shader variant from this define; without it an opaque
	 * pipeline assigned to gbuffers_entities gets the "vertex alpha" variant (ShaderKey.ENTITIES_ALPHA), which discards
	 * every fragment whose alpha is not above the vertex alpha, i.e. the whole bike.
	 */
	private static final float OPAQUE_CUTOUT = 0.1F;

	/** Opaque, back-face culled (Armagetron enables GL_CULL_FACE for models). */
	public static final RenderPipeline MODEL = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.ENTITY_NO_LIGHTMAP_SNIPPET, RenderPipelines.EYES_SNIPPET)
			.withLocation(loc("pipeline/arma_model"))
			.withShaderDefine("ALPHA_CUTOUT", OPAQUE_CUTOUT)
			.withColorTargetState(ColorTargetState.DEFAULT)
			.withDepthStencilState(DepthStencilState.DEFAULT)
			.withCull(true)
			.build()
	);

	/** Opaque, double sided (rim walls: Armagetron disables culling for the grid pass). */
	public static final RenderPipeline SOLID_2S = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.ENTITY_NO_LIGHTMAP_SNIPPET, RenderPipelines.EYES_SNIPPET)
			.withLocation(loc("pipeline/arma_solid_2s"))
			.withShaderDefine("ALPHA_CUTOUT", OPAQUE_CUTOUT)
			.withColorTargetState(ColorTargetState.DEFAULT)
			.withDepthStencilState(DepthStencilState.DEFAULT)
			.withCull(false)
			.build()
	);

	/** Translucent, double sided, no depth write (player walls, glDisable(GL_CULL_FACE) in RenderAllWithDisplayList). */
	public static final RenderPipeline WALL = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.ENTITY_NO_LIGHTMAP_SNIPPET, RenderPipelines.EYES_SNIPPET)
			.withLocation(loc("pipeline/arma_wall"))
			.withShaderDefine("ALPHA_CUTOUT", 0.004F)
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
			.withCull(false)
			.build()
	);
	public static final OitPipelineSet OIT_WALL = RenderPipelines.register(
		OitPipelineSet.builder("armacraft_client_wall", RenderPipeline.builder(RenderPipelines.OIT_ENTITY_SNIPPET, RenderPipelines.EYES_SNIPPET).withCull(false)).build()
	);

	/** Additive (ONE, ONE), no depth write: the floor grid, like the additive second floor pass. */
	public static final RenderPipeline ADDITIVE = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.ENTITY_NO_LIGHTMAP_SNIPPET, RenderPipelines.EYES_SNIPPET)
			.withLocation(loc("pipeline/arma_additive"))
			.withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
			.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
			.withCull(false)
			.build()
	);
	public static final OitPipelineSet OIT_ADDITIVE = RenderPipelines.register(
		OitPipelineSet.builder("armacraft_client_additive",
			RenderPipeline.builder(RenderPipelines.OIT_ENTITY_SNIPPET, RenderPipelines.EYES_SNIPPET).withCull(false).withShaderDefine("OIT_ADDITIVE")).build()
	);

	private static final Map<Identifier, RenderType> MODEL_TYPES = new HashMap<>();
	private static RenderType rim, wall, floor, glow;

	/** Additive vertex-colour light (ride bike light pools). */
	public static synchronized RenderType glow() {
		if (glow == null) {
			glow = RenderType.create("armacraft_glow", RenderSetup.builder(ADDITIVE).setOitPipelines(OIT_ADDITIVE)
				.withTexture("Sampler0", ArmaTextures.WHITE).createRenderSetup());
		}
		return glow;
	}

	/** Touch the class during client init so pipelines are registered before shaders load. */
	public static void init() {
	}

	public static synchronized RenderType model(Identifier texture) {
		return MODEL_TYPES.computeIfAbsent(texture, t -> RenderType.create("armacraft_model",
			RenderSetup.builder(MODEL).withTexture("Sampler0", t).createRenderSetup()));
	}

	public static synchronized RenderType rim() {
		if (rim == null) {
			rim = RenderType.create("armacraft_rim", RenderSetup.builder(SOLID_2S).withTexture("Sampler0", ArmaTextures.RIM_WALL).createRenderSetup());
		}
		return rim;
	}

	public static synchronized RenderType wall() {
		if (wall == null) {
			wall = RenderType.create("armacraft_wall", RenderSetup.builder(WALL).setOitPipelines(OIT_WALL)
				.withTexture("Sampler0", ArmaTextures.DIR_WALL).sortOnUpload().createRenderSetup());
		}
		return wall;
	}

	public static synchronized RenderType floor() {
		if (floor == null) {
			floor = RenderType.create("armacraft_floor", RenderSetup.builder(ADDITIVE).setOitPipelines(OIT_ADDITIVE)
				.withTexture("Sampler0", ArmaTextures.FLOOR_GRID).createRenderSetup());
		}
		return floor;
	}

	public static RenderType lines() {
		return RenderTypes.linesTranslucent();
	}
}

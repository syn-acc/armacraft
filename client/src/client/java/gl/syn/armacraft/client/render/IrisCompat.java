package gl.syn.armacraft.client.render;

import gl.syn.armacraft.client.ClientConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.api.v0.IrisProgram;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Iris (shaderpack) support for the custom pipelines in {@link ArmaRenderTypes}.
 *
 * <p>Why: with a shaderpack active, Iris swaps the vertex format of every {@code ENTITY}-format pipeline to its
 * extended format (mc_Entity, mc_midTexCoord, at_tangent) and redirects each known pipeline to a pack program.
 * A pipeline it does not know ("Missing program armacraft_client:pipeline/... in override list") keeps the vanilla
 * shader and its original attribute layout, so it reads the extended buffer with the wrong stride: bike, trail
 * walls, light pool and arena floor turn into screen-filling garbage triangles, and they also miss the pack's TAA
 * jitter. Assigning the pipelines to a pack program through the Iris API fixes both.
 *
 * <p>Each pipeline gets its own program (armacraft_client.json, restart to change):
 * <ul>
 *   <li>{@code render.iris_model_program} (bike, rim walls; default {@code entities}): {@code gbuffers_entities}, the
 *       opaque pack-lit entity program. The vertices carry a full-bright lightmap, so the bike stays bright at night.
 *       It must not be {@code emissive}: Iris gives {@code gbuffers_spidereyes} an additive blend override
 *       (SRC_ALPHA, ONE; ProgramId.SpiderEyes), whatever the pipeline's own blend state says, so an "opaque" bike
 *       there is added onto the scene and comes out see-through and washed out.</li>
 *   <li>{@code render.iris_wall_program} (trail and arena walls; default {@code emissive}): {@code gbuffers_spidereyes},
 *       unlit, boosted and additive in OptiFine-lineage packs (Complementary, BSL, ...), so walls glow and feed the
 *       pack's bloom. {@code entities_translucent} gives alpha-blended, pack-lit walls instead (no glow).</li>
 *   <li>{@code render.iris_light_program} (light pool, floor grid; default {@code emissive}).</li>
 * </ul>
 * Values: {@code emissive}, {@code entities}, {@code entities_translucent}, {@code beacon} or any
 * {@code IrisProgram} name. {@code render.iris_program}: {@code off} leaves the pipelines unassigned (the broken state
 * above; for comparison only); {@code lit} forces the walls to {@code entities} as well (packs that draw nothing in
 * spidereyes, e.g. Alpha Piscium); any other value uses the per-pipeline keys.
 * Only loaded when Iris is present; Iris is a compile-only dependency.
 */
public final class IrisCompat {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft_client/iris");

	private IrisCompat() {
	}

	/** Call once at client init, after {@link ArmaRenderTypes#init()}. */
	public static void init(ClientConfig.Render cfg) {
		if (!FabricLoader.getInstance().isModLoaded("iris")) {
			return;
		}
		String m = cfg.irisProgram == null ? "" : cfg.irisProgram.toLowerCase(Locale.ROOT);
		if ("off".equals(m)) {
			LOG.info("iris_program=off: armacraft pipelines left unassigned");
			return;
		}
		try {
			Assign.run(cfg.irisModelProgram, "lit".equals(m) ? "entities" : cfg.irisWallProgram, cfg.irisLightProgram);
		} catch (Throwable t) {
			// older Iris without assignPipeline (API < 0.3)
			LOG.warn("could not assign armacraft pipelines to Iris programs: {}", t.toString());
		}
	}

	/** Config value to IrisProgram enum name: the short names above, or any enum name in any case. */
	static String programName(String value, String fallback) {
		String v = value == null || value.isBlank() ? fallback : value.trim().toLowerCase(Locale.ROOT);
		return switch (v) {
			case "emissive", "spidereyes" -> "EMISSIVE_ENTITIES";
			case "lit" -> "ENTITIES";
			case "beacon", "beaconbeam" -> "BEACON_BEAM";
			default -> v.toUpperCase(Locale.ROOT);
		};
	}

	/** Separate class so nothing Iris-typed is resolved unless Iris is loaded. */
	private static final class Assign {
		static void run(String model, String wall, String light) {
			IrisApi api = IrisApi.getInstance();
			IrisProgram solid = program(model, "entities");
			// an opaque pipeline on spidereyes is drawn additively (see class comment): never what "model" means
			if (solid == IrisProgram.EMISSIVE_ENTITIES) {
				LOG.warn("iris_model_program=emissive draws the bike additively (see-through); use entities or beacon");
			}
			IrisProgram additive = program(light, "emissive");
			assign(api, ArmaRenderTypes.MODEL, solid);
			assign(api, ArmaRenderTypes.SOLID_2S, solid);
			assign(api, ArmaRenderTypes.WALL, program(wall, "emissive"));
			assign(api, ArmaRenderTypes.ADDITIVE, additive);
			// OIT_WALL / OIT_ADDITIVE are not needed: Iris turns improved transparency off while a pack is active.
		}

		private static IrisProgram program(String value, String fallback) {
			try {
				return IrisProgram.valueOf(programName(value, fallback));
			} catch (IllegalArgumentException e) {
				LOG.warn("unknown Iris program '{}', using {}", value, fallback);
				return IrisProgram.valueOf(programName(fallback, fallback));
			}
		}

		private static void assign(IrisApi api, RenderPipeline pipeline, IrisProgram program) {
			try {
				api.assignPipeline(pipeline, program);
				LOG.info("Iris: {} -> {}", pipeline.getLocation(), program);
			} catch (IllegalStateException e) {
				// already assigned (by another mod or an earlier init): keep that one, carry on with the rest
				LOG.info("Iris: {} already assigned ({})", pipeline.getLocation(), e.getMessage());
			}
		}
	}
}

package gl.syn.armacraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * Submits Armagetron geometry in the right frame phase.
 *
 * <p>{@code SubmitNodeCollector.submitCustomGeometry} puts every blended render type into the "translucent custom
 * geometry" phase, which classic transparency draws <em>before</em> translucent terrain (LevelRenderer
 * .executeClassicTransparency: translucent features, translucent terrain, then the after-terrain phase). Walls and
 * light pools do not write depth, so water, ice and glass drawn afterwards blend over them: a trail over a lake (the
 * frost walker's frosted ice is a translucent block too) or behind a glass pane all but disappears. Classic
 * transparency is what runs whenever "improved transparency" is off, and always under an Iris shaderpack (Iris turns
 * improved transparency off).
 *
 * <p>{@link #blended} submits to the after-terrain phase instead (where vanilla draws translucent particles), so walls,
 * their top lines and light pools blend over water like over land. With improved transparency on, every translucent phase is the
 * same order-independent phase, so nothing changes there.
 */
public final class ArmaSubmit {

	/** {@code render.walls_after_water}: false restores the vanilla phase (for comparison). */
	public static volatile boolean afterTerrain = true;

	private ArmaSubmit() {
	}

	/** Opaque geometry, or anything that should keep vanilla's phase for its render type. */
	public static void plain(SubmitNodeCollector out, PoseStack poseStack, RenderType type, SubmitNodeCollector.CustomGeometryRenderer renderer) {
		out.submitCustomGeometry(poseStack, type, renderer);
	}

	/** Blended geometry (walls, light pools, floor grid, wall top lines): drawn after translucent terrain. */
	public static void blended(SubmitNodeCollector out, PoseStack poseStack, RenderType type, SubmitNodeCollector.CustomGeometryRenderer renderer) {
		if (afterTerrain && type.hasBlending() && !type.isOutline()) {
			OrderedSubmitNodeCollector ordered = out.order(0);
			if (ordered instanceof SubmitNodeCollection collection) {
				collection.afterTerrain.submit(new CustomFeatureRenderer.Submit(poseStack.last().copy(), type, renderer));
				return;
			}
		}
		out.submitCustomGeometry(poseStack, type, renderer);
	}

	/** {@link #blended} for blended render types, {@link #plain} for the rest. */
	public static void auto(SubmitNodeCollector out, PoseStack poseStack, RenderType type, SubmitNodeCollector.CustomGeometryRenderer renderer) {
		if (type.hasBlending()) {
			blended(out, poseStack, type, renderer);
		} else {
			plain(out, poseStack, type, renderer);
		}
	}
}

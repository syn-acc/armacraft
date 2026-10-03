package gl.syn.armacraft.client.ride.api;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.Vec3;

/** Draws one rider's light trail. Called from {@code LevelRenderEvents.COLLECT_SUBMITS} once per trail per frame. */
public interface TrailVisual {

	/**
	 * A trail for this frame.
	 *
	 * @param points     xyz triples of feet positions along the driven path, oldest first; the polyline follows the
	 *                   terrain height (steps, falls). Only the first {@code count} points are valid.
	 * @param count      number of points
	 * @param head       the rider's current (interpolated) position: the last piece runs from the last point to here;
	 *                   null when the rider has stopped
	 * @param height     wall height above each point (blocks)
	 * @param rgb        rider colour 0xRRGGBB
	 * @param alpha      overall visibility 1..0 (fades out after the rider stops)
	 * @param fadeLength the oldest this many blocks fade towards the tail (0 = none)
	 * @param length     total length of the polyline including the head piece (blocks)
	 */
	record Ribbon(double[] points, int count, Vec3 head, double height, int rgb, float alpha, double fadeLength, double length) {}

	void submit(SubmitNodeCollector out, PoseStack poseStack, Vec3 cam, Ribbon trail);
}

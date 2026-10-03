package gl.syn.armacraft.client.ride.api;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.Vec3;

/**
 * Draws one light cycle. Called from Fabric {@code LevelRenderEvents.COLLECT_SUBMITS} once per visible bike per
 * frame (the local rider and other riders). Only JDK and Minecraft types here, so another jar can implement it.
 */
public interface BikeVisual {

	/**
	 * One bike for this frame.
	 *
	 * @param pos          feet position (bottom centre of the rider's bounding box), already interpolated for the frame
	 * @param yawDeg       body yaw in Minecraft degrees (south 0, west 90, north 180, east -90); eases through turns
	 * @param headingYaw   the cardinal drive direction the yaw is easing towards
	 * @param wheelPhase   wheel roll angle in radians (accumulated distance / wheel radius), monotonic while driving
	 * @param lean         lean -1..1, positive leans right (into a right turn)
	 * @param speed        blocks/s
	 * @param rgb          rider colour 0xRRGGBB
	 * @param alpha        1 normally; fades towards 0 after a crash
	 * @param scale        model scale in blocks per Armagetron unit (ride config {@code blocks_per_unit})
	 * @param local        true for the player's own bike
	 */
	record Pose(Vec3 pos, float yawDeg, float headingYaw, double wheelPhase, double lean, double speed, int rgb, float alpha,
		double scale, boolean local) {}

	/**
	 * @param out        the frame's submit collector
	 * @param poseStack  camera-relative pose stack (identity at the camera); translate by {@code pos - cam}
	 * @param cam        camera position
	 */
	void submit(SubmitNodeCollector out, PoseStack poseStack, Vec3 cam, Pose bike);
}

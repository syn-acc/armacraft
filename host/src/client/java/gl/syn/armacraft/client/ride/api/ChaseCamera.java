package gl.syn.armacraft.client.ride.api;

import net.minecraft.world.phys.Vec3;

/**
 * The riding camera. While riding, Minecraft's camera takes its position, rotation and (optionally) field of view
 * from here every frame. Mouse look stays free: the player's own yaw/pitch arrive as {@link Target#lookYaw} /
 * {@link Target#lookPitch} and an implementation may orbit with them or ignore them.
 */
public interface ChaseCamera {

	/**
	 * What to follow this frame.
	 *
	 * @param pos            bike feet position, interpolated for the frame
	 * @param headingYaw     cardinal drive direction (Minecraft degrees)
	 * @param lastHeadingYaw drive direction before the last turn
	 * @param sinceTurn      seconds since the last turn
	 * @param speed          blocks/s
	 * @param lookYaw        the player's free mouse-look yaw (Minecraft degrees)
	 * @param lookPitch      the player's free mouse-look pitch (degrees, positive looks down)
	 * @param scale          blocks per Armagetron unit (ride config {@code blocks_per_unit})
	 * @param rideTime       seconds since the ride started
	 * @param glanceLeft     Armagetron GLANCE_LEFT held (default keys A, Q)
	 * @param glanceRight    Armagetron GLANCE_RIGHT held (default keys P, ;)
	 * @param glanceBack     Armagetron GLANCE_BACK held (default key V): look backwards (eCamera.cpp:1647-1648 flips the
	 *                       glance direction)
	 */
	record Target(Vec3 pos, float headingYaw, float lastHeadingYaw, double sinceTurn, double speed, float lookYaw, float lookPitch,
		double scale, double rideTime, boolean glanceLeft, boolean glanceRight, boolean glanceBack) {}

	/** Snap behind the bike (deploy). */
	void reset(Target target);

	/**
	 * Per rendered frame. {@code dt} is seconds of game time since the previous frame (tick count plus partial tick,
	 * the same clock the interpolated positions use), so smoothing and interpolation never disagree.
	 */
	void update(double dt, Target target);

	/** The bike turned: +1 right, -1 left. */
	default void onTurn(int dir) {}

	Vec3 eye();

	float yaw();

	float pitch();

	/** Field of view in degrees, or NaN to keep Minecraft's. */
	default float fov() {
		return Float.NaN;
	}
}

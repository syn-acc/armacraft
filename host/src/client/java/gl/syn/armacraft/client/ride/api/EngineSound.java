package gl.syn.armacraft.client.ride.api;

import net.minecraft.world.phys.Vec3;

/** The local rider's engine. {@link #start} on deploy, {@link #update} every client tick, {@link #stop} on dismount/crash. */
public interface EngineSound {

	/**
	 * @param pos      bike position
	 * @param speed    blocks/s
	 * @param maxSpeed the configured top speed (blocks/s), for normalising pitch
	 * @param braking  brake held and reservoir not empty
	 * @param grinding rubber is being spent against a wall this tick
	 */
	record State(Vec3 pos, double speed, double maxSpeed, boolean braking, boolean grinding) {}

	void start(State state);

	void update(State state);

	void stop();
}

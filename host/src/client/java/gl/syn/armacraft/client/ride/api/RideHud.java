package gl.syn.armacraft.client.ride.api;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Riding HUD. {@link #update} runs every client tick while riding; {@link #extract} runs every frame from a Fabric
 * HUD element while riding (draw nothing there if the implementation uses the action bar instead).
 */
public interface RideHud {

	/**
	 * @param speed      blocks/s
	 * @param baseSpeed  CYCLE_SPEED in blocks/s
	 * @param rubberLeft 1 full .. 0 (crash on the next wall contact)
	 * @param brake      brake reservoir 1 full .. 0
	 * @param braking    brake held
	 * @param heading    cardinal 0 north, 1 east, 2 south, 3 west
	 * @param boost      boost reservoir 1 full .. 0 (NOT Armagetron: armacraft's accelerate key)
	 * @param boosting   boost held and reservoir not empty
	 * @param wallIn     seconds until the wall ahead at the current speed (+inf if none in sensor range)
	 * @param warning    wallIn is below the configured warning time (NOT Armagetron: armacraft's look-ahead warning)
	 */
	record State(double speed, double baseSpeed, double rubberLeft, double brake, boolean braking, int heading, double boost,
		boolean boosting, double wallIn, boolean warning) {}

	void update(State state);

	default void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) {}

	/** Ride ended: remove anything still shown. */
	void clear();

	/**
	 * Cycle mode after a crash, every client tick until the redeploy: what killed the rider and the seconds until the
	 * automatic redeploy (NaN if that is off; a turn key redeploys too).
	 */
	default void dead(String cause, double redeployIn) {}
}

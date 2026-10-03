package gl.syn.armacraft.client.kit;

import gl.syn.armacraft.client.core.V2;
import net.minecraft.world.phys.Vec3;

/**
 * Armagetron's per-object sound level (eCamera::SoundMixGameObject): per channel
 * {@code (dist*.5 +- side)/dist^2}, clamped to MAXVOL .4, {@code dist^2 = horizontal^2 + dz^2 >= 1} in Armagetron
 * units; the watched object is fixed at .2 in the in-cycle camera and scaled by .9 otherwise. Returns the mono
 * level (mean of both channels, 0..0.4). No bridge types.
 */
public final class ArmaSoundMix {

	public static final double MAXVOL = .4;

	private ArmaSoundMix() {
	}

	/**
	 * @param listener     camera position (Minecraft)
	 * @param listenerYaw  camera yaw (Minecraft degrees)
	 * @param source       sound source position (Minecraft)
	 * @param blocksPerUnit Minecraft blocks per Armagetron unit
	 * @param watched      the source is what the camera follows
	 * @param inCycle      in-cycle camera active
	 */
	public static double level(Vec3 listener, float listenerYaw, Vec3 source, double blocksPerUnit, boolean watched, boolean inCycle) {
		double s = blocksPerUnit > 0 ? blocksPerUnit : 1;
		double[] eye = {listener.x / s, -listener.z / s, listener.y / s};
		V2 look = BikeAnimator.headingFromYaw(listenerYaw);
		return level(eye, look, source.x / s, -source.z / s, source.y / s, watched, inCycle);
	}

	/** Same in Armagetron coordinates: eye {x, y, z}, camera heading {@code look} (unit). */
	public static double level(double[] eye, V2 look, double x, double y, double z, boolean watched, boolean inCycle) {
		V2 vec = new V2(x - eye[0], y - eye[1]).turn(look.conj());
		double distSq = vec.normSq() + (eye[2] - z) * (eye[2] - z);
		if (distSq < 1) {
			distSq = 1;
		}
		double dist = Math.sqrt(distSq);
		double l = (dist * .5 + vec.y()) / distSq;
		double r = (dist * .5 - vec.y()) / distSq;
		l = Math.max(0, Math.min(MAXVOL, l));
		r = Math.max(0, Math.min(MAXVOL, r));
		if (watched) {
			if (inCycle) {
				l = r = .2;
			} else {
				l *= .9;
				r *= .9;
			}
		}
		return (l + r) / 2;
	}
}

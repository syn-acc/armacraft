package gl.syn.armacraft.client.gametest;

import gl.syn.armacraft.client.ridekit.ArmaRideVisuals;
import gl.syn.armacraft.client.ride.RideClientMod;
import gl.syn.armacraft.client.ride.RideKeys;
import net.minecraft.client.KeyMapping;

/** Test-only access to the host's ride client (compiled against the host jar; the shipped client uses only the API). */
final class RideClientHooks {

	private RideClientHooks() {}

	static boolean riding() {
		return RideClientMod.client() != null && RideClientMod.client().riding();
	}

	static String lastCrash() {
		return String.valueOf(RideClientMod.client().lastCrashCause());
	}

	static KeyMapping rideKey() {
		return RideKeys.RIDE;
	}

	static boolean usesArmaCamera() {
		return RideClientMod.visuals().camera() == ArmaRideVisuals.CAMERA;
	}

	static boolean usesArmaBike() {
		return RideClientMod.visuals().bike().getClass().getName().startsWith("gl.syn.armacraft.client.ridekit.ArmaRideVisuals");
	}

	static boolean usesArmaHud() {
		return RideClientMod.visuals().hud() == ArmaRideVisuals.HUD;
	}

	static boolean usesArmaTrailAndEngine() {
		return RideClientMod.visuals().trail() == ArmaRideVisuals.TRAIL && RideClientMod.visuals().engine() == ArmaRideVisuals.ENGINE;
	}

	static String describe() {
		var v = RideClientMod.visuals();
		return "bike " + v.bike().getClass().getName() + ", camera " + v.camera().getClass().getName() + ", hud " + v.hud().getClass().getName()
			+ ", trail " + v.trail().getClass().getName() + ", engine " + v.engine().getClass().getName();
	}
}

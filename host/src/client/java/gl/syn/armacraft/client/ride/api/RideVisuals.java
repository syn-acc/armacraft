package gl.syn.armacraft.client.ride.api;

import org.jspecify.annotations.Nullable;

/**
 * Plug-in point for ride mode's look and sound. Another mod provides an implementation through the Fabric
 * entrypoint {@value #ENTRYPOINT} in its fabric.mod.json:
 *
 * <pre>{@code "entrypoints": { "armacraft:ride_visuals": ["com.example.MyRideVisuals"] }}</pre>
 *
 * At client init armacraft takes, piece by piece, the first non-null implementation over all such entrypoints,
 * and falls back to its own placeholders for anything left null. Getters are called once, after all mods have
 * initialised.
 */
public interface RideVisuals {

	String ENTRYPOINT = "armacraft:ride_visuals";

	default @Nullable BikeVisual bike() {
		return null;
	}

	default @Nullable TrailVisual trail() {
		return null;
	}

	default @Nullable ChaseCamera camera() {
		return null;
	}

	default @Nullable EngineSound engine() {
		return null;
	}

	default @Nullable RideHud hud() {
		return null;
	}
}

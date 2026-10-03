package gl.syn.armacraft.client.ride;

import gl.syn.armacraft.client.ride.api.BikeVisual;
import gl.syn.armacraft.client.ride.api.ChaseCamera;
import gl.syn.armacraft.client.ride.api.EngineSound;
import gl.syn.armacraft.client.ride.api.RideHud;
import gl.syn.armacraft.client.ride.api.RideVisuals;
import gl.syn.armacraft.client.ride.api.TrailVisual;
import gl.syn.armacraft.client.ride.render.ActionBarHud;
import gl.syn.armacraft.client.ride.render.PlaceholderBike;
import gl.syn.armacraft.client.ride.render.PlaceholderChaseCamera;
import gl.syn.armacraft.client.ride.render.PlaceholderEngine;
import gl.syn.armacraft.client.ride.render.PlaceholderTrail;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The visuals in use: for each piece the first non-null implementation over all {@code armacraft:ride_visuals}
 * entrypoints, else armacraft's placeholder.
 */
public final class RideVisualSet {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft/ride-visuals");

	private final BikeVisual bike;
	private final TrailVisual trail;
	private final ChaseCamera camera;
	private final EngineSound engine;
	private final RideHud hud;

	private RideVisualSet(BikeVisual bike, TrailVisual trail, ChaseCamera camera, EngineSound engine, RideHud hud) {
		this.bike = bike;
		this.trail = trail;
		this.camera = camera;
		this.engine = engine;
		this.hud = hud;
	}

	public static RideVisualSet placeholders() {
		return new RideVisualSet(new PlaceholderBike(), new PlaceholderTrail(), new PlaceholderChaseCamera(), new PlaceholderEngine(),
			new ActionBarHud());
	}

	public static RideVisualSet discover() {
		BikeVisual bike = null;
		TrailVisual trail = null;
		ChaseCamera camera = null;
		EngineSound engine = null;
		RideHud hud = null;
		List<EntrypointContainer<RideVisuals>> found;
		try {
			found = FabricLoader.getInstance().getEntrypointContainers(RideVisuals.ENTRYPOINT, RideVisuals.class);
		} catch (RuntimeException e) {
			LOG.error("could not load {} entrypoints, using placeholders", RideVisuals.ENTRYPOINT, e);
			found = List.of();
		}
		for (EntrypointContainer<RideVisuals> c : found) {
			try {
				RideVisuals v = c.getEntrypoint();
				String from = c.getProvider().getMetadata().getId();
				if (bike == null && v.bike() != null) {
					bike = v.bike();
					LOG.info("ride bike visual from {}", from);
				}
				if (trail == null && v.trail() != null) {
					trail = v.trail();
					LOG.info("ride trail visual from {}", from);
				}
				if (camera == null && v.camera() != null) {
					camera = v.camera();
					LOG.info("ride chase camera from {}", from);
				}
				if (engine == null && v.engine() != null) {
					engine = v.engine();
					LOG.info("ride engine sound from {}", from);
				}
				if (hud == null && v.hud() != null) {
					hud = v.hud();
					LOG.info("ride HUD from {}", from);
				}
			} catch (RuntimeException | LinkageError e) {
				LOG.error("{} entrypoint from {} failed, ignoring it", RideVisuals.ENTRYPOINT, c.getProvider().getMetadata().getId(), e);
			}
		}
		RideVisualSet p = placeholders();
		return new RideVisualSet(bike != null ? bike : p.bike, trail != null ? trail : p.trail, camera != null ? camera : p.camera,
			engine != null ? engine : p.engine, hud != null ? hud : p.hud);
	}

	public BikeVisual bike() {
		return bike;
	}

	public TrailVisual trail() {
		return trail;
	}

	public ChaseCamera camera() {
		return camera;
	}

	public EngineSound engine() {
		return engine;
	}

	public RideHud hud() {
		return hud;
	}
}

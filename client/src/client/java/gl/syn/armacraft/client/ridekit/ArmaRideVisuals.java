package gl.syn.armacraft.client.ridekit;

import gl.syn.armacraft.client.ArenaClient;
import gl.syn.armacraft.client.ClientConfig;
import gl.syn.armacraft.client.ride.api.BikeVisual;
import gl.syn.armacraft.client.ride.api.ChaseCamera;
import gl.syn.armacraft.client.ride.api.EngineSound;
import gl.syn.armacraft.client.ride.api.RideHud;
import gl.syn.armacraft.client.ride.api.RideVisuals;
import gl.syn.armacraft.client.ride.api.TrailVisual;
import net.minecraft.client.renderer.SubmitNodeCollector;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Entrypoint {@code armacraft:ride_visuals}: Armagetron's look for armacraft's ride mode, backed by this mod's kit
 * (BikeRenderer/BikeAnimator, ChaseCam/ArmaCam, ArmaGauges). Each piece can be switched back to armacraft's
 * placeholder in {@code config/armacraft_client.json} ({@code ride.bike}, {@code ride.camera}, ...; restart).
 */
public final class ArmaRideVisuals implements RideVisuals {

	private static final ClientConfig.Ride DEFAULTS = new ClientConfig.Ride();
	/**
	 * Physics blocks per Armagetron unit of the current ride (ChaseCamera.Target.scale): speeds in Armagetron units
	 * for the HUD and engine pitch, camera distances. The bike and trail are drawn at their own visual scale
	 * (BikeVisual.Pose.scale, TrailVisual.Ribbon.height = 1 unit), which the host may set larger.
	 */
	private static volatile double blocksPerUnit = 0.6;

	/** Blocks per Armagetron unit the local bike is drawn at (BikeVisual.Pose.scale). */
	private static volatile double visualScale = 1.0;

	public static double visualScale() {
		return visualScale;
	}

	static void visualScale(double s) {
		if (s > 0 && Double.isFinite(s)) {
			visualScale = s;
		}
	}

	public static double physicsScale() {
		return blocksPerUnit;
	}

	static void physicsScale(double s) {
		if (s > 0 && Double.isFinite(s)) {
			blocksPerUnit = s;
		}
	}

	public static final ArmaChaseCamera CAMERA = new ArmaChaseCamera(ArmaRideVisuals::cfg);
	public static final ArmaBikeVisual BIKE_IMPL = new ArmaBikeVisual();
	public static final ArmaRideHud HUD = new ArmaRideHud(() -> blocksPerUnit);
	public static final ArmaEngineSound ENGINE = new ArmaEngineSound(() -> blocksPerUnit);
	public static final ArmaTrailVisual TRAIL = new ArmaTrailVisual(() -> blocksPerUnit);

	private static final BikeVisual BIKE = BIKE_IMPL;

	static ClientConfig.Ride cfg() {
		ArenaClient c = ArenaClient.get();
		return c != null && c.config().ride != null ? c.config().ride : DEFAULTS;
	}

	@Override
	public @Nullable BikeVisual bike() {
		return cfg().bike ? BIKE : null;
	}

	@Override
	public @Nullable ChaseCamera camera() {
		return cfg().camera ? CAMERA : null;
	}

	@Override
	public @Nullable RideHud hud() {
		return cfg().hud ? HUD : null;
	}

	@Override
	public @Nullable EngineSound engine() {
		return cfg().engine ? ENGINE : null;
	}

	@Override
	public @Nullable TrailVisual trail() {
		return cfg().trail ? TRAIL : null;
	}
}

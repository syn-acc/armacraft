package gl.syn.armacraft.client.ride;

import com.mojang.blaze3d.vertex.PoseStack;
import gl.syn.armacraft.client.ride.api.BikeVisual;
import gl.syn.armacraft.client.ride.api.TrailVisual;
import gl.syn.armacraft.ride.RideConfig;
import gl.syn.armacraft.ride.RideHooks;
import gl.syn.armacraft.ride.core.Heading;
import gl.syn.armacraft.ride.core.Trail;
import gl.syn.armacraft.ride.net.RidePayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint of ride mode: keys, payloads, the local rider, other riders, and the (pluggable) visuals.
 * Present only in a client environment; a dedicated server never loads this class.
 */
public final class RideClientMod implements ClientModInitializer {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft/ride-client");

	private static RideVisualSet visuals = RideVisualSet.placeholders();
	private static RideClient client;
	private static final RemoteRiders REMOTE = new RemoteRiders();
	/** Diagnostics for tests: bikes and trails submitted for rendering so far. */
	public static long bikeSubmits;
	public static long trailSubmits;
	/** Vanilla view bobbing off while riding (the measured shake source); tests flip it to measure the difference. */
	public static volatile boolean suppressViewBob = true;
	/** Diagnostics: Friends List toggles refused in cycle mode. */
	public static int friendsBlocked;
	/** Diagnostics: vanilla HUD element draws skipped in cycle mode. */
	public static long hudHidden;
	/** Diagnostics: first-person hand renders skipped in cycle mode. */
	public static long handHidden;
	/** Diagnostics: block hit outlines suppressed in cycle mode. */
	public static long outlineHidden;
	private static final gl.syn.armacraft.client.ride.render.DeathCam DEATH_CAM = new gl.syn.armacraft.client.ride.render.DeathCam();

	public static gl.syn.armacraft.client.ride.render.DeathCam deathCam() {
		return DEATH_CAM;
	}

	public static RideClient client() {
		return client;
	}

	public static RemoteRiders remote() {
		return REMOTE;
	}

	public static RideVisualSet visuals() {
		return visuals;
	}

	@Override
	public void onInitializeClient() {
		RidePayloads.register();
		RideKeys.init();
		client = new RideClient(() -> visuals);
		client.setLocalConfig(RideConfig.load(FabricLoader.getInstance().getConfigDir().resolve("armacraft-ride.json")));
		RideHooks.installClientTravel(client::travel);

		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> visuals = RideVisualSet.discover());

		ClientPlayNetworking.registerGlobalReceiver(RidePayloads.Start.TYPE, (p, ctx) -> {
			RideConfig cfg = RideConfig.fromJson(p.configJson());
			REMOTE.settings(cfg.trailHeight, cfg.wallsStayUp());
			client.onStart(ctx.client(), p);
		});
		ClientPlayNetworking.registerGlobalReceiver(RidePayloads.Stop.TYPE, (p, ctx) -> {
			client.onStop(ctx.client(), p);
			if (!p.message().isEmpty() && p.reason() == RidePayloads.Stop.SERVER && ctx.player() != null) {
				LOG.info("ride stopped by server: {}", p.message());
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(RidePayloads.Sync.TYPE, (p, ctx) -> REMOTE.apply(p));
		ClientPlayNetworking.registerGlobalReceiver(RidePayloads.Cycle.TYPE, (p, ctx) -> client.onCycle(ctx.client(), p));
		ClientPlayNetworking.registerGlobalReceiver(RidePayloads.Settings.TYPE, (p, ctx) -> {
			RideConfig cfg = RideConfig.fromJson(p.configJson());
			REMOTE.settings(cfg.trailHeight, cfg.wallsStayUp());
			// /arma reload, /arma preset: this client's own file too (keys, rider colour and name, redeploy timing)
			client.reloadLocalConfig(FabricLoader.getInstance().getConfigDir().resolve("armacraft-ride.json"));
			client.onSettings(ctx.client(), p);
		});
		ClientPlayNetworking.registerGlobalReceiver(RidePayloads.WhatsNew.TYPE, (p, ctx) -> BuildStamp.print(ctx.client(), null));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> BuildStamp.onJoin());

		ClientTickEvents.START_CLIENT_TICK.register(mc -> client.handleInput(mc));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			REMOTE.tick();
			BuildStamp.tick(mc);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(() -> {
			client.onDisconnect();
			REMOTE.clear();
		}));

		LevelRenderEvents.COLLECT_SUBMITS.register(RideClientMod::collect);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("armacraft", "ride_hud"), (graphics, delta) -> {
			if (client.cycleMode()) {
				long t0 = RideFrameCost.start();
				visuals.hud().extract(graphics, delta);
				RideFrameCost.add(t0, RideFrameCost.HUD);
			}
		});
		// cycle mode swaps the HUD: no hotbar, crosshair, bars, xp or item names (chat, titles, action bar stay)
		for (Identifier id : new Identifier[] {VanillaHudElements.HOTBAR, VanillaHudElements.CROSSHAIR, VanillaHudElements.ARMOR_BAR,
			VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR, VanillaHudElements.MOUNT_HEALTH,
			VanillaHudElements.INFO_BAR, VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.HELD_ITEM_TOOLTIP,
			VanillaHudElements.SPECTATOR_TOOLTIP}) {
			HudElementRegistry.replaceElement(id, vanilla -> (graphics, delta) -> {
				if (client.cycleMode()) {
					hudHidden++;
				} else {
					vanilla.extractRenderState(graphics, delta);
				}
			});
		}
		LOG.info("armacraft ride client ready (R: cycle mode)");
	}

	// ---------------------------------------------------------------- rendering

	private static void collect(LevelRenderContext ctx) {
		long t0 = RideFrameCost.start();
		collectSubmits(ctx);
		RideFrameCost.add(t0, RideFrameCost.SUBMITS);
	}

	private static void collectSubmits(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) {
			return;
		}
		PoseStack poseStack = ctx.poseStack() != null ? ctx.poseStack() : new PoseStack();
		Vec3 cam = ctx.levelState().cameraRenderState.pos;
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		RideConfig cfg = client.config();
		// bike/trail model size (visual only); physics distances use blocksPerUnit
		double scale = cfg == null ? 1.0 : cfg.visualBlocksPerUnit;
		double height = cfg == null ? 1.0 : cfg.trailHeight;
		double maxLen = cfg == null ? 180 : cfg.trailLengthBlocks();
		double now = REMOTE.now();
		double stayUp = REMOTE.stayUp();

		// own trail (after a crash it stays up WALLS_STAY_UP_DELAY, or until the next deploy)
		RideClient.FrameState f = client.riding() ? client.frame(p, partial) : null;
		Trail own = client.trail();
		if (own.size() > 0) {
			Vec3 head = f != null ? f.pos() : null;
			float a = client.riding() ? 1f : (float) own.alpha(now, stayUp);
			if (a > 0) {
				ribbon(ctx, poseStack, cam, own, f == null ? java.util.List.of() : f.corners(), head, height, client.rgb(), a, maxLen);
			}
		}
		if (f != null && client.movement() != null) {
			int h = client.movement().heading();
			bikeSubmits++;
			visuals.bike().submit(ctx.submitNodeCollector(), poseStack, cam, new BikeVisual.Pose(f.pos(), f.bodyYaw(), Heading.yaw(h),
				f.wheelPhase(), f.lean(), client.movement().speed(), client.rgb(), 1f, scale, true));
		}

		// other riders
		for (RemoteRiders.Remote r : REMOTE.all()) {
			float a = (float) REMOTE.alpha(r);
			Entity e = r.entity();
			Vec3 pos = e != null ? lerp(e, partial) : new Vec3(r.x, r.y, r.z);
			if (a > 0 && r.trail.size() > 0) {
				ribbon(ctx, poseStack, cam, r.trail, r.riding ? pos : null, height, r.rgb, a, maxLen);
			}
			if (r.riding) {
				visuals.bike().submit(ctx.submitNodeCollector(), poseStack, cam, new BikeVisual.Pose(pos, Heading.yaw(r.heading),
					Heading.yaw(r.heading), r.wheelPhase, 0, r.speed, r.rgb, 1f, scale, false));
			}
		}
	}

	private static void ribbon(LevelRenderContext ctx, PoseStack poseStack, Vec3 cam, Trail t, Vec3 head, double height, int rgb, float alpha,
		double maxLen) {
		ribbon(ctx, poseStack, cam, t, java.util.List.of(), head, height, rgb, alpha, maxLen);
	}

	/** {@code extra}: corners of turns drawn ahead of the physics (pressed this tick), between the trail and the head. */
	private static void ribbon(LevelRenderContext ctx, PoseStack poseStack, Vec3 cam, Trail t, java.util.List<Vec3> extra, Vec3 head, double height,
		int rgb, float alpha, double maxLen) {
		double[] pts = t.pointsFrom(t.firstIndex());
		int count = t.size();
		double len = t.length();
		if (!extra.isEmpty()) {
			double[] more = java.util.Arrays.copyOf(pts, pts.length + extra.size() * 3);
			for (Vec3 c : extra) {
				if (count > 0) {
					len += Math.sqrt(Mth.square(c.x - more[3 * count - 3]) + Mth.square(c.y - more[3 * count - 2]) + Mth.square(c.z - more[3 * count - 1]));
				}
				more[3 * count] = c.x;
				more[3 * count + 1] = c.y;
				more[3 * count + 2] = c.z;
				count++;
			}
			pts = more;
		}
		if (head != null && count > 0) {
			len += Math.sqrt(Mth.square(head.x - pts[3 * count - 3]) + Mth.square(head.y - pts[3 * count - 2]) + Mth.square(head.z - pts[3 * count - 1]));
		}
		double fade = Double.isFinite(maxLen) ? Math.max(0, len - maxLen * 0.85) : 0;
		trailSubmits++;
		visuals.trail().submit(ctx.submitNodeCollector(), poseStack, cam,
			new TrailVisual.Ribbon(pts, count, head, height, rgb, alpha, fade, len));
	}

	private static Vec3 lerp(Entity e, float partial) {
		return new Vec3(Mth.lerp(partial, e.xo, e.getX()), Mth.lerp(partial, e.yo, e.getY()), Mth.lerp(partial, e.zo, e.getZ()));
	}

	/** Own trail fades on the shared client clock once the ride ends. */
	static double now() {
		return REMOTE.now();
	}
}

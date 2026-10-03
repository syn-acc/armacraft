package gl.syn.armacraft.client.ride;

import gl.syn.armacraft.client.ride.api.ChaseCamera;
import gl.syn.armacraft.client.ride.api.EngineSound;
import gl.syn.armacraft.client.ride.api.RideHud;
import gl.syn.armacraft.ride.RideConfig;
import gl.syn.armacraft.ride.RideHooks;
import gl.syn.armacraft.ride.RideService;
import gl.syn.armacraft.ride.core.CycleMovement;
import gl.syn.armacraft.ride.core.CycleParams;
import gl.syn.armacraft.ride.core.Heading;
import gl.syn.armacraft.ride.core.Surroundings;
import gl.syn.armacraft.ride.core.Terrain;
import gl.syn.armacraft.ride.core.TerrainProbe;
import gl.syn.armacraft.ride.core.Trail;
import gl.syn.armacraft.ride.net.RidePayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * The local rider: the player is the bike. While riding, {@code Player.travel} is replaced (PlayerTravelMixin ->
 * {@link #travel}): every tick the sensors measure terrain and trails, {@link CycleMovement} (Armagetron's rules)
 * decides how far to go, and {@code Entity.move} moves the player with step height 1.0, so vanilla collision has the
 * last word. Rendering reads {@link #frame}: one smoothed pose per frame on the game-time clock. Client thread only.
 */
public final class RideClient {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft/ride-client");
	private static final double DT = 0.05;
	private static final double GRAVITY = 0.08;
	private static final double DRAG = 0.98;
	/** Render-only easing of 1-block step pops (up and down): 95% after 0.15 s. Physics stays exact. */
	private static final double STEP_TAU = 0.05;
	/** Most the rendered bike may lag its physics height on a long run of steps (blocks). */
	private static final double STEP_LAG_MAX = 1.5;
	/** Body yaw swing through a 90 degree turn (critically damped, settles in ~0.2 s), and lean decay. */
	private static final double YAW_OMEGA = 28.0;
	private static final double LEAN_TAU = 0.15;
	private static final double WHEEL_RADIUS = 0.38;

	/** Live diagnostics, read by tests. */
	public static final class Stats {
		public int ticks;
		public int crashes;
		public int stops;
		public int turns;
		public double minRubberLeft = 1;
		public double maxSpeed;
		public int blockedTicks;
		public int heldTicks;
		public String lastCause = "";
		public String lastCrashDetail = "";
		public Terrain.Type lastAhead = Terrain.Type.FLAT;
		public double lastSpace = Double.POSITIVE_INFINITY;
		public boolean lastGrinding;
		/** Largest sideways movement of one physics piece (0: the bike only ever moves along its heading). */
		public double maxSideways;
	}

	/** Everything the renderers need for one frame. */
	/**
	 * Everything the renderers need for one frame: {@code heading} is the drawn heading (turns pressed but not yet
	 * applied by the physics included) and {@code corners} the corners of those turns, for the trail.
	 */
	public record FrameState(double t, double dt, Vec3 pos, float bodyYaw, double lean, double wheelPhase, int heading, java.util.List<Vec3> corners) {}

	private final Supplier<RideVisualSet> visualsRef;
	private final RideInput input = new RideInput();
	public final Stats stats = new Stats();
	private RideConfig localConfig = new RideConfig();

	private boolean riding;
	private boolean awaitingStart;
	private @Nullable RideConfig config;
	private @Nullable CycleParams params;
	private @Nullable CycleMovement move;
	private Terrain terrain = Terrain.standard();
	private Trail trail = new Trail();
	private int rgb = 0x33CCFF;
	private long sentFrom;
	private double vy;
	private boolean wasOnGround = true;
	private boolean blocked;
	private boolean grinding;
	private Surroundings.Kind frontKind = Surroundings.Kind.NONE;
	private boolean glanceLeft;
	private boolean glanceRight;
	private boolean glanceBack;
	private double wallIn = Double.POSITIVE_INFINITY;
	private boolean warning;
	private double rideTime;

	// frame clock and smoothed pose
	private long clientTicks;
	private double frameT = -1;
	private @Nullable FrameState frame;
	/**
	 * Render-only step easing. The physics y is split into a continuous part (driving, falling) and the cumulative
	 * step part (1-block step-ups and snap-downs, ground to ground). Rendering uses the continuous part exactly and
	 * eases the step part, interpolated over its tick, with {@link #STEP_TAU}; falls are never delayed.
	 */
	private double stepSumO;
	private double stepSum;
	private double stepEased;
	private boolean stepInit;
	// frame-time drawing: the last tick's real speed, the space it left ahead, and turns already drawn
	private double vLast;
	private double predSpace = Double.POSITIVE_INFINITY;
	private int predHeading = -1;
	private int visualTurns;
	private final java.util.ArrayDeque<Integer> visualDirs = new java.util.ArrayDeque<>();
	private @Nullable Vec3 lastFramePos;
	private long lastFrameNanos;
	private float lastFramePartial;
	private int lastFrameHeading;
	private final TurnTimingProbe probe = new TurnTimingProbe();
	private double turnT = -100;
	private float turnFrom;
	private float turnTo;
	private int turnDir;
	private double distO;
	private double dist;
	private @Nullable String crashCause;
	/** The level the trails belong to: a world or dimension change drops them (they are walls only there). */
	private net.minecraft.client.multiplayer.@Nullable ClientLevel trailLevel;

	// cycle mode: the light-cycle interface owns keyboard, HUD and camera; it survives crashes and redeploys
	private boolean cycleMode;
	private double deadSince = -1;
	private @Nullable Vec3 crashPos;
	private boolean screenWasOpen;
	private gl.syn.armacraft.ride.@Nullable ArmagetronProfile profile;
	private RideConfig.Keys keys = RideConfig.builtinKeys();
	/** Diagnostics: redeploys after crashes. */
	public int redeploys;

	public RideClient(Supplier<RideVisualSet> visuals) {
		this.visualsRef = visuals;
		input.setClock(this::pressClock);
		input.setPressListener(tp -> {
			if (riding && move != null) {
				double phase = lastFramePartial + (tp.nanos() - lastFrameNanos) / (DT * 1e9);
				probe.onPress(tp.nanos(), lastFramePos, lastFrameNanos, lastFrameHeading, vLast, phase - Math.floor(phase));
			}
		});
	}

	private RideVisualSet visuals() {
		return visualsRef.get();
	}

	/** The client's own armacraft-ride.json (keys; physics comes from the server). */
	public void setLocalConfig(RideConfig cfg) {
		this.localConfig = cfg;
		this.profile = cfg.importProfile();
		this.keys = cfg.effectiveKeys(profile);
		input.configure(keys, cfg.doublebindTime());
		LOG.info("cycle mode keys: {}; rider {} {}", input.describe(), cfg.riderNameOr(profile).isEmpty() ? "(player name)" : cfg.riderNameOr(profile),
			cfg.riderRgb(profile) < 0 ? "(colour from UUID)" : String.format("#%06X", cfg.riderRgb(profile)));
	}

	public RideConfig localConfig() {
		return localConfig;
	}

	/**
	 * /arma reload: re-read this client's armacraft-ride.json (keys, Armagetron profile import, rider colour and name,
	 * exact turn timing, redeploy timing) without a restart. A file that does not parse keeps the running binds.
	 *
	 * @return true when the file was read and applied
	 */
	public boolean reloadLocalConfig(java.nio.file.Path file) {
		var l = RideConfig.read(file);
		if (l.config() == null) {
			LOG.warn("{} not reloaded, running keys kept{}", file.getFileName(), l.error() == null ? " (no such file)" : ": " + l.error());
			return false;
		}
		setLocalConfig(l.config());
		input.resetHeld(); // a key that stopped being a bind would never see its release
		return true;
	}

	public boolean cycleMode() {
		return cycleMode;
	}

	/** In cycle mode after a crash (or before the first ride starts): the death cam is up. */
	public boolean dead() {
		return cycleMode && !riding;
	}

	public double deadSeconds() {
		return deadSince < 0 ? 0 : (clientTicks - deadSince) / 20.0;
	}

	public @Nullable Vec3 crashPos() {
		return crashPos;
	}

	/** Enter cycle mode (R, /arma cycle on): the interface swaps and the bike deploys facing the player's view. */
	public void enterCycleMode(Minecraft mc) {
		if (mc.player == null || cycleMode) {
			return;
		}
		if (!ClientPlayNetworking.canSend(RidePayloads.Request.TYPE)) {
			mc.player.sendSystemMessage(Component.literal("[arma] this server does not have armacraft ride mode"));
			return;
		}
		cycleMode = true;
		input.reset();
		crashPos = null;
		deadSince = clientTicks;
		mc.player.sendSystemMessage(Component.literal("[arma] cycle mode: " + RideKeys.RIDE.getTranslatedKeyMessage().getString().toUpperCase(java.util.Locale.ROOT)
			+ " or " + keyList(keys.exit) + " to leave"));
		LOG.info("cycle mode on");
		requestStart(mc, mc.player.getYRot());
	}

	/** Leave cycle mode (exit key, /arma cycle off): stow the bike and give the keyboard, HUD and camera back. */
	public void exitCycleMode(Minecraft mc, String why) {
		if (!cycleMode) {
			return;
		}
		if (riding) {
			dismount(mc, why);
		}
		cycleMode = false;
		awaitingStart = false;
		deadSince = -1;
		crashPos = null;
		input.reset();
		visuals().hud().clear();
		LOG.info("cycle mode off ({})", why);
	}

	private void requestStart(Minecraft mc, float yaw) {
		awaitingStart = true;
		ClientPlayNetworking.send(new RidePayloads.Request(RidePayloads.Request.START, yaw, localConfig.riderRgb(profile),
			localConfig.riderNameOr(profile)));
	}

	private static String keyList(java.util.List<String> names) {
		java.util.List<String> out = new java.util.ArrayList<>();
		for (String n : names) {
			out.add(n.replace("key.keyboard.", "").toUpperCase(java.util.Locale.ROOT));
		}
		return String.join("/", out);
	}

	public boolean riding() {
		return riding;
	}

	public @Nullable CycleMovement movement() {
		return move;
	}

	public Trail trail() {
		return trail;
	}

	public int rgb() {
		return rgb;
	}

	public @Nullable RideConfig config() {
		return config;
	}

	public RideInput input() {
		return input;
	}

	// ---------------------------------------------------------------- start / stop

	/** R pressed outside cycle mode: enter it (inside, R arrives as a raw key event and leaves, see RideInput). */
	public void requestToggle(Minecraft mc) {
		if (!cycleMode) {
			enterCycleMode(mc);
		}
	}

	/** {@code /arma cycle [on|off]} from the server. */
	public void onCycle(Minecraft mc, RidePayloads.Cycle c) {
		boolean on = c.action() == RidePayloads.Cycle.ON || (c.action() == RidePayloads.Cycle.TOGGLE && !cycleMode);
		if (on) {
			enterCycleMode(mc);
		} else {
			exitCycleMode(mc, "/arma cycle off");
		}
	}

	public void onStart(Minecraft mc, RidePayloads.Start s) {
		LocalPlayer p = mc.player;
		if (p == null) {
			return;
		}
		awaitingStart = false;
		config = RideConfig.fromJson(s.configJson());
		params = config.params();
		terrain = new Terrain(config.stepHeight, p.getBbHeight(), 1.0);
		move = new CycleMovement(params, s.heading());
		rgb = s.rgb();
		// a new cycle: the walls of the dead one go now (they would otherwise stay WALLS_STAY_UP_DELAY and box the
		// redeploy point in; Armagetron never respawns a cycle next to its own dead walls)
		trail = new Trail();
		sentFrom = 0;
		vy = 0;
		blocked = false;
		grinding = false;
		frontKind = Surroundings.Kind.NONE;
		wasOnGround = p.onGround();
		rideTime = 0;
		crashCause = null;
		riding = true;
		if (!cycleMode) {
			// started by the server (/arma ride): the interface swaps too
			cycleMode = true;
			input.reset();
		}
		deadSince = -1;
		input.configure(keys, config.doublebindTime());
		input.resetHeld();
		RideHooks.setRideWidth(config.bikeWidth);
		RideHooks.setClientRiding(p.getUUID(), true);
		p.refreshDimensions(); // the narrow riding box (AvatarRideMixin)
		applyStepHeight(p, config.stepHeight);
		float yaw = Heading.yaw(s.heading());
		turnFrom = turnTo = yaw;
		turnT = -100;
		turnDir = 0;
		distO = dist = 0;
		stepSumO = stepSum = stepEased = 0;
		stepInit = false;
		visualTurns = 0;
		visualDirs.clear();
		vLast = params.startSpeed;
		lastFrameNanos = 0; // no frames during the death cam: stamp presses from the first riding frame on
		predSpace = Double.POSITIVE_INFINITY;
		predHeading = -1;
		lastFrameHeading = s.heading();
		p.setYRot(yaw);
		p.yRotO = yaw;
		p.setXRot(Math.max(p.getXRot(), 5f));
		RideKeys.drain(mc);
		// lane_centering > 0: start on the block's centre line (sideways only, collision-resolved), so the first straight
		// passes 1-wide gaps. Only here: while riding nothing moves the bike sideways, so every trail piece between two
		// turns is one straight axis-aligned line, as in Armagetron.
		if (config.laneCentering > 0) {
			int lx = Heading.dx(s.heading());
			double lat = lx != 0 ? p.getZ() : p.getX();
			double toCentre = Math.floor(lat) + 0.5 - lat;
			if (Math.abs(toCentre) > 1e-3 && Math.abs(toCentre) < 0.5) {
				p.move(MoverType.SELF, lx != 0 ? new Vec3(0, 0, toCentre) : new Vec3(toCentre, 0, 0));
				p.xo = p.getX();
				p.zo = p.getZ();
			}
		}
		trail.append(p.getX(), p.getY(), p.getZ(), 0);
		frame = null;
		visuals().camera().reset(cameraTarget(p, 1f));
		visuals().engine().start(engineState(p));
		LOG.info("riding {} at {} {} {} (preset {}: speed {} b/s, rubber {} b, delay {} s)", Heading.name(s.heading()), fmt(p.getX()),
			fmt(p.getY()), fmt(p.getZ()), localConfig.preset, fmt(params.speed), fmt(params.rubber), params.delay);
	}

	/** /arma preset on the server: new physics, applied to the running bike at once. */
	public void onSettings(Minecraft mc, RidePayloads.Settings s) {
		if (!riding || move == null || mc.player == null) {
			return; // not riding: the next ride_start carries the settings
		}
		config = RideConfig.fromJson(s.configJson());
		params = config.params();
		move.setParams(params);
		RideHooks.setRideWidth(config.bikeWidth);
		mc.player.refreshDimensions();
		terrain = new Terrain(config.stepHeight, mc.player.getBbHeight(), 1.0);
		applyStepHeight(mc.player, config.stepHeight);
		input.configure(keys, config.doublebindTime());
		LOG.info("ride settings changed live: speed {} b/s, rubber {} b, delay {} s", fmt(params.speed), fmt(params.rubber), params.delay);
	}

	/** The server ended the ride (or refused to start it). */
	public void onStop(Minecraft mc, RidePayloads.Stop s) {
		boolean wasWaiting = awaitingStart;
		awaitingStart = false;
		if (!riding) {
			if (wasWaiting && cycleMode) {
				// the server would not start the ride: back to vanilla
				exitCycleMode(mc, "server: " + s.message());
			}
			return;
		}
		if (s.reason() == RidePayloads.Stop.CRASH) {
			crashLocally(mc, s.message().isEmpty() ? "crashed" : s.message(), false);
		} else {
			// dismounted by the server (/arma ride stop, died, joined the arena): leave cycle mode
			end(mc);
			exitCycleMode(mc, "server stop" + (s.message().isEmpty() ? "" : ": " + s.message()));
		}
	}

	private void dismount(Minecraft mc, String why) {
		if (!riding) {
			return;
		}
		end(mc);
		if (ClientPlayNetworking.canSend(RidePayloads.Request.TYPE)) {
			ClientPlayNetworking.send(new RidePayloads.Request(RidePayloads.Request.STOP, 0));
		}
		LOG.info("dismounted ({})", why);
	}

	private void crashLocally(Minecraft mc, String cause, boolean tellServer) {
		LocalPlayer p = mc.player;
		crashCause = cause;
		stats.crashes++;
		stats.lastCause = cause;
		if (p != null) {
			// the explosion is the server's (ride.server.CrashEffect broadcasts it to everyone, the rider included)
			if (tellServer && ClientPlayNetworking.canSend(RidePayloads.Crash.TYPE)) {
				ClientPlayNetworking.send(new RidePayloads.Crash(p.getX(), p.getY(), p.getZ(), cause));
			}
			LOG.info("CRASH: {} at {} {} {} ({})", cause, fmt(p.getX()), fmt(p.getY()), fmt(p.getZ()), stats.lastCrashDetail);
			crashPos = p.position();
			// the death cam (and so the next deploy) turns to the freest side: never straight back into the same wall
			if (move != null && config != null) {
				int best = freestHeading(p, move.heading());
				float yaw = p.getYRot() + Mth.wrapDegrees(Heading.yaw(best) - Heading.yaw(move.heading()));
				p.setYRot(yaw);
				p.yRotO = yaw;
			}
		}
		end(mc);
		deadSince = clientTicks; // cycle mode stays: death cam, then redeploy
	}

	private void end(Minecraft mc) {
		riding = false;
		stats.stops++;
		LocalPlayer p = mc.player;
		if (p != null) {
			RideHooks.setClientRiding(p.getUUID(), false);
			p.refreshDimensions();
			unstick(p);
			applyStepHeight(p, -1);
			p.setDeltaMovement(p.getDeltaMovement().multiply(0.3, 1, 0.3));
		}
		if (move != null) {
			trail.stop(RideClientMod.now());
		}
		input.resetHeld();
		glanceLeft = glanceRight = glanceBack = false;
		visuals().engine().stop();
		visuals().hud().clear();
	}

	/**
	 * The riding box is narrower than a standing player, so a ride can end closer to a wall than a player fits:
	 * move the player the shortest way (at most half a block, sideways or along) to where the full box is free.
	 */
	private static void unstick(LocalPlayer p) {
		if (p.level().noCollision(p)) {
			return;
		}
		AABB bb = p.getBoundingBox();
		for (int i = 1; i <= 10; i++) {
			double d = 0.05 * i;
			for (int h = 0; h < 4; h++) {
				double dx = Heading.dx(h) * d;
				double dz = Heading.dz(h) * d;
				if (p.level().noCollision(p, bb.move(dx, 0, dz))) {
					p.setPos(p.getX() + dx, p.getY(), p.getZ() + dz);
					return;
				}
			}
		}
	}

	public void onDisconnect() {
		riding = false;
		cycleMode = false;
		deadSince = -1;
		awaitingStart = false;
		trail = new Trail();
		RideHooks.clearClient();
		visuals().engine().stop();
	}

	private static void applyStepHeight(Player p, double h) {
		AttributeInstance a = p.getAttribute(Attributes.STEP_HEIGHT);
		if (a == null) {
			return;
		}
		a.removeModifier(RideService.STEP_MODIFIER);
		if (h >= 0) {
			a.addOrUpdateTransientModifier(new AttributeModifier(RideService.STEP_MODIFIER, h - a.getBaseValue(), AttributeModifier.Operation.ADD_VALUE));
		}
	}

	// ---------------------------------------------------------------- input

	/** Raw key event (KeyboardHandlerRideMixin). Returns true to swallow it. */
	public boolean onRawKey(int action, KeyEvent event) {
		if (!cycleMode || RideInput.screenOpen()) {
			return false;
		}
		return input.onKey(action, event);
	}

	/** Start of every client tick: the deploy key, then the key presses since the last tick, in order. */
	public void handleInput(Minecraft mc) {
		clientTicks++;
		if (mc.level != trailLevel) {
			// new world or dimension: old trails (and other riders) are not walls here
			trailLevel = mc.level;
			if (riding) {
				end(mc);
			}
			if (cycleMode) {
				exitCycleMode(mc, "world changed");
			}
			trail = new Trail();
			RideClientMod.remote().clear();
		}
		LocalPlayer p = mc.player;
		if (p == null) {
			return;
		}
		boolean toggle = false;
		while (RideKeys.RIDE.consumeClick()) {
			toggle = true;
		}
		if (toggle && mc.gui.screen() == null) {
			requestToggle(mc);
			return;
		}
		if (!cycleMode) {
			return;
		}
		if (mc.gui.screen() != null) {
			// pause menu, chat: key releases do not reach us, so nothing held survives it
			input.resetHeld();
			glanceLeft = glanceRight = glanceBack = false;
			screenWasOpen = true;
			if (move != null) {
				move.setBraking(false);
				move.setBoosting(false);
			}
			return;
		}
		if (screenWasOpen) {
			screenWasOpen = false;
			KeyMapping.releaseAll(); // vanilla re-presses physically held keys when the mouse is grabbed again
		}
		if (input.pollExit()) {
			exitCycleMode(mc, "exit key");
			return;
		}
		if (!riding || move == null) {
			deathCamInput(mc, p);
			return;
		}
		if (!localConfig.exactTurnTiming) {
			int t;
			while ((t = input.pollTurn()) != 0) {
				turn(p, t);
			}
		} // else: travel() applies each press at its own time
		move.setBraking(input.braking());
		move.setBoosting(input.held(RideInput.Action.ACCELERATE));
		glanceLeft = input.held(RideInput.Action.GLANCE_LEFT);
		glanceRight = input.held(RideInput.Action.GLANCE_RIGHT);
		glanceBack = input.held(RideInput.Action.GLANCE_BACK);
	}

	/** Dead in cycle mode: a turn key (after a short minimum) or the auto delay redeploys facing the camera. */
	private void deathCamInput(Minecraft mc, LocalPlayer p) {
		boolean turnPressed = false;
		while (input.pollTurn() != 0) {
			turnPressed = true;
		}
		if (awaitingStart) {
			return;
		}
		double dead = deadSeconds();
		boolean auto = localConfig.cycleRedeploySeconds > 0 && dead >= localConfig.cycleRedeploySeconds;
		if ((turnPressed && dead >= localConfig.cycleRedeployMinSeconds) || auto) {
			float yaw = mc.gameRenderer.mainCamera().yRot();
			redeploys++;
			LOG.info("redeploy {} after {} s ({})", Heading.name(Heading.fromYaw(yaw)), String.format("%.1f", dead), turnPressed ? "turn key" : "auto");
			requestStart(mc, yaw);
		} else if (!turnPressed) {
			visuals().hud().dead(crashCause == null ? "" : crashCause,
				localConfig.cycleRedeploySeconds > 0 ? Math.max(0, localConfig.cycleRedeploySeconds - dead) : Double.NaN);
		}
	}

	private void turn(LocalPlayer p, int dir) {
		if (move == null) {
			return;
		}
		int before = move.heading();
		if (move.requestTurn(dir)) {
			onTurned(p, before, dir);
		}
	}

	/** The physics turned. */
	private void onTurned(LocalPlayer p, int before, int dir) {
		stats.turns++;
		blocked = false;
		probe.onPhysicsTurn(p.position(), System.nanoTime());
		if (visualTurns < move.turns()) {
			// not drawn ahead (legacy timing, or a turn the frame did not foresee): draw it now
			visualTurn(p, dir, move.heading(), clientTicks / 20.0);
		}
	}

	/** The drawn bike turns: body yaw swing, the view turns with it (mouse look stays free relative to it), camera. */
	private void visualTurn(LocalPlayer p, int dir, int newHeading, double atFrameTime) {
		visualTurns++;
		visualDirs.push(dir);
		if (visualDirs.size() > 16) {
			visualDirs.removeLast();
		}
		turnFrom = frame != null ? frame.bodyYaw() : Heading.yaw(Heading.turn(newHeading, -dir));
		turnTo = Heading.yaw(newHeading);
		turnDir = dir;
		turnT = atFrameTime;
		p.setYRot(p.getYRot() + 90f * dir);
		p.yRotO += 90f * dir;
		visuals().camera().onTurn(dir);
	}

	// ---------------------------------------------------------------- movement (inside Player.travel)

	/**
	 * Installed as {@link RideHooks.TravelOverride}. One Minecraft tick of physics. With exact turn timing (default)
	 * the tick is split at every turn: a key press turns at the physics time it was pressed (the time the drawn bike
	 * showed), and a turn CYCLE_DELAY held back turns exactly when the delay is over, so corners land where the bike
	 * was, not on the next 50 ms tick boundary. Every piece moves the player with Entity.move (collision-resolved).
	 */
	public boolean travel(Player player, Vec3 in) {
		long t0 = RideFrameCost.start();
		boolean moved = travelTick(player, in);
		if (moved) {
			RideFrameCost.tick(t0);
		}
		return moved;
	}

	/** A tick is split into at most this many physics pieces (each senses terrain and trails once). */
	private static final int MAX_PIECES_PER_TICK = 8;

	private boolean travelTick(Player player, Vec3 in) {
		Minecraft mc = Minecraft.getInstance();
		if (!riding || move == null || player != mc.player || config == null) {
			return false;
		}
		LocalPlayer p = mc.player;
		stats.ticks++;
		rideTime += DT;
		distO = dist;
		stepSumO = stepSum;
		Vec3 tickStart = p.position();
		double tEnd = move.time() + DT;
		vy = p.onGround() ? -GRAVITY : (vy - GRAVITY) * DRAG;
		grinding = false;

		if (!localConfig.exactTurnTiming) {
			// legacy: presses were applied in handleInput at the tick start; queued turns run at tick starts
			applyDueQueued(p);
			if (!segment(mc, p, DT)) {
				return true;
			}
		} else {
			for (int pieces = 1; ; pieces++) {
				applyDuePresses(p);
				applyDueQueued(p);
				if (move.crashed() || !riding) {
					return true;
				}
				double now = move.time();
				if (now >= tEnd - 1e-9) {
					break;
				}
				double next = tEnd;
				RideInput.TurnPress tp = input.peekTurnPress();
				if (tp != null && tp.time() < next) {
					next = Math.max(now, tp.time());
				}
				next = Math.min(next, move.nextDueTurnTime());
				if (pieces >= MAX_PIECES_PER_TICK) {
					next = tEnd; // bounded work per tick: later presses turn at the next tick's start
				}
				if (next - now > 1e-9) {
					if (!segment(mc, p, next - now)) {
						return true;
					}
				}
			}
		}
		Vec3 after = p.position();
		p.setDeltaMovement(after.subtract(tickStart));
		p.resetFallDistance();
		vLast = Math.hypot(after.x - tickStart.x, after.z - tickStart.z) / DT;
		trail.trim(Math.max(1, config.trailLengthBlocks() - move.rubberUsed() * config.rubberWallShrink()), after.x, after.y, after.z);
		sendState(p);
		updateHudAndSound(p);
		return true;
	}

	/** Presses whose physics time has come (or is unknown): turn now, or queue for CYCLE_DELAY. */
	private void applyDuePresses(LocalPlayer p) {
		RideInput.TurnPress tp;
		while ((tp = input.peekTurnPress()) != null && (Double.isNaN(tp.time()) || tp.time() <= move.time() + 1e-9)) {
			input.pollTurnPress();
			turn(p, tp.dir());
		}
	}

	/** A turn CYCLE_DELAY held back runs once its time has come. */
	private void applyDueQueued(LocalPlayer p) {
		int before = move.heading();
		while (move.processPendingTurns()) {
			onTurned(p, before, Heading.turn(before, 1) == move.heading() ? 1 : -1);
			before = move.heading();
		}
	}

	/** {@code seg} seconds of physics along the current heading. False if the bike crashed. */
	private boolean segment(Minecraft mc, LocalPlayer p, double seg) {
		int heading = move.heading();
		Surroundings env = sense(p, heading);
		CycleMovement.Step step = move.tick(seg, env);
		grinding |= step.rubberSpent() > 1e-6;
		stats.lastGrinding = grinding;
		stats.minRubberLeft = Math.min(stats.minRubberLeft, move.rubberLeft());
		stats.maxSpeed = Math.max(stats.maxSpeed, move.speed());
		if (step.crashed()) {
			crashLocally(mc, frontKind == Surroundings.Kind.TERRAIN || blocked ? "hit a wall" : "hit a light trail", true);
			return false;
		}
		predHeading = heading;
		predSpace = env.spaceAhead() - step.distance();
		if (env.hold()) {
			stats.heldTicks++;
			return true;
		}
		double frac = seg / DT;

		// horizontal: along the heading only. Nothing moves the bike sideways while it lays a trail, so the trail
		// between two turns is one straight axis-aligned line (Armagetron walls never bend).
		double d = step.distance();
		int hx = Heading.dx(heading);
		int hz = Heading.dz(heading);
		double dx = hx * d;
		double dz = hz * d;
		double dy = vy * frac;

		Vec3 from = p.position();
		boolean groundBefore = p.onGround();
		p.move(MoverType.SELF, new Vec3(dx, dy, dz));
		Vec3 after = p.position();
		stats.maxSideways = Math.max(stats.maxSideways, Math.abs(hx != 0 ? after.z - from.z : after.x - from.x));
		double d1 = after.y - from.y;
		boolean groundAfter = p.onGround();
		boolean snapped = false;
		double along = (after.x - from.x) * hx + (after.z - from.z) * hz;
		blocked = d > 0.02 * frac && along < d * 0.5 && p.horizontalCollision;
		if (blocked) {
			stats.blockedTicks++;
		}

		// 1-block step down: snap instead of flying off. Entity.move resolves Y before X/Z, so on the tick the bike
		// leaves an edge it still lands "on ground" at the old height over nothing; check real support at the end
		// position, or a run of 1-block steps (one per block) would skip a level and turn into a flight.
		boolean supported = groundAfter && !p.level().noCollision(p, p.getBoundingBox().move(0, -0.05, 0));
		if (wasOnGround && !supported && vy <= 0) {
			double snap = terrain.snapDown() + 0.01;
			AABB below = p.getBoundingBox().move(0, -snap, 0);
			if (!p.level().noCollision(p, below)) {
				double y0 = p.getY();
				p.move(MoverType.SELF, new Vec3(0, -snap, 0));
				stepSum += d1 + (p.getY() - y0); // ground to ground: the whole drop is a step
				snapped = true;
			}
		}
		if (!snapped && groundBefore && groundAfter) {
			stepSum += d1; // a step-up (0 on flat ground)
		}
		if (p.onGround()) {
			vy = 0;
		} else if (p.verticalCollision && vy > 0) {
			vy = 0;
		}
		wasOnGround = p.onGround();
		after = p.position();
		dist += Math.max(0, along);
		// trail (physics positions, exact; a split at a turn puts the corner where it was)
		trail.append(after.x, after.y, after.z, move.turns());
		return true;
	}

	/** Of left, right and back from {@code heading}, the one with the most free road (terrain and trails), up to 32 blocks. */
	private int freestHeading(LocalPlayer p, int heading) {
		int best = Heading.turn(heading, -1);
		double bestFree = -1;
		for (int h : new int[] {Heading.turn(heading, -1), Heading.turn(heading, 1), Heading.turn(Heading.turn(heading, 1), 1)}) {
			double hw = p.getBbWidth() / 2.0;
			WorldColumns cols = new WorldColumns(p.level(), p, p.getY());
			double free = TerrainProbe.ahead(cols, terrain, p.getX(), p.getZ(), p.getY(), h, hw, 32).distance();
			Trail.Point box = new Trail.Point(p.getX(), p.getZ(), p.getY(), p.getBbHeight(), terrain.snapDown());
			if (config.lethalTrails) {
				free = Math.min(free, trail.ahead(box, h, config.trailHeight, 32, Integer.MAX_VALUE, Double.NaN, 0, 0));
				free = Math.min(free, RideClientMod.remote().ahead(box, h, config.trailHeight, 32, null));
			}
			free = Math.min(free, 32);
			if (free > bestFree + 1e-6) {
				bestFree = free;
				best = h;
			}
		}
		return best;
	}

	/** Armagetron's sensors: the front wall distance and the two diagonal-back acceleration sensors. */
	private Surroundings sense(LocalPlayer p, int heading) {
		double hw = p.getBbWidth() / 2.0;
		double x = p.getX();
		double z = p.getZ();
		double feet = p.getY();
		WorldColumns cols = new WorldColumns(p.level(), p, feet);
		int hx = Heading.dx(heading);
		if (hx != 0) {
			cols.band(Direction.Axis.Z, z - hw, z + hw);
		} else {
			cols.band(Direction.Axis.X, x - hw, x + hw);
		}
		double speed = move.speed();
		double maxDist = 4 + speed * DT * 2;
		TerrainProbe.Ahead a = TerrainProbe.ahead(cols, terrain, x, z, feet, heading, hw, maxDist);
		stats.lastAhead = a.first();
		double space = a.distance();
		frontKind = Double.isFinite(space) ? Surroundings.Kind.TERRAIN : Surroundings.Kind.NONE;
		String detail = "terrain " + a.first() + " ahead";
		if (blocked) {
			space = 0;
			frontKind = Surroundings.Kind.TERRAIN;
			detail = "blocked by vanilla collision";
		}
		// trails: the cycle is a point and walls are lines (Armagetron), measured from the bike's centre, and met at
		// the height the bike has when it gets there (a.levels(): the steps on the way)
		Trail.Point box = new Trail.Point(x, z, feet, p.getBbHeight(), terrain.snapDown());
		if (config.lethalTrails) {
			// own trail: Armagetron never collides a cycle with its current wall or the one before the last turn
			double own = trail.ahead(box, heading, config.trailHeight, maxDist, move.turns() - 1, Double.NaN, 0, 0, a.levels());
			if (own < space) {
				space = own;
				frontKind = Surroundings.Kind.SELF;
				detail = String.format("own trail piece %s; bike (%.2f,%.2f,%.2f) heading %s turns %d", trail.describePiece(trail.lastHit), x, feet, z,
					Heading.name(heading), move.turns());
			}
			double others = RideClientMod.remote().ahead(box, heading, config.trailHeight, maxDist, a.levels());
			if (others < space) {
				space = others;
				frontKind = Surroundings.Kind.ENEMY;
				detail = "another rider's trail";
			}
		}
		if (Double.isFinite(space)) {
			stats.lastCrashDetail = detail;
		}
		stats.lastSpace = space;
		cols.band(null, 0, 0);
		Surroundings.Side left = side(cols, box, x, z, feet, heading, -1, hw);
		Surroundings.Side right = side(cols, box, x, z, feet, heading, +1, hw);
		wallIn = speed > 0.1 ? space / speed : Double.POSITIVE_INFINITY;
		return new Surroundings(space, 1, left, right, a.hold() && a.distance() <= move.speed() * DT + 0.5, frontKind);
	}

	private Surroundings.Side side(WorldColumns cols, Trail.Point box, double x, double z, double feet, int heading, int side, double hw) {
		double near = params.wallNear;
		double t = TerrainProbe.side(cols, terrain, x, z, feet, heading, side, hw, near);
		Surroundings.Kind kind = Surroundings.Kind.TERRAIN;
		double own = trail.side(box, heading, side, config.trailHeight, near, Integer.MAX_VALUE);
		if (own < t) {
			t = own;
			kind = Surroundings.Kind.SELF;
		}
		double other = RideClientMod.remote().side(box, heading, side, config.trailHeight, near);
		if (other < t) {
			t = other;
			kind = Surroundings.Kind.ENEMY;
		}
		return Double.isFinite(t) ? new Surroundings.Side(t, kind) : Surroundings.Side.NONE;
	}

	/** Sent every tick while riding (the server-side features treat a rider as stale after 10 ticks without one). */
	private void sendState(LocalPlayer p) {
		if (!ClientPlayNetworking.canSend(RidePayloads.State.TYPE) || move == null) {
			return;
		}
		// resend from the last point we sent: it may have been extended in place since
		long from = Math.max(trail.firstIndex(), Math.min(sentFrom, trail.endIndex() - 1));
		double[] pts = trail.pointsFrom(from);
		int[] segs = trail.segsFrom(from);
		ClientPlayNetworking.send(new RidePayloads.State(move.heading(), (float) move.speed(), (float) move.rubberLeft(),
			(float) move.brakeReservoir(), move.braking(), move.turns(), from, trail.firstIndex(), pts, segs));
		sentFrom = Math.max(trail.firstIndex(), trail.endIndex() - 1);
	}

	private void updateHudAndSound(LocalPlayer p) {
		// NOT Armagetron: a look-ahead warning (HUD + a short blip on the rising edge) when a wall is close in time
		boolean warn = config.wallWarningSeconds > 0 && wallIn < config.wallWarningSeconds;
		if (warn && !warning) {
			p.level().playLocalSound(p.getX(), p.getY(), p.getZ(), net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BIT.value(),
				net.minecraft.sounds.SoundSource.PLAYERS, 0.6f, 1.6f, false);
		}
		warning = warn;
		visuals().hud().update(new RideHud.State(move.speed(), params.speed, move.rubberLeft(), move.brakeReservoir(),
			move.braking() && move.brakeReservoir() > 0, move.heading(), move.boostReservoir(), move.boosting(), wallIn, warning));
		visuals().engine().update(engineState(p));
	}

	private EngineSound.State engineState(LocalPlayer p) {
		return new EngineSound.State(p.position(), move == null ? 0 : move.speed(), params == null ? 18 : params.maxSpeed(),
			move != null && move.braking() && move.brakeReservoir() > 0, grinding);
	}

	// ---------------------------------------------------------------- per frame

	/**
	 * The pose for this frame, computed once per frame on the game-time clock (client ticks plus the frame's partial
	 * tick). With exact turn timing the bike is drawn where the physics will be at frame time: from the last tick's
	 * state forward by the frame's part of a tick at the speed it last moved (never past the wall it measured), turning
	 * at every press and CYCLE_DELAY turn due by then, like Armagetron's client simulating to the frame. The vertical
	 * steps are eased (render only). Physics is untouched.
	 */
	public FrameState frame(LocalPlayer p, float partial) {
		double t = (clientTicks + partial) / 20.0;
		if (frame != null && Math.abs(t - frameT) < 1e-9) {
			return frame;
		}
		double dt = frameT < 0 ? 0 : Math.max(0, Math.min(0.25, t - frameT));
		frameT = t;
		long nanos = System.nanoTime();
		double x = Mth.lerp(partial, p.xo, p.getX());
		double yLerp = Mth.lerp(partial, p.yo, p.getY());
		double z = Mth.lerp(partial, p.zo, p.getZ());
		int heading = move == null ? Heading.fromYaw(p.getYRot()) : move.heading();
		java.util.List<Vec3> corners = java.util.List.of();
		if (move != null && riding && localConfig.exactTurnTiming) {
			double tDisplay = move.time() + partial * DT;
			int planned = planTurns(tDisplay);
			if (planned > 0) {
				RideFrameCost.planned();
			}
			// the drawn turns follow the plan (rotate view and body as the press happens, before the tick applies it)
			int target = move.turns() + planned;
			if (visualTurns < move.turns()) {
				visualTurns = move.turns();
			}
			while (visualTurns < target) {
				int i = visualTurns - move.turns();
				visualTurn(p, planDir[i], planHeading[i], t - (tDisplay - planT[i]));
			}
			while (visualTurns > target && !visualDirs.isEmpty()) {
				int back = visualDirs.pop(); // a predicted turn the physics did not make: undo it
				visualTurns--;
				p.setYRot(p.getYRot() - 90f * back);
				p.yRotO -= 90f * back;
				turnTo = Heading.yaw(move.heading());
			}
			// horizontal: forward from the physics state at the last tick (allocates only for a planned corner)
			double v = vLast;
			x = p.getX();
			z = p.getZ();
			double tt = move.time();
			int h = heading;
			Vec3[] cs = planned > 0 ? new Vec3[planned] : null;
			for (int i = 0; i < planned; i++) {
				double s = Math.max(0, planT[i] - tt) * v;
				if (i == 0 && h == predHeading) {
					s = Math.min(s, Math.max(0, predSpace));
				}
				x += Heading.dx(h) * s;
				z += Heading.dz(h) * s;
				cs[i] = new Vec3(x, p.getY(), z);
				h = planHeading[i];
				tt = planT[i];
				v *= params.turnSpeedFactor;
			}
			double s = Math.max(0, tDisplay - tt) * v;
			if (planned == 0 && h == predHeading) {
				s = Math.min(s, Math.max(0, predSpace));
			}
			x += Heading.dx(h) * s;
			z += Heading.dz(h) * s;
			heading = h;
			if (cs != null) {
				corners = java.util.Arrays.asList(cs);
			}
		}
		// vertical: continuous motion (falls) interpolated exactly; the steps of the tick, interpolated over it, eased
		// (measured smoother than a spring on the step target: no velocity jump where a step lands)
		double stepNow = Mth.lerp(partial, stepSumO, stepSum);
		if (!stepInit) {
			stepEased = stepNow;
			stepInit = true;
		}
		stepEased += (stepNow - stepEased) * (1 - Math.exp(-dt / STEP_TAU));
		stepEased = Math.max(stepNow - STEP_LAG_MAX, Math.min(stepNow + STEP_LAG_MAX, stepEased));
		double yRender = yLerp - stepNow + stepEased;
		double since = Math.max(0, t - turnT);
		float body = turnTo - (float) (Mth.wrapDegrees(turnTo - turnFrom) * (1 + YAW_OMEGA * since) * Math.exp(-YAW_OMEGA * since));
		double lean = turnDir * 0.8 * Math.exp(-since / LEAN_TAU) * Math.min(1, since / 0.05);
		double wheel = (distO + (dist - distO) * partial) / WHEEL_RADIUS;
		frame = new FrameState(t, dt, new Vec3(x, yRender, z), body, lean, wheel, heading, corners);
		lastFramePos = frame.pos();
		lastFrameNanos = nanos;
		lastFramePartial = partial;
		lastFrameHeading = heading;
		probe.onFrame(frame.pos(), nanos);
		return frame;
	}

	/** At most this many turns are planned ahead of the physics per frame (presses within the next tick or two). */
	private static final int MAX_PLAN = 8;
	private final double[] planT = new double[MAX_PLAN];
	private final int[] planDir = new int[MAX_PLAN];
	private final int[] planHeading = new int[MAX_PLAN];
	private final double[] pressT = new double[MAX_PLAN];
	private final int[] pressDir = new int[MAX_PLAN];
	private @Nullable CycleMovement planMove;

	/**
	 * The turns the physics will make by physics time {@code until}: presses not applied yet, and turns CYCLE_DELAY
	 * holds back, run through a scratch copy of the cycle exactly as the next ticks will, into {@link #planT},
	 * {@link #planDir} and {@link #planHeading}. Returns how many. Nothing pending (almost every frame): no copy and
	 * no allocation; otherwise allocation-free too, bounded by {@link #MAX_PLAN}.
	 */
	private int planTurns(double until) {
		int presses = input.pendingTurnCount() == 0 ? 0 : input.pendingTurnPresses(pressT, pressDir);
		if (presses == 0 && move.nextDueTurnTime() > until) {
			return 0;
		}
		CycleMovement m = planMove = move.copyInto(planMove != null ? planMove : new CycleMovement(move.params(), move.heading()));
		int n = 0;
		for (int i = 0; i < presses && n < MAX_PLAN; i++) {
			double at = Double.isNaN(pressT[i]) ? m.time() : Math.max(m.time(), pressT[i]);
			if (at > until) {
				break;
			}
			n = planQueued(m, at, n);
			m.skipTo(at);
			if (n < MAX_PLAN && m.requestTurn(pressDir[i])) {
				planT[n] = at;
				planDir[n] = pressDir[i];
				planHeading[n] = m.heading();
				n++;
			}
		}
		return planQueued(m, until, n);
	}

	private int planQueued(CycleMovement m, double until, int n) {
		double due;
		while (n < MAX_PLAN && (due = m.nextDueTurnTime()) <= until) {
			m.skipTo(due);
			int before = m.heading();
			if (!m.processPendingTurns()) {
				break;
			}
			planT[n] = due;
			planDir[n] = Heading.turn(before, 1) == m.heading() ? 1 : -1;
			planHeading[n] = m.heading();
			n++;
		}
		return n;
	}

	/** Called from the camera mixin each frame while riding. */
	public ChaseCamera.Target cameraTarget(LocalPlayer p, float partial) {
		FrameState f = frame(p, partial);
		float heading = move == null ? p.getYRot() : Heading.yaw(f.heading());
		return new ChaseCamera.Target(f.pos(), heading, turnFrom, Math.max(0, f.t() - turnT), move == null ? 0 : move.speed(),
			p.getViewYRot(partial), p.getViewXRot(partial), config == null ? 0.6 : config.blocksPerUnit, rideTime, glanceLeft, glanceRight,
			glanceBack);
	}

	/** Physics time of a key press now: the time the drawn bike shows, carried forward from the last frame. */
	private double pressClock() {
		if (move == null || !riding) {
			return Double.NaN;
		}
		if (lastFrameNanos == 0) {
			return move.time();
		}
		double phase = lastFramePartial + (System.nanoTime() - lastFrameNanos) / (DT * 1e9);
		return move.time() + Math.max(0, Math.min(2, phase)) * DT;
	}

	public TurnTimingProbe probe() {
		return probe;
	}

	public boolean awaitingStart() {
		return awaitingStart;
	}

	public @Nullable String lastCrashCause() {
		return crashCause;
	}

	private static String fmt(double v) {
		return String.format("%.1f", v);
	}
}

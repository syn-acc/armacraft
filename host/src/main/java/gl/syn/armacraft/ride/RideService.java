package gl.syn.armacraft.ride;

import gl.syn.armacraft.Armacraft;
import gl.syn.armacraft.ArmaController;
import gl.syn.armacraft.logic.Colors;
import gl.syn.armacraft.logic.DyePalette;
import gl.syn.armacraft.ride.core.Heading;
import gl.syn.armacraft.ride.core.Trail;
import gl.syn.armacraft.ride.net.RidePayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server half of ride mode that belongs to the driving: start/stop handshake, the rider state stream (pose, rubber,
 * trail points), {@code /arma ride}, and the {@link RideRegistry} seam that the server-side features (frost walker,
 * fall damage, movement checks, crash effects, mob hits) listen on.
 *
 * <p>The player is the bike: its client drives the local player (client-authoritative movement). The server only
 * sets what the client cannot (step height attribute, invisibility so the bike replaces the body) and records the
 * trail other viewers need. Server thread only.
 */
public final class RideService {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft/ride");
	public static final Identifier STEP_MODIFIER = Identifier.fromNamespaceAndPath("armacraft", "ride_step_height");

	/** What the server holds about one rider (and, after a stop, its fading trail). */
	public static final class Rider {
		/** The rider's player. */
		public final UUID uuid;
		/** This ride (a player's old trail keeps fading under its own key after they deploy again). */
		public final UUID key = UUID.randomUUID();
		public final int rgb;
		public final Trail trail = new Trail();
		public int heading;
		public float speed;
		public float rubberLeft = 1;
		public float brake = 1;
		public boolean braking;
		public int turns;
		public boolean riding = true;
		public boolean crashed;
		public int entityId;
		public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
		public double x;
		public double y;
		public double z;
		public long lastStateTick;
		public long states;
		public String name = "";
		/** A rider without a player (tests, debugging): pose and trail only. */
		public boolean virtual;
		double prevX = Double.NaN;
		double prevY;
		double prevZ;
		boolean wasInvisible;

		Rider(UUID uuid, int rgb) {
			this.uuid = uuid;
			this.rgb = rgb;
		}
	}

	private final Path configFile;
	private RideConfig config;
	private final Map<UUID, Rider> riders = new LinkedHashMap<>();
	/** Stopped riders whose trails are still fading (CYCLE_WALLS_STAY_UP_DELAY). */
	private final Map<UUID, Rider> fading = new LinkedHashMap<>();
	private final RideSync sync = new RideSync();
	private final RideFallback fallback = new RideFallback();
	/** Server-side kills by other riders' trails (diagnostics, tests). */
	private int trailKills;
	private @Nullable MinecraftServer server;

	public RideService(Path configDir) {
		this.configFile = configDir.resolve("armacraft-ride.json");
		this.config = RideConfig.load(configFile);
	}

	public RideConfig config() {
		return config;
	}

	/**
	 * /arma reload: re-read armacraft-ride.json and apply it to running rides. Physics, rubber factors, boost, trail and
	 * visual scale reach every modded player in a {@link RidePayloads.Settings} payload (riders switch at once; every
	 * client also re-reads its own copy of the file for keys, rider colour and name). Step height and bike width are
	 * re-applied to riders here. A file that does not parse changes nothing.
	 */
	public String reloadConfig() {
		var l = RideConfig.read(configFile);
		if (l.config() == null && l.existed()) {
			return "[arma] " + configFile.getFileName() + " NOT reloaded, running values kept: " + l.error();
		}
		config = l.config() != null ? l.config() : RideConfig.load(configFile);
		RideHooks.setRideWidth(config.bikeWidth);
		if (server != null) {
			for (Rider r : riders.values()) {
				ServerPlayer p = r.virtual ? null : server.getPlayerList().getPlayer(r.uuid);
				if (p != null) {
					applyStepHeight(p, config.stepHeight);
					p.refreshDimensions();
				}
			}
		}
		int pushed = pushSettings(true);
		return "[arma] reloaded " + configFile.getFileName() + gl.syn.armacraft.config.ConfigDefaults.summary(l.changes())
			+ (pushed > 0 ? ", pushed to " + pushed + " client" + (pushed == 1 ? "" : "s") : "");
	}

	/**
	 * /arma preset NAME: switch the physics preset now. Saved to armacraft-ride.json, re-resolved, and pushed to every
	 * rider (their bikes keep going with the new settings; no restart, no redeploy).
	 */
	public String setPreset(String name) {
		String n = name.strip().toLowerCase(java.util.Locale.ROOT);
		if (!RideConfig.PRESETS.contains(n) && !n.startsWith("file:")) {
			return "unknown preset " + name + " (" + String.join(", ", RideConfig.PRESETS) + ", file:<path>)";
		}
		config.preset = n;
		config.sanitize(); // drops the cached resolution
		config.save(configFile);
		int pushed = pushSettings(false);
		var s = config.settings();
		return String.format(java.util.Locale.ROOT, "[arma] ride preset %s: CYCLE_SPEED %s, CYCLE_DELAY %s, CYCLE_ACCEL %s, CYCLE_RUBBER %s, WALLS_LENGTH %s%s",
			n, s.get("CYCLE_SPEED"), s.get("CYCLE_DELAY"), s.get("CYCLE_ACCEL"), s.get("CYCLE_RUBBER"), s.get("WALLS_LENGTH"),
			pushed > 0 ? " (live for " + pushed + " rider" + (pushed == 1 ? "" : "s") + ")" : "");
	}

	/** @param everyone false: riders only (physics); true: every modded player (a reload also re-reads client-side keys) */
	private int pushSettings(boolean everyone) {
		if (server == null) {
			return 0;
		}
		int n = 0;
		String json = config.toResolvedJson();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Rider r = riders.get(p.getUUID());
			if ((everyone || (r != null && !r.virtual)) && hasClientMod(p)) {
				ServerPlayNetworking.send(p, new RidePayloads.Settings(json));
				n++;
			}
		}
		return n;
	}

	/** /arma whatsnew: ask the player's client to print its build stamp and the what's-new notes. */
	public @Nullable String whatsNewCommand(ServerPlayer p) {
		if (!ServerPlayNetworking.canSend(p, RidePayloads.WhatsNew.TYPE)) {
			return "what's-new needs a current armacraft mod on your client";
		}
		ServerPlayNetworking.send(p, new RidePayloads.WhatsNew());
		return null;
	}

	public void register() {
		RidePayloads.register();
		ServerPlayNetworking.registerGlobalReceiver(RidePayloads.Request.TYPE, (p, ctx) -> onRequest(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(RidePayloads.State.TYPE, (p, ctx) -> onState(ctx.player(), p));
		ServerPlayNetworking.registerGlobalReceiver(RidePayloads.Crash.TYPE, (p, ctx) -> onCrash(ctx.player(), p));
	}

	// ---------------------------------------------------------------- queries

	public @Nullable Rider rider(UUID id) {
		return riders.get(id);
	}

	public boolean isRiding(ServerPlayer p) {
		return riders.containsKey(p.getUUID());
	}

	public Collection<Rider> riders() {
		return riders.values();
	}

	public Collection<Rider> fadingTrails() {
		return fading.values();
	}

	public static boolean hasClientMod(ServerPlayer p) {
		return ServerPlayNetworking.canSend(p, RidePayloads.Start.TYPE);
	}

	public static Direction direction(int heading) {
		return switch (heading & 3) {
			case Heading.NORTH -> Direction.NORTH;
			case Heading.EAST -> Direction.EAST;
			case Heading.SOUTH -> Direction.SOUTH;
			default -> Direction.WEST;
		};
	}

	// ---------------------------------------------------------------- start / stop

	/** @return null on success, else why not */
	public @Nullable String start(ServerPlayer p, float yaw) {
		return start(p, yaw, true, -1, "");
	}

	/** Test hook: starts a ride without the client-mod check (mock players in server gametests have no client). */
	public @Nullable String startUnchecked(ServerPlayer p, float yaw) {
		return start(p, yaw, false, -1, "");
	}

	/** Test hook: feed a rider state as if it came from the rider's client. */
	public void stateForTest(ServerPlayer p, RidePayloads.State s) {
		onState(p, s);
	}

	/** Test/debug hook: a rider without a player (pose and trail only), e.g. to exercise sync, fallback and lethal trails. */
	public Rider addVirtualRider(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, UUID id, String name, int rgb,
		int heading, double x, double y, double z) {
		Rider r = new Rider(id, rgb);
		r.virtual = true;
		r.dimension = dimension;
		r.name = name;
		r.heading = heading;
		r.entityId = -1;
		r.x = x;
		r.y = y;
		r.z = z;
		r.speed = (float) config.params().speed;
		riders.put(id, r);
		return r;
	}

	public void removeVirtualRider(UUID id) {
		Rider r = riders.get(id);
		if (r != null && r.virtual) {
			riders.remove(id);
			r.riding = false;
			if (server != null) {
				r.trail.stop(seconds(server.overworld()));
			}
			if (r.trail.size() > 0) {
				fading.put(r.key, r);
			}
		}
	}

	private @Nullable String start(ServerPlayer p, float yaw, boolean checkClient, int wantRgb, String wantName) {
		if (riders.containsKey(p.getUUID())) {
			return "already riding";
		}
		if (checkClient && !hasClientMod(p)) {
			return "ride mode needs the armacraft mod on your client (the player is the bike, so your client drives)";
		}
		if (p.isSpectator()) {
			return "not in spectator mode";
		}
		if (!p.isAlive()) {
			return "you are dead";
		}
		if (p.isPassenger()) {
			p.stopRiding();
		}
		ArmaController c = Armacraft.controller();
		if (c != null && c.sessions().get(p.getUUID()) != null) {
			return "you are in the arena: /arma leave first";
		}
		int heading = Heading.fromYaw(yaw);
		double[] col = Colors.fromUuid(p.getUUID());
		int rgb = wantRgb >= 0 && wantRgb <= 0xFFFFFF ? wantRgb : DyePalette.packRgb(col[0], col[1], col[2]);
		Rider r = new Rider(p.getUUID(), rgb);
		r.heading = heading;
		r.entityId = p.getId();
		r.dimension = p.level().dimension();
		r.name = cleanName(wantName, p.getPlainTextName());
		r.x = p.getX();
		r.y = p.getY();
		r.z = p.getZ();
		r.speed = (float) config.params().startSpeed;
		r.lastStateTick = p.level().getGameTime();
		r.wasInvisible = p.isInvisible();
		riders.put(p.getUUID(), r);
		// a new cycle: the walls of this player's dead one go now, for everyone (see RideClient.onStart)
		fading.values().removeIf(o -> o.uuid.equals(p.getUUID()));
		RideHooks.setRideWidth(config.bikeWidth);
		RideHooks.setServerRiding(p.getUUID(), true);
		p.refreshDimensions(); // the narrow riding box (AvatarRideMixin)
		applyStepHeight(p, config.stepHeight);
		p.setInvisible(true);
		if (hasClientMod(p)) {
			ServerPlayNetworking.send(p, new RidePayloads.Start(config.toResolvedJson(), heading, r.rgb));
		}
		RideRegistry.start(p, status(p, r));
		LOG.info("{} rides {} from {} {} {}", p.getPlainTextName(), Heading.name(heading), fmt(p.getX()), fmt(p.getY()), fmt(p.getZ()));
		return null;
	}

	/**
	 * Ends a ride. {@code notifyClient} is false when the client already knows (it sent the stop or crash itself).
	 */
	public void stop(ServerPlayer p, RideRegistry.StopReason reason, Vec3 at, boolean notifyClient, String message) {
		Rider r = riders.remove(p.getUUID());
		if (r == null) {
			return;
		}
		r.riding = false;
		r.crashed = reason == RideRegistry.StopReason.CRASH;
		r.trail.stop(seconds(p.level()));
		if (r.trail.size() > 0 && config.wallsStayUp() != 0) {
			fading.put(r.key, r);
		}
		RideHooks.setServerRiding(p.getUUID(), false);
		p.refreshDimensions();
		applyStepHeight(p, -1);
		if (!r.wasInvisible && !p.hasEffect(MobEffects.INVISIBILITY)) {
			p.setInvisible(false);
		}
		if (notifyClient && hasClientMod(p)) {
			int code = switch (reason) {
				case CRASH -> RidePayloads.Stop.CRASH;
				case DISMOUNT -> RidePayloads.Stop.DISMOUNT;
				default -> RidePayloads.Stop.SERVER;
			};
			ServerPlayNetworking.send(p, new RidePayloads.Stop(code, message));
		}
		RideRegistry.stop(p, reason, at);
		LOG.info("{} stopped riding: {} at {} {} {}{}", p.getPlainTextName(), reason, fmt(at.x), fmt(at.y), fmt(at.z),
			message.isEmpty() ? "" : " (" + message + ")");
	}

	private static void applyStepHeight(ServerPlayer p, double stepHeight) {
		AttributeInstance a = p.getAttribute(Attributes.STEP_HEIGHT);
		if (a == null) {
			return;
		}
		a.removeModifier(STEP_MODIFIER);
		if (stepHeight >= 0) {
			a.addOrUpdateTransientModifier(new AttributeModifier(STEP_MODIFIER, stepHeight - a.getBaseValue(), AttributeModifier.Operation.ADD_VALUE));
		}
	}

	// ---------------------------------------------------------------- payloads

	private void onRequest(ServerPlayer p, RidePayloads.Request req) {
		boolean riding = riders.containsKey(p.getUUID());
		boolean wantStart = req.action() == RidePayloads.Request.START || (req.action() == RidePayloads.Request.TOGGLE && !riding);
		if (wantStart) {
			String err = start(p, req.yaw(), true, req.rgb(), req.name());
			if (err != null && !"already riding".equals(err)) {
				p.sendSystemMessage(Component.literal("[arma] " + err).withStyle(ChatFormatting.RED));
				ServerPlayNetworking.send(p, new RidePayloads.Stop(RidePayloads.Stop.SERVER, err));
			}
		} else if (riding) {
			stop(p, RideRegistry.StopReason.DISMOUNT, p.position(), true, "");
		}
	}

	private void onState(ServerPlayer p, RidePayloads.State s) {
		Rider r = riders.get(p.getUUID());
		if (r == null) {
			return; // late packet after a stop
		}
		long now = p.level().getGameTime();
		r.heading = s.heading();
		double max = config.params().maxSpeed() * 1.25 + 1;
		r.speed = (float) Math.max(0, Math.min(max, Float.isFinite(s.speed()) ? s.speed() : 0));
		r.rubberLeft = clamp01(s.rubberLeft());
		r.brake = clamp01(s.brake());
		r.braking = s.braking();
		r.turns = s.turns();
		r.x = p.getX();
		r.y = p.getY();
		r.z = p.getZ();
		r.lastStateTick = now;
		r.states++;
		acceptTrail(p, r, s);
		RideRegistry.update(p, status(p, r));
	}

	/** Takes the client's new trail points if they are plausible (near the server-side position). */
	private void acceptTrail(ServerPlayer p, Rider r, RidePayloads.State s) {
		double[] pts = s.points();
		int n = pts.length / 3;
		if (n == 0) {
			r.trail.dropBefore(s.trailFirst());
			return;
		}
		double limit = 48.0;
		for (int i = 0; i < n; i++) {
			double dx = pts[3 * i] - p.getX();
			double dy = pts[3 * i + 1] - p.getY();
			double dz = pts[3 * i + 2] - p.getZ();
			if (!Double.isFinite(dx + dy + dz) || dx * dx + dy * dy + dz * dz > limit * limit * 16) {
				return; // implausible: ignore this batch
			}
		}
		r.trail.receive(s.trailFrom(), pts, s.segs());
		r.trail.dropBefore(s.trailFirst());
	}

	private void onCrash(ServerPlayer p, RidePayloads.Crash c) {
		if (!riders.containsKey(p.getUUID())) {
			return;
		}
		Vec3 at = new Vec3(c.x(), c.y(), c.z());
		if (!Double.isFinite(at.x + at.y + at.z) || at.distanceToSqr(p.position()) > 16 * 16) {
			at = p.position();
		}
		stop(p, RideRegistry.StopReason.CRASH, at, false, c.cause());
	}

	// ---------------------------------------------------------------- lifecycle

	public void onServerStarted(MinecraftServer server) {
		this.server = server;
	}

	public void onServerStopping(MinecraftServer server) {
		for (Rider r : new ArrayList<>(riders.values())) {
			ServerPlayer p = server.getPlayerList().getPlayer(r.uuid);
			if (p != null) {
				stop(p, RideRegistry.StopReason.DISCONNECT, p.position(), true, "server stopping");
			}
		}
		riders.clear();
		fading.clear();
		sync.clear();
		fallback.clear();
		RideHooks.clearServer();
		this.server = null;
	}

	public void onDisconnect(ServerPlayer p) {
		if (riders.containsKey(p.getUUID())) {
			stop(p, RideRegistry.StopReason.DISCONNECT, p.position(), false, "disconnected");
		}
	}

	public void tick(MinecraftServer server) {
		this.server = server;
		List<ServerPlayer> ended = new ArrayList<>();
		for (Rider r : riders.values()) {
			if (r.virtual) {
				continue;
			}
			ServerPlayer p = server.getPlayerList().getPlayer(r.uuid);
			if (p == null) {
				continue;
			}
			if (!p.isAlive() || p.isSpectator() || p.isPassenger() || p.isSleeping()) {
				ended.add(p);
				continue;
			}
			r.x = p.getX();
			r.y = p.getY();
			r.z = p.getZ();
			if (!p.isInvisible()) {
				p.setInvisible(true); // an expiring potion resets the flag
			}
		}
		for (ServerPlayer p : ended) {
			stop(p, RideRegistry.StopReason.DISMOUNT, p.position(), true, p.isAlive() ? "" : "died");
		}
		if (config.lethalTrails) {
			validateTrails(server);
		}
		riders.values().removeIf(r -> !r.virtual && server.getPlayerList().getPlayer(r.uuid) == null);
		// trails of stopped riders fade, then go
		double stayUp = config.wallsStayUp();
		fading.values().removeIf(r -> {
			ServerLevel lvl = server.overworld();
			// stay-up < 0 is "forever": until the player deploys again or leaves
			return !r.trail.lethal(seconds(lvl), stayUp) || (stayUp < 0 && !r.virtual && server.getPlayerList().getPlayer(r.uuid) == null);
		});
		sync.tick(server, this);
		fallback.tick(server, this);
	}

	/**
	 * Other riders' trails are walls (lethal_trails). The rider's own client predicts them with rubber; the server
	 * has the final word: a server-side move that crosses another rider's visible trail (with vertical overlap)
	 * crashes the rider. Own trails are client-predicted only.
	 */
	private void validateTrails(MinecraftServer server) {
		double now = seconds(server.overworld());
		double stayUp = config.wallsStayUp();
		List<Rider> walls = new ArrayList<>(riders.values());
		walls.addAll(fading.values());
		List<ServerPlayer> crashed = new ArrayList<>();
		List<String> causes = new ArrayList<>();
		for (Rider r : riders.values()) {
			ServerPlayer p = r.virtual ? null : server.getPlayerList().getPlayer(r.uuid);
			if (p == null) {
				continue;
			}
			if (!Double.isNaN(r.prevX) && (r.prevX != r.x || r.prevZ != r.z)) {
				double feet = Math.min(r.prevY, r.y);
				for (Rider o : walls) {
					// own trails (this ride and older ones) are the rider's client's business
					if (o.uuid.equals(r.uuid) || o.dimension != r.dimension || !o.trail.lethal(now, stayUp)) {
						continue;
					}
					double hx = o.riding ? o.x : Double.NaN;
					if (o.trail.crossedBy(r.prevX, r.prevZ, r.x, r.z, feet, p.getBbHeight(), config.trailHeight, hx, o.y, o.z)) {
						crashed.add(p);
						causes.add("hit " + o.name + "'s light trail");
						break;
					}
				}
			}
			r.prevX = r.x;
			r.prevY = r.y;
			r.prevZ = r.z;
		}
		for (int i = 0; i < crashed.size(); i++) {
			trailKills++;
			ServerPlayer p = crashed.get(i);
			stop(p, RideRegistry.StopReason.CRASH, p.position(), true, causes.get(i));
		}
	}

	public int trailKills() {
		return trailKills;
	}

	public int fallbackEntities() {
		return fallback.size();
	}

	// ---------------------------------------------------------------- helpers

	public static double seconds(net.minecraft.world.level.Level level) {
		return level.getGameTime() / 20.0;
	}

	private static RideRegistry.Status status(ServerPlayer p, Rider r) {
		return new RideRegistry.Status(direction(r.heading), r.speed, r.braking, p.level().getGameTime());
	}

	/** A client-chosen rider name: printable, at most 16 characters, else the player's name. */
	static String cleanName(String want, String fallback) {
		if (want == null) {
			return fallback;
		}
		String s = want.strip();
		if (s.isEmpty() || s.length() > 16 || !s.chars().allMatch(c -> c >= 0x20 && c < 0x7F && c != '\u00A7')) {
			return fallback;
		}
		return s;
	}

	/** /arma cycle [on|off]: tell the player's client to switch cycle mode. */
	public @Nullable String cycleCommand(ServerPlayer p, int action) {
		if (!hasClientMod(p)) {
			return "cycle mode needs the armacraft mod on your client";
		}
		ServerPlayNetworking.send(p, new RidePayloads.Cycle(action));
		return null;
	}

	private static float clamp01(float v) {
		return Float.isFinite(v) ? Math.max(0, Math.min(1, v)) : 0;
	}

	private static String fmt(double v) {
		return String.format("%.1f", v);
	}
}

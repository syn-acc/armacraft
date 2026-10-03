package gl.syn.armacraft;

import gl.syn.armacraft.bridge.BridgeClient;
import gl.syn.armacraft.bridge.Msg;
import gl.syn.armacraft.bridge.Protocol;
import gl.syn.armacraft.config.ArmaConfig;
import gl.syn.armacraft.logic.ArenaMapping;
import gl.syn.armacraft.logic.DeathTracker;
import gl.syn.armacraft.logic.DyePalette;
import gl.syn.armacraft.logic.Extrapolate;
import gl.syn.armacraft.render.ArenaRenderer;
import gl.syn.armacraft.render.EntitySet;
import gl.syn.armacraft.session.SessionManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Server-thread orchestration: drains the bridge, renders the latest snapshot, drives player sessions,
 * plays effects. Everything that touches the world happens in {@link #tick}.
 */
public final class ArmaController {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft");

	private final Path configFile;
	private ArmaConfig config;
	private final BridgeClient bridge = new BridgeClient();
	private final ArenaRenderer renderer = new ArenaRenderer();
	private final SessionManager sessions;
	private @Nullable MinecraftServer server;

	private Msg.@Nullable Round round;
	private Msg.@Nullable State lastRendered;
	private final DeathTracker deaths = new DeathTracker();
	private @Nullable ResourceKey<Level> renderedIn;
	private boolean dirtyLayout;
	private int deathFx;
	/** Events older than this when the server thread gets to them do not replay visual effects. */
	private static final double STALE_EVENT_SECONDS = 2.0;

	public ArmaController(Path configDir) {
		this.configFile = configDir.resolve("armacraft.json");
		this.config = ArmaConfig.load(configFile);
		this.sessions = new SessionManager(configDir.resolve("armacraft-restore.json"));
	}

	public ArmaConfig config() {
		return config;
	}

	public BridgeClient bridge() {
		return bridge;
	}

	public ArenaRenderer renderer() {
		return renderer;
	}

	public SessionManager sessions() {
		return sessions;
	}

	public Msg.@Nullable Round round() {
		return round;
	}

	/** The (extrapolated) snapshot rendered on the last tick, or null. */
	public Msg.@Nullable State lastRendered() {
		return lastRendered;
	}

	/** Number of death explosions played since start (diagnostics/tests). */
	public int deathFxCount() {
		return deathFx;
	}

	public void saveConfig() {
		config.sanitize();
		config.save(configFile);
	}

	// ---- lifecycle ----

	public void onServerStarted(MinecraftServer server) {
		this.server = server;
		purgeTagged(server);
		// -Darmacraft.autoConnect=false overrides the config (used by the game test run)
		boolean auto = Boolean.parseBoolean(System.getProperty("armacraft.autoConnect", String.valueOf(config.autoConnect)));
		if (auto) {
			connect(config.host, config.port);
		}
	}

	public void onServerStopping(MinecraftServer server) {
		sessions.restoreAll(server, bridge);
		bridge.stop();
		int tracked = trackedEntityCount();
		renderer.clear();
		sessions.clearCams();
		int stray = purgeTagged(server);
		LOG.info("server stopping: removed {} arena entities ({} untracked strays)", tracked + stray, stray);
		this.server = null;
	}

	private int trackedEntityCount() {
		return renderer.cycleCount() + renderer.nameCount() + renderer.wallCount() + renderer.rimCount() + sessions.camCount();
	}

	public void connect(String host, int port) {
		config.host = host;
		config.port = port;
		bridge.start(host, port);
	}

	public void disconnect() {
		bridge.stop();
		int tracked = trackedEntityCount();
		resetArena();
		LOG.info("bridge disconnected by command: removed {} arena entities", tracked);
	}

	/** Anchor, scale or other layout input changed: rebuild everything from scratch next tick. */
	public void markLayoutDirty() {
		dirtyLayout = true;
	}

	/**
	 * Re-read config/armacraft.json without a server restart (/arma reload). Layout is rebuilt next
	 * tick; the bridge reconnects only if host or port changed.
	 */
	public String reloadConfig() {
		ArmaConfig fresh = ArmaConfig.load(configFile);
		boolean endpoint = !fresh.host.equals(config.host) || fresh.port != config.port;
		config = fresh;
		markLayoutDirty();
		if (endpoint && bridge.isRunning()) {
			disconnect();
			connect(fresh.host, fresh.port);
		}
		return "[arma] reloaded " + configFile.getFileName()
			+ (endpoint ? ", reconnecting to " + fresh.host + ":" + fresh.port : "");
	}

	private void resetArena() {
		renderer.clear();
		sessions.clearCams();
		lastRendered = null;
		deaths.reset();
		if (server != null) {
			purgeTagged(server);
		}
	}

	// ---- per tick ----

	public void tick(MinecraftServer server) {
		this.server = server;
		Msg m;
		while ((m = bridge.pollInbound()) != null) {
			handle(server, m);
		}
		if (dirtyLayout) {
			dirtyLayout = false;
			resetArena();
		}
		ServerLevel level = anchorLevel(server);
		ArenaMapping map = mapping();
		if (level != null && renderedIn != null && !renderedIn.equals(level.dimension())) {
			resetArena();
		}
		renderedIn = level == null ? null : level.dimension();

		Msg.State raw = bridge.isConnected() ? bridge.latestState() : null;
		Msg.State state = raw;
		if (raw != null && config.maxExtrapolate > 0) {
			double age = (System.nanoTime() - raw.receivedNanos()) / 1e9;
			state = Extrapolate.forward(raw, Math.max(0, Math.min(config.maxExtrapolate, age)));
		}
		if (level != null) {
			if (raw != null) {
				detectDeaths(level, map, raw);
			}
			renderer.render(level, map, config, bridge.isConnected() ? round : null, state);
			lastRendered = state;
		}
		sessions.tick(server, level, map, config, state, round, bridge);
	}

	private void handle(MinecraftServer server, Msg m) {
		switch (m) {
			case Msg.Connected c -> broadcastOps(server, "[arma] connected to guest " + bridge.host() + ":" + bridge.port());
			case Msg.Disconnected d -> {
				broadcastOps(server, "[arma] guest connection lost (" + d.reason() + "), retrying");
				round = null;
				resetArena();
			}
			case Msg.Hello h -> LOG.info("guest hello: {} {} proto {} rate {}", h.game(), h.version(), h.proto(), h.rate());
			case Msg.Round r -> {
				round = r;
				deaths.reset();
			}
			case Msg.Event e -> handleEvent(server, e);
			case Msg.Joined j -> sessions.onJoined(server, j);
			case Msg.Left l -> sessions.onLeft(server, l);
			case Msg.Error err -> {
				LOG.warn("guest error: {}", err.msg());
				broadcastOps(server, "[arma] guest error: " + err.msg());
			}
			default -> {
			}
		}
	}

	private void handleEvent(MinecraftServer server, Msg.Event e) {
		switch (e.ev()) {
			case "chat" -> server.getPlayerList().broadcastSystemMessage(
				Component.literal("[arma] " + e.str("name") + ": " + e.str("text")).withStyle(ChatFormatting.AQUA), false);
			case "death" -> {
				ServerLevel level = anchorLevel(server);
				long id = e.id();
				// "removed":true = taken out of the game while alive (e.g. player left): no explosion
				boolean removed = Protocol.getBool(e.raw(), "removed", false);
				// stale backlog (server was paused/lagging): mark as exploded but do not replay the effect
				boolean stale = e.ageSeconds(System.nanoTime()) > STALE_EVENT_SECONDS;
				if (level != null && deaths.onDeathEvent(id) && !removed && !stale) {
					explosionFx(level, mapping(), id, e.x(), e.y(), colorOf(id));
				}
			}
			case "spawn" -> {
				deaths.onSpawn(e.id());
				if (sessions.byKey(e.str("bridge_key")) != null || !e.str("name").isEmpty() && isSessionName(e.str("name"))) {
					LOG.info("guest spawn event: id {} name {} at ({}, {})", e.id(), e.str("name"), e.x(), e.y());
				}
			}
			case "turn" -> {
				SessionManager.Session s = sessions.byCycleId(e.id());
				if (s != null) {
					LOG.info("guest turn event for {} (cycle {}): now heading ({}, {})", s.name, e.id(),
						Protocol.getDouble(e.raw(), "dx", 0), Protocol.getDouble(e.raw(), "dy", 0));
				}
			}
			case "round_end" -> {
				String w = e.str("winner");
				server.getPlayerList().broadcastSystemMessage(
					Component.literal("[arma] round over" + (w.isEmpty() ? "" : ", winner: " + w)).withStyle(ChatFormatting.GOLD), false);
			}
			default -> {
			}
		}
	}

	private boolean isSessionName(String name) {
		for (SessionManager.Session s : sessions.all()) {
			if (s.name.equals(name)) {
				return true;
			}
		}
		return false;
	}

	/** Fallback for guests that do not send death events: alive -> dead transitions explode once. */
	private void detectDeaths(ServerLevel level, ArenaMapping map, Msg.State st) {
		Set<Long> present = new HashSet<>();
		for (Msg.Cycle c : st.cycles()) {
			present.add(c.id());
			if (deaths.onSnapshot(c.id(), c.alive())) {
				explosionFx(level, map, c.id(), c.x(), c.y(), DyePalette.packRgb(c.r(), c.g(), c.b()));
			}
		}
		deaths.retain(present);
	}

	private int colorOf(long id) {
		Msg.State st = bridge.latestState();
		if (st != null) {
			for (Msg.Cycle c : st.cycles()) {
				if (c.id() == id) {
					return DyePalette.packRgb(c.r(), c.g(), c.b());
				}
			}
		}
		return 0xFFFFFF;
	}

	public void explosionFx(ServerLevel level, ArenaMapping map, long cycleId, double ax, double ay, int rgb) {
		double x = map.mcX(ax);
		double y = map.mcY() + 0.5;
		double z = map.mcZ(ay);
		deathFx++;
		LOG.info("death fx: cycle {} at grid ({}, {}) -> {} {} {}", cycleId, Math.round(ax * 10) / 10.0, Math.round(ay * 10) / 10.0,
			Math.round(x * 10) / 10.0, Math.round(y * 10) / 10.0, Math.round(z * 10) / 10.0);
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1, 0, 0, 0, 0);
		level.sendParticles(ParticleTypes.EXPLOSION, x, y, z, 6, 1.0, 0.5, 1.0, 0.0);
		level.sendParticles(new DustParticleOptions(rgb, 2.0f), x, y + 0.5, z, 40, 1.5, 1.0, 1.5, 0.0);
		level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 3.0f, 1.0f);
	}

	// ---- hooks from mixins / events ----

	public void onPlayerInput(ServerPlayer player, Input input) {
		sessions.onInput(player, input, bridge);
	}

	public boolean isCameraLocked(ServerPlayer player) {
		return sessions.isLocked(player.getUUID());
	}

	public void onPlayerDisconnect(ServerPlayer player) {
		sessions.leave(player, bridge);
	}

	public void onPlayerJoin(ServerPlayer player) {
		sessions.onPlayerJoin(player);
	}

	public void onChat(ServerPlayer player, String text) {
		if (config.forwardChat && sessions.get(player.getUUID()) != null) {
			bridge.send(Protocol.chat(player.getPlainTextName(), text));
		}
	}

	/** Optional: TNT inside the arena blasts holes in the Armagetron world too. */
	public void onExplosion(ServerLevel level, @Nullable Entity source, double x, double y, double z, float radius) {
		if (!config.forwardTnt || !(source instanceof PrimedTnt) || !bridge.isConnected() || server == null) {
			return;
		}
		ServerLevel anchor = anchorLevel(server);
		if (anchor != level) {
			return;
		}
		ArenaMapping map = mapping();
		if (Math.abs(y - map.mcY()) > 16) {
			return;
		}
		double ax = map.arenaX(x);
		double ay = map.arenaY(z);
		if (!insideBounds(ax, ay)) {
			return;
		}
		// Vanilla blast power ~ block radius / 2; send a radius in grid units.
		bridge.send(Protocol.explode(ax, ay, radius * 2.0 / map.scale()));
	}

	boolean insideBounds(double ax, double ay) {
		Msg.Round r = round;
		double[] b = r == null || r.bounds() == null ? new double[] {-100, -100, 100, 100} : r.bounds();
		return ax >= Math.min(b[0], b[2]) && ax <= Math.max(b[0], b[2]) && ay >= Math.min(b[1], b[3]) && ay <= Math.max(b[1], b[3]);
	}

	// ---- helpers ----

	public ArenaMapping mapping() {
		return new ArenaMapping(config.anchor.x, config.anchor.y, config.anchor.z, config.scale);
	}

	public @Nullable ServerLevel anchorLevel(MinecraftServer server) {
		Identifier id = Identifier.tryParse(config.anchor.dimension);
		if (id == null) {
			return null;
		}
		return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
	}

	/** Remove every armacraft-tagged entity in every level (startup, disconnect, stop). */
	public static int purgeTagged(MinecraftServer server) {
		int n = 0;
		for (ServerLevel level : server.getAllLevels()) {
			List<Entity> doomed = new ArrayList<>();
			for (Entity e : level.getAllEntities()) {
				if (e != null && e.entityTags().contains(EntitySet.TAG)) {
					doomed.add(e);
				}
			}
			for (Entity e : doomed) {
				e.discard();
				n++;
			}
		}
		if (n > 0) {
			LOG.info("purged {} armacraft entities", n);
		}
		return n;
	}

	private static void broadcastOps(MinecraftServer server, String text) {
		LOG.info(text);
		Component c = Component.literal(text).withStyle(ChatFormatting.GRAY);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (server.getPlayerList().isOp(p.nameAndId())) {
				p.sendSystemMessage(c);
			}
		}
	}

	public String status() {
		StringBuilder sb = new StringBuilder();
		sb.append("bridge ").append(bridge.host()).append(':').append(bridge.port()).append(' ');
		sb.append(bridge.isConnected() ? "connected" : bridge.isRunning() ? "connecting" : "stopped");
		if (bridge.lastError() != null && !bridge.isConnected()) {
			sb.append(" (").append(bridge.lastError()).append(')');
		}
		Msg.Hello h = bridge.hello();
		if (h != null) {
			sb.append("\nguest ").append(h.game()).append(' ').append(h.version()).append(" proto ").append(h.proto());
		}
		Msg.State st = lastRendered;
		sb.append("\nround ").append(round == null ? "-" : String.valueOf(round.round()));
		sb.append(", cycles ").append(st == null ? 0 : st.cycles().size());
		sb.append(", walls ").append(st == null ? 0 : st.walls().size());
		sb.append("\nentities: cycles ").append(renderer.cycleCount()).append(", wall pieces ").append(renderer.wallCount())
			.append(", rim pieces ").append(renderer.rimCount());
		sb.append("\nanchor ").append(config.anchor.dimension).append(' ')
			.append(String.format("%.1f %.1f %.1f", config.anchor.x, config.anchor.y, config.anchor.z))
			.append(", scale ").append(config.scale);
		sb.append("\nlines in/out ").append(bridge.linesIn()).append('/').append(bridge.linesOut());
		sb.append("\nmod armacraft ").append(net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(Armacraft.MOD_ID)
			.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?"));
		sb.append("\nplayers: ").append(sessions.all().size());
		for (SessionManager.Session s : sessions.all()) {
			sb.append("\n  ").append(s.name).append(' ').append(s.phase);
		}
		return sb.toString();
	}
}

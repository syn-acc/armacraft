package gl.syn.armacraft.session;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import gl.syn.armacraft.bridge.BridgeClient;
import gl.syn.armacraft.bridge.Msg;
import gl.syn.armacraft.bridge.Protocol;
import gl.syn.armacraft.config.ArmaConfig;
import gl.syn.armacraft.logic.ArenaMapping;
import gl.syn.armacraft.logic.Colors;
import gl.syn.armacraft.logic.InputTracker;
import gl.syn.armacraft.render.EntitySet;
import gl.syn.armacraft.render.Specs;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Minecraft players driving Armagetron cycles.
 *
 * <p>Camera mechanism: the player is put in spectator mode and {@link ServerPlayer#setCamera} is pointed at
 * an invisible block display ("chase cam") that follows behind their cycle with position/rotation
 * interpolation. The vanilla client keeps ticking its LocalPlayer (players are always-ticking) and keeps
 * sending ServerboundPlayerInputPacket while spectating another entity; see the mod report for the source
 * evidence. Shift (which normally exits spectating) is neutralised by PlayerMixin.
 */
public final class SessionManager {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft/session");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int ATTACH_DELAY_TICKS = 3;
	private static final int CAMERA_RESEND_TICKS = 40;
	private static final double ATTACH_MAX_DIST = 48.0;

	public enum Phase { WAITING, RIDING, DEATHCAM }

	/** What to restore when a player leaves. Persisted so a crash cannot strand someone in spectator. */
	public record RestoreInfo(String gamemode, String dimension, double x, double y, double z, float yaw, float pitch) {}

	public static final class Session {
		public final UUID uuid;
		public final String key;
		public String name;
		public final double[] color;
		final RestoreInfo restore;
		public Phase phase = Phase.WAITING;
		final InputTracker input = new InputTracker();
		int joinedEpoch = -1;
		int camAge;
		int sinceTeleport;
		int resend;
		int deathTicks;
		boolean overviewPlaced;
		Specs.@Nullable CamSpec lastCam;
		long cycleId = -1;
		long brakeSentFor = -1;

		Session(UUID uuid, String name, double[] color, RestoreInfo restore) {
			this.uuid = uuid;
			this.key = keyFor(uuid);
			this.name = name;
			this.color = color;
			this.restore = restore;
		}
	}

	private final Map<UUID, Session> sessions = new LinkedHashMap<>();
	private final EntitySet<UUID> cams = new EntitySet<>("cam");
	private final Path restoreFile;
	private final Map<String, RestoreInfo> persisted;

	public SessionManager(Path restoreFile) {
		this.restoreFile = restoreFile;
		this.persisted = loadPersisted(restoreFile);
	}

	public static String keyFor(UUID uuid) {
		return "mc:" + uuid;
	}

	public @Nullable Session get(UUID uuid) {
		return sessions.get(uuid);
	}

	public Collection<Session> all() {
		return sessions.values();
	}

	public @Nullable Session byKey(String key) {
		for (Session s : sessions.values()) {
			if (s.key.equals(key)) {
				return s;
			}
		}
		return null;
	}

	// ---- join / leave ----

	public Session join(ServerPlayer p, BridgeClient bridge) {
		Session existing = sessions.get(p.getUUID());
		if (existing != null) {
			return existing;
		}
		RestoreInfo info = new RestoreInfo(
			p.gameMode.getGameModeForPlayer().getSerializedName(),
			p.level().dimension().identifier().toString(),
			p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()
		);
		// If a stale entry exists (crash), keep the older one: it is the pre-arena state.
		persisted.putIfAbsent(p.getUUID().toString(), info);
		savePersisted();
		Session s = new Session(p.getUUID(), p.getPlainTextName(), Colors.fromUuid(p.getUUID()), persisted.get(p.getUUID().toString()));
		sessions.put(p.getUUID(), s);
		p.setGameMode(GameType.SPECTATOR);
		sendJoin(s, bridge);
		return s;
	}

	/** Leave the arena: send leave, restore gamemode and position. */
	public boolean leave(ServerPlayer p, BridgeClient bridge) {
		Session s = sessions.remove(p.getUUID());
		if (s == null) {
			return false;
		}
		bridge.send(Protocol.leave(s.key));
		cams.remove(s.uuid);
		restore(p, s.restore);
		persisted.remove(p.getUUID().toString());
		savePersisted();
		return true;
	}

	/** Restores everyone (server stopping). */
	public void restoreAll(MinecraftServer server, BridgeClient bridge) {
		for (Session s : new ArrayList<>(sessions.values())) {
			ServerPlayer p = server.getPlayerList().getPlayer(s.uuid);
			if (p != null) {
				leave(p, bridge);
			}
		}
		sessions.clear();
		cams.clear();
	}

	/** Player logged in: undo any restore entry left behind by a crash. */
	public void onPlayerJoin(ServerPlayer p) {
		if (sessions.containsKey(p.getUUID())) {
			return;
		}
		RestoreInfo info = persisted.remove(p.getUUID().toString());
		if (info != null) {
			LOG.info("restoring {} from a stale armacraft session", p.getPlainTextName());
			restore(p, info);
			savePersisted();
		}
	}

	private void restore(ServerPlayer p, RestoreInfo info) {
		if (p.getCamera() != p) {
			p.setCamera(p);
		}
		MinecraftServer server = p.level().getServer();
		ServerLevel level = null;
		Identifier id = Identifier.tryParse(info.dimension());
		if (id != null) {
			level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
		}
		if (level == null) {
			level = server.overworld();
		}
		p.teleportTo(level, info.x(), info.y(), info.z(), Set.of(), info.yaw(), info.pitch(), true);
		GameType mode = GameType.byName(info.gamemode(), GameType.SURVIVAL);
		p.setGameMode(mode);
	}

	private void sendJoin(Session s, BridgeClient bridge) {
		if (bridge.isConnected() && bridge.send(Protocol.join(s.key, s.name, s.color))) {
			s.joinedEpoch = bridge.epoch();
		}
	}

	// ---- replies ----

	public void onJoined(MinecraftServer server, Msg.Joined j) {
		Session s = byKey(j.key());
		if (s != null) {
			if (!j.name().isEmpty()) {
				s.name = j.name();
			}
			LOG.info("guest joined {} as {}", j.key(), s.name);
			tell(server, s, Component.literal("[arma] joined as " + s.name + " - A/D turn, jump/sprint brake").withStyle(ChatFormatting.AQUA));
		}
	}

	/**
	 * A "left" for a key we still hold a session for means the guest removed the player on its own
	 * (our own /arma leave removes the session before the reply arrives). End the session locally.
	 */
	public void onLeft(MinecraftServer server, Msg.Left l) {
		Session s = byKey(l.key());
		if (s == null) {
			return;
		}
		sessions.remove(s.uuid);
		cams.remove(s.uuid);
		ServerPlayer p = server.getPlayerList().getPlayer(s.uuid);
		if (p != null) {
			p.sendSystemMessage(Component.literal("[arma] the guest removed your player").withStyle(ChatFormatting.GRAY));
			restore(p, s.restore);
		}
		persisted.remove(s.uuid.toString());
		savePersisted();
	}

	private static void tell(MinecraftServer server, Session s, Component msg) {
		ServerPlayer p = server.getPlayerList().getPlayer(s.uuid);
		if (p != null) {
			p.sendSystemMessage(msg);
		}
	}

	// ---- input ----

	public void onInput(ServerPlayer p, Input in, BridgeClient bridge) {
		Session s = sessions.get(p.getUUID());
		if (s == null) {
			return;
		}
		for (InputTracker.Action a : s.input.update(in.left(), in.right(), in.jump(), in.sprint())) {
			switch (a) {
				case InputTracker.Turn t -> {
					boolean sent = bridge.send(Protocol.turn(s.key, t.dir()));
					LOG.info("input {}: turn {} ({})", s.name, t.dir(), sent ? "sent" : "not connected");
				}
				case InputTracker.Brake b -> {
					boolean sent = bridge.send(Protocol.brake(s.key, b.on()));
					LOG.info("input {}: brake {} ({})", s.name, b.on(), sent ? "sent" : "not connected");
				}
			}
		}
	}

	/** Session whose current cycle has this guest id, if any. */
	public @Nullable Session byCycleId(long id) {
		for (Session s : sessions.values()) {
			if (s.cycleId == id) {
				return s;
			}
		}
		return null;
	}

	public boolean isLocked(UUID uuid) {
		Session s = sessions.get(uuid);
		return s != null && s.phase != Phase.WAITING;
	}

	// ---- per tick ----

	public void tick(MinecraftServer server, @Nullable ServerLevel level, @Nullable ArenaMapping map, ArmaConfig cfg,
		Msg.@Nullable State state, Msg.@Nullable Round round, BridgeClient bridge) {
		List<UUID> gone = new ArrayList<>();
		for (Session s : sessions.values()) {
			ServerPlayer p = server.getPlayerList().getPlayer(s.uuid);
			if (p == null) {
				gone.add(s.uuid);
				continue;
			}
			if (s.joinedEpoch != bridge.epoch()) {
				sendJoin(s, bridge);
			}
			if (p.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
				p.setGameMode(GameType.SPECTATOR);
			}
			s.sinceTeleport++;
			if (level == null || map == null) {
				continue;
			}
			Msg.Cycle mine = findCycle(state, s.key);
			if (mine != null && mine.alive()) {
				s.cycleId = mine.id();
				s.lastCam = chaseCam(map, cfg, mine);
				if (s.phase != Phase.RIDING) {
					s.phase = Phase.RIDING;
					s.camAge = 0;
					s.overviewPlaced = false;
				}
				if (mine.id() != s.brakeSentFor) {
					// The guest does not carry a held brake over to a new cycle: re-send it once per spawn.
					s.brakeSentFor = mine.id();
					if (s.input.braking() && !mine.brake()) {
						bridge.send(Protocol.brake(s.key, true));
					}
				}
			} else if (s.phase == Phase.RIDING) {
				s.phase = Phase.DEATHCAM;
				s.deathTicks = cfg.deathCamTicks;
				s.brakeSentFor = -1;
			} else if (s.phase == Phase.DEATHCAM && --s.deathTicks <= 0) {
				s.phase = Phase.WAITING;
				s.lastCam = null;
			}
		}
		for (UUID u : gone) {
			sessions.remove(u);
		}
		if (level == null || map == null) {
			cams.clear();
			return;
		}
		cams.sync(level, currentCamSpecs());
		for (Session s : sessions.values()) {
			ServerPlayer p = server.getPlayerList().getPlayer(s.uuid);
			if (p == null) {
				continue;
			}
			if (s.phase == Phase.WAITING) {
				if (p.getCamera() != p) {
					p.setCamera(p);
				}
				if (!s.overviewPlaced) {
					double[] o = overview(map, round);
					p.teleportTo(level, o[0], o[1], o[2], Set.of(), 0f, 90f, true);
					s.overviewPlaced = true;
					s.sinceTeleport = 0;
				}
				continue;
			}
			attachCamera(p, s, level);
		}
	}

	private void attachCamera(ServerPlayer p, Session s, ServerLevel level) {
		Display cam = cams.get(s.uuid);
		Specs.CamSpec spec = s.lastCam;
		if (spec == null) {
			return;
		}
		if (cam == null) {
			// Not spawned yet, usually because its chunk is not loaded. Move the player there to load it.
			s.camAge = 0;
			if (p.level() != level || horizontalDistSqr(p, spec.x(), spec.z()) > ATTACH_MAX_DIST * ATTACH_MAX_DIST) {
				teleportNear(p, level, spec);
				s.sinceTeleport = 0;
			}
			return;
		}
		s.camAge++;
		Entity current = p.getCamera();
		if (current != cam) {
			if (p.level() != level || p.distanceToSqr(cam) > ATTACH_MAX_DIST * ATTACH_MAX_DIST) {
				teleportNear(p, level, spec);
				s.sinceTeleport = 0;
				return;
			}
			// Wait until the chase cam has been tracked (sent to the client) or the set-camera packet is ignored.
			if (s.camAge >= ATTACH_DELAY_TICKS && s.sinceTeleport >= ATTACH_DELAY_TICKS) {
				p.setCamera(cam);
				s.resend = 0;
			}
		} else if (++s.resend % CAMERA_RESEND_TICKS == 0) {
			// Cheap self-heal in case the client dropped or ignored the camera packet.
			p.connection.send(new ClientboundSetCameraPacket(cam));
		}
	}

	private static void teleportNear(ServerPlayer p, ServerLevel level, Specs.CamSpec spec) {
		if (p.getCamera() != p) {
			p.setCamera(p);
		}
		p.teleportTo(level, spec.x(), spec.y(), spec.z(), Set.of(), spec.yaw(), spec.pitch(), true);
	}

	private static double horizontalDistSqr(Entity e, double x, double z) {
		double dx = e.getX() - x;
		double dz = e.getZ() - z;
		return dx * dx + dz * dz;
	}

	private Map<UUID, Specs.CamSpec> currentCamSpecs() {
		Map<UUID, Specs.CamSpec> out = new HashMap<>();
		for (Session s : sessions.values()) {
			if (s.phase != Phase.WAITING && s.lastCam != null) {
				out.put(s.uuid, s.lastCam);
			}
		}
		return out;
	}

	static Specs.CamSpec chaseCam(ArenaMapping map, ArmaConfig cfg, Msg.Cycle c) {
		float yaw = ArenaMapping.yaw(c.dx(), c.dy());
		double[] f = ArenaMapping.facing(yaw);
		double x = map.mcX(c.x()) - f[0] * cfg.camera.back;
		double z = map.mcZ(c.y()) - f[1] * cfg.camera.back;
		double y = map.mcY() + cfg.camera.up;
		return new Specs.CamSpec(x, y, z, yaw, (float) cfg.camera.pitch, Math.max(1, cfg.camera.interpTicks));
	}

	/** Point above the arena centre, high enough to see the whole arena when looking straight down. */
	public static double[] overview(ArenaMapping map, Msg.@Nullable Round round) {
		double minx = -100;
		double miny = -100;
		double maxx = 100;
		double maxy = 100;
		if (round != null && round.bounds() != null) {
			double[] b = round.bounds();
			minx = b[0];
			miny = b[1];
			maxx = b[2];
			maxy = b[3];
		}
		double cx = (minx + maxx) / 2;
		double cy = (miny + maxy) / 2;
		double extent = Math.max(Math.abs(maxx - minx), Math.abs(maxy - miny)) * map.scale();
		double height = Math.min(extent * 0.75 + 8, 300);
		return new double[] {map.mcX(cx), map.mcY() + height, map.mcZ(cy)};
	}

	static Msg.@Nullable Cycle findCycle(Msg.@Nullable State state, String key) {
		if (state == null) {
			return null;
		}
		Msg.Cycle best = null;
		for (Msg.Cycle c : state.cycles()) {
			if (key.equals(c.bridgeKey())) {
				if (c.alive()) {
					return c;
				}
				best = c;
			}
		}
		return best;
	}

	public int camCount() {
		return cams.size();
	}

	public void clearCams() {
		cams.clear();
		for (Session s : sessions.values()) {
			s.phase = Phase.WAITING;
			s.lastCam = null;
			s.overviewPlaced = false;
		}
	}

	// ---- persistence ----

	private static Map<String, RestoreInfo> loadPersisted(Path file) {
		Map<String, RestoreInfo> out = new HashMap<>();
		if (Files.isRegularFile(file)) {
			try {
				Type t = new TypeToken<Map<String, RestoreInfo>>() {}.getType();
				Map<String, RestoreInfo> m = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), t);
				if (m != null) {
					out.putAll(m);
				}
			} catch (IOException | JsonParseException e) {
				LOG.warn("could not read {}: {}", file, e.getMessage());
			}
		}
		return out;
	}

	private void savePersisted() {
		try {
			Files.createDirectories(restoreFile.getParent());
			Files.writeString(restoreFile, GSON.toJson(persisted), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.warn("could not write {}: {}", restoreFile, e.getMessage());
		}
	}
}

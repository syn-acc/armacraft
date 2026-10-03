package gl.syn.armacraft.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import gl.syn.armacraft.client.cam.CameraController;
import gl.syn.armacraft.client.core.ArenaMapping;
import gl.syn.armacraft.client.core.ArenaModel;
import gl.syn.armacraft.client.core.CycleAnim;
import gl.syn.armacraft.client.core.CycleTrack;
import gl.syn.armacraft.client.core.V2;
import gl.syn.armacraft.client.core.WallGeom;
import gl.syn.armacraft.client.hud.ArmaHud;
import gl.syn.armacraft.client.net.DirectTransport;
import gl.syn.armacraft.client.net.RelayTransport;
import gl.syn.armacraft.client.net.Transport;
import gl.syn.armacraft.client.sound.ArmaSounds;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the client side of armacraft: the transport (relay or direct), the arena mirror, per-frame dead
 * reckoning and animation, input, camera, sound and HUD state. Everything runs on the render thread except
 * the transport reader, which only queues lines.
 */
public final class ArenaClient {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft_client");
	private static ArenaClient instance;

	private final ClientConfig cfg;
	private final ArenaModel model = new ArenaModel();
	private final CameraController camera = new CameraController();
	private final ArmaSounds sounds;
	private final ArmaHud hud;

	private Transport transport;
	private String transportKind = "none";
	private RelayTransport relay;
	private boolean relayOffered;
	private boolean inWorld;

	private ArenaMapping mapping;
	private String dimension;
	private boolean relayMapping;

	private boolean joinWanted;
	private boolean joinAcked;
	private boolean brakeHeld;
	private boolean brakeSent;
	private Integer lastOwnAliveId;

	private double lastLocal = Double.NaN;
	private double gameTime = Double.NaN;
	private double lastGameTime = Double.NaN;
	private final Map<Integer, V2> lastDrive = new HashMap<>();
	private final Map<Integer, V2> prevDrive = new HashMap<>();
	private final List<WallGeom.Seg> sensorSegs = new ArrayList<>();
	private final gl.syn.armacraft.client.dev.DevStats stats = new gl.syn.armacraft.client.dev.DevStats();

	public ArenaClient(ClientConfig cfg) {
		this.cfg = cfg;
		this.sounds = new ArmaSounds(this);
		this.hud = new ArmaHud(this);
		applyConfig();
		model.setListener(new Events());
		instance = this;
	}

	public static ArenaClient get() {
		return instance;
	}

	public static double localNow() {
		return Transport.now();
	}

	public void applyConfig() {
		if (!relayMapping) {
			mapping = cfg.mapping();
			dimension = cfg.anchor.dimension;
		}
		model.setMaxExtrapolate(cfg.render.maxExtrapolate);
		camera.chase().configure(cfg.camera.customBack, cfg.camera.customRise, cfg.camera.customPitch, cfg.camera.customZoom,
			cfg.camera.customTurnSpeed, cfg.camera.customTurnSpeed180, cfg.camera.inTurnSpeed);
		camera.chase().setMode("in".equals(cfg.camera.mode) ? gl.syn.armacraft.client.kit.ChaseCam.Mode.IN_CYCLE : gl.syn.armacraft.client.kit.ChaseCam.Mode.CHASE);
	}

	// ------------------------------------------------------------------------------------- accessors

	public ClientConfig config() {
		return cfg;
	}

	public ArenaModel model() {
		return model;
	}

	public CameraController camera() {
		return camera;
	}

	public ArmaSounds sounds() {
		return sounds;
	}

	public ArmaHud hud() {
		return hud;
	}

	public ArenaMapping mapping() {
		return mapping;
	}

	public double gameTime() {
		return gameTime;
	}

	/**
	 * {@code mc:<uuid>} with the uuid the server knows this player by (the relay forces exactly that key). On an
	 * offline-mode server it differs from the launcher profile id, so prefer the in-world player's uuid.
	 */
	public String key() {
		Minecraft mc = Minecraft.getInstance();
		UUID id = mc.player != null ? mc.player.getUUID() : mc.getUser().getProfileId();
		return "mc:" + id;
	}

	public String transportDescription() {
		return transport == null ? "none" : transport.describe();
	}

	/** Connected to a guest, in the configured dimension, and the arena is known. */
	public boolean active() {
		Minecraft mc = Minecraft.getInstance();
		if (transport == null || mc.level == null || !model.hasArena()) {
			return false;
		}
		return dimension == null || dimension.equals(mc.level.dimension().identifier().toString());
	}

	/** The player asked to ride and the arena is live: camera and input belong to Armagetron. */
	public boolean riding() {
		return joinWanted && active();
	}

	public boolean joinWanted() {
		return joinWanted;
	}

	public ArenaModel.Cycle ownCycle() {
		return model.byKey(key());
	}

	public boolean ownAlive() {
		ArenaModel.Cycle c = ownCycle();
		return c != null && c.alive;
	}

	public ArenaModel.Cycle watchedCycle() {
		Integer id = camera.watchedId();
		if (id != null) {
			ArenaModel.Cycle c = model.cycle(id);
			if (c != null) {
				return c;
			}
		}
		ArenaModel.Cycle own = ownCycle();
		return own;
	}

	public boolean isWatched(ArenaModel.Cycle c) {
		Integer id = camera.watchedId();
		return id != null && id == c.id;
	}

	public boolean inCycleCamera() {
		return camera.inCycle();
	}

	public V2 renderPos(ArenaModel.Cycle c) {
		if (!c.alive && c.deathPos != null) {
			return c.deathPos;
		}
		double t = Double.isFinite(gameTime) ? gameTime : c.track.baseT();
		return c.alive ? c.track.position(t) : c.track.basePos();
	}

	public V2 lastDrive(ArenaModel.Cycle c) {
		V2 v = prevDrive.get(c.id);
		return v != null ? v : c.anim.dir;
	}

	public double[] toMc(double x, double y, double z) {
		return new double[] {mapping.mcX(x), mapping.mcY(z), mapping.mcZ(y)};
	}

	/** Armagetron-space listener for sound mixing: the camera eye if overridden, else the Minecraft camera. */
	public double[] soundEye() {
		double[] e = camera.armaEye();
		if (e != null) {
			return e;
		}
		Minecraft mc = Minecraft.getInstance();
		var cam = mc.gameRenderer.mainCamera();
		if (cam == null || mapping == null) {
			return null;
		}
		var p = cam.position();
		double yr = Math.toRadians(cam.yRot());
		return new double[] {mapping.arenaX(p.x), mapping.arenaY(p.z), mapping.arenaZ(p.y), -Math.sin(yr), -Math.cos(yr)};
	}

	public int windowWidth() {
		return Minecraft.getInstance().getWindow().getWidth();
	}

	public int windowHeight() {
		return Minecraft.getInstance().getWindow().getHeight();
	}

	/** Arena bounds in Minecraft space {minX, minY, minZ, maxX, maxY, maxZ} for hiding the server fallback. */
	public double[] mcBounds() {
		double[] b = model.bounds();
		if (b == null || mapping == null) {
			return null;
		}
		double x1 = mapping.mcX(b[0]), x2 = mapping.mcX(b[2]);
		double z1 = mapping.mcZ(b[1]), z2 = mapping.mcZ(b[3]);
		double margin = 2 + 2 * mapping.scale();
		return new double[] {Math.min(x1, x2) - margin, mapping.ay() - 4, Math.min(z1, z2) - margin,
			Math.max(x1, x2) + margin, mapping.mcY(Math.max(cfg.render.rimHeight, 30)) + 8, Math.max(z1, z2) + margin};
	}

	// ------------------------------------------------------------------------------------- transport

	public void onWorldJoin() {
		inWorld = true;
		relayOffered = gl.syn.armacraft.client.net.RelayPresence.PRESENT && gl.syn.armacraft.client.net.RelayBinding.canSend();
		chooseTransport();
	}

	public void onWorldLeave() {
		inWorld = false;
		relayOffered = false;
		if (joinWanted && transport != null) {
			sendLeave();
		}
		joinWanted = false;
		closeTransport();
		relayMapping = false;
		applyConfig();
		camera.reset();
		sounds.stopAll();
	}

	public void onRelayAdvertised() {
		if (!gl.syn.armacraft.client.net.RelayPresence.PRESENT || !gl.syn.armacraft.client.net.RelayBinding.available()) {
			return;
		}
		relayOffered = true;
		if (inWorld && !"relay".equals(transportKind)) {
			chooseTransport();
		}
	}

	public void relayLine(String line) {
		RelayTransport r = relay;
		if (r != null) {
			r.receive(line);
		}
	}

	public void chooseTransport() {
		String mode = cfg.mode;
		String want;
		if ("off".equals(mode)) {
			want = "none";
		} else if ("relay".equals(mode)) {
			want = relayOffered ? "relay" : "none";
		} else if ("direct".equals(mode)) {
			want = "direct";
		} else {
			want = relayOffered ? "relay" : (cfg.direct.enabled && cfg.direct.autoConnect ? "direct" : "none");
		}
		if (want.equals(transportKind) && transport != null) {
			return;
		}
		boolean rejoin = joinWanted;
		if (transport != null && joinWanted) {
			sendLeave();
		}
		closeTransport();
		switch (want) {
			case "relay" -> {
				relay = new RelayTransport();
				transport = relay;
				transportKind = "relay";
				LOG.info("using relay armacraft:bridge");
				if (rejoin) {
					sendJoin();
				}
			}
			case "direct" -> {
				transport = new DirectTransport(cfg.direct.host, cfg.direct.port, t -> {
					// reader thread: just flag; the render thread re-sends join on the next frame
					reconnectPending = true;
				});
				transportKind = "direct";
				LOG.info("using direct guest {}:{}", cfg.direct.host, cfg.direct.port);
			}
			default -> transportKind = "none";
		}
		joinWanted = rejoin;
	}

	/** Force a direct connection (command). */
	public void connectDirect(String host, int port) {
		cfg.direct.host = host;
		cfg.direct.port = port;
		cfg.save();
		boolean rejoin = joinWanted;
		if (transport != null && joinWanted) {
			sendLeave();
		}
		closeTransport();
		transport = new DirectTransport(host, port, t -> reconnectPending = true);
		transportKind = "direct";
		joinWanted = rejoin;
	}

	private volatile boolean reconnectPending;

	private void closeTransport() {
		if (transport != null) {
			transport.close();
		}
		transport = null;
		relay = null;
		transportKind = "none";
		model.clear();
		joinAcked = false;
		brakeSent = false;
		lastDrive.clear();
		prevDrive.clear();
	}

	private void send(JsonObject o) {
		if (transport != null) {
			transport.send(o.toString());
		}
	}

	private void sendJoin() {
		Minecraft mc = Minecraft.getInstance();
		JsonObject o = new JsonObject();
		o.addProperty("type", "join");
		o.addProperty("key", key());
		String name = cfg.name == null || cfg.name.isBlank() ? mc.getUser().getName() : cfg.name;
		o.addProperty("name", name);
		double[] col = cfg.color != null ? cfg.color : colorFor(mc.getUser().getProfileId());
		JsonArray c = new JsonArray();
		for (double v : col) {
			c.add(v);
		}
		o.add("color", c);
		send(o);
	}

	private void sendLeave() {
		JsonObject o = new JsonObject();
		o.addProperty("type", "leave");
		o.addProperty("key", key());
		send(o);
	}

	static double[] colorFor(UUID id) {
		// bright, saturated hue from the uuid (Minecraft-side default; Armagetron players pick their own colour)
		double h = ((id.getMostSignificantBits() >>> 1) % 360) / 360.0;
		double s = 1, v = 1;
		int i = (int) Math.floor(h * 6);
		double f = h * 6 - i, p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
		return switch (i % 6) {
			case 0 -> new double[] {v, t, p};
			case 1 -> new double[] {q, v, p};
			case 2 -> new double[] {p, v, t};
			case 3 -> new double[] {p, q, v};
			case 4 -> new double[] {t, p, v};
			default -> new double[] {v, p, q};
		};
	}

	public void join() {
		if (transport == null) {
			chooseTransport();
		}
		joinWanted = true;
		sendJoin();
	}

	public void leave() {
		if (joinWanted) {
			sendLeave();
		}
		joinWanted = false;
		joinAcked = false;
		camera.reset();
		if (brakeSent) {
			brakeSent = false;
		}
	}

	// ------------------------------------------------------------------------------------- input

	/** A turn key went down (edge triggered, straight from the key event). d = -1 left, +1 right. */
	public void turnKey(int d) {
		ArenaModel.Cycle own = ownCycle();
		JsonObject o = new JsonObject();
		o.addProperty("type", "input");
		o.addProperty("key", key());
		o.addProperty("turn", d < 0 ? "left" : "right");
		send(o);
		CycleTrack.Turn p = null;
		if (own != null && own.alive && cfg.render.predictTurns && Double.isFinite(gameTime) && gameTime > 0) {
			// predict the corner where the guest will actually turn: key-to-guest lead measured from past turns
			p = own.track.predict(d, gameTime + turnLead, cfg.render.cycleDelay);
			pressGameTimes.addLast(gameTime);
			while (pressGameTimes.size() > 8) {
				pressGameTimes.removeFirst();
			}
			if (p != null) {
				sounds.turn(own);
				predictedSoundFor = own.id;
				predictedSoundAt = gameTime;
			}
		}
		stats.keyTurn(localNow(), p);
	}

	private Integer predictedSoundFor;
	private double predictedSoundAt;
	/** Estimated game time between a turn key and the guest's turn (EMA, seconds). */
	private double turnLead = 0.015;
	private final java.util.ArrayDeque<Double> pressGameTimes = new java.util.ArrayDeque<>();

	public void brakeKey(boolean down) {
		brakeHeld = down;
		sendBrake(down);
	}

	private void sendBrake(boolean down) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "input");
		o.addProperty("key", key());
		o.addProperty("brake", down);
		send(o);
		brakeSent = down;
	}

	public void sendChat(String text) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "chat");
		o.addProperty("name", Minecraft.getInstance().getUser().getName());
		o.addProperty("text", text);
		send(o);
	}

	// ------------------------------------------------------------------------------------- per frame

	/** Called at the start of every frame (before the camera is set up). */
	public void onFrame() {
		double now = localNow();
		double dtLocal = Double.isFinite(lastLocal) ? Math.max(0, Math.min(0.25, now - lastLocal)) : 0;
		lastLocal = now;

		if (reconnectPending) {
			reconnectPending = false;
			if (joinWanted) {
				sendJoin();
			}
		}
		if (transport != null) {
			Transport.Line l;
			int n = 0;
			while ((l = transport.inbox().poll()) != null && n++ < 10000) {
				model.accept(l.text(), l.local());
			}
		}
		gameTime = model.clock().gameTime(now);
		double ts = Double.isFinite(gameTime) && Double.isFinite(lastGameTime) ? gameTime - lastGameTime : dtLocal;
		if (!(ts >= 0) || ts > 0.25) {
			ts = dtLocal;
		}
		lastGameTime = gameTime;
		model.tick(ts);

		if (!active()) {
			camera.update(this, ts);
			sounds.tick();
			return;
		}

		// own cycle respawned: a held brake must be re-sent
		ArenaModel.Cycle own = ownCycle();
		Integer ownAliveId = own != null && own.alive ? own.id : null;
		if (ownAliveId != null && !ownAliveId.equals(lastOwnAliveId) && brakeHeld) {
			sendBrake(true);
		}
		lastOwnAliveId = ownAliveId;

		animate(ts);
		if (gl.syn.armacraft.client.dev.DevStats.ENABLED) {
			stats.frame(now, gameTime, own != null ? renderPos(own) : null, own != null ? own.track.headingAt(gameTime) : null,
				own != null ? own.speed : 0, own != null && own.alive, camera.view().name() + (camera.inCycle() ? "_IN" : ""));
			stats.sound(sounds.describe() + " | hidden block_display renders " + hiddenDisplays);
		}
		hud.trackSpeeds();
		camera.update(this, dtLocal);
		sounds.tick();
	}

	private void animate(double ts) {
		// sensor segments: every solid wall piece plus the rim
		sensorSegs.clear();
		sensorSegs.addAll(model.rim());
		for (ArenaModel.Wall w : model.walls()) {
			for (double[] piece : WallGeom.solidPieces(w.gaps())) {
				V2 a = V2.lerp(new V2(w.x1(), w.y1()), new V2(w.x2(), w.y2()), piece[0]);
				V2 b = V2.lerp(new V2(w.x1(), w.y1()), new V2(w.x2(), w.y2()), piece[1]);
				sensorSegs.add(new WallGeom.Seg(a.x(), a.y(), b.x(), b.y()));
			}
		}
		for (ArenaModel.Cycle c : model.cycles()) {
			V2 drive = c.alive ? c.track.headingAt(gameTime) : c.anim.dir;
			V2 last = lastDrive.get(c.id);
			if (last != null && c.alive && (Math.abs(last.x() - drive.x()) > 1e-6 || Math.abs(last.y() - drive.y()) > 1e-6)) {
				int d = CycleTrack.turnSign(last.x(), last.y(), drive.x(), drive.y());
				c.anim.turnImpulse(d);
				prevDrive.put(c.id, last);
			}
			lastDrive.put(c.id, drive);
			double fl = CycleAnim.EXTENSION * 4, fr = CycleAnim.EXTENSION * 4;
			V2 pos = renderPos(c);
			if (c.alive) {
				fl = WallGeom.raycast(pos, drive.turn(1, 1), CycleAnim.EXTENSION * 4, sensorSegs, 1e-3);
				fr = WallGeom.raycast(pos, drive.turn(1, -1), CycleAnim.EXTENSION * 4, sensorSegs, 1e-3);
			}
			double speed = c.alive && gameTime > 0 ? c.speed : 0;
			c.anim.step(ts, drive, speed, c.alive, fl, fr);
			if (c.anim.sparked) {
				sounds.spark(c, gameTime);
			}
		}
	}

	// ------------------------------------------------------------------------------------- guest events

	private static int rgb(ArenaModel.Cycle c) {
		if (c == null) {
			return 0xffffff;
		}
		return ((int) (c.r * 255) << 16) | ((int) (c.g * 255) << 8) | (int) (c.b * 255);
	}

	private final class Events implements ArenaModel.Listener {
		@Override
		public void onRound(int round) {
			hud.newRound();
			lastDrive.clear();
			prevDrive.clear();
			hud.consoleLine(List.of(new ArmaHud.Segment("Go (round " + round + ")!", 0xffffff)));
		}

		@Override
		public void onSpawn(ArenaModel.Cycle c) {
			lastDrive.remove(c.id);
			prevDrive.remove(c.id);
		}

		@Override
		public void onTurn(ArenaModel.Cycle c, int dir) {
			ArenaModel.Cycle own = ownCycle();
			if (own != null && own.id == c.id) {
				V2 b = c.track.basePos();
				stats.ownTurnEvent(localNow(), b.x(), b.y());
				double tt = c.track.baseT();
				while (!pressGameTimes.isEmpty() && tt - pressGameTimes.peekFirst() > 0.3) {
					pressGameTimes.removeFirst();
				}
				if (!pressGameTimes.isEmpty()) {
					double lead = tt - pressGameTimes.removeFirst();
					if (lead >= 0 && lead < 0.3) {
						turnLead = Math.max(0, Math.min(0.1, turnLead * 0.8 + lead * 0.2));
					}
				}
			}
			if (predictedSoundFor != null && predictedSoundFor == c.id && gameTime - predictedSoundAt < 0.4) {
				predictedSoundFor = null;   // already played when the key went down
				return;
			}
			sounds.turn(c);
		}

		@Override
		public void onDeath(ArenaModel.Cycle c, ArenaModel.Cycle killer, double x, double y) {
			sounds.explosion(x, y);
			if (c == null) {
				return;
			}
			// language/english_base.txt: player_free_frag "\1 core dumped \2.", player_free_suicide "\1 committed suicide."
			if (killer == null || killer == c) {
				hud.consoleLine(List.of(new ArmaHud.Segment(c.name, rgb(c)), new ArmaHud.Segment(" committed suicide.", 0xffffff)));
			} else {
				hud.consoleLine(List.of(new ArmaHud.Segment(killer.name, rgb(killer)), new ArmaHud.Segment(" core dumped ", 0xffffff),
					new ArmaHud.Segment(c.name, rgb(c)), new ArmaHud.Segment(".", 0xffffff)));
			}
		}

		@Override
		public void onRoundEnd(String winner) {
			if (winner != null) {
				// gamestate_winner_winner "Winner: "
				hud.centerMessage("Winner: " + winner, 0xffffff);
			}
		}

		@Override
		public void onChat(String name, String text) {
			hud.consoleLine(List.of(new ArmaHud.Segment(name == null ? "" : name, 0xffff7f), new ArmaHud.Segment(": " + text, 0xffffff)));
		}

		@Override
		public void onJoined(String key, String name) {
			if (key != null && key.equals(key())) {
				joinAcked = true;
			}
			// player_entered_game "\1 0x7fff7fentered the game."
			hud.consoleLine(List.of(new ArmaHud.Segment(name == null ? "?" : name, 0xffffff), new ArmaHud.Segment(" entered the game.", 0x7fff7f)));
		}

		@Override
		public void onError(String msg) {
			hud.consoleLine(List.of(new ArmaHud.Segment("guest: " + msg, 0xff7f7f)));
			LOG.warn("guest error: {}", msg);
		}

		@Override
		public void onRelayInfo(JsonObject msg) {
			try {
				JsonArray a = msg.getAsJsonArray("anchor");
				double s = msg.has("scale") ? msg.get("scale").getAsDouble() : cfg.render.scale;
				mapping = new ArenaMapping(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), s);
				dimension = msg.has("dimension") ? msg.get("dimension").getAsString() : dimension;
				relayMapping = true;
				LOG.info("relay mapping {} in {}", mapping, dimension);
			} catch (RuntimeException e) {
				LOG.warn("bad relay line {}", msg);
			}
		}
	}

	private int hiddenDisplays;

	public void countHiddenDisplay() {
		hiddenDisplays++;
	}

	public boolean joinAcked() {
		return joinAcked;
	}

	public String transportKind() {
		return transportKind;
	}

	public boolean relayOffered() {
		return relayOffered;
	}

	/** Change the mapping from a command and persist it. */
	public void setAnchor(double x, double y, double z) {
		cfg.anchor.x = x;
		cfg.anchor.y = y;
		cfg.anchor.z = z;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			cfg.anchor.dimension = mc.level.dimension().identifier().toString();
		}
		cfg.save();
		relayMapping = false;
		applyConfig();
	}

	public void setScale(double s) {
		cfg.render.scale = s;
		cfg.save();
		relayMapping = false;
		applyConfig();
	}
}

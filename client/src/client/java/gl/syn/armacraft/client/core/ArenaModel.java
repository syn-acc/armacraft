package gl.syn.armacraft.client.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side mirror of the guest arena, fed with PROTOCOL.md v1 guest lines. Pure Java (Gson only) so it can
 * be unit tested. All times are guest game time unless named {@code local}.
 */
public final class ArenaModel {

	public interface Listener {
		default void onHello(JsonObject msg) {}

		default void onRound(int round) {}

		default void onSpawn(Cycle c) {}

		default void onTurn(Cycle c, int dir) {}

		default void onDeath(Cycle c, Cycle killer, double x, double y) {}

		default void onRoundEnd(String winner) {}

		default void onChat(String name, String text) {}

		default void onJoined(String key, String name) {}

		default void onLeft(String key) {}

		default void onError(String msg) {}

		/** Host-originated relay line (see RELAY.md): mapping advertised by the Minecraft server. */
		default void onRelayInfo(JsonObject msg) {}
	}

	public static final class Cycle {
		public final int id;
		public String name = "";
		public float r = 1, g = 1, b = 1;
		public String bridgeKey;
		public boolean alive = true;
		public boolean brake;
		public double rubber;
		public double speed;
		public double deathTime = Double.NaN;
		public V2 deathPos;
		public double spawnTime = Double.NaN;
		public final CycleTrack track = new CycleTrack();
		public final CycleAnim anim = new CycleAnim();
		double lastStateT;
		boolean animInit;
		/** Client-side estimate of Armagetron's braking reservoir (CYCLE_BRAKE_DEPLETE/REFILL), 1 = full. */
		public double brakeReservoir = 1;

		Cycle(int id) {
			this.id = id;
		}
	}

	public record Wall(int id, int cycle, double x1, double y1, double x2, double y2, double[][] gaps) {}

	public record Explosion(double x, double y, float r, float g, float b, double t) {}

	private final GameClock clock = new GameClock();
	private final Map<Integer, Cycle> cycles = new LinkedHashMap<>();
	private List<Wall> walls = List.of();
	/** Wall set by id, folded from {@code delta} lines (PROTOCOL.md feature "delta"; the relay forwards them). */
	private final Map<Integer, Wall> deltaWalls = new LinkedHashMap<>();
	private List<WallGeom.Seg> rim = List.of();
	private double[] bounds;
	private int round = -1;
	private final List<Explosion> explosions = new ArrayList<>();
	private double lastStateT = Double.NaN;
	private double roundEndT = Double.NaN;
	private String lastWinner;
	private Listener listener = new Listener() {};
	private boolean hello;
	private double maxExtrapolate = 0.25;
	public double brakeDeplete = 1.0;   // settings.cfg CYCLE_BRAKE_DEPLETE
	public double brakeRefill = 0.1;    // settings.cfg CYCLE_BRAKE_REFILL

	public void setListener(Listener l) {
		this.listener = l == null ? new Listener() {} : l;
	}

	public void setMaxExtrapolate(double v) {
		maxExtrapolate = v;
		for (Cycle c : cycles.values()) {
			c.track.maxExtrapolate = v;
		}
	}

	public GameClock clock() {
		return clock;
	}

	public Collection<Cycle> cycles() {
		return cycles.values();
	}

	public Cycle cycle(int id) {
		return cycles.get(id);
	}

	public Cycle byKey(String key) {
		if (key == null) {
			return null;
		}
		Cycle best = null;
		for (Cycle c : cycles.values()) {
			if (key.equals(c.bridgeKey) && (best == null || c.alive || !best.alive && c.id > best.id)) {
				best = c;
			}
		}
		return best;
	}

	public List<Wall> walls() {
		return walls;
	}

	public List<WallGeom.Seg> rim() {
		return rim;
	}

	public double[] bounds() {
		return bounds;
	}

	public int round() {
		return round;
	}

	public boolean hasArena() {
		return bounds != null;
	}

	public boolean helloSeen() {
		return hello;
	}

	public List<Explosion> explosions() {
		return explosions;
	}

	public double lastStateT() {
		return lastStateT;
	}

	public double roundEndT() {
		return roundEndT;
	}

	public String lastWinner() {
		return lastWinner;
	}

	public void clear() {
		cycles.clear();
		walls = List.of();
		deltaWalls.clear();
		rim = List.of();
		bounds = null;
		round = -1;
		explosions.clear();
		clock.reset();
		lastStateT = Double.NaN;
		roundEndT = Double.NaN;
		hello = false;
	}

	/** Parse and apply one guest line. Malformed lines are ignored (returns false). */
	public boolean accept(String line, double local) {
		JsonObject o;
		try {
			JsonElement e = JsonParser.parseString(line);
			if (!e.isJsonObject()) {
				return false;
			}
			o = e.getAsJsonObject();
		} catch (RuntimeException ex) {
			return false;
		}
		String type = str(o, "type");
		if (type == null) {
			return false;
		}
		try {
			switch (type) {
				case "hello" -> {
					hello = true;
					listener.onHello(o);
				}
				case "round" -> onRound(o, local);
				case "state" -> onState(o, local);
				case "delta" -> onDelta(o, local);
				case "event" -> onEvent(o, local);
				case "joined" -> listener.onJoined(str(o, "key"), str(o, "name"));
				case "left" -> listener.onLeft(str(o, "key"));
				case "error" -> listener.onError(str(o, "msg"));
				case "relay" -> listener.onRelayInfo(o);
				default -> {
					return false;   // unknown types MUST be ignored
				}
			}
		} catch (RuntimeException ex) {
			return false;
		}
		return true;
	}

	private void onRound(JsonObject o, double local) {
		int r = o.has("round") ? o.get("round").getAsInt() : round + 1;
		List<WallGeom.Seg> segs = new ArrayList<>();
		if (o.has("static") && o.get("static").isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray("static")) {
				JsonArray a = e.getAsJsonArray();
				if (a.size() >= 4) {
					segs.add(new WallGeom.Seg(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble()));
				}
			}
		}
		rim = List.copyOf(segs);
		if (o.has("bounds") && o.get("bounds").isJsonArray()) {
			JsonArray b = o.getAsJsonArray("bounds");
			bounds = new double[] {b.get(0).getAsDouble(), b.get(1).getAsDouble(), b.get(2).getAsDouble(), b.get(3).getAsDouble()};
		} else if (!segs.isEmpty()) {
			double[] bb = {1e30, 1e30, -1e30, -1e30};
			for (WallGeom.Seg s : segs) {
				bb[0] = Math.min(bb[0], Math.min(s.x1(), s.x2()));
				bb[1] = Math.min(bb[1], Math.min(s.y1(), s.y2()));
				bb[2] = Math.max(bb[2], Math.max(s.x1(), s.x2()));
				bb[3] = Math.max(bb[3], Math.max(s.y1(), s.y2()));
			}
			bounds = bb;
		}
		if (r != round) {
			cycles.clear();
			walls = List.of();
			deltaWalls.clear();
			explosions.clear();
			roundEndT = Double.NaN;
			lastWinner = null;
		}
		round = r;
		listener.onRound(r);
	}

	private Cycle ensure(int id) {
		return cycles.computeIfAbsent(id, k -> {
			Cycle c = new Cycle(k);
			c.track.maxExtrapolate = maxExtrapolate;
			return c;
		});
	}

	/** Full snapshot: replaces the wall set (and seeds the delta fold, e.g. after a relay keyframe). */
	private void onState(JsonObject o, double local) {
		List<Wall> ws = parseWalls(o);
		deltaWalls.clear();
		for (Wall w : ws) {
			deltaWalls.put(w.id(), w);
		}
		applySnapshot(o, local, ws);
	}

	/**
	 * PROTOCOL.md {@code delta}: complete cycles, only new/changed walls, ids of removed walls. Apply in order: if
	 * {@code key}, clear the wall set; drop {@code removed}; upsert {@code walls}. The result equals the {@code state}
	 * the guest would have sent for that frame.
	 */
	private void onDelta(JsonObject o, double local) {
		if (o.has("key") && o.get("key").getAsBoolean()) {
			deltaWalls.clear();
		}
		if (o.has("removed") && o.get("removed").isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray("removed")) {
				deltaWalls.remove(e.getAsInt());
			}
		}
		for (Wall w : parseWalls(o)) {
			deltaWalls.put(w.id(), w);
		}
		applySnapshot(o, local, List.copyOf(deltaWalls.values()));
	}

	private void applySnapshot(JsonObject o, double local, List<Wall> ws) {
		double t = num(o, "t", Double.NaN);
		if (Double.isFinite(t)) {
			if (Double.isFinite(lastStateT) && t < lastStateT - GameClock.JUMP) {
				// time went backwards without a round message: stale cycles belong to the previous round
				cycles.clear();
				explosions.clear();
			}
			clock.sample(t, local);
			lastStateT = t;
		}
		java.util.Set<Integer> seen = new java.util.HashSet<>();
		if (o.has("cycles") && o.get("cycles").isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray("cycles")) {
				JsonObject c = e.getAsJsonObject();
				int id = c.get("id").getAsInt();
				seen.add(id);
				Cycle cy = ensure(id);
				applyCycle(cy, c, t);
			}
		}
		for (Iterator<Cycle> it = cycles.values().iterator(); it.hasNext(); ) {
			Cycle c = it.next();
			if (!seen.contains(c.id) && Double.isFinite(t) && t - c.lastStateT > 1.0) {
				it.remove();
			}
		}
		walls = ws;
		explosions.removeIf(x -> Double.isFinite(t) && (t - x.t > 4 || t < x.t - 1));
	}

	private static List<Wall> parseWalls(JsonObject o) {
		List<Wall> ws = new ArrayList<>();
		if (o.has("walls") && o.get("walls").isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray("walls")) {
				JsonObject w = e.getAsJsonObject();
				double[][] gaps = null;
				if (w.has("gaps") && w.get("gaps").isJsonArray()) {
					JsonArray ga = w.getAsJsonArray("gaps");
					gaps = new double[ga.size()][];
					for (int i = 0; i < ga.size(); i++) {
						JsonArray g = ga.get(i).getAsJsonArray();
						gaps[i] = new double[] {g.get(0).getAsDouble(), g.get(1).getAsDouble()};
					}
				}
				ws.add(new Wall(w.has("id") ? w.get("id").getAsInt() : 0, w.get("cycle").getAsInt(),
					w.get("x1").getAsDouble(), w.get("y1").getAsDouble(), w.get("x2").getAsDouble(), w.get("y2").getAsDouble(), gaps));
			}
		}
		return ws;
	}

	private void applyCycle(Cycle cy, JsonObject c, double t) {
		if (c.has("name")) {
			cy.name = c.get("name").getAsString();
		}
		if (c.has("color") && c.get("color").isJsonArray()) {
			JsonArray col = c.getAsJsonArray("color");
			cy.r = (float) col.get(0).getAsDouble();
			cy.g = (float) col.get(1).getAsDouble();
			cy.b = (float) col.get(2).getAsDouble();
		}
		JsonElement bk = c.get("bridge_key");
		cy.bridgeKey = bk != null && !bk.isJsonNull() ? bk.getAsString() : null;
		boolean wasAlive = cy.alive;
		cy.alive = !c.has("alive") || c.get("alive").getAsBoolean();
		cy.brake = c.has("brake") && c.get("brake").getAsBoolean();
		cy.rubber = num(c, "rubber", 0);
		cy.speed = num(c, "speed", 0);
		double x = num(c, "x", 0), y = num(c, "y", 0);
		double dx = num(c, "dx", 0), dy = num(c, "dy", 1);
		if (cy.alive || !cy.track.valid()) {
			cy.track.snapshot(x, y, dx, dy, cy.speed, Double.isFinite(t) ? t : 0);
		}
		if (!cy.animInit) {
			cy.anim.reset(new V2(dx, dy));
			cy.animInit = true;
			cy.spawnTime = t;
		}
		if (wasAlive && !cy.alive && Double.isNaN(cy.deathTime)) {
			cy.deathTime = t;
			cy.deathPos = new V2(x, y);
		}
		if (cy.alive) {
			cy.deathTime = Double.NaN;
			cy.deathPos = null;
		}
		cy.lastStateT = Double.isFinite(t) ? t : cy.lastStateT;
	}

	private void onEvent(JsonObject o, double local) {
		String ev = str(o, "ev");
		if (ev == null) {
			return;
		}
		double t = num(o, "t", Double.NaN);
		if (Double.isFinite(t) && !ev.equals("round_end")) {
			clock.sample(t, local);
		}
		switch (ev) {
			case "spawn" -> {
				int id = o.get("id").getAsInt();
				Cycle c = ensure(id);
				if (o.has("name")) {
					c.name = o.get("name").getAsString();
				}
				double dx = num(o, "dx", 0), dy = num(o, "dy", 1);
				c.alive = true;
				c.deathTime = Double.NaN;
				c.deathPos = null;
				c.brakeReservoir = 1;
				c.track.spawn(num(o, "x", 0), num(o, "y", 0), dx, dy, c.track.speed(), Double.isFinite(t) ? t : 0);
				c.anim.reset(new V2(dx, dy));
				c.animInit = true;
				c.spawnTime = t;
				listener.onSpawn(c);
			}
			case "turn" -> {
				Cycle c = cycles.get(o.get("id").getAsInt());
				if (c != null) {
					int d = c.track.turn(num(o, "x", 0), num(o, "y", 0), num(o, "dx", 0), num(o, "dy", 0), Double.isFinite(t) ? t : c.track.baseT());
					listener.onTurn(c, d);
				}
			}
			case "death" -> {
				Cycle c = cycles.get(o.get("id").getAsInt());
				double x = num(o, "x", 0), y = num(o, "y", 0);
				Cycle killer = o.has("killer") && !o.get("killer").isJsonNull() ? cycles.get(o.get("killer").getAsInt()) : null;
				if (c != null) {
					c.alive = false;
					c.deathTime = Double.isFinite(t) ? t : lastStateT;
					c.deathPos = new V2(x, y);
					c.track.dropPredictions();
					explosions.add(new Explosion(x, y, c.r, c.g, c.b, c.deathTime));
				} else {
					explosions.add(new Explosion(x, y, 1, 1, 1, Double.isFinite(t) ? t : lastStateT));
				}
				listener.onDeath(c, killer, x, y);
			}
			case "round_end" -> {
				JsonElement w = o.get("winner");
				lastWinner = w != null && !w.isJsonNull() ? w.getAsString() : null;
				roundEndT = Double.isFinite(t) ? t : lastStateT;
				listener.onRoundEnd(lastWinner);
			}
			case "chat" -> listener.onChat(str(o, "name"), str(o, "text"));
			default -> {
			}
		}
	}

	/** Advance client-side estimates (brake reservoir) by {@code dt} seconds. */
	public void tick(double dt) {
		for (Cycle c : cycles.values()) {
			if (!c.alive) {
				continue;
			}
			if (c.brake && c.brakeReservoir > 0) {
				c.brakeReservoir = Math.max(0, c.brakeReservoir - brakeDeplete * dt);
			} else if (!c.brake) {
				c.brakeReservoir = Math.min(1, c.brakeReservoir + brakeRefill * dt);
			}
		}
	}

	private static String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	private static double num(JsonObject o, String k, double def) {
		JsonElement e = o.get(k);
		if (e == null || e.isJsonNull()) {
			return def;
		}
		try {
			return e.getAsDouble();
		} catch (RuntimeException ex) {
			return def;
		}
	}
}

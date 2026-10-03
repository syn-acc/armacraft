import gl.syn.armacraft.bridge.Msg;
import gl.syn.armacraft.bridge.Protocol;
import gl.syn.armacraft.logic.ArenaMapping;
import gl.syn.armacraft.logic.DyePalette;
import gl.syn.armacraft.logic.Extrapolate;
import gl.syn.armacraft.logic.Reconcile;
import gl.syn.armacraft.logic.WallKeys;
import gl.syn.armacraft.logic.WallLayout;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-thread cost of the host's per-tick render path, minus Minecraft itself.
 *
 * <p>Compiled against the host's pure classes (bridge.Msg/Protocol, logic.*) straight from host/src (see
 * run_java_bench.py). {@code ArenaRenderer.render} and {@code EntitySet.sync} use Minecraft types, so they are
 * mirrored here line by line with stand-ins: a BlockState becomes an interned Object, a Display entity
 * becomes "the previous spec". Mirrors the host as of 2026-10-02 00:08 BST (WallKeys-based keys). Not
 * included: Display setters, entity creation/discard and vanilla entity tracking. The first two are
 * O(changed); vanilla tracking is O(entities) (see docs/perf.md).
 *
 * <p>Pipelines per snapshot:
 * <ul>
 *   <li>{@code current}: Extrapolate.forward + mirror of ArenaRenderer.render + 4x EntitySet.sync (specs for
 *   every wall piece rebuilt each tick, colour matched per wall, rim rebuilt, Reconcile.diff per set).</li>
 *   <li>{@code memo}: same output, but a per-wall-key memo (wall record + colour -> piece specs), a per-colour
 *   dye cache, rim built once per round and a generation sweep instead of Reconcile.diff. This is what
 *   docs/perf-patches/0002-host-render-memo.patch does.</li>
 *   <li>{@code memo_delta}: the memo pipeline fed the way BridgeClient feeds it with patch 0004, i.e. unchanged
 *   walls keep their record instances between snapshots.</li>
 * </ul>
 * {@code current}/{@code memo} ticks alternate between two independently parsed copies of the same snapshot
 * with different extrapolation ages, so (like the live case with full snapshots) every tick sees fresh
 * objects but only head walls / cycles actually change. Also printed: Protocol.parse of the full line and of
 * the same frame as a delta would carry it (all cycles, head walls only).
 *
 * <p>Usage: {@code java -cp gson.jar:classes HostTickBench states.jsonl [batchMillis]}
 */
public final class HostTickBench {

	static volatile long sink;

	// ---- config defaults (ArmaConfig) ----
	static final double WALL_HEIGHT = 3.0, WALL_THICKNESS = 0.2, MAX_PIECE_BLOCKS = 32.0;
	static final int INTERP = 3;
	static final float VIEW_RANGE = 6.0f;
	static final String WALL_MATERIAL = "glass";

	// ---- stand-ins for Specs records (same fields, BlockState -> Object) ----
	record WallSpec(double x, double y, double z, WallLayout.Transform t, Object block, int glowRgb, int interp, float viewRange) {}

	record CycleSpec(double x, double y, double z, float yaw, Object block, int glowRgb, int interp, float viewRange) {}

	record NameSpec(double x, double y, double z, String text, int rgb, int interp, float viewRange) {}

	/** EntitySet stand-in: key -> last applied spec. Counts creates/updates/removes like the real one would. */
	static final class Set0<K> {
		final Map<K, Object> tracked = new HashMap<>();
		long creates, updates, removes;

		/** mirror of EntitySet.rekeyPrefix (copies the key set). */
		@SuppressWarnings("unchecked")
		void rekeyPrefix(String from, String to) {
			for (K k : new ArrayList<>(tracked.keySet())) {
				if (k instanceof String s && s.startsWith(from)) {
					K nk = (K) (to + s.substring(from.length()));
					Object v = tracked.remove(k);
					if (!tracked.containsKey(nk)) {
						tracked.put(nk, v);
					}
				}
			}
		}

		void syncCurrent(Map<K, ?> desired) {
			Reconcile.Diff<K> diff = Reconcile.diff(tracked.keySet(), desired.keySet());
			for (K k : diff.remove()) {
				tracked.remove(k);
				removes++;
			}
			for (Map.Entry<K, ?> e : desired.entrySet()) {
				Object prev = tracked.get(e.getKey());
				if (prev == null) {
					tracked.put(e.getKey(), e.getValue());
					creates++;
				} else if (!prev.equals(e.getValue())) {
					tracked.put(e.getKey(), e.getValue());
					updates++;
				}
			}
		}
	}

	/** Generation-sweep EntitySet (no Reconcile.diff, identity fast path). */
	static final class Set1<K> {
		static final class T {
			Object spec;
			int gen;
		}

		final Map<K, T> tracked = new HashMap<>();
		int gen;
		long creates, updates, removes;

		void begin() {
			gen++;
		}

		void want(K k, Object spec) {
			T t = tracked.get(k);
			if (t == null) {
				t = new T();
				t.spec = spec;
				tracked.put(k, t);
				creates++;
			} else if (t.spec != spec && !t.spec.equals(spec)) {
				t.spec = spec;
				updates++;
			}
			t.gen = gen;
		}

		@SuppressWarnings("unchecked")
		void rekeyPrefix(String from, String to) {
			for (K k : new ArrayList<>(tracked.keySet())) {
				if (k instanceof String s && s.startsWith(from)) {
					K nk = (K) (to + s.substring(from.length()));
					T v = tracked.remove(k);
					if (!tracked.containsKey(nk)) {
						tracked.put(nk, v);
					}
				}
			}
		}

		void end() {
			for (Iterator<T> it = tracked.values().iterator(); it.hasNext(); ) {
				if (it.next().gen != gen) {
					it.remove();
					removes++;
				}
			}
		}
	}

	final ArenaMapping map = new ArenaMapping(0.5, 100, 0.5, 1.0);
	final Map<String, Object> blockCache = new HashMap<>();

	Object dyeBlock(String dye, String material) {
		boolean concrete = "concrete".equalsIgnoreCase(material);
		return blockCache.computeIfAbsent(dye + (concrete ? "_concrete" : "_stained_glass"), id -> new Object());
	}

	WallSpec wallSpec(WallLayout.Piece p, Object block, int glow) {
		double sx = map.mcX(p.sx());
		double sz = map.mcZ(p.sy());
		double ex = map.mcX(p.ex());
		double ez = map.mcZ(p.ey());
		WallLayout.Transform t = WallLayout.transform(ex - sx, ez - sz, WALL_THICKNESS, WALL_HEIGHT);
		return new WallSpec(sx, map.mcY(), sz, t, block, glow, INTERP, VIEW_RANGE);
	}

	// ------------------------------------------------------------------ current (mirror of ArenaRenderer)
	final Set0<Long> cycles0 = new Set0<>(), names0 = new Set0<>();
	final Set0<String> walls0 = new Set0<>(), rim0 = new Set0<>();
	Map<Long, double[]> prevHeads0 = Map.of(), prevHeads1 = Map.of();

	void renderCurrent(Msg.State state, Msg.Round round) {
		// rimSpecs(): rebuilt every tick
		Map<String, WallSpec> rim = new LinkedHashMap<>();
		Object rimBlock = dyeBlock("light_gray", "concrete");
		List<Msg.Seg> segs = round.statics();
		for (int i = 0; i < segs.size(); i++) {
			Msg.Seg s = segs.get(i);
			for (WallLayout.Piece p : WallLayout.pieces("r" + round.round() + "." + i, s.x1(), s.y1(), s.x2(), s.y2(), List.of(), MAX_PIECE_BLOCKS / map.scale())) {
				rim.put(p.key(), wallSpec(p, rimBlock, -1));
			}
		}
		rim0.syncCurrent(rim);
		Map<Long, CycleSpec> cycleSpecs = new LinkedHashMap<>();
		Map<Long, NameSpec> nameSpecs = new LinkedHashMap<>();
		Map<Long, Msg.Cycle> byId = new HashMap<>();
		for (Msg.Cycle c : state.cycles()) {
			byId.put(c.id(), c);
			if (!c.alive()) {
				continue;
			}
			Object block = dyeBlock(DyePalette.closest(c.r(), c.g(), c.b()).name(), "concrete");
			cycleSpecs.put(c.id(), new CycleSpec(map.mcX(c.x()), map.mcY(), map.mcZ(c.y()), ArenaMapping.yaw(c.dx(), c.dy()), block,
				DyePalette.packRgb(c.r(), c.g(), c.b()), INTERP, VIEW_RANGE));
			if (!c.name().isEmpty()) {
				nameSpecs.put(c.id(), new NameSpec(map.mcX(c.x()), map.mcY() + 1.6, map.mcZ(c.y()), c.name(),
					DyePalette.packRgb(c.r(), c.g(), c.b()), INTERP, VIEW_RANGE));
			}
		}
		Map<String, WallSpec> wallSpecs = new LinkedHashMap<>();
		WallKeys.Result keys = WallKeys.assign(state.walls(), prevHeads0);
		prevHeads0 = keys.heads();
		keys.rekeys().forEach(walls0::rekeyPrefix);
		for (int wi = 0; wi < state.walls().size(); wi++) {
			Msg.Wall w = state.walls().get(wi);
			Msg.Cycle owner = byId.get(w.cycle());
			double r = owner != null ? owner.r() : 1;
			double g = owner != null ? owner.g() : 1;
			double b = owner != null ? owner.b() : 1;
			Object block = dyeBlock(DyePalette.closest(r, g, b).name(), WALL_MATERIAL);
			int glow = DyePalette.packRgb(r, g, b);
			for (WallLayout.Piece p : WallLayout.pieces(keys.keys().get(wi), w.x1(), w.y1(), w.x2(), w.y2(), w.gaps(), MAX_PIECE_BLOCKS / map.scale())) {
				wallSpecs.put(p.key(), wallSpec(p, block, glow));
			}
		}
		cycles0.syncCurrent(cycleSpecs);
		names0.syncCurrent(nameSpecs);
		walls0.syncCurrent(wallSpecs);
	}

	// ------------------------------------------------------------------ memo (proposed)
	static final class WallMemo {
		Msg.Wall wall;
		int rgb;
		final List<String> keys = new ArrayList<>(1);
		final List<WallSpec> specs = new ArrayList<>(1);
		int gen;
	}

	final Set1<Long> cycles1 = new Set1<>(), names1 = new Set1<>();
	final Set1<String> walls1 = new Set1<>(), rim1 = new Set1<>();
	final Map<String, WallMemo> wallMemo = new HashMap<>();
	final Map<Integer, Object> blockByRgb = new HashMap<>();
	long memoHits, memoMisses;
	long rimRound = Long.MIN_VALUE;
	final List<String> rimKeys = new ArrayList<>();
	final List<WallSpec> rimSpecs = new ArrayList<>();
	int memoGen;

	Object blockFor(double r, double g, double b, String material) {
		int rgb = DyePalette.packRgb(r, g, b);
		return blockByRgb.computeIfAbsent(rgb, k -> dyeBlock(DyePalette.closest(r, g, b).name(), material));
	}

	void renderMemo(Msg.State state, Msg.Round round) {
		if (round.round() != rimRound) {
			rimRound = round.round();
			rimKeys.clear();
			rimSpecs.clear();
			Object rimBlock = dyeBlock("light_gray", "concrete");
			List<Msg.Seg> segs = round.statics();
			for (int i = 0; i < segs.size(); i++) {
				Msg.Seg s = segs.get(i);
				for (WallLayout.Piece p : WallLayout.pieces("r" + round.round() + "." + i, s.x1(), s.y1(), s.x2(), s.y2(), List.of(), MAX_PIECE_BLOCKS / map.scale())) {
					rimKeys.add(p.key());
					rimSpecs.add(wallSpec(p, rimBlock, -1));
				}
			}
		}
		rim1.begin();
		for (int i = 0; i < rimKeys.size(); i++) {
			rim1.want(rimKeys.get(i), rimSpecs.get(i));
		}
		rim1.end();

		Map<Long, Msg.Cycle> byId = new HashMap<>(state.cycles().size() * 2);
		cycles1.begin();
		names1.begin();
		for (Msg.Cycle c : state.cycles()) {
			byId.put(c.id(), c);
			if (!c.alive()) {
				continue;
			}
			int rgb = DyePalette.packRgb(c.r(), c.g(), c.b());
			cycles1.want(c.id(), new CycleSpec(map.mcX(c.x()), map.mcY(), map.mcZ(c.y()), ArenaMapping.yaw(c.dx(), c.dy()),
				blockFor(c.r(), c.g(), c.b(), "concrete"), rgb, INTERP, VIEW_RANGE));
			if (!c.name().isEmpty()) {
				names1.want(c.id(), new NameSpec(map.mcX(c.x()), map.mcY() + 1.6, map.mcZ(c.y()), c.name(), rgb, INTERP, VIEW_RANGE));
			}
		}
		cycles1.end();
		names1.end();

		memoGen++;
		walls1.begin();
		WallKeys.Result keys = WallKeys.assign(state.walls(), prevHeads1);
		prevHeads1 = keys.heads();
		keys.rekeys().forEach(walls1::rekeyPrefix);
		for (int wi = 0; wi < state.walls().size(); wi++) {
			Msg.Wall w = state.walls().get(wi);
			String key = keys.keys().get(wi);
			Msg.Cycle owner = byId.get(w.cycle());
			double r = owner != null ? owner.r() : 1;
			double g = owner != null ? owner.g() : 1;
			double b = owner != null ? owner.b() : 1;
			int rgb = DyePalette.packRgb(r, g, b);
			WallMemo m = wallMemo.get(key);
			if (m == null || m.rgb != rgb || (m.wall != w && !m.wall.equals(w))) {
				if (m == null) {
					m = new WallMemo();
					wallMemo.put(key, m);
				}
				m.wall = w;
				m.rgb = rgb;
				m.keys.clear();
				m.specs.clear();
				Object block = blockFor(r, g, b, WALL_MATERIAL);
				for (WallLayout.Piece p : WallLayout.pieces(key, w.x1(), w.y1(), w.x2(), w.y2(), w.gaps(), MAX_PIECE_BLOCKS / map.scale())) {
					m.keys.add(p.key());
					m.specs.add(wallSpec(p, block, rgb));
				}
				memoMisses++;
			} else {
				memoHits++;
			}
			m.gen = memoGen;
			for (int i = 0; i < m.keys.size(); i++) {
				walls1.want(m.keys.get(i), m.specs.get(i));
			}
		}
		walls1.end();
		wallMemo.values().removeIf(m -> m.gen != memoGen);
	}

	// ------------------------------------------------------------------ driver

	public static void main(String[] args) throws Exception {
		List<String> lines = new ArrayList<>();
		for (String l : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
			if (l.startsWith("{\"type\":\"state\"")) {
				lines.add(l);
			}
		}
		long batchNanos = (args.length > 1 ? Long.parseLong(args[1]) : 40) * 1_000_000L;
		// the guest's default square map at SIZE_FACTOR 0: bounds [0,0,500,500], 4 rim segments
		Msg.Round round = new Msg.Round(1, 0, List.of(
			new Msg.Seg(0, 0, 500, 0), new Msg.Seg(500, 0, 500, 500),
			new Msg.Seg(500, 500, 0, 500), new Msg.Seg(0, 500, 0, 0)), new double[] {0, 0, 500, 500});

		com.sun.management.ThreadMXBean tmx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		System.out.println("bytes\twalls\talive\tpieces\textrap_us\tcurrent_us\tcurrent_alloc_kb\tmemo_us\tmemo_alloc_kb\tmemo_delta_us\tupdates_per_tick\tparse_us\tdelta_bytes\tdelta_parse_us");

		// warmup across all inputs
		long warmEnd = System.nanoTime() + 4_000_000_000L;
		while (System.nanoTime() < warmEnd) {
			for (String s : lines) {
				Msg.State[] st = {(Msg.State) Protocol.parse(s), (Msg.State) Protocol.parse(s)};
				HostTickBench b = new HostTickBench();
				for (int i = 0; i < 20; i++) {
					Msg.State x = Extrapolate.forward(st[i & 1], 0.005 + 0.015 * (i % 3));
					b.renderCurrent(x, round);
					b.renderMemo(x, round);
				}
			}
		}

		for (String s : lines) {
			Msg.State a = (Msg.State) Protocol.parse(s);
			Msg.State c = (Msg.State) Protocol.parse(s);
			Msg.State[] st = {a, c};
			int alive = 0;
			for (Msg.Cycle cy : a.cycles()) {
				alive += cy.alive() ? 1 : 0;
			}
			HostTickBench cur = new HostTickBench();
			HostTickBench memo = new HostTickBench();
			// steady state first (initial creates are a one-off)
			for (int i = 0; i < 4; i++) {
				Msg.State x = Extrapolate.forward(st[i & 1], 0.005 + 0.015 * (i % 3));
				cur.renderCurrent(x, round);
				memo.renderMemo(x, round);
			}
			long u0 = cur.walls0.updates + cur.cycles0.updates + cur.names0.updates;
			int[] tick = {0};
			double[] ex = time(() -> {
				Msg.State x = Extrapolate.forward(st[tick[0] & 1], 0.005 + 0.015 * (tick[0]++ % 3));
				return x.walls().size();
			}, batchNanos, tmx);
			tick[0] = 0;
			double[] tc = time(() -> {
				Msg.State x = Extrapolate.forward(st[tick[0] & 1], 0.005 + 0.015 * (tick[0]++ % 3));
				cur.renderCurrent(x, round);
				return cur.walls0.tracked.size();
			}, batchNanos, tmx);
			long ticksCur = tick[0];
			long u1 = cur.walls0.updates + cur.cycles0.updates + cur.names0.updates;
			tick[0] = 0;
			double[] tm = time(() -> {
				Msg.State x = Extrapolate.forward(st[tick[0] & 1], 0.005 + 0.015 * (tick[0]++ % 3));
				memo.renderMemo(x, round);
				return memo.walls1.tracked.size();
			}, batchNanos, tmx);
			if (memo.walls1.tracked.size() != cur.walls0.tracked.size()) {
				throw new IllegalStateException("memo pipeline diverged: " + memo.walls1.tracked.size() + " vs " + cur.walls0.tracked.size());
			}
			// with protocol deltas the host keeps unchanged Wall records across snapshots, so only head
			// walls are new objects each tick: one parsed copy + Extrapolate (which re-creates head walls only)
			HostTickBench memoDelta = new HostTickBench();
			for (int i = 0; i < 4; i++) {
				memoDelta.renderMemo(Extrapolate.forward(a, 0.005 + 0.015 * (i % 3)), round);
			}
			tick[0] = 0;
			double[] td = time(() -> {
				Msg.State x = Extrapolate.forward(a, 0.005 + 0.015 * (tick[0]++ % 3));
				memoDelta.renderMemo(x, round);
				return memoDelta.walls1.tracked.size();
			}, batchNanos, tmx);
			double[] tp = time(() -> System.identityHashCode(Protocol.parse(s)), batchNanos, tmx);
			// the same frame as a delta would carry it: all cycles, only head walls
			String delta = deltaLine(s);
			double[] tdp = time(() -> System.identityHashCode(Protocol.parse(delta)), batchNanos, tmx);
			System.out.printf("%d\t%d\t%d\t%d\t%.1f\t%.1f\t%.1f\t%.1f\t%.1f\t%.1f\t%.1f\t%.1f\t%d\t%.1f%n",
				s.getBytes(StandardCharsets.UTF_8).length + 1, a.walls().size(), alive, cur.walls0.tracked.size(),
				ex[0], tc[0], tc[1], tm[0], tm[1], td[0], (u1 - u0) / (double) Math.max(1, ticksCur), tp[0],
				delta.getBytes(StandardCharsets.UTF_8).length + 1, tdp[0]);
		}
	}

	/**
	 * The same frame as a protocol delta would carry it: all cycles, only head walls (end within 0.75 of
	 * their alive cycle), empty "removed". Kept as type "state" so today's Protocol.parse can time it.
	 */
	static String deltaLine(String state) {
		com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(state).getAsJsonObject();
		com.google.gson.JsonArray cyc = o.getAsJsonArray("cycles");
		com.google.gson.JsonArray keep = new com.google.gson.JsonArray();
		for (com.google.gson.JsonElement we : o.getAsJsonArray("walls")) {
			com.google.gson.JsonObject w = we.getAsJsonObject();
			for (com.google.gson.JsonElement ce : cyc) {
				com.google.gson.JsonObject c = ce.getAsJsonObject();
				if (c.get("alive").getAsBoolean() && c.get("id").getAsLong() == w.get("cycle").getAsLong()
					&& Math.hypot(w.get("x2").getAsDouble() - c.get("x").getAsDouble(), w.get("y2").getAsDouble() - c.get("y").getAsDouble()) <= 0.75) {
					keep.add(w);
					break;
				}
			}
		}
		o.add("walls", keep);
		o.add("removed", new com.google.gson.JsonArray());
		return o.toString();
	}

	interface Op {
		long run();
	}

	/** {median microseconds per op over 7 batches, allocated KB per op} */
	static double[] time(Op op, long batchNanos, com.sun.management.ThreadMXBean tmx) {
		int n = 1;
		while (true) {
			long t0 = System.nanoTime();
			for (int i = 0; i < n; i++) {
				sink += op.run();
			}
			if (System.nanoTime() - t0 >= batchNanos || n >= 1 << 20) {
				break;
			}
			n *= 2;
		}
		double[] per = new double[7];
		long tid = Thread.currentThread().getId();
		long a0 = tmx.getThreadAllocatedBytes(tid);
		long ops = 0;
		for (int b = 0; b < per.length; b++) {
			long t0 = System.nanoTime();
			for (int i = 0; i < n; i++) {
				sink += op.run();
			}
			per[b] = (System.nanoTime() - t0) / 1e3 / n;
			ops += n;
		}
		long a1 = tmx.getThreadAllocatedBytes(tid);
		Arrays.sort(per);
		return new double[] {per[per.length / 2], (a1 - a0) / 1024.0 / ops};
	}
}

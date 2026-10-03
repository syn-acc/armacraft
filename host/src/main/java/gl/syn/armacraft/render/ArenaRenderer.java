package gl.syn.armacraft.render;

import gl.syn.armacraft.bridge.Msg;
import gl.syn.armacraft.config.ArmaConfig;
import gl.syn.armacraft.logic.ArenaMapping;
import gl.syn.armacraft.logic.DyePalette;
import gl.syn.armacraft.logic.WallKeys;
import gl.syn.armacraft.logic.WallLayout;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns protocol snapshots into display entities in one level. All methods run on the server thread.
 *
 * <p>Per tick the work is O(walls) cheap comparisons plus O(changed) spec building: each player wall's
 * piece specs are memoised per reconciliation key (see {@link WallKeys}) against the wall record they were
 * built from (plus the owner colour), and reused as the same instances while the wall is unchanged, so
 * {@link EntitySet#want} short-circuits on identity. Only head walls (and walls with new holes) rebuild.
 * Rim specs are built once per round.
 */
public final class ArenaRenderer {

	public static final int RIM_RGB = 0xC0C0C0;

	private final EntitySet<Long> cycles = new EntitySet<>("cycle");
	private final EntitySet<Long> names = new EntitySet<>("name");
	private final EntitySet<String> walls = new EntitySet<>("wall");
	private final EntitySet<String> rim = new EntitySet<>("rim");
	private final Map<String, BlockState> blockCache = new HashMap<>();
	/** packed 0xRRGGBB -> closest dye name (DyePalette.closest is ~17 trig-heavy conversions). */
	private final Map<Integer, String> dyeByRgb = new HashMap<>();
	/** Start points of each cycle's unnumbered (id 0) head wall in the previous snapshot. */
	private Map<Long, double[]> prevHeads = Map.of();

	/** Everything a wall spec depends on besides the wall itself; a change rebuilds all memos. */
	private record Layout(ArenaMapping map, double wallHeight, double wallThickness, double maxPieceBlocks, String wallMaterial,
		int interpTicks, float viewRange) {
		static Layout of(ArenaMapping map, ArmaConfig cfg) {
			return new Layout(map, cfg.wallHeight, cfg.wallThickness, cfg.maxPieceBlocks, cfg.wallMaterial, cfg.interpTicks, cfg.viewRange);
		}
	}

	private static final class WallMemo {
		Msg.Wall wall;
		int rgb;
		int gen;
		final List<String> keys = new ArrayList<>(2);
		final List<Specs.WallSpec> specs = new ArrayList<>(2);
	}

	private final Map<String, WallMemo> wallMemo = new HashMap<>();
	private int memoGen;
	private @Nullable Layout layout;
	private Msg.@Nullable Round rimRound;
	private final Map<String, Specs.WallSpec> rimCache = new LinkedHashMap<>();

	public void render(ServerLevel level, ArenaMapping map, ArmaConfig cfg, Msg.@Nullable Round round, Msg.@Nullable State state) {
		Layout lay = Layout.of(map, cfg);
		if (!lay.equals(layout)) {
			layout = lay;
			wallMemo.clear();
			rimRound = null;
		}
		if (round != rimRound) {
			rimRound = round;
			rimCache.clear();
			rimCache.putAll(rimSpecs(map, cfg, round));
		}
		rim.sync(level, rimCache);
		cycles.begin(level);
		names.begin(level);
		walls.begin(level);
		if (state != null) {
			Map<Long, Msg.Cycle> byId = new HashMap<>(state.cycles().size() * 2);
			for (Msg.Cycle c : state.cycles()) {
				byId.put(c.id(), c);
				if (!c.alive()) {
					continue;
				}
				cycles.want(c.id(), cycleSpec(map, cfg, c));
				if (cfg.nameTags && !c.name().isEmpty()) {
					names.want(c.id(), new Specs.NameSpec(map.mcX(c.x()), map.mcY() + 1.6, map.mcZ(c.y()), c.name(),
						DyePalette.packRgb(c.r(), c.g(), c.b()), cfg.interpTicks, cfg.viewRange));
				}
			}
			WallKeys.Result keys = WallKeys.assign(state.walls(), prevHeads);
			prevHeads = keys.heads();
			keys.rekeys().forEach(walls::rekeyPrefix);
			int gen = ++memoGen;
			for (int wi = 0; wi < state.walls().size(); wi++) {
				Msg.Wall w = state.walls().get(wi);
				WallMemo m = wallMemo(map, cfg, keys.keys().get(wi), w, byId.get(w.cycle()));
				m.gen = gen;
				for (int i = 0; i < m.keys.size(); i++) {
					walls.want(m.keys.get(i), m.specs.get(i));
				}
			}
			wallMemo.values().removeIf(m -> m.gen != gen);
		} else {
			wallMemo.clear();
		}
		cycles.end();
		names.end();
		walls.end();
	}

	/** The memo for this wall key, rebuilt only when the wall record or its owner colour changed. */
	private WallMemo wallMemo(ArenaMapping map, ArmaConfig cfg, String key, Msg.Wall w, Msg.@Nullable Cycle owner) {
		double r = owner != null ? owner.r() : 1;
		double g = owner != null ? owner.g() : 1;
		double b = owner != null ? owner.b() : 1;
		int glow = DyePalette.packRgb(r, g, b);
		WallMemo m = wallMemo.get(key);
		if (m != null && m.rgb == glow && (m.wall == w || m.wall.equals(w))) {
			return m;
		}
		if (m == null) {
			m = new WallMemo();
			wallMemo.put(key, m);
		}
		m.wall = w;
		m.rgb = glow;
		m.keys.clear();
		m.specs.clear();
		BlockState block = dyeBlock(dyeName(glow, r, g, b), cfg.wallMaterial);
		for (WallLayout.Piece p : WallLayout.pieces(key, w.x1(), w.y1(), w.x2(), w.y2(), w.gaps(), cfg.maxPieceBlocks / map.scale())) {
			m.keys.add(p.key());
			m.specs.add(wallSpec(map, cfg, p, block, glow, cfg.wallHeight));
		}
		return m;
	}

	private String dyeName(int rgb, double r, double g, double b) {
		return dyeByRgb.computeIfAbsent(rgb, k -> DyePalette.closest(r, g, b).name());
	}

	Map<String, Specs.WallSpec> rimSpecs(ArenaMapping map, ArmaConfig cfg, Msg.@Nullable Round round) {
		Map<String, Specs.WallSpec> out = new LinkedHashMap<>();
		if (round == null) {
			return out;
		}
		BlockState block = Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY).defaultBlockState();
		List<Msg.Seg> segs = round.statics();
		for (int i = 0; i < segs.size(); i++) {
			Msg.Seg s = segs.get(i);
			for (WallLayout.Piece p : WallLayout.pieces("r" + round.round() + "." + i, s.x1(), s.y1(), s.x2(), s.y2(), List.of(), cfg.maxPieceBlocks / map.scale())) {
				out.put(p.key(), wallSpec(map, cfg, p, block, -1, cfg.wallHeight));
			}
		}
		return out;
	}

	Specs.CycleSpec cycleSpec(ArenaMapping map, ArmaConfig cfg, Msg.Cycle c) {
		int rgb = DyePalette.packRgb(c.r(), c.g(), c.b());
		BlockState block = dyeBlock(dyeName(rgb, c.r(), c.g(), c.b()), "concrete");
		return new Specs.CycleSpec(map.mcX(c.x()), map.mcY(), map.mcZ(c.y()), ArenaMapping.yaw(c.dx(), c.dy()), block,
			rgb, cfg.interpTicks, cfg.viewRange);
	}

	static Specs.WallSpec wallSpec(ArenaMapping map, ArmaConfig cfg, WallLayout.Piece p, BlockState block, int glow, double height) {
		double sx = map.mcX(p.sx());
		double sz = map.mcZ(p.sy());
		double ex = map.mcX(p.ex());
		double ez = map.mcZ(p.ey());
		WallLayout.Transform t = WallLayout.transform(ex - sx, ez - sz, cfg.wallThickness, height);
		return new Specs.WallSpec(sx, map.mcY(), sz, t, block, glow, cfg.interpTicks, cfg.viewRange);
	}

	/** {@code minecraft:<dye>_stained_glass} or {@code minecraft:<dye>_concrete}. */
	public BlockState dyeBlock(String dye, String material) {
		boolean concrete = "concrete".equalsIgnoreCase(material);
		return blockCache.computeIfAbsent(dye + (concrete ? "_concrete" : "_stained_glass"), id -> {
			DyeColor color = DyeColor.byName(dye, DyeColor.WHITE);
			Block b = (concrete ? Blocks.CONCRETE : Blocks.STAINED_GLASS).pick(color);
			return b.defaultBlockState();
		});
	}

	public int cycleCount() {
		return cycles.size();
	}

	public int wallCount() {
		return walls.size();
	}

	public int rimCount() {
		return rim.size();
	}

	public int nameCount() {
		return names.size();
	}

	public String diagnostics() {
		return String.join("; ", cycles.diagnostics(), names.diagnostics(), walls.diagnostics(), rim.diagnostics());
	}

	public void clear() {
		cycles.clear();
		names.clear();
		walls.clear();
		rim.clear();
		wallMemo.clear();
		rimRound = null;
		rimCache.clear();
		prevHeads = Map.of();
	}
}

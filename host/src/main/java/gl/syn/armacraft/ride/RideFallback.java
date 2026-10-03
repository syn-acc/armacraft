package gl.syn.armacraft.ride;

import gl.syn.armacraft.logic.DyePalette;
import gl.syn.armacraft.logic.WallLayout;
import gl.syn.armacraft.render.EntitySet;
import gl.syn.armacraft.render.Specs;
import gl.syn.armacraft.ride.core.Heading;
import gl.syn.armacraft.ride.core.Trail;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What players without the mod see of a rider: a glowing concrete stand-in bike (block display, interpolated) and
 * the trail as thin stained-glass block displays, one per trail piece, built with the arena renderer's
 * {@link EntitySet} / {@link Specs} / {@link WallLayout}. Tagged {@code armacraft.ridefx}: never saved, purged with
 * the arena entities, and not sent to modded clients ({@code EntityRideFxMixin}), which draw their own.
 */
public final class RideFallback {

	public static final String KIND = "ridefx";
	public static final String TAG = EntitySet.TAG + "." + KIND;
	private static final int MAX_PIECES = 400;
	private static final double THICKNESS = 0.08;

	private final Map<ResourceKey<Level>, EntitySet<String>> sets = new HashMap<>();
	private final Map<Integer, BlockState[]> blocks = new HashMap<>();

	public void tick(MinecraftServer server, RideService ride) {
		RideConfig cfg = ride.config();
		Map<ResourceKey<Level>, List<RideService.Rider>> byDim = new HashMap<>();
		if (cfg.vanillaFallback) {
			for (RideService.Rider r : ride.riders()) {
				if (r.dimension != null) {
					byDim.computeIfAbsent(r.dimension, k -> new ArrayList<>()).add(r);
				}
			}
			for (RideService.Rider r : ride.fadingTrails()) {
				if (r.dimension != null) {
					byDim.computeIfAbsent(r.dimension, k -> new ArrayList<>()).add(r);
				}
			}
		}
		for (ResourceKey<Level> dim : byDim.keySet()) {
			sets.computeIfAbsent(dim, k -> new EntitySet<>(KIND));
		}
		for (Map.Entry<ResourceKey<Level>, EntitySet<String>> e : sets.entrySet()) {
			ServerLevel level = server.getLevel(e.getKey());
			EntitySet<String> set = e.getValue();
			set.begin(level);
			if (level != null) {
				for (RideService.Rider r : byDim.getOrDefault(e.getKey(), List.of())) {
					want(set, cfg, r, r.riding);
				}
			}
			set.end();
		}
	}

	private void want(EntitySet<String> set, RideConfig cfg, RideService.Rider r, boolean riding) {
		BlockState[] b = blocks.computeIfAbsent(r.rgb, RideFallback::blocksFor);
		String prefix = r.key.toString();
		if (riding) {
			// CycleSpec's body spans 1.5 behind to 0.5 ahead of its position: shift it so it centres on the rider
			int h = r.heading;
			set.want(prefix + ":bike", new Specs.CycleSpec(r.x + Heading.dx(h) * 0.5, r.y, r.z + Heading.dz(h) * 0.5, Heading.yaw(h), b[0], r.rgb, 2, 4f));
		}
		Trail t = r.trail;
		int n = t.size();
		int first = Math.max(0, n - 1 - MAX_PIECES);
		for (int k = first; k < n; k++) {
			double ax = t.x(k);
			double ay = t.y(k);
			double az = t.z(k);
			double bx;
			double by;
			double bz;
			if (k + 1 < n) {
				bx = t.x(k + 1);
				by = t.y(k + 1);
				bz = t.z(k + 1);
			} else if (riding) {
				bx = r.x;
				by = r.y;
				bz = r.z;
			} else {
				break;
			}
			double dx = bx - ax;
			double dz = bz - az;
			if (dx * dx + dz * dz < 0.01) {
				continue;
			}
			WallLayout.Transform tr = WallLayout.transform(dx, dz, THICKNESS, cfg.trailHeight);
			set.want(prefix + ":" + t.index(k), new Specs.WallSpec(ax, Math.min(ay, by), az, tr, b[1], r.rgb, 2, 4f));
		}
	}

	private static BlockState[] blocksFor(int rgb) {
		DyePalette.Dye d = DyePalette.closest(((rgb >> 16) & 0xFF) / 255.0, ((rgb >> 8) & 0xFF) / 255.0, (rgb & 0xFF) / 255.0);
		DyeColor c = DyeColor.byName(d.name(), DyeColor.LIGHT_BLUE);
		return new BlockState[] {Blocks.CONCRETE.pick(c).defaultBlockState(), Blocks.STAINED_GLASS.pick(c).defaultBlockState()};
	}

	public int size() {
		int n = 0;
		for (EntitySet<String> s : sets.values()) {
			n += s.size();
		}
		return n;
	}

	public void clear() {
		for (EntitySet<String> s : sets.values()) {
			s.clear();
		}
		sets.clear();
	}
}

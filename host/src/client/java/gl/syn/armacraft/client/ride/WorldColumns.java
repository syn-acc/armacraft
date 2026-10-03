package gl.syn.armacraft.client.ride;

import gl.syn.armacraft.ride.core.Column;
import gl.syn.armacraft.ride.core.TerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link TerrainProbe.ColumnSource} over a Minecraft level: block collision shapes (with the rider's collision
 * context, the same one {@code Entity.move} uses) between {@code feetY - 4} and {@code feetY + 4}, clipped to the
 * bike's lateral band when one is set. Still water tops become {@link Column.Kind#WATER}, lava
 * {@link Column.Kind#LAVA}. Unloaded chunks give {@link Column#UNLOADED}. One instance per tick (it caches).
 */
final class WorldColumns implements TerrainProbe.ColumnSource {

	private final Level level;
	private final CollisionContext ctx;
	private final int y0;
	private final int y1;
	private final Map<Long, Column> cache = new HashMap<>();
	private Direction.Axis bandAxis;
	private double bandMin;
	private double bandMax;
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

	WorldColumns(Level level, Entity rider, double feetY) {
		this.level = level;
		this.ctx = CollisionContext.of(rider);
		int fy = (int) Math.floor(feetY);
		this.y0 = fy - 4;
		this.y1 = fy + 4;
	}

	/** Clip shapes on {@code axis} to [min, max] (the bike's width across its heading); null for none. */
	void band(Direction.Axis axis, double min, double max) {
		if (axis != bandAxis || min != bandMin || max != bandMax) {
			cache.clear();
		}
		this.bandAxis = axis;
		this.bandMin = min;
		this.bandMax = max;
	}

	@Override
	public Column column(int bx, int bz) {
		long key = ((long) bx << 32) ^ (bz & 0xffffffffL);
		Column c = cache.get(key);
		if (c == null) {
			c = load(bx, bz);
			cache.put(key, c);
		}
		return c;
	}

	private Column load(int bx, int bz) {
		if (!level.hasChunk(bx >> 4, bz >> 4)) {
			return Column.UNLOADED;
		}
		Column c = new Column();
		for (int y = y0; y <= y1; y++) {
			pos.set(bx, y, bz);
			BlockState state = level.getBlockState(pos);
			FluidState fluid = state.getFluidState();
			// liquids are classified from the fluid (the rider's own water shape depends on where it stands)
			VoxelShape shape = state.isAir() || state.getBlock() instanceof LiquidBlock ? null : state.getCollisionShape(level, pos, ctx);
			boolean solid = false;
			if (shape != null && !shape.isEmpty()) {
				for (AABB box : shape.toAabbs()) {
					if (bandAxis != null) {
						double lo = (bandAxis == Direction.Axis.X ? bx + box.minX : bz + box.minZ);
						double hi = (bandAxis == Direction.Axis.X ? bx + box.maxX : bz + box.maxZ);
						if (hi <= bandMin + 1e-6 || lo >= bandMax - 1e-6) {
							continue;
						}
					}
					c.solid(y + box.minY, y + box.maxY);
					solid = true;
				}
			}
			if (!solid && !fluid.isEmpty()) {
				if (fluid.is(FluidTags.WATER)) {
					if (fluid.isSource() && !level.getFluidState(pos.above()).is(FluidTags.WATER)) {
						c.add(y, y + 1, Column.Kind.WATER);
					}
				} else if (fluid.is(FluidTags.LAVA)) {
					c.add(y, y + Math.max(0.1, fluid.getHeight(level, pos)), Column.Kind.LAVA);
				}
			}
		}
		return c;
	}
}

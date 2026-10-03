package gl.syn.armacraft.ride.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * Freezes water under and ahead of a rider into vanilla {@code frosted_ice}, mirroring 26.3 Frost Walker's
 * {@code ReplaceDisk} effect: only {@code minecraft:water} whose fluid is still source water, with an
 * {@code #minecraft:air} block above and no building-blocking entity inside, is replaced via
 * {@code setBlockAndUpdate} (so {@code FrostedIceBlock.onPlace} schedules its 60-120 tick melt), followed by a
 * {@code block_place} game event. Differences from vanilla: no on-ground / not-a-passenger condition (a bike on the
 * water surface is not "on ground" server-side), the strip ahead, and a +-{@code frostVerticalReach} layer search
 * so a water surface one step up or down is found.
 */
public final class FrostWalker {

	private FrostWalker() {}

	/** @return blocks frozen */
	public static int apply(ServerLevel level, ServerPlayer rider, int hx, int hz, double speed, RideServerConfig cfg) {
		double lookahead = FrostArea.lookaheadBlocks(speed, cfg.frostLookaheadSeconds, cfg.frostLookaheadMaxBlocks);
		int layer = FrostArea.supportLayer(rider.getY());
		int frozen = 0;
		BlockState ice = Blocks.FROSTED_ICE.defaultBlockState();
		for (FrostArea.Col c : FrostArea.columns(rider.getX(), rider.getZ(), hx, hz, lookahead, cfg.frostRadius, cfg.frostStripHalfWidth)) {
			for (int y = layer - cfg.frostVerticalReach; y <= layer + cfg.frostVerticalReach; y++) {
				BlockPos pos = new BlockPos(c.x(), y, c.z());
				if (!level.isLoaded(pos)) {
					break; // never force-load a chunk from here
				}
				if (canFreeze(level, pos) && level.setBlockAndUpdate(pos, ice)) {
					level.gameEvent(rider, GameEvent.BLOCK_PLACE, pos);
					frozen++;
				}
			}
		}
		return frozen;
	}

	/** Vanilla Frost Walker's predicate (matchesTag(UP, AIR), matchesBlocks(WATER), matchesFluids(WATER), unobstructed). */
	public static boolean canFreeze(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!state.is(Blocks.WATER) || !state.getFluidState().isSourceOfType(Fluids.WATER)) {
			return false;
		}
		if (!level.getBlockState(pos.above()).is(BlockTags.AIR)) {
			return false;
		}
		return level.isUnobstructed(null, Shapes.block().move(pos));
	}
}

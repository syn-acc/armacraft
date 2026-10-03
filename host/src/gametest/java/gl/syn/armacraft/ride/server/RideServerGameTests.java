package gl.syn.armacraft.ride.server;

import gl.syn.armacraft.ride.RideRegistry;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless checks of the server-side ride features on a real 26.3 GameTest server. Riders are real ServerPlayers
 * ({@link CapturingPlayer}) registered through {@link RideRegistry} exactly as the ride handler would. Coordinates
 * are relative to each test's 8x8x8 structure.
 */
public class RideServerGameTests {

	private static RideRegistry.Status status(GameTestHelper helper, Direction heading, double speed) {
		return new RideRegistry.Status(heading, speed, false, helper.getLevel().getGameTime());
	}

	private static void floor(GameTestHelper helper, int y, Block block) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, y, z, block);
			}
		}
	}

	private static Vec3 abs(GameTestHelper helper, double x, double y, double z) {
		return helper.absoluteVec(new Vec3(x, y, z));
	}

	private static void assertIce(GameTestHelper helper, int x, int y, int z, String why) {
		BlockState s = helper.getBlockState(new BlockPos(x, y, z));
		helper.assertTrue(s.is(Blocks.FROSTED_ICE), "expected frosted ice at " + x + "," + y + "," + z + " (" + why + "), got " + s);
	}

	private static void assertWaterSource(GameTestHelper helper, int x, int y, int z, String why) {
		BlockState s = helper.getBlockState(new BlockPos(x, y, z));
		helper.assertTrue(s.is(Blocks.WATER) && s.getFluidState().isSourceOfType(Fluids.WATER),
			"expected water source at " + x + "," + y + "," + z + " (" + why + "), got " + s);
	}

	// ------------------------------------------------------------------ frost walker

	/** The run directory's config file may be from an earlier build: tests run with the current defaults. */
	private static void frostDefaults() {
		RideServerConfig c = RideServer.get().config();
		RideServerConfig d = new RideServerConfig();
		c.frostWalker = d.frostWalker;
		c.frostRadius = d.frostRadius;
		c.frostLookaheadSeconds = d.frostLookaheadSeconds;
		c.frostLookaheadMaxBlocks = d.frostLookaheadMaxBlocks;
		c.frostStripHalfWidth = d.frostStripHalfWidth;
		c.frostVerticalReach = d.frostVerticalReach;
	}

	/**
	 * The defaults freeze a thin lane right at the bike: the rider's own column (radius 1) and a one-row strip ahead,
	 * 0.1 s of travel long and at most 2 blocks. Rider over a pool at block (2, z), heading east. Row 3: the lane at
	 * 30 blocks/s (3 blocks of travel, capped at 2). Row 5: at 8 blocks/s (1 block). Row 6: water under stone and a
	 * waterlogged slab stay. Row 0: flowing water stays.
	 */
	@GameTest(maxTicks = 100)
	public void frostWalkerFreezesAThinLaneAtTheBike(GameTestHelper helper) {
		floor(helper, 0, Blocks.STONE);
		floor(helper, 1, Blocks.WATER);
		helper.setBlock(3, 2, 6, Blocks.STONE); // water at (3,1,6) has no air above
		helper.setBlock(4, 1, 6, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.WATERLOGGED, true));
		helper.setBlock(3, 1, 0, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1)); // flowing, not a source
		CapturingPlayer[] rider = new CapturingPlayer[1];
		helper.startSequence()
			.thenExecute(() -> {
				frostDefaults();
				rider[0] = CapturingPlayer.join(helper, "rs_frost");
				rider[0].teleport(abs(helper, 2.5, 2.0, 3.5));
				RideRegistry.start(rider[0].player, status(helper, Direction.EAST, 30.0));
			})
			.thenWaitUntil(() -> assertIce(helper, 2, 1, 3, "under the rider"))
			.thenExecute(() -> {
				assertIce(helper, 3, 1, 3, "strip, 1 ahead");
				assertIce(helper, 4, 1, 3, "strip, 2 ahead");
				assertWaterSource(helper, 5, 1, 3, "past the 2-block cap (30 blocks/s x 0.1 s = 3)");
				assertWaterSource(helper, 1, 1, 3, "behind: its centre is a full block from the rider");
				for (int x = 1; x <= 5; x++) {
					assertWaterSource(helper, x, 1, 2, "one row wide");
					assertWaterSource(helper, x, 1, 4, "one row wide");
				}
				// melts behind you: vanilla FrostedIceBlock.onPlace scheduled its tick
				ServerLevel level = helper.getLevel();
				for (BlockPos rel : new BlockPos[] {new BlockPos(2, 1, 3), new BlockPos(4, 1, 3)}) {
					helper.assertTrue(level.getBlockTicks().hasScheduledTick(helper.absolutePos(rel), Blocks.FROSTED_ICE),
						"no melt tick scheduled at " + rel);
				}
				RideServer s = RideServer.get();
				helper.assertTrue(s != null && s.frozenTotal() > 0, "RideServer not installed or nothing counted");
				rider[0].teleport(abs(helper, 2.5, 2.0, 5.5));
				RideRegistry.update(rider[0].player, status(helper, Direction.EAST, 8.0));
			})
			.thenWaitUntil(() -> assertIce(helper, 2, 1, 5, "under the rider, row 5"))
			.thenExecute(() -> {
				assertIce(helper, 3, 1, 5, "strip, 1 ahead (8 blocks/s x 0.1 s = 0.8)");
				assertWaterSource(helper, 4, 1, 5, "past the strip at 8 blocks/s");
				rider[0].teleport(abs(helper, 2.5, 2.0, 6.5));
				RideRegistry.update(rider[0].player, status(helper, Direction.EAST, 30.0));
			})
			.thenWaitUntil(() -> assertIce(helper, 2, 1, 6, "under the rider, row 6"))
			.thenExecute(() -> {
				assertWaterSource(helper, 3, 1, 6, "no air above");
				BlockState slab = helper.getBlockState(new BlockPos(4, 1, 6));
				helper.assertTrue(slab.is(Blocks.STONE_SLAB) && slab.getValue(SlabBlock.WATERLOGGED), "waterlogged slab replaced: " + slab);
				rider[0].teleport(abs(helper, 2.5, 2.0, 0.5));
				RideRegistry.update(rider[0].player, status(helper, Direction.EAST, 30.0));
			})
			.thenWaitUntil(() -> assertIce(helper, 2, 1, 0, "under the rider, row 0"))
			.thenExecute(() -> {
				BlockState flowing = helper.getBlockState(new BlockPos(3, 1, 0));
				helper.assertFalse(flowing.is(Blocks.FROSTED_ICE), "flowing water was frozen");
				assertIce(helper, 4, 1, 0, "source water past the flowing block");
			})
			.thenExecute(() -> {
				RideRegistry.stop(rider[0].player, RideRegistry.StopReason.DISMOUNT, rider[0].player.position());
				rider[0].leave();
			})
			.thenSucceed();
	}

	// ------------------------------------------------------------------ fall damage

	/** Land a 10-block fall through the real move-packet path (doCheckFallDamage -> fallOn -> causeFallDamage). */
	private static void fallTen(CapturingPlayer p, GameTestHelper helper, double x, double z) {
		p.teleport(abs(helper, x, 11.0, z));
		for (int y = 10; y >= 1; y--) {
			p.serverTick();
			p.move(abs(helper, x, y, z), y == 1);
		}
	}

	@GameTest(maxTicks = 300, skyAccess = true) // no barrier roof: the 10-block falls start above the structure
	public void noFallDamageWhileRidingAndDuringGrace(GameTestHelper helper) {
		floor(helper, 0, Blocks.STONE);
		CapturingPlayer[] p = new CapturingPlayer[1];
		float[] before = new float[1];
		helper.startSequence()
			.thenExecute(() -> {
				p[0] = CapturingPlayer.join(helper, "rs_fall");
				ServerPlayer pl = p[0].player;
				DamageSource fall = pl.damageSources().fall();
				// baseline: not riding, direct call and a real landing
				helper.assertTrue(pl.causeFallDamage(10, 1.0F, fall) && pl.getHealth() < 20, "baseline fall damage missing");
				pl.setHealth(20);
				pl.damageCooldownTime = 0;
				fallTen(p[0], helper, 2.5, 2.5);
				helper.assertTrue(pl.getHealth() == 13, "baseline landing: expected 20 - 7, got " + pl.getHealth());
				pl.setHealth(20);
				pl.damageCooldownTime = 0; // so the riding landings below would hurt if not cancelled
				// riding: direct call and a real landing
				RideRegistry.start(pl, status(helper, Direction.EAST, 0));
				helper.assertFalse(pl.causeFallDamage(10, 1.0F, fall), "fall damage while riding");
				fallTen(p[0], helper, 2.5, 2.5);
				helper.assertTrue(pl.getHealth() == 20, "landing while riding hurt: " + pl.getHealth());
				// crash mid-air: grace
				RideRegistry.stop(pl, RideRegistry.StopReason.CRASH, pl.position());
				helper.assertFalse(pl.causeFallDamage(10, 1.0F, fall), "fall damage right after CRASH");
			})
			.thenIdle(30)
			.thenExecute(() -> {
				ServerPlayer pl = p[0].player;
				fallTen(p[0], helper, 5.5, 5.5);
				helper.assertTrue(pl.getHealth() == 20, "landing 30 ticks after CRASH hurt: " + pl.getHealth());
			})
			.thenIdle(15) // grace (40 ticks) is over
			.thenExecute(() -> {
				ServerPlayer pl = p[0].player;
				before[0] = pl.getHealth();
				fallTen(p[0], helper, 2.5, 5.5);
				helper.assertTrue(pl.getHealth() < before[0], "fall damage did not come back after the grace period");
				pl.setHealth(20);
				// DISMOUNT also starts a grace
				RideRegistry.start(pl, status(helper, Direction.EAST, 0));
				RideRegistry.stop(pl, RideRegistry.StopReason.DISMOUNT, pl.position());
				helper.assertFalse(pl.causeFallDamage(10, 1.0F, pl.damageSources().fall()), "fall damage right after DISMOUNT");
				// DISCONNECT does not
				RideRegistry.start(pl, status(helper, Direction.EAST, 0));
				RideRegistry.stop(pl, RideRegistry.StopReason.DISCONNECT, pl.position());
			})
			.thenIdle(25)
			.thenExecute(() -> {
				ServerPlayer pl = p[0].player;
				helper.assertTrue(pl.causeFallDamage(10, 1.0F, pl.damageSources().fall()), "DISCONNECT stop left a grace");
				p[0].leave();
			})
			.thenSucceed();
	}

	// ------------------------------------------------------------------ crash effect

	@GameTest(maxTicks = 100)
	public void crashStopPlaysExplosionForEveryoneWithoutDamage(GameTestHelper helper) {
		floor(helper, 0, Blocks.STONE);
		helper.setBlock(3, 1, 4, Blocks.GLASS);
		helper.setBlock(5, 1, 4, Blocks.GLASS);
		helper.setBlock(4, 1, 3, Blocks.GLASS);
		helper.setBlock(4, 2, 5, Blocks.GLASS);
		CapturingPlayer[] ps = new CapturingPlayer[2];
		Pig[] pig = new Pig[1];
		helper.startSequence()
			.thenExecute(() -> {
				ps[0] = CapturingPlayer.join(helper, "rs_crash_rider");
				ps[1] = CapturingPlayer.join(helper, "rs_crash_watch");
				ps[0].teleport(abs(helper, 4.5, 1.0, 4.5));
				ps[1].teleport(abs(helper, 1.5, 1.0, 1.5));
				pig[0] = helper.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(6, 1, 6));
			})
			.thenIdle(2)
			.thenExecute(() -> {
				ServerPlayer rider = ps[0].player;
				Vec3 at = abs(helper, 4.5, 1.0, 4.5);
				// DISMOUNT: no effect
				RideRegistry.start(rider, status(helper, Direction.EAST, 10));
				ps[0].clearSent();
				ps[1].clearSent();
				RideRegistry.stop(rider, RideRegistry.StopReason.DISMOUNT, at);
				for (CapturingPlayer c : ps) {
					helper.assertTrue(explosionParticles(c, at) == 0 && explosionSounds(c) == 0, "DISMOUNT played the crash effect");
				}
				// CRASH: everyone in range gets particles + sound
				RideRegistry.start(rider, status(helper, Direction.EAST, 10));
				RideRegistry.stop(rider, RideRegistry.StopReason.CRASH, at);
				for (CapturingPlayer c : ps) {
					helper.assertTrue(explosionParticles(c, at) == 1, c.player.getPlainTextName() + " got no explosion_emitter particle");
					helper.assertTrue(explosionSounds(c) == 1, c.player.getPlainTextName() + " got no explode sound");
				}
			})
			.thenIdle(3)
			.thenExecute(() -> {
				helper.assertBlockPresent(Blocks.GLASS, 3, 1, 4);
				helper.assertBlockPresent(Blocks.GLASS, 5, 1, 4);
				helper.assertBlockPresent(Blocks.GLASS, 4, 1, 3);
				helper.assertBlockPresent(Blocks.GLASS, 4, 2, 5);
				helper.assertBlockPresent(Blocks.STONE, 4, 0, 4);
				helper.assertTrue(pig[0].getHealth() == pig[0].getMaxHealth(), "pig hurt by the crash effect");
				for (CapturingPlayer c : ps) {
					helper.assertTrue(c.player.getHealth() == 20, c.player.getPlainTextName() + " hurt by the crash effect");
					c.leave();
				}
			})
			.thenSucceed();
	}

	private static int explosionParticles(CapturingPlayer c, Vec3 at) {
		int n = 0;
		for (ClientboundLevelParticlesPacket p : c.sent(ClientboundLevelParticlesPacket.class)) {
			if (p.particle().getType() == ParticleTypes.EXPLOSION_EMITTER && Math.abs(p.x() - at.x) < 1e-6 && Math.abs(p.z() - at.z) < 1e-6) {
				n++;
			}
		}
		return n;
	}

	private static int explosionSounds(CapturingPlayer c) {
		int n = 0;
		for (ClientboundSoundPacket p : c.sent(ClientboundSoundPacket.class)) {
			if (p.getSound().is(SoundEvents.GENERIC_EXPLODE.key())) {
				n++;
			}
		}
		return n;
	}

	// ------------------------------------------------------------------ mob hits

	/**
	 * Rider at (1.5, 3.5) heading east at 8 blocks/s: damage 4 per hit, hit box x 1.5..3.2, z 3.0..4.0. A pig at
	 * x 2.7 (glass at x 4 keeps it in the box after knockback) is hit at t1 and t1 + 10, never in between. A second
	 * rider has a player standing in its box: never hurt.
	 */
	@GameTest(maxTicks = 200)
	public void mobAheadOfMovingRiderIsHitOncePerCooldown(GameTestHelper helper) {
		floor(helper, 0, Blocks.STONE);
		for (int z = 1; z <= 5; z++) {
			helper.setBlock(4, 1, z, Blocks.GLASS);
			helper.setBlock(4, 2, z, Blocks.GLASS);
		}
		CapturingPlayer[] ps = new CapturingPlayer[3]; // rider, rider2, bystander
		Pig[] pig = new Pig[1];
		boolean[] riding = new boolean[1];
		List<Long> drops = new ArrayList<>();
		float[] last = new float[1];
		double[] x0 = new double[2];
		helper.onEachTick(() -> {
			if (riding[0]) {
				// the ride handler reports state every tick
				RideRegistry.update(ps[0].player, status(helper, Direction.EAST, 8.0));
				RideRegistry.update(ps[1].player, status(helper, Direction.EAST, 8.0));
			}
			if (pig[0] != null && pig[0].isAlive()) {
				float h = pig[0].getHealth();
				if (h < last[0]) {
					drops.add(helper.getTick());
				}
				last[0] = h;
			}
		});
		helper.startSequence()
			.thenExecute(() -> {
				ps[0] = CapturingPlayer.join(helper, "rs_mob_rider");
				ps[1] = CapturingPlayer.join(helper, "rs_mob_rider2");
				ps[2] = CapturingPlayer.join(helper, "rs_mob_bystand");
				ps[0].teleport(abs(helper, 1.5, 1.0, 3.5));
				ps[1].teleport(abs(helper, 1.5, 1.0, 6.5));
				ps[2].teleport(abs(helper, 2.7, 1.0, 6.5)); // inside rider2's box
				pig[0] = helper.spawnWithNoFreeWill(EntityTypes.PIG, new Vec3(2.7, 1.0, 3.5));
			})
			.thenIdle(5) // let the pig settle
			.thenExecute(() -> {
				last[0] = pig[0].getHealth();
				x0[0] = pig[0].getX();
				x0[1] = pig[0].getZ();
				helper.assertTrue(last[0] == 10.0F, "pig health " + last[0]);
				riding[0] = true;
				RideRegistry.start(ps[0].player, status(helper, Direction.EAST, 8.0));
				RideRegistry.start(ps[1].player, status(helper, Direction.EAST, 8.0));
			})
			.thenWaitUntil(() -> helper.assertTrue(drops.size() >= 2, "hits so far: " + drops))
			.thenExecute(() -> {
				riding[0] = false;
				RideRegistry.stop(ps[0].player, RideRegistry.StopReason.DISMOUNT, ps[0].player.position());
				RideRegistry.stop(ps[1].player, RideRegistry.StopReason.DISMOUNT, ps[1].player.position());
				helper.assertTrue(drops.get(1) - drops.get(0) == 10, "hits not 10 ticks apart: " + drops);
				helper.assertTrue(Math.abs(pig[0].getHealth() - 2.0F) < 1e-4, "expected 10 - 4 - 4 = 2 health, got " + pig[0].getHealth());
				helper.assertTrue(pig[0].getX() > x0[0] + 0.3, "no knockback along +x: " + x0[0] + " -> " + pig[0].getX());
				helper.assertTrue(Math.abs(pig[0].getZ() - x0[1]) < 0.2, "knockback not along the heading: z " + x0[1] + " -> " + pig[0].getZ());
				helper.assertTrue(ps[2].player.getHealth() == 20, "a player in front of a rider was hurt: " + ps[2].player.getHealth());
			})
			.thenIdle(15)
			.thenExecute(() -> {
				helper.assertTrue(drops.size() == 2, "hit after the ride stopped: " + drops);
				helper.assertTrue(ps[2].player.getHealth() == 20, "bystander hurt");
				for (CapturingPlayer c : ps) {
					c.leave();
				}
				pig[0].discard();
			})
			.thenSucceed();
	}

	// ------------------------------------------------------------------ movement checks

	/** A catch-up burst of {@code packets} client ticks inside one server tick, each moving {@code step} up. */
	private static int burst(CapturingPlayer p, int packets, double step) {
		p.acceptTeleport();
		p.serverTick();
		p.clearSent();
		Vec3 start = p.player.position();
		for (int i = 1; i <= packets; i++) {
			p.move(start.add(0, step * i, 0), false);
		}
		return p.sent(ClientboundPlayerPositionPacket.class).size();
	}

	/** One server tick, one move packet; returns rubber-band teleports sent. */
	private static int moveOnce(CapturingPlayer p, Vec3 target) {
		p.serverTick();
		p.clearSent();
		p.move(target, true);
		return p.sent(ClientboundPlayerPositionPacket.class).size();
	}

	private static int maxFlyingTicks(ServerPlayer player) {
		try {
			Method m = ServerGamePacketListenerImpl.class.getDeclaredMethod("getMaximumFlyingTicks", Entity.class);
			m.setAccessible(true);
			return (int) m.invoke(player.connection, player);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	@GameTest(maxTicks = 100)
	public void movementChecksRelaxedOnlyWhileRiding(GameTestHelper helper) {
		floor(helper, 0, Blocks.STONE);
		// corner: 2-high block east of the start column
		helper.setBlock(4, 1, 3, Blocks.STONE);
		helper.setBlock(4, 2, 3, Blocks.STONE);
		// a 1-thick wall at x 6, rows z 5..7
		for (int z = 5; z <= 7; z++) {
			helper.setBlock(6, 1, z, Blocks.STONE);
			helper.setBlock(6, 2, z, Blocks.STONE);
		}
		CapturingPlayer[] p = new CapturingPlayer[1];
		helper.startSequence()
			.thenExecute(() -> {
				p[0] = CapturingPlayer.join(helper, "rs_move");
				ServerPlayer pl = p[0].player;
				RideRegistry.Status fast = status(helper, Direction.EAST, 20.0);

				// "moved too quickly": 7 client ticks of 1.6 blocks land in one server tick (11.2 blocks)
				p[0].teleport(abs(helper, 4.5, 12.0, 4.5));
				helper.assertTrue(burst(p[0], 7, 1.6) == 1, "vanilla did not rubber-band a 7 x 1.6 burst (test premise)");
				RideRegistry.start(pl, fast);
				double y0 = pl.getY();
				int tp = burst(p[0], 7, 1.6);
				helper.assertTrue(tp == 0, "rider rubber-banded on a 7 x 1.6 catch-up burst");
				helper.assertTrue(Math.abs(pl.getY() - (y0 + 11.2)) < 1e-6, "rider not at burst target: " + pl.getY());
				helper.assertTrue(burst(p[0], 12, 2.6) == 1, "relaxation is unbounded: 12 x 2.6 accepted");
				RideRegistry.stop(pl, RideRegistry.StopReason.DISMOUNT, pl.position());

				// "moved wrongly": turn around a corner inside one tick (+z 0.8 then +x 1.0); the server resolves
				// X first and stops at the block
				Vec3 start = abs(helper, 3.5, 1.0, 3.5);
				Vec3 target = abs(helper, 4.5, 1.0, 4.3);
				p[0].teleport(start);
				helper.assertTrue(moveOnce(p[0], target) == 1, "vanilla accepted the corner move (test premise)");
				p[0].teleport(start);
				RideRegistry.start(pl, fast);
				helper.assertTrue(moveOnce(p[0], target) == 0, "rider rubber-banded on a corner turn");
				helper.assertTrue(pl.position().distanceTo(target) < 1e-6, "rider not at corner target: " + pl.position());
				// still no skipping a 1-block wall while riding
				p[0].teleport(abs(helper, 5.5, 1.0, 6.5));
				helper.assertTrue(moveOnce(p[0], abs(helper, 7.3, 1.0, 6.5)) == 1, "rider passed through a 1-block wall");

				// floating limit: x2 for the rider only
				int riding = maxFlyingTicks(pl);
				RideRegistry.stop(pl, RideRegistry.StopReason.DISMOUNT, pl.position());
				int vanilla = maxFlyingTicks(pl);
				helper.assertTrue(vanilla == 80 && riding == 160, "floating limit vanilla " + vanilla + " riding " + riding);
				p[0].leave();
			})
			.thenSucceed();
	}
}

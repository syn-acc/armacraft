package gl.syn.armacraft.ride.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RideServerConfigTest {

	@Test
	void defaultsMatchTheSpec() {
		RideServerConfig c = new RideServerConfig();
		assertTrue(c.frostWalker);
		// a thin lane right at the bike
		assertEquals(1, c.frostRadius);
		assertEquals(0, c.frostStripHalfWidth);
		assertEquals(0.1, c.frostLookaheadSeconds);
		assertEquals(2, c.frostLookaheadMaxBlocks);
		assertTrue(c.noFallDamage);
		assertEquals(2.0, c.fallGraceSeconds);
		assertTrue(c.mobHits);
		assertTrue(c.crashEffect);
		assertTrue(c.relaxMovementChecks);
	}

	@Test
	void loadWritesDefaultsAndReadsBackSnakeCase(@TempDir Path dir) throws Exception {
		Path f = dir.resolve(RideServerConfig.FILE_NAME);
		RideServerConfig c = RideServerConfig.load(f);
		assertEquals(1, c.frostRadius);
		String json = Files.readString(f);
		assertTrue(json.contains("\"frost_lookahead_seconds\""), json);
		assertTrue(json.contains("\"mob_hit_cooldown_ticks\""), json);
		Files.writeString(f, "{\"frost_radius\": 5, \"mob_hits\": false, \"fall_grace_seconds\": 1.5}");
		RideServerConfig d = RideServerConfig.load(f);
		assertEquals(5, d.frostRadius);
		assertFalse(d.mobHits);
		assertEquals(30, d.fallGraceTicks());
		assertEquals(0.1, d.frostLookaheadSeconds, "missing keys keep defaults");
	}

	@Test
	void sanitizeClampsUnsafeValues() {
		RideServerConfig c = RideServerConfig.fromJson("{\"frost_radius\": 99, \"moved_wrongly_max_blocks\": 5,"
			+ " \"max_bike_speed\": -1, \"mob_hit_cooldown_ticks\": 0, \"floating_ticks_multiplier\": 0.1}");
		assertEquals(16, c.frostRadius);
		assertEquals(1.5, c.movedWronglyMaxBlocks);
		assertEquals(1, c.maxBikeSpeed);
		assertEquals(1, c.mobHitCooldownTicks);
		assertEquals(1, c.floatingTicksMultiplier);
	}

	@Test
	void brokenFileFallsBackToDefaults(@TempDir Path dir) throws Exception {
		Path f = dir.resolve(RideServerConfig.FILE_NAME);
		Files.writeString(f, "{ not json");
		RideServerConfig c = RideServerConfig.load(f);
		assertEquals(1, c.frostRadius);
	}
}

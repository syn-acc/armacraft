package gl.syn.armacraft.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gl.syn.armacraft.ride.RideConfig;
import gl.syn.armacraft.ride.server.RideServer;
import gl.syn.armacraft.ride.server.RideServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigDefaultsTest {

	private static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void untouchedKeysFollowAChangedDefaultAndOverridesStay() {
		// written by an older build: radius default was 3; the user set lookahead themselves
		JsonObject file = json("{\"frost_radius\": 3, \"frost_lookahead_max_blocks\": 9, \"crash_volume\": 4.0, \"boost\": {\"accel\": 20}}");
		JsonObject old = json("{\"frost_radius\": 3, \"frost_lookahead_max_blocks\": 2, \"crash_volume\": 4.0, \"boost\": {\"accel\": 20.0}}");
		JsonObject now = json("{\"frost_radius\": 1, \"frost_lookahead_max_blocks\": 2, \"crash_volume\": 4, \"boost\": {\"accel\": 25.0}}");
		ConfigDefaults.Result r = ConfigDefaults.reconcile(file, old, now);
		assertEquals(1, r.merged().get("frost_radius").getAsInt(), "never touched: the new default applies");
		assertEquals(9, r.merged().get("frost_lookahead_max_blocks").getAsInt(), "an override stays");
		assertEquals(25.0, r.merged().getAsJsonObject("boost").get("accel").getAsDouble(), "nested keys too");
		List<ConfigDefaults.Change> adopted = r.of(ConfigDefaults.Kind.ADOPTED);
		assertEquals(List.of("frost_radius", "boost.accel"), adopted.stream().map(ConfigDefaults.Change::key).toList());
		List<ConfigDefaults.Change> over = r.of(ConfigDefaults.Kind.OVERRIDE);
		assertEquals(1, over.size(), "4.0 and 4 are the same value: " + over);
		assertEquals("frost_lookahead_max_blocks=9 (default 2)", over.get(0).toString());
	}

	@Test
	void withoutASnapshotEveryDifferenceIsAnOverride() {
		JsonObject file = json("{\"frost_radius\": 3, \"keys\": {\"accelerate\": [\"key.keyboard.v\"]}}");
		ConfigDefaults.Result r = ConfigDefaults.reconcile(file, null, json("{\"frost_radius\": 1, \"keys\": {}}"));
		assertEquals(3, r.merged().get("frost_radius").getAsInt());
		assertEquals(2, r.of(ConfigDefaults.Kind.OVERRIDE).size());
		assertEquals("unset", r.changes().get(1).defaultValue(), "a key the defaults do not have");
		assertTrue(ConfigDefaults.summary(r.changes()).contains("2 keys differ"), ConfigDefaults.summary(r.changes()));
	}

	@Test
	void aFileWrittenByTheOldDefaultsAdoptsNewOnesOnLoad(@TempDir Path dir) throws Exception {
		Path f = dir.resolve(RideServerConfig.FILE_NAME);
		RideServerConfig first = RideServerConfig.load(f);
		assertTrue(Files.isRegularFile(ConfigDefaults.snapshotFile(f)), "the defaults snapshot is written with the file");
		// simulate "the default was 3 when this file was written": file and snapshot both say 3, the code says 1
		Files.writeString(f, Files.readString(f).replace("\"frost_radius\": 1", "\"frost_radius\": 3"));
		Path snap = ConfigDefaults.snapshotFile(f);
		Files.writeString(snap, Files.readString(snap).replace("\"frost_radius\": 1", "\"frost_radius\": 3"));
		ConfigDefaults.Loaded<RideServerConfig> l = RideServerConfig.read(f);
		assertNotNull(l.config());
		assertEquals(first.frostRadius, l.config().frostRadius, "stale default replaced by the current one");
		assertEquals(ConfigDefaults.Kind.ADOPTED, l.changes().get(0).kind());
		assertTrue(Files.readString(f).contains("\"frost_radius\": 1"), "and written back");
		// an edit after that is an override and survives
		Files.writeString(f, Files.readString(f).replace("\"frost_radius\": 1", "\"frost_radius\": 4"));
		l = RideServerConfig.read(f);
		assertEquals(4, l.config().frostRadius);
		assertEquals(ConfigDefaults.Kind.OVERRIDE, l.changes().get(0).kind());
	}

	@Test
	void aFileThatDoesNotParseIsLeftAloneAndReloadKeepsTheRunningValues(@TempDir Path dir) throws Exception {
		Path f = dir.resolve(RideServerConfig.FILE_NAME);
		RideServer server = new RideServer(RideServerConfig.load(f), f);
		Files.writeString(f, Files.readString(f).replace("\"frost_radius\": 1", "\"frost_radius\": 2").replace("\"frost_walker\": true", "\"frost_walker\": false"));
		assertTrue(server.reloadConfig().contains("reloaded"), "a good file reloads");
		assertEquals(2, server.config().frostRadius);
		assertFalse(server.config().frostWalker);
		String broken = "{ \"frost_radius\": 7, oops";
		Files.writeString(f, broken);
		String msg = server.reloadConfig();
		assertTrue(msg.contains("NOT reloaded"), msg);
		assertEquals(2, server.config().frostRadius, "running values kept");
		assertEquals(broken, Files.readString(f), "the half-edited file is not overwritten with defaults");
		Files.delete(f);
		assertTrue(server.reloadConfig().contains("defaults written"));
		assertEquals(1, server.config().frostRadius);
	}

	@Test
	void rideConfigReadKeepsKeysAndReportsOverrides(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("armacraft-ride.json");
		RideConfig.load(f);
		Files.writeString(f, "{\"terrain_rubber_factor\": 9, \"visual_blocks_per_unit\": 2.0, \"keys\": {\"accelerate\": [\"key.keyboard.v\"]}}");
		ConfigDefaults.Loaded<RideConfig> l = RideConfig.read(f);
		assertNotNull(l.config());
		assertEquals(9.0, l.config().terrainRubberFactor);
		assertEquals(9.0, l.config().params().terrainRubberFactor, "the rubber factor reaches the physics parameters");
		assertEquals(2.0, l.config().visualBlocksPerUnit);
		assertEquals(List.of("key.keyboard.v"), l.config().keys.accelerate);
		assertEquals(3, l.changes().size(), l.changes().toString());
		Files.writeString(f, "not json at all");
		l = RideConfig.read(f);
		assertNull(l.config());
		assertNotNull(l.error());
		assertEquals("not json at all", Files.readString(f));
	}

	@Test
	void configDiffListsOverridesAgainstCurrentDefaults(@TempDir Path dir) throws Exception {
		Path f = dir.resolve(RideServerConfig.FILE_NAME);
		Files.writeString(f, "{\"frost_radius\": 3, \"frost_walker\": true, \"made_up\": 1}");
		JsonObject defaults = RideServerConfigDefaults.tree();
		String out = ConfigDiff.report(f, defaults, false);
		assertTrue(out.contains("* frost_radius"), out);
		assertTrue(out.contains("default 1"), out);
		assertTrue(out.contains("+ made_up"), out);
		assertFalse(out.contains("frost_walker "), "unchanged keys are listed only with --all: " + out);
		assertTrue(ConfigDiff.report(f, defaults, true).contains("frost_walker"), "--all lists every key");
		assertTrue(ConfigDiff.report(dir.resolve("nope.json"), defaults, false).contains("no such file"));
	}

	/** The server config's defaults as a JSON tree, the way ConfigDiff builds them. */
	private static final class RideServerConfigDefaults {
		static JsonObject tree() {
			return JsonParser.parseString(new RideServerConfig().toJson()).getAsJsonObject();
		}
	}
}

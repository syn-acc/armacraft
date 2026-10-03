package gl.syn.armacraft.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** client/RELAY.md "Client → server" server rules: type whitelist, forced key/name, clamping. */
class RelayRulesTest {

	private static final UUID ME = UUID.fromString("0f3a0000-0000-4000-8000-000000000001");
	private static final String KEY = "mc:" + ME;
	private static final double[] DEF = {0.25, 0.5, 0.75};

	private static RelayRules.Inbound in(String line) {
		return RelayRules.inbound(line, ME, "Jamie", DEF);
	}

	private static JsonObject only(RelayRules.Inbound in) {
		assertTrue(in.accepted(), "dropped: " + in.dropped());
		assertEquals(1, in.forward().size());
		return in.forward().getFirst();
	}

	@Test
	void joinForcesKeyAndProfileNameAndClampsColour() {
		JsonObject j = only(in("{\"type\":\"join\",\"key\":\"mc:someone-else\",\"name\":\"Admin\",\"color\":[5,-1,0.5],\"extra\":1}"));
		assertEquals("join", j.get("type").getAsString());
		assertEquals(KEY, j.get("key").getAsString());
		assertEquals("Jamie", j.get("name").getAsString());
		assertEquals(1.0, j.getAsJsonArray("color").get(0).getAsDouble());
		assertEquals(0.0, j.getAsJsonArray("color").get(1).getAsDouble());
		assertEquals(0.5, j.getAsJsonArray("color").get(2).getAsDouble());
		assertFalse(j.has("extra"), "messages are rebuilt, unknown client fields never reach the guest");
	}

	@Test
	void joinWithoutKeyOrWithBadColourGetsDefaults() {
		JsonObject j = only(in("{\"type\":\"join\"}"));
		assertEquals(KEY, j.get("key").getAsString());
		assertEquals(0.25, j.getAsJsonArray("color").get(0).getAsDouble());
		for (String bad : new String[] {"[1,1]", "\"red\"", "[1,\"x\",1]", "[1,[1],1]", "{}"}) {
			JsonObject k = only(in("{\"type\":\"join\",\"color\":" + bad + "}"));
			assertEquals(0.75, k.getAsJsonArray("color").get(2).getAsDouble(), bad);
		}
	}

	@Test
	void leaveAndInputForceKey() {
		assertEquals(KEY, only(in("{\"type\":\"leave\",\"key\":\"mc:victim\"}")).get("key").getAsString());
		JsonObject turn = only(in("{\"type\":\"input\",\"key\":\"mc:victim\",\"turn\":\"left\"}"));
		assertEquals(KEY, turn.get("key").getAsString());
		assertEquals("left", turn.get("turn").getAsString());
		assertFalse(turn.has("brake"));
		JsonObject brake = only(in("{\"type\":\"input\",\"brake\":true}"));
		assertEquals(KEY, brake.get("key").getAsString());
		assertTrue(brake.get("brake").getAsBoolean());
		assertFalse(only(in("{\"type\":\"input\",\"key\":\"x\",\"brake\":false}")).get("brake").getAsBoolean());
	}

	@Test
	void inputWithTurnAndBrakeKeepsBothInOrder() {
		RelayRules.Inbound r = in("{\"type\":\"input\",\"turn\":\"right\",\"brake\":true}");
		assertTrue(r.accepted());
		assertEquals(2, r.forward().size());
		assertEquals("right", r.forward().get(0).get("turn").getAsString());
		assertTrue(r.forward().get(1).get("brake").getAsBoolean());
	}

	@Test
	void badInputsAreDropped() {
		assertFalse(in("{\"type\":\"input\",\"turn\":\"up\"}").accepted());
		assertFalse(in("{\"type\":\"input\"}").accepted());
		assertFalse(in("{\"type\":\"input\",\"brake\":\"yes\"}").accepted());
		assertFalse(in("{\"type\":\"input\",\"turn\":1}").accepted());
	}

	@Test
	void chatForcesProfileNameStripsControlAndCaps() {
		JsonObject c = only(in("{\"type\":\"chat\",\"name\":\"Server\",\"text\":\"hi\\n\\u0007there\"}"));
		assertEquals("Jamie", c.get("name").getAsString());
		assertEquals("hithere", c.get("text").getAsString());
		assertFalse(c.has("key"));
		String longText = "x".repeat(1000);
		assertEquals(RelayRules.MAX_CHAT_CHARS, only(in("{\"type\":\"chat\",\"text\":\"" + longText + "\"}")).get("text").getAsString().length());
		assertFalse(in("{\"type\":\"chat\",\"text\":\"  \\n \"}").accepted());
		assertFalse(in("{\"type\":\"chat\"}").accepted());
		// surrogate pairs are never split
		String emoji = "😀".repeat(300);
		String got = only(in("{\"type\":\"chat\",\"text\":\"" + emoji + "\"}")).get("text").getAsString();
		assertEquals(RelayRules.MAX_CHAT_CHARS, got.codePointCount(0, got.length()));
	}

	@Test
	void hostOnlyAndUnknownTypesAreDropped() {
		for (String line : new String[] {
			"{\"type\":\"cmd\",\"line\":\"CYCLE_SPEED 200\"}",
			"{\"type\":\"explode\",\"x\":0,\"y\":0,\"r\":100}",
			"{\"type\":\"hello\",\"proto\":1,\"features\":[\"delta\"]}",
			"{\"type\":\"relay\"}",
			"{\"type\":\"JOIN\"}",
			"{\"type\":1}",
			"{\"key\":\"mc:x\"}",
			"[{\"type\":\"join\"}]",
			"\"join\"",
			"not json",
			"",
		}) {
			RelayRules.Inbound r = in(line);
			assertFalse(r.accepted(), line);
			assertTrue(r.forward().isEmpty(), line);
		}
	}

	@Test
	void forwardedLinesAreValidProtocolJson() {
		String line = only(in("{\"type\":\"join\",\"color\":[0.1,0.6,1.0]}")).toString();
		JsonObject back = JsonParser.parseString(line).getAsJsonObject();
		assertEquals("join", back.get("type").getAsString());
		assertEquals(3, back.getAsJsonArray("color").size());
	}

	@Test
	void namesAreCleaned() {
		assertEquals("player", RelayRules.cleanName("\u0000\n"));
		assertEquals("Syn", RelayRules.cleanName(" Syn\t"));
		assertEquals(RelayRules.MAX_NAME_CHARS, RelayRules.cleanName("n".repeat(100)).length());
	}
}

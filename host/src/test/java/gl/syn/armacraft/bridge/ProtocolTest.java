package gl.syn.armacraft.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolTest {

	@Test
	void parsesHello() {
		Msg.Hello h = assertInstanceOf(Msg.Hello.class,
			Protocol.parse("{\"type\":\"hello\",\"proto\":1,\"game\":\"armagetronad\",\"version\":\"0.2.9.3.0\",\"rate\":30}"));
		assertEquals(1, h.proto());
		assertEquals("armagetronad", h.game());
		assertEquals("0.2.9.3.0", h.version());
		assertEquals(30.0, h.rate());
	}

	@Test
	void parsesRound() {
		Msg.Round r = assertInstanceOf(Msg.Round.class, Protocol.parse(
			"{\"type\":\"round\",\"round\":3,\"t\":0.0,\"static\":[[-100,-100,100,-100],[100,-100,100,100]],\"bounds\":[-100,-100,100,100]}"));
		assertEquals(3, r.round());
		assertEquals(2, r.statics().size());
		assertEquals(new Msg.Seg(-100, -100, 100, -100), r.statics().get(0));
		assertArrayEquals(new double[] {-100, -100, 100, 100}, r.bounds());
	}

	@Test
	void parsesProtocolExampleState() {
		String line = "{\"type\":\"state\",\"t\":12.34,"
			+ "\"cycles\":[{\"id\":17,\"name\":\"Syn\",\"color\":[0.1,0.6,1.0],\"x\":10.0,\"y\":-4.5,"
			+ "\"dx\":0.0,\"dy\":1.0,\"speed\":30.0,\"alive\":true,\"brake\":false,"
			+ "\"rubber\":0.2,\"bridge_key\":\"mc:0f3a\"}],"
			+ "\"walls\":[{\"id\":901,\"cycle\":17,\"x1\":0,\"y1\":0,\"x2\":0,\"y2\":40,\"gaps\":[[0.25,0.30]]}]}";
		Msg.State s = assertInstanceOf(Msg.State.class, Protocol.parse(line, 42L));
		assertEquals(12.34, s.t(), 1e-9);
		assertEquals(42L, s.receivedNanos());
		Msg.Cycle c = s.cycles().get(0);
		assertEquals(17, c.id());
		assertEquals("Syn", c.name());
		assertEquals(0.6, c.g(), 1e-9);
		assertEquals(-4.5, c.y(), 1e-9);
		assertEquals(30.0, c.speed(), 1e-9);
		assertTrue(c.alive());
		assertEquals("mc:0f3a", c.bridgeKey());
		Msg.Wall w = s.walls().get(0);
		assertEquals(901, w.id());
		assertEquals(17, w.cycle());
		assertEquals(40, w.y2(), 1e-9);
		assertEquals(1, w.gaps().size());
		assertEquals(0.25, w.gaps().get(0).from(), 1e-9);
	}

	@Test
	void stateIsLenient() {
		// unknown fields, missing optionals, null bridge_key, mistyped values, junk entries
		String line = "{\"type\":\"state\",\"extra\":{\"a\":1},"
			+ "\"cycles\":[{\"id\":1},{\"name\":\"no id\"},\"junk\",{\"id\":\"2\",\"x\":\"3.5\",\"alive\":false,\"bridge_key\":null,\"color\":[1]}],"
			+ "\"walls\":[{\"id\":5,\"x2\":3,\"gaps\":[[0.1],[\"x\",1],[0.2,0.3]]},{\"cycle\":1}]}";
		Msg.State s = assertInstanceOf(Msg.State.class, Protocol.parse(line));
		assertEquals(2, s.cycles().size());
		Msg.Cycle a = s.cycles().get(0);
		assertEquals(1, a.id());
		assertTrue(a.alive());           // default
		assertEquals(1.0, a.dy(), 1e-9); // default heading north
		assertNull(a.bridgeKey());
		Msg.Cycle b = s.cycles().get(1);
		assertEquals(2, b.id());
		assertEquals(3.5, b.x(), 1e-9);
		assertFalse(b.alive());
		assertNull(b.bridgeKey());
		assertEquals(1.0, b.r(), 1e-9);  // bad colour -> white
		assertEquals(1, s.walls().size());
		assertEquals(1, s.walls().get(0).gaps().size());
		assertEquals(-1, s.walls().get(0).cycle());
	}

	@Test
	void parsesEventsAndReplies() {
		Msg.Event d = assertInstanceOf(Msg.Event.class,
			Protocol.parse("{\"type\":\"event\",\"ev\":\"death\",\"id\":17,\"x\":10,\"y\":5,\"killer\":23,\"t\":12.3}"));
		assertEquals("death", d.ev());
		assertEquals(17, d.id());
		assertEquals(10, d.x(), 1e-9);
		Msg.Event chat = assertInstanceOf(Msg.Event.class, Protocol.parse("{\"type\":\"event\",\"ev\":\"chat\",\"name\":\"Syn\",\"text\":\"gg\"}"));
		assertEquals("gg", chat.str("text"));
		assertEquals("", chat.str("missing"));
		assertEquals(new Msg.Joined("mc:x", "Jamie"), Protocol.parse("{\"type\":\"joined\",\"key\":\"mc:x\",\"name\":\"Jamie\"}"));
		assertEquals(new Msg.Left("mc:x"), Protocol.parse("{\"type\":\"left\",\"key\":\"mc:x\"}"));
		assertEquals(new Msg.Error("nope"), Protocol.parse("{\"type\":\"error\",\"msg\":\"nope\"}"));
	}

	@Test
	void unknownAndMalformed() {
		assertEquals(new Msg.Unknown("future_thing"), Protocol.parse("{\"type\":\"future_thing\",\"x\":1}"));
		assertEquals(new Msg.Unknown(""), Protocol.parse("{\"no_type\":true}"));
		assertNull(Protocol.parse("not json"));
		assertNull(Protocol.parse("[1,2,3]"));
		assertNull(Protocol.parse(""));
		assertNull(Protocol.parse("   "));
		assertNull(Protocol.parse("{\"type\":\"state\""));
	}

	@Test
	void buildsHostMessages() {
		JsonObject j = Protocol.join("mc:abc", "Jamie", new double[] {0.1, 0.6, 1.0});
		assertEquals(JsonParser.parseString("{\"type\":\"join\",\"key\":\"mc:abc\",\"name\":\"Jamie\",\"color\":[0.1,0.6,1.0]}"), j);
		assertEquals(JsonParser.parseString("{\"type\":\"leave\",\"key\":\"mc:abc\"}"), Protocol.leave("mc:abc"));
		assertEquals(JsonParser.parseString("{\"type\":\"input\",\"key\":\"mc:abc\",\"turn\":\"left\"}"), Protocol.turn("mc:abc", "left"));
		assertEquals(JsonParser.parseString("{\"type\":\"input\",\"key\":\"mc:abc\",\"brake\":true}"), Protocol.brake("mc:abc", true));
		assertEquals(JsonParser.parseString("{\"type\":\"explode\",\"x\":10.0,\"y\":5.0,\"r\":8.0}"), Protocol.explode(10, 5, 8));
		assertEquals(JsonParser.parseString("{\"type\":\"cmd\",\"line\":\"CYCLE_SPEED 20\"}"), Protocol.cmd("CYCLE_SPEED 20"));
		assertEquals(JsonParser.parseString("{\"type\":\"chat\",\"name\":\"Jamie\",\"text\":\"hi\"}"), Protocol.chat("Jamie", "hi"));
		// one line each: no raw newlines even with hostile text
		assertFalse(Protocol.chat("a\nb", "c\nd").toString().contains("\n"));
	}
}

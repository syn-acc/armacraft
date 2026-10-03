package gl.syn.armacraft.client.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PROTOCOL.md {@code delta} folding. Relay clients receive the host's negotiated deltas verbatim, preceded by a
 * {@code state} keyframe (client/RELAY.md) when they start listening mid-stream.
 */
class ArenaModelDeltaTest {

	private static final String ROUND = "{\"type\":\"round\",\"round\":3,\"t\":0,\"static\":[[0,0,100,0]],\"bounds\":[0,0,100,100]}";
	private static final String CYC = "\"cycles\":[{\"id\":17,\"name\":\"Syn\",\"color\":[0.1,0.6,1.0],\"x\":10,\"y\":40,\"dx\":1,\"dy\":0,\"speed\":30,\"alive\":true}]";

	private static String wall(int id, double x1, double y1, double x2, double y2) {
		return "{\"id\":" + id + ",\"cycle\":17,\"x1\":" + x1 + ",\"y1\":" + y1 + ",\"x2\":" + x2 + ",\"y2\":" + y2 + "}";
	}

	private static List<Integer> ids(ArenaModel m) {
		return m.walls().stream().map(ArenaModel.Wall::id).sorted().toList();
	}

	@Test
	void keyframeThenDeltasFoldLikeTheHost() {
		ArenaModel m = new ArenaModel();
		assertTrue(m.accept(ROUND, 0));
		// relay keyframe: full state (with the marker field v1 parsers ignore)
		assertTrue(m.accept("{\"type\":\"state\",\"t\":1.0," + CYC + ",\"walls\":[" + wall(880, 0, 0, 0, 40) + "," + wall(-17, 0, 40, 10, 40) + "],\"keyframe\":true}", 1));
		assertEquals(List.of(-17, 880), ids(m));
		// head wall gets its real id: old -cycle id removed, new id upserted, others untouched
		assertTrue(m.accept("{\"type\":\"delta\",\"seq\":9,\"key\":false,\"t\":1.1," + CYC + ",\"walls\":[" + wall(905, 0, 40, 12.5, 40) + "],\"removed\":[-17]}", 1.1));
		assertEquals(List.of(880, 905), ids(m));
		// a changed wall replaces the previous record with the same id
		assertTrue(m.accept("{\"type\":\"delta\",\"seq\":10,\"key\":false,\"t\":1.2," + CYC + ",\"walls\":[" + wall(905, 0, 40, 15, 40) + "],\"removed\":[]}", 1.2));
		assertEquals(15.0, m.walls().stream().filter(w -> w.id() == 905).findFirst().orElseThrow().x2());
		assertEquals(2, m.walls().size());
		assertEquals(1.2, m.lastStateT(), 1e-9);
		assertTrue(m.cycle(17).alive);
	}

	@Test
	void keyDeltaReplacesTheWallSet() {
		ArenaModel m = new ArenaModel();
		m.accept(ROUND, 0);
		m.accept("{\"type\":\"delta\",\"seq\":1,\"key\":true,\"t\":1.0," + CYC + ",\"walls\":[" + wall(1, 0, 0, 0, 5) + "," + wall(2, 0, 5, 5, 5) + "],\"removed\":[]}", 1);
		assertEquals(List.of(1, 2), ids(m));
		m.accept("{\"type\":\"delta\",\"seq\":2,\"key\":true,\"t\":1.1," + CYC + ",\"walls\":[" + wall(3, 1, 1, 1, 2) + "],\"removed\":[]}", 1.1);
		assertEquals(List.of(3), ids(m));
	}

	@Test
	void newRoundClearsTheFold() {
		ArenaModel m = new ArenaModel();
		m.accept(ROUND, 0);
		m.accept("{\"type\":\"delta\",\"seq\":1,\"key\":true,\"t\":5.0," + CYC + ",\"walls\":[" + wall(1, 0, 0, 0, 5) + "],\"removed\":[]}", 5);
		m.accept(ROUND.replace("\"round\":3", "\"round\":4"), 6);
		assertTrue(m.walls().isEmpty());
		// a non-key delta after the round only carries its own walls (the guest keys the first delta of a round anyway)
		m.accept("{\"type\":\"delta\",\"seq\":2,\"key\":false,\"t\":0.1," + CYC + ",\"walls\":[" + wall(7, 0, 0, 1, 0) + "],\"removed\":[]}", 6.1);
		assertEquals(List.of(7), ids(m));
	}

	@Test
	void plainStatesStillReplaceEverything() {
		ArenaModel m = new ArenaModel();
		m.accept(ROUND, 0);
		// v1 guests send fresh head walls with id 0, possibly several: a state keeps all of them
		m.accept("{\"type\":\"state\",\"t\":1.0," + CYC + ",\"walls\":[" + wall(0, 0, 0, 0, 5) + "," + wall(0, 5, 5, 9, 5) + "]}", 1);
		assertEquals(2, m.walls().size());
		m.accept("{\"type\":\"state\",\"t\":1.1," + CYC + ",\"walls\":[]}", 1.1);
		assertTrue(m.walls().isEmpty());
	}
}

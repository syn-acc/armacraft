package gl.syn.armacraft.client.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Feeds lines captured from the real guest (armacraft-guest:dev, 2026-10-02). */
class ArenaModelTest {

	static final String ROUND = "{\"type\":\"round\",\"round\":2,\"t\":0,\"static\":[[0,0,125,0],[125,0,125,125],[0,125,125,125],[0,125,0,0]],\"bounds\":[0,0,125,125]}";
	static final String STATE_1 = "{\"type\":\"state\",\"t\":0.6266,\"cycles\":[{\"id\":22,\"name\":\"Armagetron\",\"color\":[1,1,1],\"x\":63.75,\"y\":29.3853,\"dx\":0,\"dy\":1,\"speed\":29.5355,\"alive\":true,\"brake\":false,\"rubber\":0},{\"id\":24,\"name\":\"Probe\",\"color\":[1,0.5333,0],\"x\":29.3853,\"y\":61.25,\"dx\":1,\"dy\":0,\"speed\":29.5355,\"alive\":true,\"brake\":false,\"rubber\":0,\"bridge_key\":\"mc:probe\"}],\"walls\":[{\"id\":0,\"cycle\":24,\"x1\":12.5,\"y1\":61.25,\"x2\":29.3853,\"y2\":61.25},{\"id\":0,\"cycle\":22,\"x1\":63.75,\"y1\":12.5,\"x2\":63.75,\"y2\":29.3853}]}";
	static final String TURN = "{\"type\":\"event\",\"ev\":\"turn\",\"id\":22,\"x\":63.75,\"y\":30.0,\"dx\":-1,\"dy\":0,\"t\":0.65}";
	static final String DEATH = "{\"type\":\"event\",\"ev\":\"death\",\"id\":24,\"x\":63.75,\"y\":61.25,\"killer\":22,\"t\":1.8051}";
	static final String STATE_2 = "{\"type\":\"state\",\"t\":1.9646,\"cycles\":[{\"id\":22,\"name\":\"Armagetron\",\"color\":[1,1,1],\"x\":40.0,\"y\":30.0,\"dx\":-1,\"dy\":0,\"speed\":30.1333,\"alive\":true,\"brake\":false,\"rubber\":0},{\"id\":24,\"name\":\"Probe\",\"color\":[1,0.5333,0],\"x\":63.75,\"y\":61.25,\"dx\":1,\"dy\":0,\"speed\":29.9988,\"alive\":false,\"brake\":false,\"rubber\":1,\"bridge_key\":\"mc:probe\"}],\"walls\":[{\"id\":26,\"cycle\":24,\"x1\":12.5,\"y1\":61.25,\"x2\":63.5639,\"y2\":61.25,\"gaps\":[[0.9336,1]]}]}";

	@Test
	void followsARoundFromGuestLines() {
		ArenaModel m = new ArenaModel();
		List<String> events = new ArrayList<>();
		m.setListener(new ArenaModel.Listener() {
			@Override
			public void onTurn(ArenaModel.Cycle c, int dir) {
				events.add("turn " + c.id + " " + dir);
			}

			@Override
			public void onDeath(ArenaModel.Cycle c, ArenaModel.Cycle killer, double x, double y) {
				events.add("death " + c.id + " by " + killer.id);
			}
		});
		assertTrue(m.accept("{\"type\":\"hello\",\"proto\":1,\"game\":\"armagetronad\",\"version\":\"0.2.9.3.0\",\"rate\":30}", 0));
		assertTrue(m.accept(ROUND, 0));
		assertArrayEquals(new double[] {0, 0, 125, 125}, m.bounds());
		assertEquals(4, m.rim().size());
		assertTrue(m.accept(STATE_1, 10.0));
		assertEquals(2, m.cycles().size());
		ArenaModel.Cycle probe = m.byKey("mc:probe");
		assertNotNull(probe);
		assertEquals(24, probe.id);
		assertEquals(0.5333f, probe.g, 1e-4);
		assertEquals(2, m.walls().size());

		assertTrue(m.accept(TURN, 10.03));
		assertEquals(List.of("turn 22 -1"), events);   // north -> west is a left turn
		ArenaModel.Cycle ai = m.cycle(22);
		V2 p = ai.track.position(0.7);
		assertEquals(63.75 - 0.05 * 30.1333, p.x(), 0.2);
		assertEquals(30.0, p.y(), 1e-9);

		assertTrue(m.accept(DEATH, 11.2));
		assertFalse(probe.alive);
		assertEquals(1.8051, probe.deathTime, 1e-9);
		assertEquals(1, m.explosions().size());
		assertEquals("death 24 by 22", events.get(1));

		assertTrue(m.accept(STATE_2, 11.4));
		assertFalse(m.cycle(24).alive);
		assertEquals(1, m.walls().size());
		assertEquals(0.9336, m.walls().get(0).gaps()[0][0], 1e-9);
	}

	@Test
	void ignoresUnknownAndMalformedLines() {
		ArenaModel m = new ArenaModel();
		assertFalse(m.accept("{\"type\":\"future_thing\",\"x\":1}", 0));
		assertFalse(m.accept("not json", 0));
		assertFalse(m.accept("[1,2]", 0));
		assertTrue(m.accept("{\"type\":\"state\",\"t\":1,\"cycles\":[],\"walls\":[],\"extra\":{\"nested\":true}}", 0));
	}

	@Test
	void brakeReservoirDepletesAndRefillsLikeSettingsCfg() {
		ArenaModel m = new ArenaModel();
		m.accept("{\"type\":\"state\",\"t\":1,\"cycles\":[{\"id\":1,\"x\":0,\"y\":0,\"dx\":1,\"dy\":0,\"speed\":30,\"alive\":true,\"brake\":true}],\"walls\":[]}", 0);
		m.tick(0.5);
		assertEquals(0.5, m.cycle(1).brakeReservoir, 1e-9);
		m.accept("{\"type\":\"state\",\"t\":1.5,\"cycles\":[{\"id\":1,\"x\":0,\"y\":0,\"dx\":1,\"dy\":0,\"speed\":30,\"alive\":true,\"brake\":false}],\"walls\":[]}", 0.5);
		m.tick(1.0);
		assertEquals(0.6, m.cycle(1).brakeReservoir, 1e-9);
	}
}

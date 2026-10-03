package gl.syn.armacraft.logic;

import gl.syn.armacraft.bridge.Msg;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ExtrapolateTest {

	private static Msg.Cycle cycle(long id, double x, double y, double dx, double dy, double speed, boolean alive) {
		return new Msg.Cycle(id, "c" + id, 1, 0, 0, x, y, dx, dy, speed, alive, false, 0, null);
	}

	@Test
	void movesAliveCyclesAndTheirHeadWallOnly() {
		Msg.State st = new Msg.State(1.0,
			List.of(cycle(1, 0, 10, 0, 1, 20, true), cycle(2, 5, 5, 1, 0, 20, false)),
			List.of(
				new Msg.Wall(10, 1, 0, 0, 0, 10, List.of()),   // head wall of 1 (ends at cycle)
				new Msg.Wall(11, 1, -5, 0, 0, 0, List.of()),   // older wall of 1
				new Msg.Wall(12, 2, 0, 5, 5, 5, List.of())),   // wall of dead cycle
			0L);
		Msg.State out = Extrapolate.forward(st, 0.05);
		assertEquals(11.0, out.cycles().get(0).y(), 1e-9);   // 20 * 0.05 = 1 unit north
		assertEquals(5.0, out.cycles().get(1).x(), 1e-9);    // dead: unchanged
		assertEquals(11.0, out.walls().get(0).y2(), 1e-9);
		assertEquals(0.0, out.walls().get(1).x2(), 1e-9);
		assertEquals(5.0, out.walls().get(2).x2(), 1e-9);
		assertEquals(1.05, out.t(), 1e-9);
	}

	@Test
	void zeroAgeIsIdentity() {
		Msg.State st = new Msg.State(0, List.of(), List.of(), 0L);
		assertSame(st, Extrapolate.forward(st, 0));
	}
}

package gl.syn.armacraft.logic;

import gl.syn.armacraft.bridge.Msg;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WallKeysTest {

	private static Msg.Wall wall(long id, long cycle, double x1, double y1, double x2, double y2) {
		return new Msg.Wall(id, cycle, x1, y1, x2, y2, List.of());
	}

	@Test
	void numberedWallsKeyById() {
		WallKeys.Result r = WallKeys.assign(List.of(wall(901, 17, 0, 0, 0, 5), wall(902, 18, 1, 1, 2, 1)), Map.of());
		assertEquals(List.of("w901", "w902"), r.keys());
		assertTrue(r.rekeys().isEmpty());
		assertTrue(r.heads().isEmpty());
	}

	@Test
	void unnumberedHeadWallsOfDifferentCyclesDoNotCollide() {
		// live guest: every fresh head wall arrives as id 0
		WallKeys.Result r = WallKeys.assign(List.of(
			wall(0, 1840, 14.12, 61.25, 14.12, 62.4),
			wall(1841, 1840, 12.5, 61.25, 14.12, 61.25),
			wall(0, 777, 63.75, 12.5, 63.75, 20)), Map.of());
		assertEquals(List.of("c1840h", "w1841", "c777h"), r.keys());
		assertArrayEquals(new double[] {14.12, 61.25}, r.heads().get(1840L));
		assertArrayEquals(new double[] {63.75, 12.5}, r.heads().get(777L));
	}

	@Test
	void duplicatesInOneSnapshotAreKeptWithSuffix() {
		WallKeys.Result r = WallKeys.assign(List.of(wall(0, 5, 0, 0, 1, 0), wall(0, 5, 3, 3, 4, 3), wall(7, 5, 9, 9, 9, 8), wall(7, 6, 1, 2, 3, 2)), Map.of());
		assertEquals(List.of("c5h", "c5h#2", "w7", "w7#2"), r.keys());
	}

	@Test
	void headWallGettingItsIdIsRekeyedNotRespawned() {
		WallKeys.Result first = WallKeys.assign(List.of(wall(0, 1840, 14.12, 61.25, 14.12, 70)), Map.of());
		WallKeys.Result second = WallKeys.assign(List.of(wall(1850, 1840, 14.12, 61.25, 14.12, 71)), first.heads());
		assertEquals(List.of("w1850"), second.keys());
		assertEquals(Map.of("c1840h:", "w1850:"), second.rekeys());
		// a different start point is a different wall: no rekey
		WallKeys.Result other = WallKeys.assign(List.of(wall(1851, 1840, 20, 61.25, 20, 71)), first.heads());
		assertTrue(other.rekeys().isEmpty());
	}
}

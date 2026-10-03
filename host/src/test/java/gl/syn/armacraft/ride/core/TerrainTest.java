package gl.syn.armacraft.ride.core;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainTest {

	static final Terrain T = Terrain.standard();
	static final double FEET = 64.0;

	/** Ground: solid blocks up to y=63 (top at 64). */
	static Column ground() {
		return new Column().blocks(60, 63);
	}

	@Test
	void flat() {
		Terrain.Result r = T.classify(ground(), FEET);
		assertEquals(Terrain.Type.FLAT, r.type());
		assertEquals(64.0, r.floorY(), 1e-9);
	}

	@Test
	void oneBlockStepUpIsClimbed() {
		Terrain.Result r = T.classify(ground().blocks(64, 64), FEET);
		assertEquals(Terrain.Type.STEP_UP, r.type());
		assertEquals(65.0, r.floorY(), 1e-9);
	}

	@Test
	void halfSlabIsAStepToo() {
		assertEquals(Terrain.Type.STEP_UP, T.classify(ground().solid(64, 64.5), FEET).type());
	}

	@Test
	void twoBlockObstacleIsAWall() {
		assertEquals(Terrain.Type.WALL, T.classify(ground().blocks(64, 65), FEET).type());
	}

	@Test
	void fenceHeightIsAWall() {
		assertEquals(Terrain.Type.WALL, T.classify(ground().solid(64, 65.5), FEET).type());
	}

	@Test
	void oneBlockHighGapIsAWall() {
		// ceiling block at y=65: only one block of air above the floor
		assertEquals(Terrain.Type.WALL, T.classify(ground().blocks(65, 66), FEET).type());
		// two blocks of air: fits (player is 1.8 tall)
		assertEquals(Terrain.Type.FLAT, T.classify(ground().blocks(66, 67), FEET).type());
	}

	@Test
	void stepUpWithoutHeadroomIsAWall() {
		// step at 64, ceiling at 66: 1 block of air above the step
		assertEquals(Terrain.Type.WALL, T.classify(ground().blocks(64, 64).blocks(66, 66), FEET).type());
	}

	@Test
	void oneBlockDownIsSnapped() {
		Terrain.Result r = T.classify(new Column().blocks(60, 62), FEET);
		assertEquals(Terrain.Type.STEP_DOWN, r.type());
		assertEquals(63.0, r.floorY(), 1e-9);
	}

	@Test
	void deeperIsADrop() {
		assertEquals(Terrain.Type.DROP, T.classify(new Column().blocks(50, 61), FEET).type());
		assertEquals(Terrain.Type.DROP, T.classify(new Column(), FEET).type(), "no floor in range");
	}

	@Test
	void dropUnderAnOverhangIsAWall() {
		// floor far below, but a block hangs at head height
		assertEquals(Terrain.Type.WALL, T.classify(new Column().blocks(50, 55).blocks(65, 70), FEET).type());
	}

	@Test
	void stillWaterSurfaceIsDrivable() {
		Column lake = new Column().blocks(55, 60).add(61, 62, Column.Kind.WATER).add(62, 63, Column.Kind.WATER)
			.add(63, 64, Column.Kind.WATER);
		Terrain.Result r = T.classify(lake, FEET);
		assertEquals(Terrain.Type.WATER, r.type());
		assertEquals(64.0, r.floorY(), 1e-9, "the bike rides on the surface: no dip");
	}

	@Test
	void lavaIsAWall() {
		assertEquals(Terrain.Type.WALL, T.classify(new Column().blocks(60, 62).add(63, 64, Column.Kind.LAVA), FEET).type());
	}

	@Test
	void unloadedIsUnknown() {
		assertEquals(Terrain.Type.UNKNOWN, T.classify(Column.UNLOADED, FEET).type());
	}

	// ---- probe

	/** Map-backed world; default column: ground at 64. */
	static final class World implements TerrainProbe.ColumnSource {
		final Map<Long, Column> cols = new HashMap<>();
		Column fallback = ground();

		World set(int x, int z, Column c) {
			cols.put(((long) x << 32) ^ (z & 0xffffffffL), c);
			return this;
		}

		@Override
		public Column column(int bx, int bz) {
			return cols.getOrDefault(((long) bx << 32) ^ (bz & 0xffffffffL), fallback);
		}
	}

	@Test
	void probeFindsWallDistanceFromFrontFace() {
		World w = new World().set(10, 0, ground().blocks(64, 65));
		// bike centred at x=5.5, z=0.5, heading east: front face at 5.8, wall face at 10
		TerrainProbe.Ahead a = TerrainProbe.ahead(w, T, 5.5, 0.5, FEET, Heading.EAST, 0.3, 16);
		assertEquals(4.2, a.distance(), 1e-9);
		assertFalse(a.hold());
	}

	@Test
	void probeClimbsStairsButStopsAtTheSecondLevelWithoutStep() {
		World w = new World().set(3, 0, ground().blocks(64, 64)).set(4, 0, ground().blocks(64, 65)).set(5, 0, ground().blocks(64, 67));
		TerrainProbe.Ahead a = TerrainProbe.ahead(w, T, 0.5, 0.5, FEET, Heading.EAST, 0.3, 16);
		// 3: +1, 4: +1 relative to 65 -> fine, 5: +2 relative to 66 -> wall at x=5
		assertEquals(5 - 0.8, a.distance(), 1e-9);
		assertEquals(Terrain.Type.FLAT, a.first());
	}

	@Test
	void probeWestUsesNegativeAxisColumns() {
		World w = new World().set(-4, 0, ground().blocks(64, 65));
		// heading west from x=0.5: front face at 0.2; column -4 spans [-4,-3): near face at x=-3
		TerrainProbe.Ahead a = TerrainProbe.ahead(w, T, 0.5, 0.5, FEET, Heading.WEST, 0.3, 16);
		assertEquals(3.2, a.distance(), 1e-9);
	}

	@Test
	void probeNorthAndSouth() {
		World w = new World().set(0, -6, ground().blocks(64, 65)).set(0, 6, ground().blocks(64, 65));
		assertEquals(0.2 + 5, TerrainProbe.ahead(w, T, 0.5, 0.5, FEET, Heading.NORTH, 0.3, 16).distance(), 1e-9);
		assertEquals(5.2, TerrainProbe.ahead(w, T, 0.5, 0.5, FEET, Heading.SOUTH, 0.3, 16).distance(), 1e-9);
	}

	@Test
	void probeSeesWallsUnderEitherHalfOfTheBike() {
		// bike straddles z=0/z=1 (centre z=1.0): a wall only in row z=0 still blocks
		World w = new World().set(4, 0, ground().blocks(64, 65));
		assertEquals(4 - 0.8, TerrainProbe.ahead(w, T, 0.5, 1.0, FEET, Heading.EAST, 0.3, 16).distance(), 1e-9);
		// centred in row z=1: the wall in row 0 is beside, not ahead
		assertEquals(Double.POSITIVE_INFINITY, TerrainProbe.ahead(w, T, 0.5, 1.5, FEET, Heading.EAST, 0.3, 16).distance());
	}

	@Test
	void probeReportsWaterDropAndHold() {
		Column lake = new Column().blocks(55, 60).add(63, 64, Column.Kind.WATER);
		World w = new World().set(2, 0, lake).set(3, 0, lake);
		TerrainProbe.Ahead a = TerrainProbe.ahead(w, T, 0.5, 0.5, FEET, Heading.EAST, 0.3, 4);
		assertTrue(a.water());
		assertEquals(Double.POSITIVE_INFINITY, a.distance());

		World cliff = new World().set(2, 0, new Column().blocks(40, 50));
		TerrainProbe.Ahead d = TerrainProbe.ahead(cliff, T, 0.5, 0.5, FEET, Heading.EAST, 0.3, 8);
		assertEquals(1.2, d.dropAt(), 1e-9);
		assertEquals(Double.POSITIVE_INFINITY, d.distance(), "a cliff is driven off, not a wall");

		World far = new World().set(3, 0, Column.UNLOADED);
		TerrainProbe.Ahead h = TerrainProbe.ahead(far, T, 0.5, 0.5, FEET, Heading.EAST, 0.3, 8);
		assertTrue(h.hold());
	}

	@Test
	void sideSensorMeasuresClearanceToParallelWall() {
		World w = new World();
		for (int x = -10; x <= 10; x++) {
			w.set(x, 3, ground().blocks(64, 66)); // wall row south of the lane
		}
		// heading east at z=1.5: the right side is south (+z); wall face at z=3; side face at 1.8
		double r = TerrainProbe.side(w, T, 0.5, 1.5, FEET, Heading.EAST, +1, 0.3, 3.6);
		assertEquals(1.2, r, 1e-9);
		double l = TerrainProbe.side(w, T, 0.5, 1.5, FEET, Heading.EAST, -1, 0.3, 3.6);
		assertEquals(Double.POSITIVE_INFINITY, l);
	}
}

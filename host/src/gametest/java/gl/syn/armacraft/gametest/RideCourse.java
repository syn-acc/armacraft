package gl.syn.armacraft.gametest;

import gl.syn.armacraft.Armacraft;
import gl.syn.armacraft.client.ride.RideClient;
import gl.syn.armacraft.client.ride.RideClientMod;
import gl.syn.armacraft.client.ride.RideKeys;
import gl.syn.armacraft.ride.RideRegistry;
import gl.syn.armacraft.ride.core.CycleMovement;
import gl.syn.armacraft.ride.core.Heading;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The ride course: superflat survival world (feet at y=-60) with built obstacles, driven with real key presses.
 * <pre>
 * A  lane z=0 east:  1-block step up x=8..13, step down at 14; 1-high gap (ceiling y=-59) at x=24: stop, rubber
 *    drains, a right turn saves it, rubber regenerates; south into a 2-block wall at z=30: rubber to 0, crash.
 * S  z=-50: a staircase of 1-block steps climbed while turning; never crashes on its own fresh trail.
 * D  z=-100 loop: east, right, right, right: back across the first leg, own-trail crash.
 * E  z=-150 loop: same, but turn left when rubber is below 75%: saved, regenerates.
 * F  z=-200: east under a bridge (deck y=-58 at x=19..21), left, left, left: south up the ramp over the deck,
 *    crossing the first leg 3 blocks above it: no crash.
 * G  z=-258: crash into a wall, redeploy north from the crash point, step up, U-turn back along the first wall and
 *    on across where the dead cycle's trail was (gone at the redeploy).
 * H  z=-300..-340, flat: double-bind 180 and back along the own wall; adjust towards the old wall; the tightest box.
 * I  the same three on a staircase (one block up every two blocks, east and south).
 * J  z=-509: a 1-wide gap in a wall taken 0.25 off its centre (the narrow riding box); dismount inside it.
 * B  lake x=100..140 (water y=-63..-61, flush with the ground): never dips; frost-walker ice (server feature).
 * C  plateau x=196..219 top y=-54: drive off, fall 6 blocks, keep riding, no damage (server feature).
 * </pre>
 * Server features (frost walker, fall immunity) belong to the separate server-side ride code; their assertions are
 * hard when it is registered on {@link RideRegistry} and logged otherwise.
 */
final class RideCourse {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft/ride-course");
	private static final int SECOND = 20;
	private static final int Y = -60;
	/** Half of bike_width (0.2): the riding box against terrain. */
	private static final double HW = 0.1;
	/** Staircase I: origin and size; ground height is floor(dx / 2) + floor(dz / 2) blocks above Y. */
	private static final int IX = 0;
	private static final int IZ = -440;
	private static final int IN = 40;

	private final ClientGameTestContext ctx;
	private TestSingleplayerContext sp;
	private final List<String> results = new ArrayList<>();
	private boolean serverFeatures;

	RideCourse(ClientGameTestContext ctx) {
		this.ctx = ctx;
	}

	void run() {
		try (TestSingleplayerContext s = ctx.worldBuilder().adjustSettings(c -> c.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL)).create()) {
			this.sp = s;
			sp.getConnection().waitForChunksRender();
			sp.getServer().runOnServer(srv -> Armacraft.ride().config().lethalTrails = true); // never trust a config an earlier run saved
			serverFeatures = RideRegistry.listenerCount() > 0;
			LOG.info("course: server-side ride features registered: {}", serverFeatures);
			sp.getServer().runOnServer(server -> build(server.overworld()));
			stepsHeadroomAndWall();
			staircaseWithTurns();
			selfTrailCrash();
			rubberSavesFromTrail();
			bridgeOverOwnTrail();
			redeployAtWallUTurn();
			maneuvers("H", 10.5, -304.5, 30.5, -304.5, false);
			maneuvers("I", IX + 10.5, IZ + IN - 14.5, IX + 28.5, IZ + IN - 14.5, true);
			narrowGap();
			lake();
			cliff();
			boolean connected = ctx.computeOnClient(mc -> mc.getConnection() != null && mc.player != null);
			check(connected, "still connected (no kick)");
			LOG.info("course results:{}  {}", System.lineSeparator(), String.join(System.lineSeparator() + "  ", results));
		}
	}

	// ---------------------------------------------------------------- world

	private static void build(ServerLevel l) {
		BlockState stone = Blocks.STONE.defaultBlockState();
		BlockState glass = Blocks.GLASS.defaultBlockState();
		// A: step platform, ceiling, 2-block wall
		for (int x = 8; x <= 13; x++) {
			for (int z = -1; z <= 1; z++) {
				l.setBlockAndUpdate(new BlockPos(x, Y, z), stone);
			}
		}
		for (int z = -3; z <= 3; z++) {
			l.setBlockAndUpdate(new BlockPos(24, Y + 1, z), glass);
		}
		for (int x = 19; x <= 28; x++) {
			l.setBlockAndUpdate(new BlockPos(x, Y, 30), stone);
			l.setBlockAndUpdate(new BlockPos(x, Y + 1, 30), stone);
		}
		// S: a stepped pyramid around (20, -50): ring k (k = 0..5) is k blocks high, 2 blocks wide per level
		for (int x = 0; x <= 40; x++) {
			for (int z = -70; z <= -30; z++) {
				int ring = Math.min(Math.min(x, 40 - x), Math.min(z + 70, -30 - z)) / 2;
				int h = Math.min(ring, 8);
				for (int y = 0; y < h; y++) {
					l.setBlock(new BlockPos(x, Y + y, z), stone, 2);
				}
			}
		}
		// F: ramp and deck at x=19..21
		for (int x = 19; x <= 21; x++) {
			l.setBlockAndUpdate(new BlockPos(x, Y, -206), stone);
			for (int y = Y; y <= Y + 1; y++) {
				l.setBlockAndUpdate(new BlockPos(x, y, -205), stone);
			}
			for (int y = Y; y <= Y + 2; y++) {
				l.setBlockAndUpdate(new BlockPos(x, y, -204), stone);
			}
			for (int z = -203; z <= -197; z++) {
				l.setBlockAndUpdate(new BlockPos(x, Y + 2, z), stone);
			}
			for (int y = Y; y <= Y + 1; y++) {
				l.setBlockAndUpdate(new BlockPos(x, y, -196), stone);
			}
			l.setBlockAndUpdate(new BlockPos(x, Y, -195), stone);
		}
		// G: a 4-high wall along x=3 (z -275..-245) and a 1-block platform north of z=-261 beside it
		for (int z = -275; z <= -245; z++) {
			for (int y = Y; y <= Y + 3; y++) {
				l.setBlock(new BlockPos(3, y, z), stone, 2);
			}
		}
		for (int x = 4; x <= 10; x++) {
			for (int z = -275; z <= -262; z++) {
				l.setBlock(new BlockPos(x, Y, z), stone, 2);
			}
		}
		// I: staircase, one block up every two blocks east and south
		for (int dx = 0; dx < IN; dx++) {
			for (int dz = 0; dz < IN; dz++) {
				int h = dx / 2 + dz / 2;
				for (int y = 0; y < h; y++) {
					l.setBlock(new BlockPos(IX + dx, Y + y, IZ + dz), stone, 2);
				}
			}
		}
		// J: a wall three blocks thick (x=20..22, z=-520..-500) with a 1-wide gap at z=-510
		for (int x = 20; x <= 22; x++) {
			for (int z = -520; z <= -500; z++) {
				if (z != -510) {
					for (int y = Y; y <= Y + 2; y++) {
						l.setBlock(new BlockPos(x, y, z), stone, 2);
					}
				}
			}
		}
		// B: lake
		BlockState water = Blocks.WATER.defaultBlockState();
		for (int x = 100; x <= 140; x++) {
			for (int z = -6; z <= 6; z++) {
				for (int y = Y - 3; y <= Y - 1; y++) {
					l.setBlock(new BlockPos(x, y, z), water, 2);
				}
			}
		}
		// C: plateau
		for (int x = 196; x <= 219; x++) {
			for (int z = -4; z <= 4; z++) {
				for (int y = Y; y <= Y + 5; y++) {
					l.setBlock(new BlockPos(x, y, z), stone, 2);
				}
			}
		}
	}

	// ---------------------------------------------------------------- A

	private void stepsHeadroomAndWall() {
		deploy(0.5, Y, 0.5, -90f);
		double[] maxY = {-1000};
		double[] minStep = {99};
		double[] lastX = {Double.NaN};
		ctx.waitFor(mc -> {
			Vec3 p = mc.player.position();
			if (p.x > 6 && p.x < 17 && !Double.isNaN(lastX[0])) {
				minStep[0] = Math.min(minStep[0], p.x - lastX[0]);
			}
			lastX[0] = p.x;
			if (p.x > 8.5 && p.x < 13.5) {
				maxY[0] = Math.max(maxY[0], p.y);
			}
			return p.x > 18;
		}, 10 * SECOND);
		double y18 = ctx.computeOnClient(mc -> mc.player.getY());
		check(Math.abs(maxY[0] - (Y + 1)) < 0.01, "A: stepped up 1 block onto the platform (max y " + maxY[0] + ")");
		check(Math.abs(y18 - Y) < 0.01, "A: stepped back down 1 block (y " + y18 + ")");
		check(minStep[0] > 0.5, "A: never stopped on the steps (min advance per tick " + fmt(minStep[0]) + " blocks)");

		// 1-high gap at x=24: a wall
		ctx.waitFor(mc -> rubber() < 0.75 || !riding(), 5 * SECOND);
		check(riding(), "A: still riding while grinding the low gap");
		double x = ctx.computeOnClient(mc -> mc.player.getX());
		check(x < 24 - HW + 0.01 && x > 23.5, "A: stopped at the 1-high gap, front face at the wall (x " + fmt(x) + ")");
		double r0 = rubber();
		check(r0 < 0.75, "A: rubber dropped while pressing into it (" + fmt(r0) + ")");
		ctx.takeScreenshot("ride-course-A1-headroom-stop");
		turnRight();
		ctx.waitTicks(2);
		check(riding() && heading() == Heading.SOUTH, "A: turned away (south) before rubber ran out");
		double r1 = rubber();
		ctx.waitTicks(10); // 0.5 s, ~9 blocks: well before the 2-block wall further south
		double r2 = rubber();
		check(r2 > r1 + 0.01, "A: rubber regenerated after turning away (" + fmt(r1) + " -> " + fmt(r2) + ")");

		// 2-block wall at z=30: crash at 0 rubber
		RideClient rc = RideClientMod.client();
		int crashesBefore = rc.stats.crashes;
		double[] minRubber = {1};
		double[] maxZ = {-1000};
		ctx.waitFor(mc -> {
			if (RideClientMod.client().riding()) {
				CycleMovement m = RideClientMod.client().movement();
				minRubber[0] = Math.min(minRubber[0], m.rubberLeft());
				maxZ[0] = Math.max(maxZ[0], mc.player.getZ());
			}
			return !RideClientMod.client().riding();
		}, 6 * SECOND);
		check(rc.stats.crashes == crashesBefore + 1 && "hit a wall".equals(rc.stats.lastCause), "A: crashed into the 2-block wall (" + rc.stats.lastCause + ")");
		check(maxZ[0] < 30 - HW + 0.01, "A: never went through the wall (max z " + fmt(maxZ[0]) + ")");
		check(rc.movement().rubberLeft() == 0 && minRubber[0] < 0.35, "A: rubber hit 0 at the crash (min seen while riding " + fmt(minRubber[0]) + ")");
		ctx.waitTicks(3);
		ctx.takeScreenshot("ride-course-A2-wall-crash");
		sp.getServer().waitFor(s -> Armacraft.ride().riders().isEmpty(), 2 * SECOND);
		check(true, "A: server ended the ride on the crash");
	}

	// ---------------------------------------------------------------- S

	/** Climb the stepped pyramid in a zig-zag: right, left, right, ... every few ticks, over 1-block steps. */
	private void staircaseWithTurns() {
		deploy(1.5, Y, -68.5, -90f); // east along the bottom ring edge
		RideClient rc = RideClientMod.client();
		int crashes = rc.stats.crashes;
		double[] maxY = {-1000};
		int turns = 0;
		boolean right = true;
		for (int i = 0; i < 12 && riding(); i++) {
			ctx.waitTicks(5);
			if (right) {
				turnRight();
			} else {
				turnLeft();
			}
			right = !right;
			turns++;
			maxY[0] = Math.max(maxY[0], ctx.computeOnClient(mc -> mc.player.getY()));
		}
		ctx.waitTicks(4);
		maxY[0] = Math.max(maxY[0], ctx.computeOnClient(mc -> mc.player.getY()));
		String cause = rc.stats.lastCause;
		boolean ownTrailCrash = rc.stats.crashes > crashes && cause.contains("light trail");
		check(!ownTrailCrash, "S: zig-zag up 1-block steps with " + turns + " turns, no own-fresh-trail crash (crashes "
			+ (rc.stats.crashes - crashes) + (rc.stats.crashes > crashes ? ", cause " + cause : "") + ")");
		check(maxY[0] >= Y + 2, "S: climbed at least 2 levels (max y " + maxY[0] + ")");
		ctx.takeScreenshot("ride-course-S-staircase");
		dismount();
	}

	// ---------------------------------------------------------------- D / E

	private void loopBack(double z0) {
		deploy(0.5, Y, z0, -90f);
		ctx.waitFor(mc -> mc.player.getX() > 16.0, 5 * SECOND);
		turnRight(); // south
		ctx.waitFor(mc -> mc.player.getZ() > z0 + 7, 5 * SECOND);
		turnRight(); // west
		ctx.waitFor(mc -> mc.player.getX() < 9.5, 5 * SECOND);
		turnRight(); // north, back towards the first leg
		ctx.waitTicks(2);
	}

	private void selfTrailCrash() {
		loopBack(-99.5);
		check(heading() == Heading.NORTH, "D: heading north towards the first leg");
		RideClient rc = RideClientMod.client();
		int before = rc.stats.crashes;
		double[] minZ = {1000};
		ctx.waitFor(mc -> {
			minZ[0] = Math.min(minZ[0], mc.player.getZ());
			return !RideClientMod.client().riding();
		}, 4 * SECOND);
		check(rc.stats.crashes == before + 1 && rc.stats.lastCause.contains("light trail"), "D: crashed into own trail (" + rc.stats.lastCause + ")");
		check(minZ[0] > -99.5, "D: stopped short of the trail line (min z " + fmt(minZ[0]) + ")");
		dismount();
		sp.getServer().runOnServer(s -> player(s).teleportTo(s.overworld(), 8.5, Y + 14, -86.5, Set.of(), 180f, 60f, true));
		ctx.waitTicks(10);
		ctx.takeScreenshot("ride-course-D-own-trail-loop");
	}

	private void rubberSavesFromTrail() {
		loopBack(-149.5);
		ctx.waitFor(mc -> rubber() < 0.75 || !riding(), 4 * SECOND);
		check(riding(), "E: still riding while pressing into own trail");
		turnLeft(); // west, along the first leg
		ctx.waitTicks(2);
		check(riding() && heading() == Heading.WEST, "E: turned away from the trail in time");
		// Measure regeneration from the turn on: in a gametest the press's sub-tick phase comes from test-thread wall
		// time, so how much rubber the last tick before the corner burns is not what this checks.
		double r0 = rubber();
		ctx.waitTicks(16);
		double r1 = rubber();
		check(riding(), "E: no crash after turning away");
		check(r1 > r0, "E: rubber regenerating (" + fmt(r0) + " -> " + fmt(r1) + ")");
		ctx.takeScreenshot("ride-course-E-saved-along-trail");
		dismount();
	}

	// ---------------------------------------------------------------- F

	private void bridgeOverOwnTrail() {
		deploy(0.5, Y, -199.5, -90f);
		ctx.waitFor(mc -> mc.player.getX() > 39.0, 6 * SECOND);
		turnLeft(); // north
		ctx.waitFor(mc -> mc.player.getZ() < -207.0, 5 * SECOND);
		turnLeft(); // west
		ctx.waitFor(mc -> mc.player.getX() < 21.0, 5 * SECOND);
		turnLeft(); // south: up the ramp, over the first leg
		double[] yOver = {Double.NaN};
		ctx.waitFor(mc -> {
			Vec3 p = mc.player.position();
			if (p.z > -200.6 && p.z < -198.4) {
				yOver[0] = Double.isNaN(yOver[0]) ? p.y : Math.min(yOver[0], p.y);
			}
			return !RideClientMod.client().riding() || p.z > -190;
		}, 6 * SECOND);
		check(riding(), "F: crossed over own trail on the bridge without crashing (" + RideClientMod.client().stats.lastCause + ")");
		check(Math.abs(yOver[0] - (Y + 3)) < 0.01, "F: crossed 3 blocks above the trail (y " + yOver[0] + ")");
		ctx.takeScreenshot("ride-course-F-after-bridge");
		dismount();
		sp.getServer().runOnServer(s -> player(s).teleportTo(s.overworld(), 32.5, Y + 9, -188.5, Set.of(), 135f, 35f, true));
		ctx.waitTicks(10);
		ctx.takeScreenshot("ride-course-F-bridge-view");
	}

	// ---------------------------------------------------------------- G

	/**
	 * The user's crash: crash into a wall, redeploy north from the crash point (pressed against the wall), step up,
	 * turn into the wall, turn again: a tight U-turn back along its own first wall must ride alongside it.
	 */
	private void redeployAtWallUTurn() {
		deploy(10.5, Y, -258.5, 90f); // west, into the wall at x=3
		ctx.waitFor(mc -> !RideClientMod.client().riding(), 6 * SECOND);
		check("hit a wall".equals(RideClientMod.client().stats.lastCause), "G: crashed into the wall (" + RideClientMod.client().stats.lastCause + ")");
		double crashX = ctx.computeOnClient(mc -> mc.player.getX());
		ctx.runOnClient(mc -> mc.player.setYRot(180f)); // look north: the redeploy heading
		ctx.waitTicks(14);
		int crashes = RideClientMod.client().stats.crashes;
		turnRight(); // any turn key redeploys
		ctx.waitFor(mc -> RideClientMod.client().riding(), 5 * SECOND);
		check(heading() == Heading.NORTH, "G: redeployed north from the crash point (x " + fmt(crashX) + ")");
		ctx.waitFor(mc -> mc.player.getZ() < -268.0 || !RideClientMod.client().riding(), 4 * SECOND);
		double y = ctx.computeOnClient(mc -> mc.player.getY());
		check(riding() && Math.abs(y - (Y + 1)) < 0.01, "G: stepped up onto the platform beside the wall (y " + y + ")");
		turnLeft(); // west, into the wall
		ctx.waitTicks(3);
		turnLeft(); // south: back along its own first wall, down the step, and on across the line the dead cycle's
		// trail ran along (z = -258.5, up to the crash point): it went when the new cycle deployed
		ctx.waitFor(mc -> mc.player.getZ() > -252.0 || !RideClientMod.client().riding(), 3 * SECOND);
		boolean ok = riding() && RideClientMod.client().stats.crashes == crashes;
		String cause = RideClientMod.client().stats.lastCause;
		String detail = RideClientMod.client().stats.lastCrashDetail;
		check(ok && heading() == Heading.SOUTH, "G: tight U-turn rides alongside its own first wall and across the dead cycle's trail line (crashes "
			+ (RideClientMod.client().stats.crashes - crashes) + (ok ? "" : ", " + cause + ": " + detail) + ")");
		check(straightTrail(), "G: every trail piece is axis-aligned");
		dismount();
	}

	// ---------------------------------------------------------------- H / I

	/**
	 * A competitive player's moves against the own wall, with real key presses (keys pressed in the same tick are
	 * "at once": the first turns at the press, the rest queue and follow CYCLE_DELAY apart, the tightest the game
	 * allows). The real engine's outcome for the same scripts (RideDiffTest recordings uturn, adjust, box): survive,
	 * survive, die inside the box on the second wall.
	 */
	private void maneuvers(String id, double x1, double z1, double x2, double z2, boolean stairs) {
		String where = id + (stairs ? " (staircase)" : " (flat)");
		RideClient rc = RideClientMod.client();
		// 1. double-bind 180, back along the own wall, then an adjust towards it
		deploy(x1, groundY(x1, z1, stairs), z1, 180f); // north
		ctx.runOnClient(mc -> RideClientMod.client().stats.maxSideways = 0);
		int crashes = rc.stats.crashes;
		ctx.waitFor(mc -> mc.player.getZ() < z1 - 14 || !RideClientMod.client().riding(), 4 * SECOND);
		press("j", "i"); // right, right
		ctx.waitTicks(6);
		double gap = ctx.computeOnClient(mc -> mc.player.getX()) - x1;
		check(riding() && heading() == Heading.SOUTH, where + ": double-bind 180 made, riding back" + why());
		check(gap > 1.0 && gap < 2.4, where + ": back along its own wall, " + fmt(gap) + " blocks beside it");
		press("j", "w"); // right (towards the old wall), left
		ctx.waitTicks(6);
		double gap2 = ctx.computeOnClient(mc -> mc.player.getX()) - x1;
		check(riding() && heading() == Heading.SOUTH, where + ": adjust towards the old wall made" + why());
		check(gap2 > 0 && gap2 < 0.6, where + ": now " + String.format("%.4f", gap2) + " blocks beside the old wall");
		ctx.waitFor(mc -> mc.player.getZ() > z1 + 3 || !RideClientMod.client().riding(), 4 * SECOND);
		check(riding() && rc.stats.crashes == crashes, where + ": rode on beside the old wall and past its start" + why());
		check(straightTrail(), where + ": every trail piece is axis-aligned");
		double sideways = ctx.computeOnClient(mc -> RideClientMod.client().stats.maxSideways);
		check(sideways == 0, where + ": the bike never moved sideways (" + sideways + ")");
		ctx.takeScreenshot("ride-course-" + id + "1-adjust");
		dismount();

		// 2. the tightest box: four lefts at once
		deploy(x2, groundY(x2, z2, stairs), z2, 180f);
		crashes = rc.stats.crashes;
		ctx.waitFor(mc -> mc.player.getZ() < z2 - 12 || !RideClientMod.client().riding(), 4 * SECOND);
		press("w", "e", "f", "s");
		ctx.waitFor(mc -> !RideClientMod.client().riding(), 8 * SECOND);
		// the first corner: the second wall runs west from it, along its z
		double[] corner = {ctx.computeOnClient(mc -> {
			gl.syn.armacraft.ride.core.Trail t = RideClientMod.client().trail();
			for (int k = 1; k < t.size(); k++) {
				if (t.seg(k) == 1) {
					return t.z(k - 1);
				}
			}
			return Double.NaN;
		})};
		Vec3 at = ctx.computeOnClient(mc -> RideClientMod.client().crashPos() != null ? RideClientMod.client().crashPos() : mc.player.position());
		int turns = ctx.computeOnClient(mc -> RideClientMod.client().movement().turns());
		check(rc.stats.crashes == crashes + 1 && rc.stats.lastCause.contains("light trail"), where + ": boxed in, died on its own wall ("
			+ rc.stats.lastCause + ": " + rc.stats.lastCrashDetail + ")");
		check(turns == 4, where + ": all four turns of the box made before that (" + turns + ")");
		check(rc.stats.lastCrashDetail.contains("seg 1; bike"), where + ": on the second wall (" + rc.stats.lastCrashDetail + ")");
		double beside = at.x - x2;
		check(beside < -0.01 && beside > -0.7, where + ": the fourth leg ran " + fmt(-beside) + " blocks beside the first wall");
		check(Math.abs(at.z - corner[0]) < 0.05 && at.z > corner[0], where + ": stopped in front of the second wall (" + String.format("%.4f", at.z - corner[0])
			+ " blocks)");
		ctx.takeScreenshot("ride-course-" + id + "2-box");
		dismount();
	}

	private static double groundY(double x, double z, boolean stairs) {
		if (!stairs) {
			return Y;
		}
		return Y + (int) Math.floor(x - IX) / 2 + (int) Math.floor(z - IZ) / 2;
	}

	// ---------------------------------------------------------------- J

	/**
	 * The riding box is 0.2 wide: a lane 0.25 off the centre of a 1-wide gap (a 0.6 player box fits only within
	 * 0.2) goes through, in a straight line. Dismounting inside the gap leaves the player clear of the walls.
	 */
	private void narrowGap() {
		sp.getServer().runOnServer(srv -> Armacraft.ride().config().laneCentering = 0); // deploy exactly where placed
		deploy(5.5, Y, -509.25, -90f);
		double[] z = {0, 0};
		ctx.waitFor(mc -> {
			z[0] = Math.max(z[0], Math.abs(mc.player.getZ() + 509.25));
			return mc.player.getX() > 20.6 || !RideClientMod.client().riding();
		}, 5 * SECOND);
		check(riding(), "J: into the 1-wide gap 0.25 off its centre" + why());
		ctx.getInput().pressKey(InputConstants.getKey("key.keyboard.backspace"));
		ctx.waitFor(mc -> !RideClientMod.client().cycleMode(), 3 * SECOND);
		ctx.waitTicks(5);
		double px = ctx.computeOnClient(mc -> mc.player.getX());
		boolean clear = ctx.computeOnClient(mc -> mc.level.noCollision(mc.player));
		float width = ctx.computeOnClient(mc -> mc.player.getBbWidth());
		check(px > 20 && px < 23.3, "J: dismounted inside the gap (x " + fmt(px) + ")");
		check(Math.abs(width - 0.6f) < 1e-6 && clear, "J: back to the 0.6 player box, clear of the walls (width " + width + ", clear " + clear + ")");
		// and straight through
		deploy(5.5, Y, -509.25, -90f);
		float ridingWidth = ctx.computeOnClient(mc -> mc.player.getBbWidth());
		ctx.waitFor(mc -> {
			z[1] = Math.max(z[1], Math.abs(mc.player.getZ() + 509.25));
			return mc.player.getX() > 27 || !RideClientMod.client().riding();
		}, 5 * SECOND);
		check(riding() && z[0] == 0 && z[1] == 0, "J: through the gap in a straight line (sideways " + z[0] + ", " + z[1] + ")" + why());
		check(Math.abs(ridingWidth - 0.2f) < 1e-6, "J: riding box " + ridingWidth + " wide");
		dismount();
		sp.getServer().runOnServer(srv -> Armacraft.ride().config().laneCentering = 3.0);
	}

	// ---------------------------------------------------------------- B / C

	private void lake() {
		// the run directory's server config may be from an earlier build: the current frost defaults (a thin lane)
		sp.getServer().runOnServer(srv -> {
			gl.syn.armacraft.ride.server.RideServer rs = gl.syn.armacraft.ride.server.RideServer.get();
			if (rs != null) {
				gl.syn.armacraft.ride.server.RideServerConfig c = rs.config();
				gl.syn.armacraft.ride.server.RideServerConfig d = new gl.syn.armacraft.ride.server.RideServerConfig();
				c.frostWalker = d.frostWalker;
				c.frostRadius = d.frostRadius;
				c.frostLookaheadSeconds = d.frostLookaheadSeconds;
				c.frostLookaheadMaxBlocks = d.frostLookaheadMaxBlocks;
				c.frostStripHalfWidth = d.frostStripHalfWidth;
			}
		});
		lakeCrossing("B", 0.5, false);
		ctx.takeScreenshot("ride-course-B-lake");
		dismount();
		lakeCrossing("B (boost)", 3.5, true);
		ctx.takeScreenshot("ride-course-B-lake-boost");
		dismount();
	}

	/**
	 * East across the lake (x=100..140) along row floor(z). The ice (a server feature, cosmetic: the bike drives on
	 * water anyway) has to be one unbroken row under the trail and never further than 2 blocks ahead of the bike.
	 */
	private void lakeCrossing(String id, double z, boolean boost) {
		int row = (int) Math.floor(z);
		deploy(86.5, Y, z, -90f);
		if (boost) {
			ctx.getInput().holdKey(InputConstants.getKey("key.keyboard.left.shift"));
		}
		double minY = 1000;
		double maxSpeed = 0;
		double maxAhead = Double.NEGATIVE_INFINITY;
		boolean alive = true;
		for (int i = 0; i < 8 * SECOND; i++) {
			ctx.waitTicks(1);
			double[] st = ctx.computeOnClient(mc -> new double[] {mc.player.getX(), mc.player.getY(), RideClientMod.client().riding() ? 1 : 0,
				RideClientMod.client().movement().speed()});
			if (st[0] > 100 && st[0] < 140) {
				minY = Math.min(minY, st[1]);
				maxSpeed = Math.max(maxSpeed, st[3]);
			}
			// where the farthest frosted block of this row starts, from the bike as the server has it
			double ahead = sp.getServer().computeOnServer(s -> {
				ServerPlayer p = player(s);
				for (int x = 141; x >= 99; x--) {
					if (s.overworld().getBlockState(new BlockPos(x, Y - 1, row)).is(Blocks.FROSTED_ICE)) {
						return x - p.getX();
					}
				}
				return Double.NEGATIVE_INFINITY;
			});
			maxAhead = Math.max(maxAhead, ahead);
			alive = st[2] > 0;
			if (st[0] > 142 || !alive) {
				break;
			}
		}
		if (boost) {
			ctx.getInput().releaseKey(InputConstants.getKey("key.keyboard.left.shift"));
		}
		int[] ice = sp.getServer().computeOnServer(s -> {
			int inRow = 0;
			int beside = 0;
			for (int x = 100; x <= 140; x++) {
				if (s.overworld().getBlockState(new BlockPos(x, Y - 1, row)).is(Blocks.FROSTED_ICE)) {
					inRow++;
				}
				for (int dz = -1; dz <= 1; dz += 2) {
					if (s.overworld().getBlockState(new BlockPos(x, Y - 1, row + dz)).is(Blocks.FROSTED_ICE)) {
						beside++;
					}
				}
			}
			return new int[] {inRow, beside};
		});
		check(alive, id + ": crossed the lake riding" + why());
		check(minY >= Y - 0.001, id + ": never dipped into the water (min y " + minY + ", top speed " + fmt(maxSpeed) + " b/s)");
		if (boost) {
			check(maxSpeed > 22, id + ": boosted across (top speed " + fmt(maxSpeed) + " b/s)");
		}
		soft(ice[0] == 41, id + ": the ice is one unbroken row under the trail (" + ice[0] + " of 41 blocks, top speed " + fmt(maxSpeed) + " b/s)");
		soft(ice[1] == 0, id + ": one block wide (" + ice[1] + " frosted blocks in the rows beside it)");
		soft(maxAhead <= 2.0 + 1e-6, id + ": never further than 2 blocks ahead of the bike (the farthest frosted block started "
			+ fmt(maxAhead) + " ahead)");
	}

	private void cliff() {
		deploy(197.5, Y + 6, 0.5, -90f);
		float hp0 = sp.getServer().computeOnServer(s -> player(s).getHealth());
		ctx.waitFor(mc -> mc.player.getX() > 220.5 && mc.player.getY() < Y + 3 || !RideClientMod.client().riding(), 4 * SECOND);
		ctx.takeScreenshot("ride-course-C-falling");
		ctx.waitFor(mc -> mc.player.onGround() && mc.player.getY() < Y + 0.01 || !RideClientMod.client().riding(), 4 * SECOND);
		ctx.waitTicks(5);
		float hp1 = sp.getServer().computeOnServer(s -> player(s).getHealth());
		double y = ctx.computeOnClient(mc -> mc.player.getY());
		check(riding(), "C: drove off the cliff and kept riding");
		check(Math.abs(y - Y) < 0.01, "C: landed on the ground below (y " + y + ")");
		soft(hp1 >= hp0, "C: no fall damage (health " + hp0 + " -> " + hp1 + ")");
		dismount();
	}

	// ---------------------------------------------------------------- helpers

	/** Default turn keys: W/E/F/Left turn left, J/I/O/Right turn right (raw key presses). */
	private void turnLeft() {
		ctx.getInput().pressKey(InputConstants.getKey("key.keyboard.w"));
	}

	private void turnRight() {
		ctx.getInput().pressKey(InputConstants.getKey("key.keyboard.j"));
	}

	/** Keys pressed "at once" (the same client tick): left keys d e f s w, right keys i j k l o. */
	private void press(String... keys) {
		ctx.runOnClient(mc -> {
			long h = mc.getWindow().handle();
			for (String k : keys) {
				InputConstants.Key key = InputConstants.getKey("key.keyboard." + k);
				mc.keyboardHandler.keyPress(h, 1, new net.minecraft.client.input.KeyEvent(key.getValue(), 0, 0));
				mc.keyboardHandler.keyPress(h, 0, new net.minecraft.client.input.KeyEvent(key.getValue(), 0, 0));
			}
		});
	}

	/** Why the ride ended, if it did (for a failing check's message). */
	private String why() {
		return riding() ? "" : " [not riding: " + RideClientMod.client().stats.lastCause + ": " + RideClientMod.client().stats.lastCrashDetail + "]";
	}

	private boolean straightTrail() {
		return ctx.computeOnClient(mc -> RideClientMod.client().trail().axisAligned(1e-9));
	}

	private void deploy(double x, double y, double z, float yaw) {
		dismount();
		sp.getServer().runOnServer(s -> {
			ServerPlayer p = player(s);
			p.teleportTo(s.overworld(), x, y, z, Set.of(), yaw, 15f, true);
			p.setHealth(p.getMaxHealth());
			p.resetFallDistance();
		});
		ctx.waitFor(mc -> mc.player.position().distanceTo(new Vec3(x, y, z)) < 0.2, 5 * SECOND);
		ctx.waitTicks(10);
		ctx.getInput().pressKey(RideKeys.RIDE);
		ctx.waitFor(mc -> RideClientMod.client().riding(), 5 * SECOND);
		LOG.info("deployed at {} {} {} heading {}", x, y, z, Heading.name(heading()));
	}

	/** Leave cycle mode (Backspace): bike stowed, vanilla back. */
	private void dismount() {
		if (ctx.computeOnClient(mc -> RideClientMod.client().cycleMode())) {
			ctx.getInput().pressKey(InputConstants.getKey("key.keyboard.backspace"));
			ctx.waitFor(mc -> !RideClientMod.client().cycleMode(), 3 * SECOND);
		}
		sp.getServer().waitFor(s -> Armacraft.ride().riders().isEmpty(), 3 * SECOND);
	}

	private static ServerPlayer player(MinecraftServer s) {
		return s.getPlayerList().getPlayers().get(0);
	}

	private boolean riding() {
		return ctx.computeOnClient(mc -> RideClientMod.client().riding());
	}

	private double rubber() {
		return ctx.computeOnClient(mc -> {
			CycleMovement m = RideClientMod.client().movement();
			return m == null ? 0 : m.rubberLeft();
		});
	}

	private int heading() {
		return ctx.computeOnClient(mc -> {
			CycleMovement m = RideClientMod.client().movement();
			return m == null ? -1 : m.heading();
		});
	}

	private void check(boolean ok, String what) {
		results.add((ok ? "PASS " : "FAIL ") + what);
		LOG.info("{} {}", ok ? "PASS" : "FAIL", what);
		if (!ok) {
			ctx.takeScreenshot("ride-course-FAIL");
			throw new AssertionError(what + System.lineSeparator() + String.join(System.lineSeparator(), results));
		}
	}

	/** Server-side ride features: hard when registered, logged otherwise. */
	private void soft(boolean ok, String what) {
		if (serverFeatures) {
			check(ok, what);
		} else {
			results.add((ok ? "PASS " : "SKIP ") + what + (ok ? "" : " [server-side ride features not registered]"));
			LOG.info("{} {}", ok ? "PASS" : "SKIP (no server features)", what);
		}
	}

	private static String fmt(double v) {
		return String.format("%.3f", v);
	}
}

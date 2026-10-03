package gl.syn.armacraft.gametest;

import gl.syn.armacraft.Armacraft;
import gl.syn.armacraft.ride.RideFallback;
import gl.syn.armacraft.ride.RideService;
import gl.syn.armacraft.ride.core.Heading;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Ride mode with several riders on a real GameTest server: another rider's trail as a server-validated wall, the
 * vertical-overlap rule, and the display-entity stand-ins for clients without the mod. Riders are mock players
 * (no client mod: the client-mod check is skipped through {@link RideService#startUnchecked}) plus one virtual rider
 * (a trail and a pose without a player).
 */
public class RideMultiplayerGameTests {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft/ride-mp-gametest");

	@GameTest(maxTicks = 200)
	public void otherRidersTrailsAndVanillaFallback(GameTestHelper helper) {
		RideService ride = Armacraft.ride();
		ServerLevel level = helper.getLevel();
		Vec3 o = helper.absoluteVec(new Vec3(1, 2, 1));
		UUID virtualId = UUID.randomUUID();
		ServerPlayer[] mock = new ServerPlayer[2];
		int[] killsBefore = {0};

		helper.startSequence()
			.thenExecute(() -> {
				killsBefore[0] = ride.trailKills();
				// rider A (virtual): a trail along x at z = o.z + 3, riding east at its end
				RideService.Rider a = ride.addVirtualRider(level.dimension(), virtualId, "virtualA", 0xFF3030, Heading.EAST, o.x + 6, o.y, o.z + 3);
				a.trail.append(o.x, o.y, o.z + 3, 0);
				a.trail.append(o.x + 6, o.y, o.z + 3, 0);
				// B rides on the trail's level, C rides 5 blocks above it
				mock[0] = helper.makeMockServerPlayerInLevel();
				mock[1] = helper.makeMockServerPlayerInLevel();
				mock[0].teleportTo(level, o.x + 3, o.y, o.z + 1, Set.of(), 0f, 0f, true);
				mock[1].teleportTo(level, o.x + 2, o.y + 5, o.z + 1, Set.of(), 0f, 0f, true);
				helper.assertTrue(ride.startUnchecked(mock[0], 0f) == null, "B started riding");
				helper.assertTrue(ride.startUnchecked(mock[1], 0f) == null, "C started riding");
			})
			.thenWaitUntil(() -> {
				List<Display> fx = level.getEntitiesOfClass(Display.class, new AABB(o.x - 2, o.y - 2, o.z - 2, o.x + 9, o.y + 8, o.z + 9),
					e -> e.entityTags().contains(RideFallback.TAG));
				// A's bike + A's trail piece + B's bike + C's bike (B and C have no trail yet)
				helper.assertTrue(fx.size() >= 4, "fallback displays for vanilla viewers: " + fx.size());
				for (Display d : fx) {
					helper.assertTrue(!d.shouldBeSaved(), "fallback displays are never saved");
					helper.assertTrue(d.broadcastToPlayer(mock[0]), "a player without the mod is sent the fallback");
				}
				long bikes = fx.stream().filter(d -> d instanceof Display.BlockDisplay bd && bd.getBlockState().getBlock().getDescriptionId().contains("concrete")).count();
				helper.assertTrue(bikes >= 3, "three stand-in bikes (concrete), got " + bikes);
				LOG.info("fallback displays near the riders: {} ({} bikes)", fx.size(), bikes);
			})
			.thenExecute(() -> {
				// both cross the line z = o.z + 3 in one move: B at the trail's height, C on a "bridge" above it
				mock[0].teleportTo(level, o.x + 3, o.y, o.z + 5, Set.of(), 0f, 0f, true);
				mock[1].teleportTo(level, o.x + 2, o.y + 5, o.z + 5, Set.of(), 0f, 0f, true);
			})
			.thenWaitUntil(() -> {
				helper.assertTrue(ride.rider(mock[0].getUUID()) == null && ride.trailKills() > killsBefore[0],
					"B crossed another rider's trail at its height: the server crashed it");
			})
			.thenExecute(() -> {
				helper.assertTrue(ride.rider(mock[1].getUUID()) != null, "C crossed 5 blocks above the trail and still rides");
				LOG.info("server trail validation: B crashed (kills {} -> {}), C above the trail still riding", killsBefore[0], ride.trailKills());
				// a rider's collision box is narrow (bike_width), a player's again after the ride
				helper.assertTrue(Math.abs(mock[1].getBbWidth() - 0.2f) < 1e-6, "riding box width " + mock[1].getBbWidth());
				// C crashes with a trail: its walls stay up (WALLS_STAY_UP_DELAY) ...
				RideService.Rider c = ride.rider(mock[1].getUUID());
				c.trail.append(o.x + 2, o.y + 5, o.z + 1, 0);
				c.trail.append(o.x + 2, o.y + 5, o.z + 5, 0);
				ride.stop(mock[1], gl.syn.armacraft.ride.RideRegistry.StopReason.CRASH, mock[1].position(), false, "");
				helper.assertTrue(Math.abs(mock[1].getBbWidth() - 0.6f) < 1e-6, "player box width after the ride " + mock[1].getBbWidth());
				helper.assertTrue(ride.fadingTrails().stream().anyMatch(r -> r.uuid.equals(mock[1].getUUID())),
					"a dead cycle's walls stay up (stay-up delay " + ride.config().wallsStayUp() + " s)");
				// ... until its player deploys a new cycle: then they are gone, for everyone
				helper.assertTrue(ride.startUnchecked(mock[1], 0f) == null, "C deployed again");
				helper.assertTrue(ride.fadingTrails().stream().noneMatch(r -> r.uuid.equals(mock[1].getUUID())),
					"the dead cycle's walls went when its player deployed again");
				ride.stop(mock[1], gl.syn.armacraft.ride.RideRegistry.StopReason.DISMOUNT, mock[1].position(), false, "");
				ride.removeVirtualRider(virtualId);
			})
			.thenSucceed();
	}

	@SuppressWarnings("unused")
	private static boolean tagged(Entity e) {
		return e.entityTags().contains(RideFallback.TAG);
	}
}

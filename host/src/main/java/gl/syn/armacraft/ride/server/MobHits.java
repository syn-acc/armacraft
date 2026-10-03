package gl.syn.armacraft.ride.server;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Living non-player mobs in a box ahead of a moving rider take speed-scaled damage (as the rider's attack, so kill
 * credit and retaliation work like a melee hit) plus knockback along the heading, at most once per cooldown per mob.
 * Players are never touched: only {@link Mob}s are selected, and a Player is never a Mob.
 */
public final class MobHits {

	private MobHits() {}

	/** @return mobs hit this call */
	public static int apply(ServerLevel level, ServerPlayer rider, int hx, int hz, double speed, long now, HitCooldown cooldown,
		RideServerConfig cfg) {
		double damage = MobHitMath.damage(speed, cfg.mobHitMinSpeed, cfg.mobDamagePerSpeed, cfg.mobDamageMax);
		if (!(damage > 0) || (hx == 0 && hz == 0)) {
			return 0;
		}
		AABB bb = rider.getBoundingBox();
		double[] b = MobHitMath.aheadBox(new double[] {bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ}, hx, hz,
			MobHitMath.reach(speed, cfg.mobHitReach), cfg.mobHitLateral);
		AABB box = new AABB(b[0], b[1], b[2], b[3], b[4], b[5]);
		List<Mob> mobs = level.getEntitiesOfClass(Mob.class, box,
			m -> m.isAlive() && m.isAttackable() && !m.isInvulnerable() && !m.isPassengerOfSameVehicle(rider));
		double knockback = MobHitMath.knockback(speed, cfg.mobKnockbackBase, cfg.mobKnockbackPerSpeed, cfg.mobKnockbackMax);
		int hit = 0;
		for (Mob mob : mobs) {
			if (!cooldown.tryHit(mob.getUUID(), now, cfg.mobHitCooldownTicks)) {
				continue;
			}
			DamageSource source = rider.damageSources().playerAttack(rider);
			mob.hurtServer(level, source, (float) damage);
			if (knockback > 0 && mob.isAlive()) {
				// LivingEntity.knockback pushes away from (xd, zd): pass the reverse heading to push along it
				mob.knockback(knockback, -hx, -hz, source, (float) damage);
			}
			hit++;
		}
		return hit;
	}
}

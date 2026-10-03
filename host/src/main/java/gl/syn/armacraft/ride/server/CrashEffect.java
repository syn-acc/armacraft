package gl.syn.armacraft.ride.server;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Explosion look and sound at a crash point, for every player in range. Particles and a sound only: no
 * {@code ServerLevel.explode}, so no block damage, no entity damage, no knockback.
 */
public final class CrashEffect {

	private CrashEffect() {}

	/** @return players the main particle packet reached (overrideLimiter: within 512 blocks) */
	public static int play(ServerLevel level, Vec3 at, float volume) {
		int reached = level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, true, true, at.x, at.y + 0.5, at.z, 1, 0, 0, 0, 0);
		level.sendParticles(ParticleTypes.EXPLOSION, true, true, at.x, at.y + 0.5, at.z, 10, 1.0, 0.5, 1.0, 0);
		RandomSource r = level.getRandom();
		float pitch = (1.0F + (r.nextFloat() - r.nextFloat()) * 0.2F) * 0.7F; // ServerExplosion's pitch
		level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, volume, pitch);
		return reached;
	}
}

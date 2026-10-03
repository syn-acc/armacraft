package gl.syn.armacraft.client.ride.render;

import gl.syn.armacraft.client.ride.api.EngineSound;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/** Placeholder engine: vanilla minecart rolling loop, pitch and volume following speed. */
public final class PlaceholderEngine implements EngineSound {

	private Loop loop;

	@Override
	public void start(State s) {
		stop();
		loop = new Loop();
		loop.set(s);
		Minecraft.getInstance().getSoundManager().play(loop);
	}

	@Override
	public void update(State s) {
		if (loop == null || loop.isStopped()) {
			start(s);
			return;
		}
		loop.set(s);
	}

	@Override
	public void stop() {
		if (loop != null) {
			loop.end();
			loop = null;
		}
	}

	private static final class Loop extends AbstractTickableSoundInstance {
		Loop() {
			super(SoundEvents.MINECART_RIDING, SoundSource.PLAYERS, RandomSource.create());
			this.looping = true;
			this.delay = 0;
			this.volume = 0.0001f;
			this.relative = false;
		}

		void set(State s) {
			double f = s.maxSpeed() > 0 ? Math.min(1.5, s.speed() / (s.maxSpeed() * 0.5)) : 1;
			this.volume = (float) Math.max(0.05, Math.min(0.7, 0.25 + 0.35 * f));
			this.pitch = (float) Math.max(0.5, Math.min(2.0, 0.6 + 0.8 * f + (s.grinding() ? 0.3 : 0)));
			this.x = s.pos().x;
			this.y = s.pos().y;
			this.z = s.pos().z;
		}

		void end() {
			this.stop();
		}

		@Override
		public void tick() {
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}
	}
}

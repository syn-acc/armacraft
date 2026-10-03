package gl.syn.armacraft.client.ridekit;

import gl.syn.armacraft.client.ArenaClient;
import gl.syn.armacraft.client.kit.ArmaSoundMix;
import gl.syn.armacraft.client.kit.EngineLoop;
import gl.syn.armacraft.client.ride.api.EngineSound;
import gl.syn.armacraft.client.sound.ArmaSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.function.DoubleSupplier;

/**
 * Ride mode's engine: Armagetron's cyclrun loop through {@link EngineLoop}, pitch {@code speed / CYCLE_SOUND_SPEED}
 * in Armagetron units (gCycle::SoundMix), level from Armagetron's own mix (eCamera::SoundMixGameObject: the watched
 * cycle seen from the camera eye) times {@code sound.gain}. Grinding plays Armagetron's spark sound, turns (from the
 * camera's {@code onTurn}) its turn sound. {@code config/armacraft_client.json sound.enabled=false} mutes all of it.
 */
public final class ArmaEngineSound implements EngineSound {

	private final DoubleSupplier blocksPerUnit;
	private volatile State state;
	private EngineLoop loop;
	private long lastSpark;

	public ArmaEngineSound(DoubleSupplier blocksPerUnit) {
		this.blocksPerUnit = blocksPerUnit;
	}

	private static boolean enabled() {
		ArenaClient c = ArenaClient.get();
		return c == null || c.config().sound.enabled;
	}

	private static double gain() {
		ArenaClient c = ArenaClient.get();
		return c == null ? 6.0 : c.config().sound.gain;
	}

	private static double soundSpeed() {
		ArenaClient c = ArenaClient.get();
		return c == null ? 30.0 : c.config().sound.soundSpeed;
	}

	@Override
	public void start(State s) {
		state = s;
		stopLoop();
		if (!enabled()) {
			return;
		}
		loop = new EngineLoop(new EngineLoop.Target() {
			@Override
			public Vec3 position() {
				State st = state;
				return st == null ? null : st.pos();
			}

			@Override
			public double speed() {
				State st = state;
				double bpu = blocksPerUnit.getAsDouble();
				return st == null ? 0 : st.speed() / (bpu > 0 ? bpu : 1);
			}

			@Override
			public float volume() {
				State st = state;
				if (st == null) {
					return 0;
				}
				ArmaChaseCamera cam = ArmaRideVisuals.CAMERA;
				Vec3 eye = cam.eye();
				if (eye == Vec3.ZERO) {
					eye = st.pos();
				}
				double level = ArmaSoundMix.level(eye, cam.yaw(), st.pos(), blocksPerUnit.getAsDouble(), true, false);
				return (float) (level * gain());
			}

			@Override
			public boolean active() {
				return state != null;
			}
		}, soundSpeed());
		Minecraft.getInstance().getSoundManager().play(loop);
	}

	@Override
	public void update(State s) {
		state = s;
		if (s.grinding() && enabled()) {
			long now = System.nanoTime();
			if (now - lastSpark > 150_000_000L) {
				lastSpark = now;
				oneShot(ArmaSounds.SPARK, 0.5, s.pos());
			}
		}
	}

	@Override
	public void stop() {
		state = null;
		stopLoop();
	}

	/** Armagetron's turn sound (expl.wav at 5x speed), from the camera's onTurn. */
	void turned() {
		State s = state;
		if (s != null && enabled()) {
			oneShot(ArmaSounds.TURN, 1.0, s.pos());
		}
	}

	/** Armagetron level of the watched cycle seen from the camera eye, times gain and a per-sound factor. */
	private void oneShot(SoundEvent ev, double factor, Vec3 at) {
		ArmaChaseCamera cam = ArmaRideVisuals.CAMERA;
		double level = ArmaSoundMix.level(cam.eye(), cam.yaw(), at, blocksPerUnit.getAsDouble(), true, false);
		float vol = (float) Math.min(1, level * gain() * factor);
		if (vol > 0.001f) {
			Minecraft.getInstance().getSoundManager().play(new OneShot(ev, at, vol));
		}
	}

	private static final class OneShot extends AbstractSoundInstance {
		OneShot(SoundEvent ev, Vec3 at, float volume) {
			super(ev, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
			this.x = at.x;
			this.y = at.y;
			this.z = at.z;
			this.volume = volume;
			this.pitch = 1;
			this.attenuation = SoundInstance.Attenuation.NONE;
		}
	}

	private void stopLoop() {
		if (loop != null) {
			loop.finish();
			Minecraft.getInstance().getSoundManager().stop(loop);
			loop = null;
		}
	}
}

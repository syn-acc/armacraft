package gl.syn.armacraft.client.kit;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Armagetron's engine loop (sound/cyclrun.wav) for any moving target: positioned, looping, distance attenuation off
 * (Armagetron mixes its own level, see {@link ArmaSoundMix}), playback speed {@code speed / CYCLE_SOUND_SPEED}
 * like gCycle::SoundMix. Updated every client tick from the {@link Target}. No bridge types.
 *
 * <pre>
 * EngineLoop loop = new EngineLoop(target, 30.0);
 * Minecraft.getInstance().getSoundManager().play(loop);   // stops itself when target.active() turns false
 * </pre>
 */
public final class EngineLoop extends AbstractTickableSoundInstance {

	public static final SoundEvent ENGINE = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("armacraft_client", "engine"));

	/** What the loop follows. */
	public interface Target {
		/** Minecraft position of the sound source. */
		Vec3 position();

		/** Speed in Armagetron units per second (pitch = speed / soundSpeed; Minecraft clamps pitch to 0.5..2). */
		double speed();

		/** Linear volume 0..1 (e.g. {@link ArmaSoundMix#level} times a gain). */
		float volume();

		/** False ends the loop. */
		boolean active();
	}

	private final Target target;
	private final double soundSpeed;

	/** @param soundSpeed CYCLE_SOUND_SPEED (settings.cfg 30) */
	public EngineLoop(Target target, double soundSpeed) {
		super(ENGINE, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
		this.target = target;
		this.soundSpeed = soundSpeed > 0 ? soundSpeed : 30;
		this.looping = true;
		this.delay = 0;
		this.attenuation = SoundInstance.Attenuation.NONE;
		update();
	}

	private void update() {
		Vec3 p = target.position();
		if (p != null) {
			x = p.x;
			y = p.y;
			z = p.z;
		}
		volume = Math.max(0.0001f, Math.min(1f, target.volume()));
		pitch = (float) (target.speed() / soundSpeed);
	}

	@Override
	public void tick() {
		if (!target.active()) {
			stop();
			return;
		}
		update();
	}

	/** Stop from outside (the sound manager also needs {@code SoundManager.stop(this)}). */
	public void finish() {
		stop();
	}

	@Override
	public boolean canStartSilent() {
		return true;
	}
}

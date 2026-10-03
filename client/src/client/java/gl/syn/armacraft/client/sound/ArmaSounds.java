package gl.syn.armacraft.client.sound;

import gl.syn.armacraft.client.ArenaClient;
import gl.syn.armacraft.client.core.ArenaModel;
import gl.syn.armacraft.client.core.V2;
import gl.syn.armacraft.client.kit.ArmaSoundMix;
import gl.syn.armacraft.client.kit.EngineLoop;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Armagetron's sounds (sound/cyclrun.wav, sound/expl.wav) mixed like eCamera::SoundMixGameObject and
 * gCycle::SoundMix:
 * <ul>
 *   <li>Volume falls off as {@code (dist*.5 +- side)/dist^2} per channel, capped at MAXVOL .4, with
 *       {@code dist^2 = horizontal^2 + dz^2 >= 1}; the watched cycle is fixed at .2 in the in-cycle camera
 *       and scaled by .9 otherwise. Minecraft only gets the mono level (sum/2) times {@code gain}; panning
 *       comes from the positioned source (attenuation off).</li>
 *   <li>Engine: looping, playback speed {@code speed / CYCLE_SOUND_SPEED}.</li>
 *   <li>Turn: Armagetron 0.2.9 without the moviepack plays expl.wav at speed 5 (pre-rendered as turn.ogg).</li>
 *   <li>Grind sparks: expl.wav at speed 4 at half volume (spark.ogg).</li>
 *   <li>Explosion: expl.wav at speed 1, volume x4.</li>
 * </ul>
 */
public final class ArmaSounds {

	public static final SoundEvent ENGINE = EngineLoop.ENGINE;
	public static final SoundEvent TURN = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("armacraft_client", "turn"));
	public static final SoundEvent EXPLODE = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("armacraft_client", "explode"));
	public static final SoundEvent SPARK = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("armacraft_client", "spark"));

	private final ArenaClient client;
	private final Map<Integer, EngineLoop> engines = new HashMap<>();
	private final Map<Integer, Double> lastStart = new HashMap<>();
	private final Map<Integer, Double> lastSpark = new HashMap<>();

	public ArmaSounds(ArenaClient client) {
		this.client = client;
	}

	/** Armagetron per-channel mix level for an object at (x, y, z) (arena coordinates), mono-averaged. */
	public double armaLevel(double x, double y, double z, boolean watched) {
		double[] eye = client.soundEye();
		if (eye == null) {
			return 0;
		}
		return ArmaSoundMix.level(eye, new V2(eye[3], eye[4]), x, y, z, watched, client.inCycleCamera());
	}

	private float mcVolume(double armaLevel, double factor) {
		double v = armaLevel * factor * client.config().sound.gain;
		return (float) Math.max(0, Math.min(1, v));
	}

	public void tick() {
		Minecraft mc = Minecraft.getInstance();
		boolean on = client.config().sound.enabled && client.active();
		double now = ArenaClient.localNow();
		for (ArenaModel.Cycle c : client.model().cycles()) {
			EngineLoop e = engines.get(c.id);
			boolean want = on && c.alive && client.gameTime() > -10;
			Double started = lastStart.get(c.id);
			if (want && (e == null || e.isStopped() || !mc.getSoundManager().isActive(e)) && (started == null || now - started > 1.0)) {
				if (e != null) {
					mc.getSoundManager().stop(e);
				}
				e = new EngineLoop(new CycleTarget(c.id), client.config().sound.soundSpeed);
				engines.put(c.id, e);
				lastStart.put(c.id, now);
				mc.getSoundManager().play(e);
			} else if (!want && e != null) {
				e.finish();
				mc.getSoundManager().stop(e);
				engines.remove(c.id);
			}
		}
		for (Iterator<Map.Entry<Integer, EngineLoop>> it = engines.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Integer, EngineLoop> en = it.next();
			if (client.model().cycle(en.getKey()) == null || !on) {
				en.getValue().finish();
				mc.getSoundManager().stop(en.getValue());
				it.remove();
			}
		}
	}

	/** Dev stats: active engine loops and the watched cycle's engine volume/pitch. */
	public String describe() {
		Minecraft mc = Minecraft.getInstance();
		int active = 0;
		String watched = "-";
		for (Map.Entry<Integer, EngineLoop> e : engines.entrySet()) {
			if (mc.getSoundManager().isActive(e.getValue())) {
				active++;
			}
			ArenaModel.Cycle c = client.model().cycle(e.getKey());
			if (c != null && client.isWatched(c)) {
				watched = String.format("vol %.3f pitch %.2f", e.getValue().getVolume(), e.getValue().getPitch());
			}
		}
		return "engines " + active + "/" + engines.size() + " watched " + watched + " oneshots " + oneShots;
	}

	private int oneShots;

	public void stopAll() {
		Minecraft mc = Minecraft.getInstance();
		for (EngineLoop e : engines.values()) {
			e.finish();
			mc.getSoundManager().stop(e);
		}
		engines.clear();
	}

	public void turn(ArenaModel.Cycle c) {
		oneShot(TURN, c.id, 1.0);
	}

	public void spark(ArenaModel.Cycle c, double now) {
		Double last = lastSpark.get(c.id);
		if (last != null && now - last < 0.12) {
			return;
		}
		lastSpark.put(c.id, now);
		oneShot(SPARK, c.id, .5);
	}

	public void explosion(double x, double y) {
		if (!client.config().sound.enabled || !client.active()) {
			return;
		}
		double[] mc = client.toMc(x, y, 0);
		double level = armaLevel(x, y, 0, false);
		play(EXPLODE, mc, mcVolume(level, 4));
	}

	private void oneShot(SoundEvent ev, int cycleId, double factor) {
		if (!client.config().sound.enabled || !client.active()) {
			return;
		}
		ArenaModel.Cycle c = client.model().cycle(cycleId);
		if (c == null) {
			return;
		}
		V2 p = client.renderPos(c);
		double[] mc = client.toMc(p.x(), p.y(), .75);
		double level = armaLevel(p.x(), p.y(), .75, client.isWatched(c));
		play(ev, mc, mcVolume(level, factor));
	}

	private void play(SoundEvent ev, double[] pos, float volume) {
		if (volume <= 0.001f) {
			return;
		}
		oneShots++;
		Minecraft.getInstance().getSoundManager().play(new OneShot(ev, pos, volume));
	}

	private static final class OneShot extends AbstractSoundInstance {
		OneShot(SoundEvent ev, double[] pos, float volume) {
			super(ev, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
			this.x = pos[0];
			this.y = pos[1];
			this.z = pos[2];
			this.volume = volume;
			this.pitch = 1;
			this.attenuation = SoundInstance.Attenuation.NONE;
		}
	}

	/** Arena cycle as an {@link EngineLoop.Target}. */
	private final class CycleTarget implements EngineLoop.Target {
		private final int id;

		CycleTarget(int id) {
			this.id = id;
		}

		@Override
		public Vec3 position() {
			ArenaModel.Cycle c = client.model().cycle(id);
			if (c == null) {
				return null;
			}
			V2 p = client.renderPos(c);
			double[] mc = client.toMc(p.x(), p.y(), .75);
			return new Vec3(mc[0], mc[1], mc[2]);
		}

		@Override
		public double speed() {
			ArenaModel.Cycle c = client.model().cycle(id);
			return c == null ? 0 : c.speed;
		}

		@Override
		public float volume() {
			ArenaModel.Cycle c = client.model().cycle(id);
			if (c == null || !c.alive) {
				return 0;
			}
			V2 p = client.renderPos(c);
			return mcVolume(armaLevel(p.x(), p.y(), .75, client.isWatched(c)), 1);
		}

		@Override
		public boolean active() {
			ArenaModel.Cycle c = client.model().cycle(id);
			return c != null && c.alive && client.active();
		}
	}
}

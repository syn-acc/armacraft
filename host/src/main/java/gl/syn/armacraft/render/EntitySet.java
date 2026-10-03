package gl.syn.armacraft.render;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Display entities keyed by protocol id, reconciled against the desired specs every tick.
 *
 * <p>Per tick: {@link #begin}, then {@link #want} for every desired key, then {@link #end}, which discards
 * every tracked entity that was not wanted this tick (a generation sweep: no per-tick key-set copies).
 * {@link #sync} wraps the three for callers that already have a map.
 *
 * <p>Entities whose chunk is not loaded are simply not created (and retried next tick). Entities removed
 * by the world (chunk unload; they are never saved, see EntityMixin) are recreated when their chunk is
 * loaded again.
 */
public final class EntitySet<K> {

	public static final String TAG = "armacraft";

	private static final class Tracked {
		final Display entity;
		Specs spec;
		int gen;

		Tracked(Display entity, Specs spec, int gen) {
			this.entity = entity;
			this.spec = spec;
			this.gen = gen;
		}
	}

	private final String kindTag;
	private final Map<K, Tracked> tracked = new HashMap<>();
	private int gen;
	private @Nullable ServerLevel level;
	/** Diagnostics: creations skipped because the chunk was not loaded / rejected by the level. */
	private long skippedUnloaded;
	private long addRejected;

	public EntitySet(String kind) {
		this.kindTag = TAG + "." + kind;
	}

	public void sync(ServerLevel level, Map<K, ? extends Specs> desired) {
		begin(level);
		for (Map.Entry<K, ? extends Specs> e : desired.entrySet()) {
			want(e.getKey(), e.getValue());
		}
		end();
	}

	/** Start a tick: everything not {@link #want wanted} before {@link #end} is discarded. */
	public void begin(ServerLevel level) {
		this.level = level;
		gen++;
	}

	public void want(K key, Specs spec) {
		ServerLevel level = this.level;
		if (level == null) {
			throw new IllegalStateException("want() outside begin()/end()");
		}
		Tracked t = tracked.get(key);
		if (t != null && (t.entity.isRemoved() || t.entity.level() != level)) {
			if (!t.entity.isRemoved()) {
				t.entity.discard();
			}
			tracked.remove(key);
			t = null;
		}
		if (t == null) {
			if (!canSpawnAt(level, spec.x(), spec.z())) {
				skippedUnloaded++;
				return;
			}
			Display d = spec.create(level);
			d.addTag(TAG);
			d.addTag(kindTag);
			if (level.addFreshEntity(d)) {
				tracked.put(key, new Tracked(d, spec, gen));
			} else {
				addRejected++;
			}
			return;
		}
		t.gen = gen;
		// identity first: the renderer reuses spec instances for unchanged walls
		if (t.spec != spec && !t.spec.equals(spec)) {
			spec.update(t.entity, t.spec);
			t.spec = spec;
		}
	}

	public void end() {
		for (Iterator<Tracked> it = tracked.values().iterator(); it.hasNext(); ) {
			Tracked t = it.next();
			if (t.gen != gen) {
				t.entity.discard();
				it.remove();
			}
		}
		level = null;
	}

	public @Nullable Display get(K key) {
		Tracked t = tracked.get(key);
		return t == null || t.entity.isRemoved() ? null : t.entity;
	}

	/**
	 * Moves tracked entries whose String key starts with {@code fromPrefix} to the same key with
	 * {@code toPrefix}, keeping their entities (used when a guest wall gets its real id). An entry already
	 * present under the target key wins; the moved one is discarded.
	 */
	@SuppressWarnings("unchecked")
	public void rekeyPrefix(String fromPrefix, String toPrefix) {
		for (K k : new java.util.ArrayList<>(tracked.keySet())) {
			if (k instanceof String s && s.startsWith(fromPrefix)) {
				K to = (K) (toPrefix + s.substring(fromPrefix.length()));
				Tracked t = tracked.remove(k);
				if (tracked.containsKey(to)) {
					if (!t.entity.isRemoved()) {
						t.entity.discard();
					}
				} else {
					tracked.put(to, t);
				}
			}
		}
	}

	public void remove(K key) {
		Tracked t = tracked.remove(key);
		if (t != null && !t.entity.isRemoved()) {
			t.entity.discard();
		}
	}

	public int size() {
		return tracked.size();
	}

	public String diagnostics() {
		return kindTag + ": tracked=" + tracked.size() + " skippedUnloaded=" + skippedUnloaded + " addRejected=" + addRejected;
	}

	public void clear() {
		for (Tracked t : tracked.values()) {
			if (!t.entity.isRemoved()) {
				t.entity.discard();
			}
		}
		tracked.clear();
	}

	static boolean canSpawnAt(ServerLevel level, double x, double z) {
		BlockPos pos = BlockPos.containing(x, level.getMinY() + 1, z);
		return level.isLoaded(pos) && level.areEntitiesLoaded(ChunkPos.pack(pos));
	}
}

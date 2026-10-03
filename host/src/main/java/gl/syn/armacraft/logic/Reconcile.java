package gl.syn.armacraft.logic;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** Id-based diff between what exists in the world and what the latest snapshot wants. */
public final class Reconcile {

	public record Diff<K>(Set<K> add, Set<K> keep, Set<K> remove) {}

	private Reconcile() {}

	public static <K> Diff<K> diff(Collection<K> existing, Collection<K> desired) {
		Set<K> desiredSet = new LinkedHashSet<>(desired);
		Set<K> existingSet = new LinkedHashSet<>(existing);
		Set<K> add = new LinkedHashSet<>();
		Set<K> keep = new LinkedHashSet<>();
		Set<K> remove = new LinkedHashSet<>();
		for (K k : desiredSet) {
			if (existingSet.contains(k)) {
				keep.add(k);
			} else {
				add.add(k);
			}
		}
		for (K k : existingSet) {
			if (!desiredSet.contains(k)) {
				remove.add(k);
			}
		}
		return new Diff<>(add, keep, remove);
	}
}

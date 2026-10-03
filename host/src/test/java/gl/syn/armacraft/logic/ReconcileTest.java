package gl.syn.armacraft.logic;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReconcileTest {

	@Test
	void splitsIntoAddKeepRemove() {
		Reconcile.Diff<Long> d = Reconcile.diff(List.of(1L, 2L, 3L), List.of(2L, 3L, 4L, 5L));
		assertEquals(Set.of(4L, 5L), d.add());
		assertEquals(Set.of(2L, 3L), d.keep());
		assertEquals(Set.of(1L), d.remove());
	}

	@Test
	void emptyCases() {
		Reconcile.Diff<String> a = Reconcile.diff(List.of(), List.of("x"));
		assertEquals(Set.of("x"), a.add());
		assertEquals(Set.of(), a.remove());
		Reconcile.Diff<String> b = Reconcile.diff(List.of("x"), List.of());
		assertEquals(Set.of("x"), b.remove());
		assertEquals(Set.of(), b.add());
	}

	@Test
	void duplicatesCollapse() {
		Reconcile.Diff<Integer> d = Reconcile.diff(List.of(1, 1, 2), List.of(2, 2, 3));
		assertEquals(Set.of(3), d.add());
		assertEquals(Set.of(2), d.keep());
		assertEquals(Set.of(1), d.remove());
	}
}

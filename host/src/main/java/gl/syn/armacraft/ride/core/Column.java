package gl.syn.armacraft.ride.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Collision intervals of one block column (or of the 1-2 columns under the bike's width, merged), as absolute
 * y ranges. The Minecraft adapter fills it from block collision shapes; tests build it by hand.
 */
public final class Column {

	public enum Kind {
		/** Anything solid. */
		SOLID,
		/** Top block of still water: drivable while riding (it collides like a full block from above). */
		WATER,
		/** Lava: never driven onto, treated as a wall. */
		LAVA
	}

	public record Interval(double minY, double maxY, Kind kind) {}

	public static final Column UNLOADED = new Column(false);

	private final boolean loaded;
	private final List<Interval> intervals = new ArrayList<>(6);

	public Column() {
		this(true);
	}

	private Column(boolean loaded) {
		this.loaded = loaded;
	}

	public boolean loaded() {
		return loaded;
	}

	public List<Interval> intervals() {
		return intervals;
	}

	public Column add(double minY, double maxY, Kind kind) {
		if (!loaded) {
			throw new IllegalStateException("unloaded column");
		}
		if (maxY > minY) {
			intervals.add(new Interval(minY, maxY, kind));
		}
		return this;
	}

	public Column solid(double minY, double maxY) {
		return add(minY, maxY, Kind.SOLID);
	}

	/** Full solid blocks at block rows {@code fromY..toY} inclusive. */
	public Column blocks(int fromY, int toY) {
		for (int y = fromY; y <= toY; y++) {
			solid(y, y + 1);
		}
		return this;
	}

	/** Union of two columns (both must be loaded, else the result is unloaded). */
	public static Column merge(Column a, Column b) {
		if (!a.loaded || !b.loaded) {
			return UNLOADED;
		}
		Column c = new Column();
		c.intervals.addAll(a.intervals);
		c.intervals.addAll(b.intervals);
		return c;
	}
}

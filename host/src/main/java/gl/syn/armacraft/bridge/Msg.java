package gl.syn.armacraft.bridge;

import com.google.gson.JsonObject;
import gl.syn.armacraft.logic.WallLayout;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** Guest to host messages (PROTOCOL.md), plus two synthetic connection markers produced by the client. */
public sealed interface Msg {

	/** features: optional protocol features the guest offers (e.g. "delta"); empty for older guests. */
	record Hello(int proto, String game, String version, double rate, List<String> features) implements Msg {
		public Hello(int proto, String game, String version, double rate) {
			this(proto, game, version, rate, List.of());
		}
	}

	record Seg(double x1, double y1, double x2, double y2) {}

	/** bounds may be null when the guest omits it. */
	record Round(long round, double t, List<Seg> statics, double @Nullable [] bounds) implements Msg {}

	record Cycle(
		long id, String name, double r, double g, double b,
		double x, double y, double dx, double dy, double speed,
		boolean alive, boolean brake, double rubber, @Nullable String bridgeKey
	) implements Msg {}

	record Wall(long id, long cycle, double x1, double y1, double x2, double y2, List<WallLayout.Gap> gaps) implements Msg {}

	/** receivedNanos is the host's System.nanoTime() at parse time (used for extrapolation). */
	record State(double t, List<Cycle> cycles, List<Wall> walls, long receivedNanos) implements Msg {}

	/**
	 * Opt-in delta snapshot (PROTOCOL.md "delta" feature): all cycles, only walls that are new or changed,
	 * ids of walls that are gone. key=true replaces the whole wall set. BridgeClient folds these into a
	 * full {@link State}, so the server thread never sees a Delta.
	 */
	record Delta(long seq, boolean key, double t, List<Cycle> cycles, List<Wall> walls, long[] removed, long receivedNanos) implements Msg {}

	/** Generic event; the raw object is kept so unknown events and extra fields survive. */
	record Event(String ev, JsonObject raw, long receivedNanos) implements Msg {
		public Event(String ev, JsonObject raw) {
			this(ev, raw, System.nanoTime());
		}

		public double ageSeconds(long nowNanos) {
			return (nowNanos - receivedNanos) / 1e9;
		}

		public long id() {
			return Protocol.getLong(raw, "id", -1);
		}

		public double x() {
			return Protocol.getDouble(raw, "x", 0);
		}

		public double y() {
			return Protocol.getDouble(raw, "y", 0);
		}

		public String str(String key) {
			return Protocol.getString(raw, key, "");
		}
	}

	record Joined(String key, String name) implements Msg {}

	record Left(String key) implements Msg {}

	record Error(String msg) implements Msg {}

	/** Unknown type, ignored by the host. */
	record Unknown(String type) implements Msg {}

	/** Synthetic: TCP connection established. epoch increments per connection. */
	record Connected(int epoch) implements Msg {}

	/** Synthetic: TCP connection lost or closed. */
	record Disconnected(String reason) implements Msg {}
}

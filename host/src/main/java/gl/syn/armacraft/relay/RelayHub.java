package gl.syn.armacraft.relay;

import com.google.gson.JsonObject;
import gl.syn.armacraft.bridge.Msg;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The relay's state, independent of Minecraft so it can be unit tested (client/RELAY.md).
 *
 * <p>Threads: {@link #guestLine} runs on the bridge reader thread, everything else on the server thread. One lock
 * covers subscribers, caches and riders, so a client that subscribes while lines are flowing gets
 * {@code relay, hello, round, keyframe} and then exactly the live lines that follow the keyframe, never a
 * reordered or missing one. Sinks must not block (Minecraft's {@code Connection.send} only queues onto the
 * channel's event loop, FIFO).
 */
public final class RelayHub {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft/relay");

	/** One subscribed client. */
	public interface Sink {
		void send(String line);
	}

	/** The guest connection (production: the host's {@code BridgeClient}). */
	public interface Guest {
		boolean connected();

		/** Queue a host message; false if dropped (not connected / queue full). */
		boolean send(JsonObject msg);
	}

	/** A player who joined through the relay. {@code join} is the sanitised join, re-sent after a guest reconnect. */
	record Rider(String key, JsonObject join) {}

	/** All client messages: burst 40, 20/s sustained (turns are never coalesced, only refused beyond this). */
	static final double MSG_BURST = 40;
	static final double MSG_PER_SECOND = 20;
	/** Chat on top of that: burst 4, one per 2 s. */
	static final double CHAT_BURST = 4;
	static final double CHAT_PER_SECOND = 0.5;
	private static final int RECENT = 32;

	private final Object lock = new Object();
	private final Guest guest;
	private final LongSupplier nanos;

	private final Map<UUID, Sink> subscribers = new LinkedHashMap<>();
	private final Map<UUID, Rider> riders = new HashMap<>();
	private final Map<UUID, Bucket> msgBuckets = new HashMap<>();
	private final Map<UUID, Bucket> chatBuckets = new HashMap<>();
	private @Nullable String relayLine;
	private @Nullable String hello;
	private @Nullable String round;
	/** Host's folded snapshot as of the last state/delta line, reset by round/hello. Source of keyframes. */
	private Msg.@Nullable State folded;
	private final ArrayDeque<String> recentForwarded = new ArrayDeque<>();

	private long linesRelayed;
	private long linesOversized;
	private long clientDropped;
	private long sinkErrors;

	public RelayHub(Guest guest, LongSupplier nanos) {
		this.guest = guest;
		this.nanos = nanos;
	}

	// ------------------------------------------------------------------------------------------------ guest -> clients

	/**
	 * Bridge reader thread, once per parsed guest line, after the BridgeClient applied it. {@code folded} is the
	 * host's full snapshot after this line (for {@code state}/{@code delta}; ignored otherwise).
	 */
	public void guestLine(String line, Msg msg, Msg.@Nullable State folded) {
		List<JsonObject> rejoin = List.of();
		synchronized (lock) {
			switch (msg) {
				case Msg.Hello h -> {
					// a new guest connection: everything cached belongs to the old one, and the guest forgot our riders
					hello = line;
					round = null;
					this.folded = null;
					rejoin = new ArrayList<>(riders.size());
					for (Rider r : riders.values()) {
						rejoin.add(r.join());
					}
				}
				case Msg.Round r -> {
					round = line;
					this.folded = null;
				}
				case Msg.State s -> this.folded = s;
				case Msg.Delta d -> this.folded = folded;
				case Msg.Left l -> riders.values().removeIf(r -> r.key().equals(l.key()));
				default -> {
				}
			}
			if (!fitsClientbound(line)) {
				linesOversized++;
				LOG.warn("relay: dropped a {} line of {} chars (over {} bytes)", typeOf(msg), line.length(), BridgePayload.MAX_CLIENTBOUND_BYTES);
			} else {
				linesRelayed++;
				for (Sink s : subscribers.values()) {
					deliver(s, line);
				}
			}
			for (JsonObject j : rejoin) {
				guest.send(j);
			}
		}
	}

	// ------------------------------------------------------------------------------------------------ subscriptions

	/**
	 * Start relaying to a client that can receive {@code armacraft:bridge}: the relay line, then (if the guest is
	 * connected) the cached hello, round and a keyframe of the current snapshot, then live lines.
	 *
	 * @return false if already subscribed (nothing sent)
	 */
	public boolean subscribe(UUID id, Sink sink) {
		synchronized (lock) {
			if (subscribers.containsKey(id)) {
				return false;
			}
			if (relayLine != null) {
				deliver(sink, relayLine);
			}
			if (guest.connected()) {
				if (hello != null) {
					deliver(sink, hello);
				}
				if (round != null) {
					deliver(sink, round);
				}
				if (folded != null) {
					String kf = RelayLines.keyframe(folded);
					if (fitsClientbound(kf)) {
						deliver(sink, kf);
					}
				}
			}
			subscribers.put(id, sink);
			return true;
		}
	}

	public void unsubscribe(UUID id) {
		synchronized (lock) {
			subscribers.remove(id);
		}
	}

	public boolean isSubscribed(UUID id) {
		synchronized (lock) {
			return subscribers.containsKey(id);
		}
	}

	public int subscriberCount() {
		synchronized (lock) {
			return subscribers.size();
		}
	}

	/** Mapping line (anchor/scale/dimension). Re-sent to every subscriber when it changes. */
	public void setRelayLine(String line) {
		synchronized (lock) {
			if (line.equals(relayLine)) {
				return;
			}
			relayLine = line;
			for (Sink s : subscribers.values()) {
				deliver(s, line);
			}
		}
	}

	// ------------------------------------------------------------------------------------------------ clients -> guest

	/**
	 * Validate one client line (RelayRules + rate limits + rider gating). Does not forward: the caller may act on an
	 * accepted join first (end a vanilla session), then calls {@link #forward}.
	 */
	public RelayRules.Inbound check(UUID id, String profileName, String line, double[] defaultColor) {
		RelayRules.Inbound in = RelayRules.inbound(line, id, profileName, defaultColor);
		synchronized (lock) {
			if (!in.accepted()) {
				clientDropped++;
				return in;
			}
			long now = nanos.getAsLong();
			if (!msgBuckets.computeIfAbsent(id, k -> new Bucket(MSG_BURST, MSG_PER_SECOND, now)).take(now)) {
				clientDropped++;
				return RelayRules.Inbound.drop(in.type(), "rate limited");
			}
			boolean rider = riders.containsKey(id);
			switch (in.type()) {
				case "input", "leave", "chat" -> {
					if (!rider) {
						clientDropped++;
						return RelayRules.Inbound.drop(in.type(), "not joined through the relay");
					}
				}
				default -> {
				}
			}
			if (in.type().equals("chat") && !chatBuckets.computeIfAbsent(id, k -> new Bucket(CHAT_BURST, CHAT_PER_SECOND, now)).take(now)) {
				clientDropped++;
				return RelayRules.Inbound.drop("chat", "rate limited");
			}
			return in;
		}
	}

	/** Forward an accepted message to the guest and keep rider bookkeeping. */
	public void forward(UUID id, RelayRules.Inbound in) {
		if (!in.accepted()) {
			return;
		}
		synchronized (lock) {
			switch (in.type()) {
				case "join" -> riders.put(id, new Rider(RelayRules.keyFor(id), in.forward().getFirst()));
				case "leave" -> riders.remove(id);
				default -> {
				}
			}
			for (JsonObject j : in.forward()) {
				// a join dropped while the guest is down is re-sent on the next guest hello
				guest.send(j);
				remember(j.toString());
			}
		}
	}

	/** Player left the server: stop relaying and remove their cycle from the guest. */
	public void disconnected(UUID id) {
		synchronized (lock) {
			subscribers.remove(id);
			msgBuckets.remove(id);
			chatBuckets.remove(id);
			Rider r = riders.remove(id);
			if (r != null) {
				JsonObject leave = gl.syn.armacraft.bridge.Protocol.leave(r.key());
				guest.send(leave);
				remember(leave.toString());
			}
		}
	}

	public boolean isRider(UUID id) {
		synchronized (lock) {
			return riders.containsKey(id);
		}
	}

	/** The sanitised join line the guest got for this rider, or null. */
	public @Nullable String riderJoin(UUID id) {
		synchronized (lock) {
			Rider r = riders.get(id);
			return r == null ? null : r.join().toString();
		}
	}

	/** Server stopping: forget everything (the guest drops a closed host connection's players itself). */
	public void clear() {
		synchronized (lock) {
			subscribers.clear();
			riders.clear();
			msgBuckets.clear();
			chatBuckets.clear();
			hello = null;
			round = null;
			folded = null;
			relayLine = null;
		}
	}

	// ------------------------------------------------------------------------------------------------ diagnostics

	/** Last forwarded client messages, oldest first (diagnostics and tests). */
	public List<String> recentForwarded() {
		synchronized (lock) {
			return List.copyOf(recentForwarded);
		}
	}

	public String stats() {
		synchronized (lock) {
			return "relay: " + subscribers.size() + " clients, " + riders.size() + " riders, lines " + linesRelayed
				+ " (" + linesOversized + " oversized), client msgs dropped " + clientDropped + ", sink errors " + sinkErrors;
		}
	}

	// ------------------------------------------------------------------------------------------------ helpers

	private void deliver(Sink s, String line) {
		try {
			s.send(line);
		} catch (RuntimeException e) {
			if (sinkErrors++ % 100 == 0) {
				LOG.warn("relay: send to a client failed ({} so far): {}", sinkErrors, e.toString());
			}
		}
	}

	private void remember(String line) {
		recentForwarded.addLast(line);
		while (recentForwarded.size() > RECENT) {
			recentForwarded.removeFirst();
		}
	}

	static boolean fitsClientbound(String line) {
		// 3 bytes per UTF-16 unit is the worst case; only count exactly when that bound is exceeded
		return line.length() * 3L <= BridgePayload.MAX_CLIENTBOUND_BYTES || BridgePayload.utf8Length(line) <= BridgePayload.MAX_CLIENTBOUND_BYTES;
	}

	private static String typeOf(Msg m) {
		return m.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
	}

	/** Token bucket on a nanosecond clock. */
	static final class Bucket {
		private final double burst;
		private final double perSecond;
		private double tokens;
		private long last;

		Bucket(double burst, double perSecond, long now) {
			this.burst = burst;
			this.perSecond = perSecond;
			this.tokens = burst;
			this.last = now;
		}

		boolean take(long now) {
			tokens = Math.min(burst, tokens + Math.max(0, now - last) / 1e9 * perSecond);
			last = now;
			if (tokens >= 1) {
				tokens -= 1;
				return true;
			}
			return false;
		}
	}
}

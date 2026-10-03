package gl.syn.armacraft.client.net;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** A source of guest lines and a sink for host messages (PROTOCOL.md). */
public abstract class Transport {

	/** Received guest lines with their local receive time (seconds, System.nanoTime based). */
	public record Line(String text, double local) {}

	protected final Queue<Line> inbox = new ConcurrentLinkedQueue<>();

	public static double now() {
		return System.nanoTime() * 1e-9;
	}

	public Queue<Line> inbox() {
		return inbox;
	}

	/** Queue a host message (one JSON object, no newline). Never blocks the caller. */
	public abstract void send(String line);

	public abstract boolean connected();

	public abstract String describe();

	public abstract void close();

	/** Called when the transport (re)connects; the owner re-sends join etc. */
	public interface ConnectListener {
		void onConnected(Transport t);
	}
}

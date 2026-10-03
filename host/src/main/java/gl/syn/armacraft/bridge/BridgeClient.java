package gl.syn.armacraft.bridge;

import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TCP client for the guest. Runs on its own daemon thread, reconnects with exponential backoff.
 *
 * <p>Threading contract: the bridge thread only parses and enqueues. The server thread drains
 * {@link #pollInbound()} and reads {@link #latestState()}; it never blocks on the socket. Outbound
 * messages go through a bounded queue drained by a per-connection writer thread.
 */
public final class BridgeClient {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft/bridge");

	/** Hard cap on one JSON line (protects against a misbehaving peer). */
	static final int MAX_LINE_CHARS = 16 * 1024 * 1024;
	private static final int CONNECT_TIMEOUT_MS = 3000;
	private static final long BACKOFF_MIN_MS = 500;
	private static final long BACKOFF_MAX_MS = 2_000;
	/** Ask guests that offer it for `delta` snapshots (-Darmacraft.delta=false keeps full v1 snapshots). */
	static final boolean USE_DELTA = Boolean.parseBoolean(System.getProperty("armacraft.delta", "true"));

	private final AtomicReference<Msg.@Nullable State> latestState = new AtomicReference<>();
	private final ConcurrentLinkedQueue<Msg> inbound = new ConcurrentLinkedQueue<>();
	/** Approximate size of {@link #inbound}; oldest entries are dropped beyond {@link #MAX_INBOUND}. */
	private final AtomicInteger inboundSize = new AtomicInteger();
	static final int MAX_INBOUND = 4096;
	private final LinkedBlockingQueue<String> outbound = new LinkedBlockingQueue<>(8192);
	private final AtomicInteger epoch = new AtomicInteger();
	/** Wall set rebuilt from `delta` messages. Bridge (reader) thread only. */
	private final Map<Long, Msg.Wall> deltaWalls = new LinkedHashMap<>();

	private volatile boolean running;
	private volatile boolean connected;
	private volatile String host = "127.0.0.1";
	private volatile int port = 4535;
	private volatile @Nullable String lastError;
	private volatile Msg.@Nullable Hello hello;
	private volatile @Nullable Socket socket;
	private volatile long linesIn;
	private volatile long linesOut;
	private @Nullable Thread thread;
	public volatile java.util.function.BiConsumer<String, Msg> lineTap = (line, msg) -> { }; // armacraft-relay

	public synchronized void start(String host, int port) {
		stop();
		this.host = host;
		this.port = port;
		this.running = true;
		Thread t = new Thread(this::run, "armacraft-bridge");
		t.setDaemon(true);
		this.thread = t;
		t.start();
	}

	public synchronized void stop() {
		running = false;
		closeSocket();
		Thread t = thread;
		thread = null;
		if (t != null) {
			t.interrupt();
			try {
				t.join(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		connected = false;
		latestState.set(null);
		outbound.clear();
		// An explicit stop is not a "connection lost" event: drop markers/events from the old connection.
		inbound.clear();
		inboundSize.set(0);
	}

	public boolean isRunning() {
		return running;
	}

	public boolean isConnected() {
		return connected;
	}

	public int epoch() {
		return epoch.get();
	}

	public String host() {
		return host;
	}

	public int port() {
		return port;
	}

	public @Nullable String lastError() {
		return lastError;
	}

	public Msg.@Nullable Hello hello() {
		return hello;
	}

	public long linesIn() {
		return linesIn;
	}

	public long linesOut() {
		return linesOut;
	}

	public Msg.@Nullable State latestState() {
		return latestState.get();
	}

	public @Nullable Msg pollInbound() {
		Msg m = inbound.poll();
		if (m != null) {
			inboundSize.decrementAndGet();
		}
		return m;
	}

	private void enqueue(Msg m) {
		inbound.add(m);
		if (inboundSize.incrementAndGet() > MAX_INBOUND && inbound.poll() != null) {
			inboundSize.decrementAndGet();
		}
	}

	/** Queue a message for the guest. Dropped (returns false) when not connected or the queue is full. */
	public boolean send(JsonObject msg) {
		if (!connected) {
			return false;
		}
		return outbound.offer(msg.toString());
	}

	private void run() {
		long backoff = BACKOFF_MIN_MS;
		boolean loggedFailure = false;
		while (running) {
			Socket s = new Socket();
			Thread writer = null;
			try {
				s.setTcpNoDelay(true);
				s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
				socket = s;
				outbound.clear();
				latestState.set(null);
				hello = null;
				connected = true;
				int ep = epoch.incrementAndGet();
				lastError = null;
				loggedFailure = false;
				backoff = BACKOFF_MIN_MS;
				LOG.info("connected to guest {}:{} (epoch {})", host, port, ep);
				enqueue(new Msg.Connected(ep));
				final OutputStream out = s.getOutputStream();
				writer = new Thread(() -> writeLoop(out), "armacraft-bridge-writer");
				writer.setDaemon(true);
				writer.start();
				readLoop(s.getInputStream());
				lastError = "guest closed connection";
			} catch (IOException e) {
				lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
				if (!loggedFailure || connected) {
					LOG.info("bridge {}:{} not available: {}", host, port, lastError);
					loggedFailure = true;
				}
			} finally {
				boolean wasConnected = connected;
				connected = false;
				closeQuietly(s);
				socket = null;
				if (writer != null) {
					writer.interrupt();
				}
				latestState.set(null);
				if (wasConnected) {
					LOG.info("disconnected from guest: {}", lastError);
					enqueue(new Msg.Disconnected(lastError == null ? "closed" : lastError));
				}
			}
			if (!running) {
				break;
			}
			try {
				Thread.sleep(backoff);
			} catch (InterruptedException e) {
				break;
			}
			backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
		}
	}

	private void readLoop(InputStream in) throws IOException {
		Reader r = new InputStreamReader(in, StandardCharsets.UTF_8);
		char[] buf = new char[64 * 1024];
		StringBuilder line = new StringBuilder(4096);
		while (running) {
			int n = r.read(buf);
			if (n < 0) {
				return;
			}
			int start = 0;
			for (int i = 0; i < n; i++) {
				if (buf[i] == '\n') {
					line.append(buf, start, i - start);
					handleLine(line.toString());
					line.setLength(0);
					start = i + 1;
				}
			}
			line.append(buf, start, n - start);
			if (line.length() > MAX_LINE_CHARS) {
				throw new IOException("line exceeds " + MAX_LINE_CHARS + " chars");
			}
		}
	}

	void handleLine(String line) {
		linesIn++;
		Msg m = Protocol.parse(line);
		if (m == null) {
			return;
		}
		switch (m) {
			case Msg.State st -> latestState.set(st);
			case Msg.Delta d -> applyDelta(d);
			case Msg.Hello h -> {
				hello = h;
				deltaWalls.clear();
				if (USE_DELTA && h.features().contains("delta")) {
					// v1 guests never offer it; a guest that does switches to `delta` lines after this
					send(Protocol.hello(List.of("delta")));
				}
				enqueue(h);
			}
			case Msg.Unknown u -> {
				// ignored (forward compatibility)
			}
			default -> enqueue(m);
		}
		lineTap.accept(line, m); // armacraft-relay
	}

	/**
	 * Bridge thread only. Folds a delta into the current wall set and publishes a full State, so the server
	 * thread is unchanged. Unchanged walls keep their record instances across snapshots, which lets the
	 * renderer's memo hit on identity.
	 */
	private void applyDelta(Msg.Delta d) {
		if (d.key()) {
			deltaWalls.clear();
		}
		for (long id : d.removed()) {
			deltaWalls.remove(id);
		}
		for (Msg.Wall w : d.walls()) {
			deltaWalls.put(w.id(), w);
		}
		latestState.set(new Msg.State(d.t(), d.cycles(), List.copyOf(deltaWalls.values()), d.receivedNanos()));
	}

	private void writeLoop(OutputStream out) {
		List<String> batch = new ArrayList<>();
		try {
			while (connected && !Thread.currentThread().isInterrupted()) {
				String first = outbound.poll(250, TimeUnit.MILLISECONDS);
				if (first == null) {
					continue;
				}
				batch.clear();
				batch.add(first);
				outbound.drainTo(batch, 255);
				StringBuilder sb = new StringBuilder();
				for (String s : batch) {
					sb.append(s).append('\n');
				}
				out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
				out.flush();
				linesOut += batch.size();
			}
		} catch (InterruptedException e) {
			// shutting down
		} catch (IOException e) {
			lastError = "write failed: " + e.getMessage();
			closeSocket();
		}
	}

	private void closeSocket() {
		closeQuietly(socket);
	}

	private static void closeQuietly(@Nullable Socket s) {
		if (s != null) {
			try {
				s.close();
			} catch (IOException ignored) {
				// ignore
			}
		}
	}
}

package gl.syn.armacraft.client.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Direct mode: this client is a PROTOCOL.md host. A reader thread connects to the guest (reconnecting with
 * backoff) and queues lines; a writer thread sends host messages as soon as they are queued, with
 * TCP_NODELAY so a turn key reaches the guest without waiting for the next Minecraft tick.
 */
public final class DirectTransport extends Transport {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft_client/direct");
	private static final int MAX_LINE = 8 * 1024 * 1024;

	private final String host;
	private final int port;
	private final ConnectListener listener;
	private final BlockingQueue<String> outbox = new LinkedBlockingQueue<>(4096);
	private volatile Socket socket;
	private volatile boolean running = true;
	private volatile boolean connected;
	private final Thread reader;
	private final Thread writer;
	private final java.util.function.Consumer<Line> sink;

	public DirectTransport(String host, int port, ConnectListener listener) {
		this(host, port, listener, null);
	}

	/** @param sink if non-null, received lines go here (on the reader thread) instead of the inbox */
	public DirectTransport(String host, int port, ConnectListener listener, java.util.function.Consumer<Line> sink) {
		this.host = host;
		this.port = port;
		this.listener = listener;
		this.sink = sink;
		reader = new Thread(this::readLoop, "armacraft_client direct reader");
		reader.setDaemon(true);
		writer = new Thread(this::writeLoop, "armacraft_client direct writer");
		writer.setDaemon(true);
		reader.start();
		writer.start();
	}

	private void readLoop() {
		long backoff = 250;
		while (running) {
			try (Socket s = new Socket()) {
				s.setTcpNoDelay(true);
				s.connect(new InetSocketAddress(host, port), 3000);
				socket = s;
				connected = true;
				backoff = 250;
				LOG.info("connected to guest {}:{}", host, port);
				if (listener != null) {
					listener.onConnected(this);
				}
				BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8), 1 << 16);
				String line;
				while (running && (line = in.readLine()) != null) {
					if (line.length() > MAX_LINE) {
						continue;
					}
					if (!line.isBlank()) {
						Line l = new Line(line, now());
						if (sink != null) {
							sink.accept(l);
						} else {
							inbox.add(l);
						}
					}
				}
			} catch (IOException e) {
				if (running) {
					LOG.debug("guest {}:{} not reachable: {}", host, port, e.getMessage());
				}
			} finally {
				connected = false;
				socket = null;
			}
			if (!running) {
				break;
			}
			try {
				Thread.sleep(backoff);
			} catch (InterruptedException e) {
				break;
			}
			backoff = Math.min(5000, backoff * 2);
		}
	}

	private void writeLoop() {
		while (running) {
			String msg;
			try {
				msg = outbox.poll(500, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				break;
			}
			if (msg == null) {
				continue;
			}
			Socket s = socket;
			if (s == null || !connected) {
				continue;   // dropped while disconnected; join is re-sent on reconnect
			}
			try {
				OutputStream out = s.getOutputStream();
				out.write((msg + "\n").getBytes(StandardCharsets.UTF_8));
				out.flush();
			} catch (IOException e) {
				LOG.debug("send failed: {}", e.getMessage());
				try {
					s.close();
				} catch (IOException ignored) {
				}
			}
		}
	}

	@Override
	public void send(String line) {
		if (!outbox.offer(line)) {
			LOG.warn("direct outbox full, dropping {}", line);
		}
	}

	@Override
	public boolean connected() {
		return connected;
	}

	@Override
	public String describe() {
		return "direct " + host + ":" + port + (connected ? " (connected)" : " (connecting)");
	}

	@Override
	public void close() {
		running = false;
		Socket s = socket;
		if (s != null) {
			try {
				s.close();
			} catch (IOException ignored) {
			}
		}
		reader.interrupt();
		writer.interrupt();
	}
}

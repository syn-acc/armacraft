package gl.syn.armacraft.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tiny in-process guest for the game test: 40x40 arena, one AI cycle driving a square, and a bridge cycle per
 * join that turns on input. Deliberately independent from tools/fake_guest.py (which the game test can use
 * instead via -Darmacraft.gametest.guest=host:port).
 */
final class MiniGuest implements AutoCloseable {

	private static final double[][] DIRS = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};

	final List<String> received = new CopyOnWriteArrayList<>();
	private final ServerSocket server;
	private volatile boolean running = true;
	private volatile Socket socket;
	private final Object lock = new Object();

	// sim state (guarded by lock)
	private double t;
	private double aiX = -10;
	private double aiY = -10;
	private int aiDir = 0;
	private double aiLegStart = 0;
	private double aiWallX = -10;
	private double aiWallY = -10;
	private int aiWallId = 500;
	private String bridgeKey;
	private String bridgeName;
	private double bx;
	private double by;
	private int bDir;
	private boolean bBrake;

	MiniGuest() throws IOException {
		server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
		Thread th = new Thread(this::serve, "armacraft-test-guest");
		th.setDaemon(true);
		th.start();
	}

	int port() {
		return server.getLocalPort();
	}

	private void serve() {
		try {
			Socket s = server.accept();
			socket = s;
			OutputStream out = s.getOutputStream();
			write(out, "{\"type\":\"hello\",\"proto\":1,\"game\":\"armagetronad\",\"version\":\"mini\",\"rate\":20}");
			write(out, "{\"type\":\"round\",\"round\":1,\"t\":0,\"static\":[[-20,-20,20,-20],[20,-20,20,20],[20,20,-20,20],[-20,20,-20,-20]],\"bounds\":[-20,-20,20,20]}");
			Thread reader = new Thread(() -> read(s, out), "armacraft-test-guest-reader");
			reader.setDaemon(true);
			reader.start();
			while (running && !s.isClosed()) {
				String state;
				synchronized (lock) {
					step(0.05);
					state = state();
				}
				write(out, state);
				Thread.sleep(50);
			}
		} catch (IOException | InterruptedException e) {
			// closed
		}
	}

	private void read(Socket s, OutputStream out) {
		try (BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = in.readLine()) != null) {
				received.add(line);
				JsonObject m = JsonParser.parseString(line).getAsJsonObject();
				String type = m.has("type") ? m.get("type").getAsString() : "";
				synchronized (lock) {
					switch (type) {
						case "join" -> {
							bridgeKey = m.get("key").getAsString();
							bridgeName = m.get("name").getAsString();
							bx = 0;
							by = 0;
							bDir = 0;
							write(out, "{\"type\":\"joined\",\"key\":\"" + bridgeKey + "\",\"name\":\"" + bridgeName + "\"}");
							write(out, "{\"type\":\"event\",\"ev\":\"spawn\",\"id\":7,\"x\":0,\"y\":0,\"t\":" + t + "}");
						}
						case "leave" -> {
							write(out, "{\"type\":\"left\",\"key\":\"" + bridgeKey + "\"}");
							bridgeKey = null;
						}
						case "input" -> {
							if (m.has("turn")) {
								bDir = Math.floorMod(bDir + ("left".equals(m.get("turn").getAsString()) ? -1 : 1), 4);
							}
							if (m.has("brake")) {
								bBrake = m.get("brake").getAsBoolean();
							}
						}
						default -> {
						}
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			// closed
		}
	}

	private void step(double dt) {
		t += dt;
		aiX += DIRS[aiDir][0] * 10 * dt;
		aiY += DIRS[aiDir][1] * 10 * dt;
		if (t - aiLegStart > 2.0) {
			aiDir = (aiDir + 1) % 4;
			aiLegStart = t;
			aiWallX = aiX;
			aiWallY = aiY;
			aiWallId++;
		}
		if (bridgeKey != null) {
			double sp = bBrake ? 0.5 : 1.0;
			bx = clamp(bx + DIRS[bDir][0] * sp * dt);
			by = clamp(by + DIRS[bDir][1] * sp * dt);
		}
	}

	private static double clamp(double v) {
		return Math.max(-15, Math.min(15, v));
	}

	private String state() {
		JsonObject o = new JsonObject();
		o.addProperty("type", "state");
		o.addProperty("t", t);
		JsonArray cycles = new JsonArray();
		cycles.add(cycle(3, "AI", 1, 0.2, 0.1, aiX, aiY, aiDir, 10, false, null));
		if (bridgeKey != null) {
			cycles.add(cycle(7, bridgeName, 0.1, 0.6, 1.0, bx, by, bDir, bBrake ? 0.5 : 1, bBrake, bridgeKey));
		}
		o.add("cycles", cycles);
		JsonArray walls = new JsonArray();
		JsonObject w = new JsonObject();
		w.addProperty("id", aiWallId);
		w.addProperty("cycle", 3);
		w.addProperty("x1", aiWallX);
		w.addProperty("y1", aiWallY);
		w.addProperty("x2", aiX);
		w.addProperty("y2", aiY);
		walls.add(w);
		o.add("walls", walls);
		return o.toString();
	}

	private static JsonObject cycle(int id, String name, double r, double g, double b, double x, double y, int dir, double speed, boolean brake, String key) {
		JsonObject c = new JsonObject();
		c.addProperty("id", id);
		c.addProperty("name", name);
		JsonArray col = new JsonArray();
		col.add(r);
		col.add(g);
		col.add(b);
		c.add("color", col);
		c.addProperty("x", x);
		c.addProperty("y", y);
		c.addProperty("dx", DIRS[dir][0]);
		c.addProperty("dy", DIRS[dir][1]);
		c.addProperty("speed", speed);
		c.addProperty("alive", true);
		c.addProperty("brake", brake);
		if (key != null) {
			c.addProperty("bridge_key", key);
		}
		return c;
	}

	private static void write(OutputStream out, String line) {
		try {
			synchronized (out) {
				out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
				out.flush();
			}
		} catch (IOException e) {
			// closed
		}
	}

	@Override
	public void close() {
		running = false;
		try {
			server.close();
		} catch (IOException ignored) {
			// ignore
		}
		Socket s = socket;
		if (s != null) {
			try {
				s.close();
			} catch (IOException ignored) {
				// ignore
			}
		}
	}
}

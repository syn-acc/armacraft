package gl.syn.armacraft.client.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process PROTOCOL.md guest for the client game test, on an ephemeral loopback port. Unlike host's MiniGuest it
 * offers the {@code delta} feature, so the host switches to deltas and the relay forwards them verbatim: this is
 * the path a relay client sees against the real guest. One AI cycle drives short legs (a new wall per leg, at most
 * {@link #KEEP} closed walls, older ones removed); one bridge cycle per join turns on input.
 */
final class DeltaGuest implements AutoCloseable {

	private static final double[][] DIRS = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
	static final int KEEP = 4;

	final List<String> received = new CopyOnWriteArrayList<>();
	private final ServerSocket server;
	private volatile boolean running = true;
	private volatile Socket socket;
	private final Object lock = new Object();

	// sim (guarded by lock)
	private double t;
	private double aiX = -20, aiY = -20;
	private int aiDir;
	private double aiLeg;
	private int nextWallId = 500;
	private final Map<Integer, double[]> closedWalls = new LinkedHashMap<>();
	private double[] aiHead = {-20, -20};
	private int aiHeadId = nextWallId++;
	private String bridgeKey, bridgeName;
	private double bx, by;
	private int bDir;
	private double[] bHead = {0, 0};
	private int bHeadId;
	private boolean bBrake;
	// delta bookkeeping (guarded by lock)
	private volatile boolean delta;
	private boolean deltaKey = true;
	private long seq;
	private final Map<Integer, String> sentWalls = new LinkedHashMap<>();
	volatile int deltasSent;
	volatile int keyDeltasSent;

	DeltaGuest() throws IOException {
		server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
		Thread th = new Thread(this::serve, "armacraft-client-test-guest");
		th.setDaemon(true);
		th.start();
	}

	int port() {
		return server.getLocalPort();
	}

	/** Current wall ids (what a full state would list now). */
	Set<Integer> wallIds() {
		synchronized (lock) {
			Set<Integer> ids = new TreeSet<>(closedWalls.keySet());
			ids.add(aiHeadId);
			if (bridgeKey != null) {
				ids.add(bHeadId);
			}
			return ids;
		}
	}

	int bridgeDir() {
		synchronized (lock) {
			return bDir;
		}
	}

	private void serve() {
		try {
			Socket s = server.accept();
			socket = s;
			OutputStream out = s.getOutputStream();
			write(out, "{\"type\":\"hello\",\"proto\":1,\"game\":\"armagetronad\",\"version\":\"delta-test\",\"rate\":20,\"features\":[\"delta\"]}");
			write(out, "{\"type\":\"round\",\"round\":1,\"t\":0,\"static\":[[-50,-50,50,-50],[50,-50,50,50],[50,50,-50,50],[-50,50,-50,-50]],\"bounds\":[-50,-50,50,50]}");
			Thread reader = new Thread(() -> read(s, out), "armacraft-client-test-guest-reader");
			reader.setDaemon(true);
			reader.start();
			while (running && !s.isClosed()) {
				String line;
				synchronized (lock) {
					step(0.05);
					line = delta ? delta() : state();
				}
				write(out, line);
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
						case "hello" -> {
							boolean want = false;
							if (m.has("features")) {
								for (JsonElement f : m.getAsJsonArray("features")) {
									want |= "delta".equals(f.getAsString());
								}
							}
							delta = want;
							deltaKey = true;
						}
						case "join" -> {
							bridgeKey = m.get("key").getAsString();
							bridgeName = m.get("name").getAsString();
							bx = 0;
							by = 0;
							bDir = 0;
							bHead = new double[] {0, 0};
							bHeadId = nextWallId++;
							write(out, "{\"type\":\"joined\",\"key\":\"" + bridgeKey + "\",\"name\":\"" + bridgeName + "\"}");
							write(out, "{\"type\":\"event\",\"ev\":\"spawn\",\"id\":7,\"x\":0,\"y\":0,\"dx\":0,\"dy\":1,\"t\":" + t + "}");
						}
						case "leave" -> {
							write(out, "{\"type\":\"left\",\"key\":\"" + bridgeKey + "\"}");
							bridgeKey = null;
						}
						case "input" -> {
							if (m.has("turn") && bridgeKey != null && bridgeKey.equals(m.get("key").getAsString())) {
								bDir = Math.floorMod(bDir + ("left".equals(m.get("turn").getAsString()) ? -1 : 1), 4);
								closedWalls.put(bHeadId, new double[] {bHead[0], bHead[1], bx, by});
								bHead = new double[] {bx, by};
								bHeadId = nextWallId++;
								write(out, "{\"type\":\"event\",\"ev\":\"turn\",\"id\":7,\"x\":" + bx + ",\"y\":" + by + ",\"dx\":" + DIRS[bDir][0] + ",\"dy\":" + DIRS[bDir][1] + ",\"t\":" + t + "}");
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
		aiX += DIRS[aiDir][0] * 20 * dt;
		aiY += DIRS[aiDir][1] * 20 * dt;
		aiLeg += dt;
		if (aiLeg > 0.5) {
			aiLeg = 0;
			closedWalls.put(aiHeadId, new double[] {aiHead[0], aiHead[1], aiX, aiY});
			aiHead = new double[] {aiX, aiY};
			aiHeadId = nextWallId++;
			aiDir = (aiDir + 1) % 4;
			while (closedWalls.size() > KEEP) {
				closedWalls.remove(closedWalls.keySet().iterator().next());
			}
		}
		if (bridgeKey != null) {
			double sp = bBrake ? 2 : 5;
			bx = Math.max(-45, Math.min(45, bx + DIRS[bDir][0] * sp * dt));
			by = Math.max(-45, Math.min(45, by + DIRS[bDir][1] * sp * dt));
		}
	}

	private Map<Integer, String> currentWalls() {
		Map<Integer, String> w = new LinkedHashMap<>();
		closedWalls.forEach((id, v) -> w.put(id, wall(id, 3, v[0], v[1], v[2], v[3])));
		w.put(aiHeadId, wall(aiHeadId, 3, aiHead[0], aiHead[1], aiX, aiY));
		if (bridgeKey != null) {
			w.put(bHeadId, wall(bHeadId, 7, bHead[0], bHead[1], bx, by));
		}
		return w;
	}

	private JsonArray cycles() {
		JsonArray cycles = new JsonArray();
		cycles.add(cycle(3, "AI", 1, 0.2, 0.1, aiX, aiY, aiDir, 20, false, null));
		if (bridgeKey != null) {
			cycles.add(cycle(7, bridgeName, 0.1, 0.6, 1.0, bx, by, bDir, bBrake ? 2 : 5, bBrake, bridgeKey));
		}
		return cycles;
	}

	private String state() {
		StringBuilder sb = new StringBuilder("{\"type\":\"state\",\"t\":").append(t).append(",\"cycles\":").append(cycles()).append(",\"walls\":[");
		sb.append(String.join(",", currentWalls().values())).append("]}");
		return sb.toString();
	}

	/** PROTOCOL.md delta against what this host was last sent. */
	private String delta() {
		Map<Integer, String> now = currentWalls();
		boolean key = deltaKey;
		deltaKey = false;
		List<String> changed = new ArrayList<>();
		List<Integer> removed = new ArrayList<>();
		if (key) {
			changed.addAll(now.values());
		} else {
			now.forEach((id, j) -> {
				if (!j.equals(sentWalls.get(id))) {
					changed.add(j);
				}
			});
			for (Integer id : sentWalls.keySet()) {
				if (!now.containsKey(id)) {
					removed.add(id);
				}
			}
		}
		sentWalls.clear();
		sentWalls.putAll(now);
		deltasSent++;
		if (key) {
			keyDeltasSent++;
		}
		return "{\"type\":\"delta\",\"seq\":" + (++seq) + ",\"key\":" + key + ",\"t\":" + t + ",\"cycles\":" + cycles()
			+ ",\"walls\":[" + String.join(",", changed) + "],\"removed\":" + removed + "}";
	}

	private static String wall(int id, int cycle, double x1, double y1, double x2, double y2) {
		return "{\"id\":" + id + ",\"cycle\":" + cycle + ",\"x1\":" + x1 + ",\"y1\":" + y1 + ",\"x2\":" + x2 + ",\"y2\":" + y2 + "}";
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

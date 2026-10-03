package gl.syn.armacraft.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real TCP against an in-JVM fake guest: queues, threading, reconnect. No Minecraft needed. */
class BridgeClientTest {

	private static void await(BooleanSupplier cond, String what) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000;
		while (!cond.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("timed out waiting for " + what);
			}
			Thread.sleep(10);
		}
	}

	private static List<Msg> drain(BridgeClient c) {
		List<Msg> out = new ArrayList<>();
		Msg m;
		while ((m = c.pollInbound()) != null) {
			out.add(m);
		}
		return out;
	}

	private static void writeLine(OutputStream out, String s) throws Exception {
		out.write((s + "\n").getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	@Test
	@Timeout(30)
	void connectsReceivesSendsAndReconnects() throws Exception {
		try (ServerSocket ss = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
			BridgeClient c = new BridgeClient();
			c.start("127.0.0.1", ss.getLocalPort());
			try {
				// --- first connection ---
				Socket s1 = ss.accept();
				OutputStream out = s1.getOutputStream();
				BufferedReader in = new BufferedReader(new InputStreamReader(s1.getInputStream(), StandardCharsets.UTF_8));
				await(c::isConnected, "connect");
				assertEquals(1, c.epoch());
				writeLine(out, "{\"type\":\"hello\",\"proto\":1,\"game\":\"armagetronad\",\"version\":\"x\",\"rate\":30}");
				writeLine(out, "{\"type\":\"bogus\"}");
				writeLine(out, "garbage that is not json");
				// split one message across two writes to exercise line reassembly
				out.write("{\"type\":\"state\",\"t\":1.5,\"cycles\":[{\"id\":7,".getBytes(StandardCharsets.UTF_8));
				out.flush();
				Thread.sleep(50);
				writeLine(out, "\"x\":1,\"y\":2}],\"walls\":[]}");
				writeLine(out, "{\"type\":\"event\",\"ev\":\"chat\",\"name\":\"a\",\"text\":\"b\"}");
				await(() -> c.latestState() != null, "state");
				assertEquals(1.5, c.latestState().t(), 1e-9);
				assertEquals(7, c.latestState().cycles().get(0).id());
				await(() -> c.hello() != null, "hello");

				List<Msg> msgs = new ArrayList<>();
				await(() -> {
					msgs.addAll(drain(c));
					return msgs.stream().anyMatch(m -> m instanceof Msg.Event);
				}, "event");
				assertInstanceOf(Msg.Connected.class, msgs.get(0));
				assertTrue(msgs.stream().noneMatch(m -> m instanceof Msg.Unknown), "unknown types are dropped");

				// host -> guest
				assertTrue(c.send(Protocol.turn("mc:u", "left")));
				JsonObject got = JsonParser.parseString(in.readLine()).getAsJsonObject();
				assertEquals("input", got.get("type").getAsString());
				assertEquals("left", got.get("turn").getAsString());

				// --- guest drops: client reports it and reconnects ---
				s1.close();
				await(() -> !c.isConnected(), "disconnect noticed");
				assertFalse(c.send(Protocol.turn("mc:u", "right")), "sends are dropped while disconnected");
				Socket s2 = ss.accept();
				await(c::isConnected, "reconnect");
				assertEquals(2, c.epoch());
				List<Msg> after = new ArrayList<>();
				await(() -> {
					after.addAll(drain(c));
					return after.stream().anyMatch(m -> m instanceof Msg.Connected);
				}, "second connected marker");
				assertTrue(after.stream().anyMatch(m -> m instanceof Msg.Disconnected));
				assertEquals(null, c.latestState(), "stale state cleared on reconnect");
				s2.close();
			} finally {
				c.stop();
			}
			assertFalse(c.isRunning());
		}
	}

	@Test
	void inboundQueueIsBoundedAndKeepsNewest() {
		BridgeClient c = new BridgeClient();
		int n = BridgeClient.MAX_INBOUND + 500;
		for (int i = 0; i < n; i++) {
			c.handleLine("{\"type\":\"event\",\"ev\":\"turn\",\"id\":" + i + "}");
		}
		List<Msg> got = drain(c);
		assertEquals(BridgeClient.MAX_INBOUND, got.size());
		assertEquals(500, ((Msg.Event) got.get(0)).id(), "oldest entries are dropped first");
		assertEquals(n - 1, ((Msg.Event) got.get(got.size() - 1)).id());
	}

	@Test
	@Timeout(20)
	void retriesWhileGuestIsDown() throws Exception {
		int port;
		try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			port = probe.getLocalPort();
		}
		BridgeClient c = new BridgeClient();
		c.start("127.0.0.1", port);
		try {
			Thread.sleep(700); // at least one failed attempt + backoff
			assertFalse(c.isConnected());
			assertNotNull(c.lastError());
			try (ServerSocket ss = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
				Socket s = ss.accept();
				await(c::isConnected, "connect after guest came up");
				s.close();
			}
		} finally {
			c.stop();
		}
	}
}

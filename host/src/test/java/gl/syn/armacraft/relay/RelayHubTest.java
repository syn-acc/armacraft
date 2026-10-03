package gl.syn.armacraft.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gl.syn.armacraft.bridge.Msg;
import gl.syn.armacraft.bridge.Protocol;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** RelayHub: line order for late subscribers, keyframes, rider bookkeeping, limits. */
class RelayHubTest {

	private static final UUID A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
	private static final UUID B = UUID.fromString("00000000-0000-4000-8000-00000000000b");
	private static final double[] DEF = {1, 1, 1};

	private static final String HELLO = "{\"type\":\"hello\",\"proto\":1,\"game\":\"armagetronad\",\"version\":\"0.2.9.3.0\",\"rate\":30,\"features\":[\"delta\"]}";
	private static final String ROUND = "{\"type\":\"round\",\"round\":3,\"t\":0.0,\"static\":[[0,0,10,0]],\"bounds\":[0,0,10,10]}";
	private static final String STATE = "{\"type\":\"state\",\"t\":1.5,\"cycles\":[{\"id\":17,\"name\":\"Syn\",\"color\":[0.1,0.6,1.0],"
		+ "\"x\":10,\"y\":-4.5,\"dx\":0,\"dy\":1,\"speed\":30,\"alive\":true,\"brake\":false,\"rubber\":0.2,\"bridge_key\":\"mc:k\"}],"
		+ "\"walls\":[{\"id\":901,\"cycle\":17,\"x1\":0,\"y1\":0,\"x2\":0,\"y2\":40,\"gaps\":[[0.25,0.3]]}]}";

	static final class FakeGuest implements RelayHub.Guest {
		boolean connected = true;
		final List<String> sent = new CopyOnWriteArrayList<>();

		@Override
		public boolean connected() {
			return connected;
		}

		@Override
		public boolean send(JsonObject msg) {
			if (!connected) {
				return false;
			}
			sent.add(msg.toString());
			return true;
		}
	}

	private final FakeGuest guest = new FakeGuest();
	private final AtomicLong clock = new AtomicLong(1_000_000_000L);
	private final RelayHub hub = new RelayHub(guest, clock::get);

	private static String type(String line) {
		return JsonParser.parseString(line).getAsJsonObject().get("type").getAsString();
	}

	private void feed(String line) {
		Msg m = Protocol.parse(line);
		hub.guestLine(line, m, m instanceof Msg.State s ? s : null);
	}

	@Test
	void lateSubscriberGetsRelayHelloRoundKeyframeThenLive() {
		hub.setRelayLine(RelayLines.relay(0.5, 100, 0.5, 2.0, "minecraft:overworld"));
		feed(HELLO);
		feed(ROUND);
		feed(STATE);
		List<String> got = new ArrayList<>();
		assertTrue(hub.subscribe(A, got::add));
		assertFalse(hub.subscribe(A, got::add), "second subscribe is a no-op");
		feed("{\"type\":\"event\",\"ev\":\"turn\",\"id\":17,\"x\":0,\"y\":5,\"dx\":1,\"dy\":0,\"t\":1.6}");
		assertEquals(List.of("relay", "hello", "round", "state", "event"), got.stream().map(RelayHubTest::type).toList());
		assertEquals(HELLO, got.get(1), "guest lines are verbatim");
		assertEquals(ROUND, got.get(2));
		JsonObject relay = JsonParser.parseString(got.get(0)).getAsJsonObject();
		assertEquals(1, relay.get("proto").getAsInt());
		assertEquals(2.0, relay.get("scale").getAsDouble());
		assertEquals(100.0, relay.getAsJsonArray("anchor").get(1).getAsDouble());
		assertEquals("minecraft:overworld", relay.get("dimension").getAsString());
		// the keyframe re-parses to the same snapshot
		Msg.State kf = (Msg.State) Protocol.parse(got.get(3));
		Msg.State orig = (Msg.State) Protocol.parse(STATE);
		assertEquals(orig.t(), kf.t());
		assertEquals(orig.cycles(), kf.cycles());
		assertEquals(orig.walls(), kf.walls());
		assertTrue(JsonParser.parseString(got.get(3)).getAsJsonObject().get("keyframe").getAsBoolean());
	}

	@Test
	void deltaStreamKeyframeComesFromTheFoldedSnapshot() {
		feed(HELLO);
		String delta = "{\"type\":\"delta\",\"seq\":5,\"key\":false,\"t\":2.0,\"cycles\":[],\"walls\":[{\"id\":905,\"cycle\":17,\"x1\":0,\"y1\":40,\"x2\":12.5,\"y2\":40}],\"removed\":[880]}";
		Msg.State foldedByBridge = (Msg.State) Protocol.parse(STATE.replace("\"t\":1.5", "\"t\":2.0"));
		hub.guestLine(delta, Protocol.parse(delta), foldedByBridge);
		List<String> got = new ArrayList<>();
		hub.subscribe(A, got::add);
		hub.guestLine(delta, Protocol.parse(delta), foldedByBridge);
		assertEquals(List.of("hello", "state", "delta"), got.stream().map(RelayHubTest::type).toList());
		assertEquals(2.0, ((Msg.State) Protocol.parse(got.get(1))).t());
		assertEquals(delta, got.get(2), "deltas are relayed verbatim");
	}

	@Test
	void roundOrHelloInvalidatesCaches() {
		feed(HELLO);
		feed(ROUND);
		feed(STATE);
		feed(ROUND.replace("\"round\":3", "\"round\":4"));
		List<String> got = new ArrayList<>();
		hub.subscribe(A, got::add);
		assertEquals(List.of("hello", "round"), got.stream().map(RelayHubTest::type).toList(), "no stale keyframe after a new round");
		assertTrue(got.get(1).contains("\"round\":4"));

		feed(HELLO.replace("0.2.9.3.0", "next"));
		List<String> got2 = new ArrayList<>();
		hub.subscribe(B, got2::add);
		assertEquals(List.of("hello"), got2.stream().map(RelayHubTest::type).toList(), "new guest connection: old round dropped");
	}

	@Test
	void disconnectedGuestSendsOnlyTheRelayLine() {
		hub.setRelayLine(RelayLines.relay(0, 0, 0, 1, "minecraft:overworld"));
		feed(HELLO);
		feed(ROUND);
		guest.connected = false;
		List<String> got = new ArrayList<>();
		hub.subscribe(A, got::add);
		assertEquals(List.of("relay"), got.stream().map(RelayHubTest::type).toList());
	}

	@Test
	void relayLineIsResentOnChangeOnly() {
		List<String> got = new ArrayList<>();
		hub.setRelayLine(RelayLines.relay(0, 0, 0, 1, "minecraft:overworld"));
		hub.subscribe(A, got::add);
		hub.setRelayLine(RelayLines.relay(0, 0, 0, 1, "minecraft:overworld"));
		assertEquals(1, got.size());
		hub.setRelayLine(RelayLines.relay(5, 64, 5, 0.5, "minecraft:the_nether"));
		assertEquals(2, got.size());
		assertTrue(got.get(1).contains("the_nether"));
	}

	@Test
	void unsubscribedAndOversizedLinesAreNotSent() {
		List<String> got = new ArrayList<>();
		hub.subscribe(A, got::add);
		String huge = "{\"type\":\"state\",\"t\":1,\"pad\":\"" + "€".repeat(BridgePayload.MAX_CLIENTBOUND_BYTES / 3 + 10) + "\"}";
		feed(huge);
		assertTrue(got.isEmpty(), "line over 4 MiB of UTF-8 must never reach the encoder");
		assertTrue(hub.stats().contains("1 oversized"), hub.stats());
		hub.unsubscribe(A);
		feed(STATE);
		assertTrue(got.isEmpty());
	}

	@Test
	void sinkFailureDoesNotStopOtherClients() {
		List<String> got = new ArrayList<>();
		hub.subscribe(A, line -> {
			throw new IllegalStateException("closed");
		});
		hub.subscribe(B, got::add);
		feed(STATE);
		assertEquals(1, got.size());
	}

	@Test
	void ridersAreTrackedAndRejoinedAfterGuestReconnect() {
		RelayRules.Inbound join = hub.check(A, "Jamie", "{\"type\":\"join\",\"key\":\"mc:evil\",\"name\":\"x\"}", DEF);
		assertTrue(join.accepted());
		hub.forward(A, join);
		assertTrue(hub.isRider(A));
		assertEquals(1, guest.sent.size());
		assertTrue(guest.sent.getFirst().contains("\"key\":\"mc:" + A + "\""));
		assertEquals(guest.sent.getFirst(), hub.riderJoin(A));

		feed(HELLO); // guest reconnected: it forgot bridge players of the old connection
		assertEquals(2, guest.sent.size());
		assertEquals(guest.sent.get(0), guest.sent.get(1), "the same sanitised join is re-sent");

		hub.disconnected(A);
		assertFalse(hub.isRider(A));
		assertEquals("{\"type\":\"leave\",\"key\":\"mc:" + A + "\"}", guest.sent.getLast());
		hub.disconnected(A);
		assertEquals(3, guest.sent.size(), "no second leave");
	}

	@Test
	void joinWhileGuestDownIsSentOnHello() {
		guest.connected = false;
		hub.forward(A, hub.check(A, "Jamie", "{\"type\":\"join\"}", DEF));
		assertTrue(guest.sent.isEmpty());
		guest.connected = true;
		feed(HELLO);
		assertEquals(1, guest.sent.size());
		assertTrue(guest.sent.getFirst().startsWith("{\"type\":\"join\""));
	}

	@Test
	void guestLeftEndsTheRider() {
		hub.forward(A, hub.check(A, "Jamie", "{\"type\":\"join\"}", DEF));
		feed("{\"type\":\"left\",\"key\":\"mc:" + A + "\"}");
		assertFalse(hub.isRider(A));
		int before = guest.sent.size();
		hub.disconnected(A);
		assertEquals(before, guest.sent.size(), "no leave for a key the guest already removed");
	}

	@Test
	void inputLeaveAndChatRequireRelayJoin() {
		assertFalse(hub.check(A, "Jamie", "{\"type\":\"input\",\"turn\":\"left\"}", DEF).accepted());
		assertFalse(hub.check(A, "Jamie", "{\"type\":\"leave\"}", DEF).accepted());
		assertFalse(hub.check(A, "Jamie", "{\"type\":\"chat\",\"text\":\"hi\"}", DEF).accepted());
		hub.forward(A, hub.check(A, "Jamie", "{\"type\":\"join\"}", DEF));
		RelayRules.Inbound turn = hub.check(A, "Jamie", "{\"type\":\"input\",\"key\":\"mc:" + B + "\",\"turn\":\"left\"}", DEF);
		assertTrue(turn.accepted());
		hub.forward(A, turn);
		assertEquals("{\"type\":\"input\",\"key\":\"mc:" + A + "\",\"turn\":\"left\"}", guest.sent.getLast());
		hub.forward(A, hub.check(A, "Jamie", "{\"type\":\"leave\"}", DEF));
		assertFalse(hub.isRider(A));
		assertEquals("{\"type\":\"leave\",\"key\":\"mc:" + A + "\"}", guest.sent.getLast());
		assertEquals(guest.sent, hub.recentForwarded());
	}

	@Test
	void cmdAndExplodeNeverReachTheGuest() {
		hub.forward(A, hub.check(A, "Jamie", "{\"type\":\"join\"}", DEF));
		int before = guest.sent.size();
		for (String l : new String[] {"{\"type\":\"cmd\",\"line\":\"KICK Syn\"}", "{\"type\":\"explode\",\"x\":0,\"y\":0,\"r\":50}"}) {
			RelayRules.Inbound in = hub.check(A, "Jamie", l, DEF);
			assertFalse(in.accepted());
			hub.forward(A, in);
		}
		assertEquals(before, guest.sent.size());
	}

	@Test
	void rateLimitRefusesBurstsButNeverMergesTurns() {
		hub.forward(A, hub.check(A, "Jamie", "{\"type\":\"join\"}", DEF));
		int ok = 0;
		for (int i = 0; i < 100; i++) {
			RelayRules.Inbound in = hub.check(A, "Jamie", "{\"type\":\"input\",\"turn\":\"left\"}", DEF);
			if (in.accepted()) {
				hub.forward(A, in);
				ok++;
			}
		}
		assertEquals((int) RelayHub.MSG_BURST - 1, ok, "burst (minus the join), then refused");
		assertEquals(ok, guest.sent.stream().filter(s -> s.contains("\"turn\"")).count(), "each accepted turn forwarded once");
		clock.addAndGet(1_000_000_000L);
		int after = 0;
		for (int i = 0; i < 100; i++) {
			if (hub.check(A, "Jamie", "{\"type\":\"input\",\"brake\":true}", DEF).accepted()) {
				after++;
			}
		}
		assertEquals((int) RelayHub.MSG_PER_SECOND, after, "refills at the sustained rate");
		// other players have their own bucket
		assertTrue(hub.check(B, "Other", "{\"type\":\"join\"}", DEF).accepted());
	}

	@Test
	void chatHasItsOwnStricterLimit() {
		hub.forward(A, hub.check(A, "Jamie", "{\"type\":\"join\"}", DEF));
		int ok = 0;
		for (int i = 0; i < 10; i++) {
			if (hub.check(A, "Jamie", "{\"type\":\"chat\",\"text\":\"spam\"}", DEF).accepted()) {
				ok++;
			}
		}
		assertEquals((int) RelayHub.CHAT_BURST, ok);
	}

	@Test
	void clearForgetsEverything() {
		hub.setRelayLine(RelayLines.relay(0, 0, 0, 1, "minecraft:overworld"));
		feed(HELLO);
		hub.subscribe(A, s -> { });
		hub.forward(B, hub.check(B, "B", "{\"type\":\"join\"}", DEF));
		hub.clear();
		assertFalse(hub.isSubscribed(A));
		assertFalse(hub.isRider(B));
		List<String> got = new ArrayList<>();
		hub.subscribe(A, got::add);
		assertTrue(got.isEmpty());
	}

	@Test
	void concurrentSubscribeNeverLosesOrReordersLiveLines() throws Exception {
		feed(HELLO);
		feed(ROUND);
		feed(STATE);
		int n = 2000;
		CountDownLatch start = new CountDownLatch(1);
		Thread reader = new Thread(() -> {
			try {
				start.await();
			} catch (InterruptedException e) {
				return;
			}
			for (int i = 0; i < n; i++) {
				String ev = "{\"type\":\"event\",\"ev\":\"chat\",\"name\":\"g\",\"text\":\"" + i + "\"}";
				hub.guestLine(ev, Protocol.parse(ev), null);
			}
		});
		reader.start();
		List<List<String>> streams = new ArrayList<>();
		start.countDown();
		for (int i = 0; i < 50; i++) {
			List<String> got = new CopyOnWriteArrayList<>();
			streams.add(got);
			hub.subscribe(UUID.randomUUID(), got::add);
			Thread.onSpinWait();
		}
		reader.join();
		for (List<String> got : streams) {
			assertEquals("hello", type(got.get(0)));
			assertEquals("round", type(got.get(1)));
			assertEquals("state", type(got.get(2)));
			int prev = -1;
			for (String l : got.subList(3, got.size())) {
				int i = Integer.parseInt(JsonParser.parseString(l).getAsJsonObject().get("text").getAsString());
				assertEquals(prev + 1 == 0 ? i : prev + 1, i, "live lines must be contiguous");
				prev = i;
			}
			assertTrue(prev == -1 || prev == n - 1, "stream ends with the last line");
		}
	}
}

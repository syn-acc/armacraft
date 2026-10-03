package gl.syn.armacraft.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** client/RELAY.md wire format: VarInt byte length + UTF-8 JSON, no newline. (Moved from client/.) */
class BridgePayloadTest {

	@Test
	void roundTripsUtf8Lines() {
		String line = "{\"type\":\"chat\",\"name\":\"Jämie\",\"text\":\"gg ✓\"}";
		ByteBuf buf = Unpooled.buffer();
		BridgePayload.codec(1024).encode(buf, new BridgePayload(line));
		int bytes = line.getBytes(StandardCharsets.UTF_8).length;
		assertEquals(bytes, buf.getByte(0));          // < 128 bytes: single-byte VarInt
		assertEquals(1 + bytes, buf.readableBytes());
		assertEquals(line, BridgePayload.codec(1024).decode(buf).line());
	}

	@Test
	void multiByteVarIntForLongLines() {
		String line = "{\"type\":\"state\",\"pad\":\"" + "x".repeat(70_000) + "\"}";
		ByteBuf buf = Unpooled.buffer();
		BridgePayload.codec(BridgePayload.MAX_CLIENTBOUND_BYTES).encode(buf, new BridgePayload(line));
		assertEquals(line, BridgePayload.codec(BridgePayload.MAX_CLIENTBOUND_BYTES).decode(buf).line());
	}

	@Test
	void rejectsOversizedLinesBothWays() {
		String line = "x".repeat(100);
		assertThrows(IllegalArgumentException.class, () -> BridgePayload.codec(50).encode(Unpooled.buffer(), new BridgePayload(line)));
		ByteBuf buf = Unpooled.buffer();
		BridgePayload.codec(1000).encode(buf, new BridgePayload(line));
		assertThrows(IllegalArgumentException.class, () -> BridgePayload.codec(50).decode(buf));
		ByteBuf truncated = Unpooled.buffer();
		truncated.writeByte(10);
		truncated.writeBytes(new byte[] {1, 2, 3});
		assertThrows(IllegalArgumentException.class, () -> BridgePayload.codec(50).decode(truncated));
	}

	@Test
	void serverboundLimitIs8KiB() {
		String ok = "{\"type\":\"chat\",\"text\":\"" + "x".repeat(BridgePayload.MAX_SERVERBOUND_BYTES - 30) + "\"}";
		ByteBuf buf = Unpooled.buffer();
		BridgePayload.codec(BridgePayload.MAX_CLIENTBOUND_BYTES).encode(buf, new BridgePayload(ok + "x".repeat(100)));
		assertThrows(IllegalArgumentException.class, () -> BridgePayload.codec(BridgePayload.MAX_SERVERBOUND_BYTES).decode(buf));
	}

	@Test
	void utf8LengthMatchesTheEncoder() {
		for (String s : new String[] {"", "abc", "Jämie", "€uro", "😀 ok", "\uD800 lone surrogate"}) {
			assertEquals(s.getBytes(StandardCharsets.UTF_8).length, BridgePayload.utf8Length(s), s);
		}
	}
}

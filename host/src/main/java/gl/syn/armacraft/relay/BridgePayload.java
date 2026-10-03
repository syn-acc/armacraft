package gl.syn.armacraft.relay;

import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;

/**
 * Relay payload on channel {@code armacraft:bridge} (client/RELAY.md). Lives in the host jar only; the optional
 * client renderer ({@code armacraft_client}) depends on armacraft and uses this class, so the two jars never ship
 * the same class name and the id is registered exactly once.
 *
 * <p>Wire format, both directions: VarInt byte length, then that many bytes of UTF-8 holding exactly one
 * PROTOCOL.md JSON object without the trailing newline. Server to client carries guest lines verbatim plus the
 * host-originated {@code relay} line and keyframes; client to server carries host messages.
 *
 * <p>Common code (no client classes): registered from {@link BridgeRelay}, a main entrypoint, on both sides.
 */
public record BridgePayload(String line) implements CustomPacketPayload {

	public static final Identifier ID = Identifier.fromNamespaceAndPath("armacraft", "bridge");
	public static final CustomPacketPayload.Type<BridgePayload> TYPE = new CustomPacketPayload.Type<>(ID);

	/** Largest clientbound line (bytes). Guest snapshots of big games can be tens of KB. */
	public static final int MAX_CLIENTBOUND_BYTES = 4 * 1024 * 1024;
	/** Largest serverbound line (bytes). Host messages are tiny; this bounds abuse. */
	public static final int MAX_SERVERBOUND_BYTES = 8 * 1024;

	public static StreamCodec<ByteBuf, BridgePayload> codec(int maxBytes) {
		return new StreamCodec<>() {
			@Override
			public BridgePayload decode(ByteBuf buf) {
				int len = readVarInt(buf);
				if (len < 0 || len > maxBytes || len > buf.readableBytes()) {
					throw new IllegalArgumentException("armacraft:bridge line of " + len + " bytes (max " + maxBytes + ")");
				}
				byte[] bytes = new byte[len];
				buf.readBytes(bytes);
				return new BridgePayload(new String(bytes, StandardCharsets.UTF_8));
			}

			@Override
			public void encode(ByteBuf buf, BridgePayload payload) {
				byte[] bytes = payload.line().getBytes(StandardCharsets.UTF_8);
				if (bytes.length > maxBytes) {
					throw new IllegalArgumentException("armacraft:bridge line of " + bytes.length + " bytes (max " + maxBytes + ")");
				}
				writeVarInt(buf, bytes.length);
				buf.writeBytes(bytes);
			}
		};
	}

	private static boolean registered;

	/** Registers both directions once (idempotent). Called from the common initializer of the host mod. */
	public static synchronized void registerTypes() {
		if (registered) {
			return;
		}
		PayloadTypeRegistry.clientboundPlay().registerLarge(TYPE, codec(MAX_CLIENTBOUND_BYTES), MAX_CLIENTBOUND_BYTES + 8);
		PayloadTypeRegistry.serverboundPlay().register(TYPE, codec(MAX_SERVERBOUND_BYTES));
		registered = true;
	}

	/** True once the host mod registered the payload type (always, when armacraft is installed and initialised). */
	public static synchronized boolean available() {
		return registered;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	/** UTF-8 byte length without allocating (lines are checked against the limits before sending). */
	public static int utf8Length(String s) {
		int n = 0;
		for (int i = 0, len = s.length(); i < len; i++) {
			char c = s.charAt(i);
			if (c < 0x80) {
				n++;
			} else if (c < 0x800) {
				n += 2;
			} else if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
				n += 4;
				i++;
			} else if (Character.isSurrogate(c)) {
				n++; // String.getBytes(UTF_8) writes a lone surrogate as '?'
			} else {
				n += 3;
			}
		}
		return n;
	}

	static int readVarInt(ByteBuf buf) {
		int value = 0;
		int shift = 0;
		while (true) {
			if (!buf.isReadable()) {
				throw new IllegalArgumentException("truncated VarInt");
			}
			byte b = buf.readByte();
			value |= (b & 0x7F) << shift;
			if ((b & 0x80) == 0) {
				return value;
			}
			shift += 7;
			if (shift >= 35) {
				throw new IllegalArgumentException("VarInt too big");
			}
		}
	}

	static void writeVarInt(ByteBuf buf, int value) {
		while ((value & ~0x7F) != 0) {
			buf.writeByte((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		buf.writeByte(value);
	}
}

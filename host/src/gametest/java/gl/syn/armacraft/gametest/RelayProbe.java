package gl.syn.armacraft.gametest;

import com.google.gson.JsonParser;
import gl.syn.armacraft.gametest.mixin.ConnectionAccessor;
import gl.syn.armacraft.gametest.mixin.ServerCommonPacketListenerAccessor;
import gl.syn.armacraft.relay.BridgePayload;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only stand-in for a client with armacraft_client installed, on a GameTest mock player (whose connection is
 * a netty EmbeddedChannel):
 * <ul>
 *   <li>{@link #registerChannel()} sends Fabric's {@code minecraft:register} for {@code armacraft:bridge}, exactly what
 *       a modded client does, so {@code ServerPlayNetworking.canSend} becomes true through Fabric's own code;</li>
 *   <li>{@link #send(String)} delivers a serverbound {@code armacraft:bridge} payload through the real
 *       {@code handleCustomPayload} path (Fabric dispatches it to the relay's global receiver);</li>
 *   <li>an outbound handler records every clientbound {@code armacraft:bridge} payload, in order.</li>
 * </ul>
 * Relay lines are sent from the bridge reader thread; an EmbeddedChannel is not thread-safe, so bridge payloads are
 * consumed here (never reach the channel's buffer) and flushes from other threads are dropped.
 */
final class RelayProbe {

	final ServerPlayer player;
	final List<String> lines = new CopyOnWriteArrayList<>();
	private final Thread serverThread = Thread.currentThread();

	RelayProbe(ServerPlayer player) {
		this.player = player;
		Channel ch = ((ConnectionAccessor) ((ServerCommonPacketListenerAccessor) player.connection).armacraft$connection()).armacraft$channel();
		ch.pipeline().addFirst("armacraft-relay-probe", new ChannelOutboundHandlerAdapter() {
			@Override
			public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
				if (msg instanceof ClientboundCustomPayloadPacket p && p.payload() instanceof BridgePayload b) {
					lines.add(b.line());
					promise.trySuccess();
					return;
				}
				super.write(ctx, msg, promise);
			}

			@Override
			public void flush(ChannelHandlerContext ctx) throws Exception {
				if (Thread.currentThread() == serverThread) {
					super.flush(ctx);
				}
			}
		});
	}

	String key() {
		return "mc:" + player.getUUID();
	}

	void registerChannel() {
		player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(new RegistrationPayload(RegistrationPayload.REGISTER, List.of(BridgePayload.ID))));
	}

	void send(String line) {
		player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(new BridgePayload(line)));
	}

	/** Disconnect the way the server does (channel closes, then handleDisconnection), so Fabric's DISCONNECT fires. */
	void disconnect(String reason) {
		net.minecraft.network.Connection conn = ((ServerCommonPacketListenerAccessor) player.connection).armacraft$connection();
		conn.disconnect(new net.minecraft.network.DisconnectionDetails(net.minecraft.network.chat.Component.literal(reason)));
		conn.handleDisconnection();
	}

	List<String> types() {
		return lines.stream().map(l -> {
			try {
				return JsonParser.parseString(l).getAsJsonObject().get("type").getAsString();
			} catch (RuntimeException e) {
				return "?";
			}
		}).toList();
	}

	long count(String type) {
		return types().stream().filter(type::equals).count();
	}
}

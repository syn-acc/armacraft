package gl.syn.armacraft.ride.server;

import com.mojang.authlib.GameProfile;
import gl.syn.armacraft.ride.RideRegistry;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Test-only: a real ServerPlayer joined through {@code PlayerList.placeNewPlayer} with a real
 * {@code ServerGamePacketListenerImpl}, on an in-memory connection that records every packet the server sends.
 * Lets a test drive the vanilla packet handler (move packets, teleport accepts) and see rubber-band teleports,
 * particles and sounds. Same construction as {@code GameTestHelper.makeMockServerPlayerInLevel}, minus its
 * forced creative mode.
 */
final class CapturingPlayer {

	final ServerPlayer player;
	private final List<Packet<?>> sent;

	private CapturingPlayer(ServerPlayer player, List<Packet<?>> sent) {
		this.player = player;
		this.sent = sent;
	}

	static CapturingPlayer join(GameTestHelper helper, String name) {
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
		ServerPlayer player = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation());
		List<Packet<?>> sent = Collections.synchronizedList(new ArrayList<>());
		Connection connection = new Connection(PacketFlow.SERVERBOUND) {
			@Override
			public void send(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush) {
				sent.add(packet);
				super.send(packet, listener, flush);
			}
		};
		new EmbeddedChannel(connection);
		server.getPlayerList().placeNewPlayer(connection, player, cookie);
		CapturingPlayer p = new CapturingPlayer(player, sent);
		player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		p.acceptTeleport();
		player.setGameMode(GameType.SURVIVAL);
		return p;
	}

	/** Server teleport + the client's accept, so the handler takes move packets from there. */
	void teleport(Vec3 abs) {
		player.connection.teleport(abs.x, abs.y, abs.z, 0.0F, 0.0F);
		acceptTeleport();
	}

	/**
	 * Accept the latest teleport the server sent, like a client does on its next tick. A server tick runs first so
	 * the handler's per-tick baseline (firstGood) is the teleport target; otherwise the accept's own position
	 * change is measured from the old baseline and trips "moved too quickly" into another teleport.
	 */
	void acceptTeleport() {
		for (int attempt = 0; attempt < 3; attempt++) {
			List<ClientboundPlayerPositionPacket> tps = sent(ClientboundPlayerPositionPacket.class);
			if (tps.isEmpty()) {
				return;
			}
			int before = tps.size();
			int id = tps.get(before - 1).id();
			serverTick();
			player.connection.handleAcceptTeleportPacket(
				new ServerboundAcceptTeleportationPacket(id, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
			if (sent(ClientboundPlayerPositionPacket.class).size() == before) {
				return;
			}
		}
		throw new IllegalStateException(player.getPlainTextName() + ": server keeps re-sending teleports");
	}

	/** One client tick's movement: a position packet, then the tick-end packet (26.3 allows one position per client tick). */
	void move(Vec3 abs, boolean onGround) {
		player.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(abs.x, abs.y, abs.z, onGround, false));
		player.connection.handleClientTickEnd(ServerboundClientTickEndPacket.INSTANCE);
	}

	/** Run the connection's per-server-tick work (tickPlayer: resets the per-tick move baseline). */
	void serverTick() {
		player.connection.tick();
	}

	@SuppressWarnings("unchecked")
	<T> List<T> sent(Class<T> type) {
		List<T> out = new ArrayList<>();
		synchronized (sent) {
			for (Packet<?> p : sent) {
				if (type.isInstance(p)) {
					out.add((T) p);
				}
			}
		}
		return out;
	}

	void clearSent() {
		sent.clear();
	}

	void leave() {
		if (RideRegistry.isRiding(player)) {
			RideRegistry.stop(player, RideRegistry.StopReason.DISCONNECT, player.position());
		}
		player.level().getServer().getPlayerList().remove(player);
	}
}

package gl.syn.armacraft.client.net;

import gl.syn.armacraft.relay.BridgePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.function.Consumer;

/**
 * Every use of the host's {@code armacraft:bridge} payload class in this mod. Only touched when
 * {@link RelayPresence#PRESENT}, so a host without the relay never makes this jar fail to load.
 */
public final class RelayBinding {

	private RelayBinding() {}

	/** Relay classes present and the payload type registered by the host's common initializer. */
	public static boolean available() {
		return RelayPresence.PRESENT && BridgePayload.available();
	}

	public static boolean canSend() {
		return available() && ClientPlayNetworking.canSend(BridgePayload.TYPE);
	}

	public static boolean isChannel(Collection<Identifier> channels) {
		return available() && channels.contains(BridgePayload.ID);
	}

	public static void registerReceiver(Consumer<String> lines) {
		if (available()) {
			ClientPlayNetworking.registerGlobalReceiver(BridgePayload.TYPE, (payload, context) -> lines.accept(payload.line()));
		}
	}

	public static void send(String line) {
		if (canSend()) {
			ClientPlayNetworking.send(new BridgePayload(line));
		}
	}
}

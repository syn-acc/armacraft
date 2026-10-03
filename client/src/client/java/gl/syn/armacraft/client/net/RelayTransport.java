package gl.syn.armacraft.client.net;


/**
 * Relay mode (client/RELAY.md): guest lines arrive as {@code armacraft:bridge} payloads from the Minecraft
 * server; host messages go back the same channel and the server forwards them after forcing {@code key}.
 */
public final class RelayTransport extends Transport {

	private volatile boolean open = true;

	/** Called by the global payload receiver. */
	public void receive(String line) {
		if (open && line != null && !line.isBlank()) {
			inbox.add(new Line(line, now()));
		}
	}

	@Override
	public void send(String line) {
		if (open) {
			RelayBinding.send(line);
		}
	}

	@Override
	public boolean connected() {
		return open && RelayBinding.canSend();
	}

	@Override
	public String describe() {
		return "relay armacraft:bridge" + (connected() ? "" : " (server not offering it)");
	}

	@Override
	public void close() {
		open = false;
	}
}

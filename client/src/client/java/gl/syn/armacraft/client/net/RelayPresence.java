package gl.syn.armacraft.client.net;

/**
 * Whether the installed armacraft (host) jar has the relay ({@code gl.syn.armacraft.relay}). No symbolic reference to
 * relay classes here, so this jar also runs next to an older host: relay mode is then simply unavailable.
 */
public final class RelayPresence {

	public static final boolean PRESENT = exists("gl.syn.armacraft.relay.BridgePayload");

	private RelayPresence() {}

	private static boolean exists(String name) {
		try {
			Class.forName(name, false, RelayPresence.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}
}

package gl.syn.armacraft.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import gl.syn.armacraft.bridge.Msg;
import gl.syn.armacraft.logic.WallLayout;

/** Host-originated lines on the relay channel (client/RELAY.md "Server → client"). Pure. */
public final class RelayLines {

	public static final int PROTO = 1;

	private RelayLines() {}

	/** {@code {"type":"relay","proto":1,"anchor":[ax,ay,az],"scale":s,"dimension":"..."}} */
	public static String relay(double ax, double ay, double az, double scale, String dimension) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "relay");
		o.addProperty("proto", PROTO);
		JsonArray a = new JsonArray();
		a.add(ax);
		a.add(ay);
		a.add(az);
		o.add("anchor", a);
		o.addProperty("scale", scale);
		o.addProperty("dimension", dimension);
		return o.toString();
	}

	/**
	 * A full v1 {@code state} line rebuilt from the host's folded snapshot. Sent once to a client that starts
	 * listening mid-stream: when the host negotiated {@code delta} with the guest, the live lines are deltas
	 * against a wall set this client never saw, so it needs a keyframe first. {@code "keyframe":true} marks it
	 * (unknown fields are ignored by v1 parsers).
	 */
	public static String keyframe(Msg.State st) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "state");
		o.addProperty("t", st.t());
		JsonArray cycles = new JsonArray();
		for (Msg.Cycle c : st.cycles()) {
			JsonObject j = new JsonObject();
			j.addProperty("id", c.id());
			j.addProperty("name", c.name());
			JsonArray col = new JsonArray();
			col.add(c.r());
			col.add(c.g());
			col.add(c.b());
			j.add("color", col);
			j.addProperty("x", c.x());
			j.addProperty("y", c.y());
			j.addProperty("dx", c.dx());
			j.addProperty("dy", c.dy());
			j.addProperty("speed", c.speed());
			j.addProperty("alive", c.alive());
			j.addProperty("brake", c.brake());
			j.addProperty("rubber", c.rubber());
			if (c.bridgeKey() != null) {
				j.addProperty("bridge_key", c.bridgeKey());
			}
			cycles.add(j);
		}
		o.add("cycles", cycles);
		JsonArray walls = new JsonArray();
		for (Msg.Wall w : st.walls()) {
			JsonObject j = new JsonObject();
			j.addProperty("id", w.id());
			j.addProperty("cycle", w.cycle());
			j.addProperty("x1", w.x1());
			j.addProperty("y1", w.y1());
			j.addProperty("x2", w.x2());
			j.addProperty("y2", w.y2());
			if (!w.gaps().isEmpty()) {
				JsonArray gaps = new JsonArray();
				for (WallLayout.Gap g : w.gaps()) {
					JsonArray ga = new JsonArray();
					ga.add(g.from());
					ga.add(g.to());
					gaps.add(ga);
				}
				j.add("gaps", gaps);
			}
			walls.add(j);
		}
		o.add("walls", walls);
		o.addProperty("keyframe", true);
		return o.toString();
	}
}

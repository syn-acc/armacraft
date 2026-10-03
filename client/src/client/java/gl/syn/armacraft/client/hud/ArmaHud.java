package gl.syn.armacraft.client.hud;

import gl.syn.armacraft.client.ArenaClient;
import gl.syn.armacraft.client.core.ArenaModel;
import gl.syn.armacraft.client.kit.ArmaGauges;
import gl.syn.armacraft.client.kit.ArmaScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Armagetron's HUD (src/tron/gHud.cpp, src/render/rConsoleGraph.cpp, v0.2.9.3.0) drawn with Armagetron's own
 * font texture (textures/font.png, 16x8 glyph grid of 16x32 cells):
 * <ul>
 *   <li>GLmeter_subby gauges: needle line from {@code (-.1x,.1y)*size} to {@code (-x,y)*size} where
 *       {@code x = cos(pi*v/max)}, {@code y = sin(pi*v/max)}, needle colour (.5,.5,1), min/max labels 0xcccccc,
 *       value 0xccffff, title 0xff3333. Speed at (0,-.9), Rubber Used at (-.48,-.9), Brakes at (.48,-.9),
 *       size .175; speed max starts at 50 and grows by 10.</li>
 *   <li>"Fastest: name speed" (0xbf9d50) at (-.2,-.95) and "Enemies: n Friends: m" (0xfefefe) at (.45,-.95), size .13.</li>
 *   <li>Center message ("Winner: x") at 4x console glyph size, visible 1 s then fading for 1 s.</li>
 *   <li>Console lines top-left, e.g. "x core dumped y."</li>
 * </ul>
 * Screen coordinates are Armagetron's -1..1 viewport space over a 4:3 area centred at the bottom of the window.
 */
public final class ArmaHud {

	public record Segment(String text, int rgb) {}

	private record Line(List<Segment> segments, double time) {}

	private final ArenaClient client;
	private final Deque<Line> console = new ArrayDeque<>();
	private String center;
	private int centerRgb = 0xFFFFFF;
	private double centerTime = -100;
	private double fastest;
	private String fastestName = "";

	public ArmaHud(ArenaClient client) {
		this.client = client;
	}

	public void consoleLine(List<Segment> segments) {
		console.addLast(new Line(segments, ArenaClient.localNow()));
		while (console.size() > 6) {
			console.removeFirst();
		}
	}

	public void centerMessage(String text, int rgb) {
		center = text;
		centerRgb = rgb;
		centerTime = ArenaClient.localNow();
	}

	public void newRound() {
		fastest = 0;
		fastestName = "";
	}

	public void trackSpeeds() {
		for (ArenaModel.Cycle c : client.model().cycles()) {
			if (c.alive && c.speed > fastest) {
				fastest = c.speed;
				fastestName = c.name;
			}
		}
	}

	private final ArmaGauges gauges = new ArmaGauges();
	private ArmaScreen scr;

	private void text(GuiGraphicsExtractor g, String str, double x, double y, double cw, double ch, int rgb, float alpha) {
		scr.text(str, x, y, cw, ch, rgb, alpha);
	}

	private final java.util.Map<Integer, Double> cameIntoView = new java.util.HashMap<>();

	/**
	 * gCycle::RenderName: the name of every other cycle, white at alpha .75, glyph height rCHEIGHT_NORMAL
	 * (32/480), one glyph height above the projected point (0.8, 0, 2.0) of the cycle model; shown for
	 * FADEOUT_NAME_DELAY = 5 s after the cycle comes into view, fading out during the last second.
	 */
	private void names(GuiGraphicsExtractor g, double now) {
		var mc = net.minecraft.client.Minecraft.getInstance();
		var cam = mc.gameRenderer.mainCamera();
		if (cam == null || !cam.isInitialized()) {
			return;
		}
		var cp = cam.position();
		var fwd = cam.forwardVector();
		var up = cam.upVector();
		var left = cam.leftVector();
		double tanV = Math.tan(Math.toRadians(cam.getFov()) / 2);
		double aspect = (double) client.windowWidth() / Math.max(1, client.windowHeight());
		ArenaModel.Cycle own = client.ownCycle();
		java.util.Set<Integer> seen = new java.util.HashSet<>();
		for (ArenaModel.Cycle c : client.model().cycles()) {
			if (!c.alive || c == own) {
				continue;
			}
			gl.syn.armacraft.client.core.V2 p = client.renderPos(c);
			gl.syn.armacraft.client.core.V2 d = c.anim.dir;
			double[] mcp = client.toMc(p.x() + d.x() * .4, p.y() + d.y() * .4, 1.0);
			double rx = mcp[0] - cp.x, ry = mcp[1] - cp.y, rz = mcp[2] - cp.z;
			double f = rx * fwd.x() + ry * fwd.y() + rz * fwd.z();
			if (f <= 0) {
				continue;
			}
			double xp = -(rx * left.x() + ry * left.y() + rz * left.z()) / f / (tanV * aspect);
			double yp = (rx * up.x() + ry * up.y() + rz * up.z()) / f / tanV + 32 / 480.0;
			if (xp <= -1 || xp >= 1 || yp <= -1 || yp >= 1) {
				continue;
			}
			seen.add(c.id);
			double since = cameIntoView.computeIfAbsent(c.id, k -> now);
			double age = now - since;
			double fade = 5.0;
			if (age > fade) {
				continue;
			}
			float alpha = .75f;
			if (age > fade - 1) {
				alpha = (float) (.75 - (age - (fade - 1)) * .75);
			}
			double ch = 32 / 480.0;
			double cw = ch * (16 / 640.0) / (32 / 480.0) / aspect * (4 / 3.0);
			double toMyX = scr.windowToX();
			double width = c.name.length() * cw;
			text(g, c.name, (xp - width / 2) * toMyX, scr.windowY(yp), cw * toMyX, ch, 0xffffff, alpha);
		}
		cameIntoView.keySet().retainAll(seen);
	}

	// ----------------------------------------------------------------------------------- render

	public void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (!client.config().hud.enabled || !client.active()) {
			return;
		}
		scr = ArmaScreen.of(g);
		float w = scr.w, h = scr.h, unitX = scr.unitX, unitY = scr.unitY;
		double now = ArenaClient.localNow();

		// console (rConsoleGraph: top-left, native glyph size 15x31 px at >= 1280x720)
		// rConsoleGraph: rCWIDTH_CON = 15*2/W, rCHEIGHT_CON = 31*2/H in whole-window -1..1 coordinates
		double cw = 15 * 2.0 / Math.max(1280, client.windowWidth()) * ((w / 2f) / unitX);
		double ch = 31 * 2.0 / Math.max(720, client.windowHeight()) * ((h / 2f) / unitY);
		double ty = scr.windowY(0.99);
		for (Line l : console) {
			double age = now - l.time;
			if (age > 9) {
				continue;
			}
			float alpha = (float) Math.min(1, 9 - age);
			double tx = -0.95 * (w / 2f) / unitX;
			for (Segment s : l.segments) {
				text(g, s.text, tx, ty, cw, ch, s.rgb, alpha);
				tx += s.text.length() * cw;
			}
			ty -= ch;
		}

		names(g, now);

		ArenaModel.Cycle me = client.watchedCycle();
		if (me != null && client.gameTime() > -2) {
			gauges.rubberMax = client.config().hud.rubberMax;
			gauges.draw(scr, new ArmaGauges.Model(me.speed, me.rubber, me.brakeReservoir));
		}

		{
			double size = .13;
			String msg = "  Fastest: " + fastestName + " " + String.format("%.1f", fastest);
			int length = msg.length();
			text(g, msg, -0.2 - ((.15 * size * (length - 1.5)) / 2.0), -0.95, .15 * size, .3 * size, 0xbf9d50, 1);
			int enemies = 0, friends = 0;
			ArenaModel.Cycle own = client.ownCycle();
			for (ArenaModel.Cycle c : client.model().cycles()) {
				if (c.alive && c != own) {
					enemies++;
				}
			}
			String alive = "Enemies: " + enemies + " Friends: " + friends;
			length = alive.length();
			text(g, alive, .45 - ((.15 * size * (length - 1.5)) / 2.0), -0.95, .15 * size, .3 * size, 0xfefefe, 1);
		}

		// alive list (adaptation: Armagetron shows this on its score table; names in cycle colour)
		{
			double size = .13;
			double gw = .15 * size, gh = .3 * size;
			double x = (w / 2f) / unitX * 0.95 - 16 * gw;
			double y = scr.windowY(0.97);
			List<ArenaModel.Cycle> list = new ArrayList<>(client.model().cycles());
			list.sort((a, b) -> Boolean.compare(b.alive, a.alive));
			for (ArenaModel.Cycle c : list) {
				int rgb = ((int) (c.r * 255) << 16) | ((int) (c.g * 255) << 8) | (int) (c.b * 255);
				String name = c.name.length() > 14 ? c.name.substring(0, 14) : c.name;
				text(g, (c.alive ? "  " : "x ") + name, x, y, gw, gh, c.alive ? rgb : 0x777777, c.alive ? 1 : 0.8f);
				y -= gh;
			}
		}

		// countdown before the round (adaptation requested for the Minecraft side)
		double t = client.gameTime();
		if (Double.isFinite(t) && t < 0 && t > -10 && client.model().hasArena()) {
			String s = Integer.toString((int) Math.ceil(-t));
			double cwc = 16 / 640.0 * 4, chc = 32 / 480.0 * 4;
			text(g, s, -cwc * s.length() / 2, scr.windowY(0) + chc / 2 + 0.25, cwc, chc, 0xffffff, 1);
		}

		// center message
		double age = now - centerTime;
		if (center != null && age < 2) {
			float alpha = (float) Math.max(0, Math.min(1, 1 - (age - 1)));
			double cwc = 16 / 640.0 * 4, chc = 32 / 480.0 * 4;
			double needed = cwc * center.length();
			double fak = needed > 1.6 ? 1.6 / needed : 1;
			text(g, center, -cwc * fak * center.length() / 2, scr.windowY(0) + chc * fak / 2, cwc * fak, chc * fak, centerRgb, alpha);
		}
	}
}

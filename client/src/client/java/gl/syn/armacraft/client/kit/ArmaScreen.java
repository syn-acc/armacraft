package gl.syn.armacraft.client.kit;

import gl.syn.armacraft.client.render.ArmaTextures;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2fStack;

/**
 * Armagetron 2D drawing on a Minecraft GUI frame: Armagetron's -1..1 screen coordinates over a 4:3 area anchored
 * at the bottom centre (rViewport::CorrectAspectBottom), Armagetron's own font (textures/font.png, 16x8 grid of
 * 16x32 glyph cells, rTextField-style fixed advance) and thick lines. Create one per frame with {@link #of}.
 * No bridge types.
 *
 * <p>The area is always 4:3 (see {@link #viewport}): as tall as the window when the window is 4:3 or wider, and as
 * wide as the window, standing on its bottom edge, when it is narrower (a portrait window). So one x unit and one y
 * unit always have the same proportion: gauges stay round and glyphs keep their shape at any window shape. Things
 * that belong to the whole window rather than to the bottom area (centre-screen messages) place themselves with
 * {@link #windowY}.
 */
public final class ArmaScreen {

	public final GuiGraphicsExtractor g;
	public final float w, h, unitX, unitY, cx, cy;

	private ArmaScreen(GuiGraphicsExtractor g) {
		this.g = g;
		w = g.guiWidth();
		h = g.guiHeight();
		float[] v = viewport(w, h);
		unitX = v[0];
		unitY = v[1];
		cx = v[2];
		cy = v[3];
	}

	/**
	 * The 4:3 area for a {@code w} x {@code h} GUI frame: {unitX, unitY, cx, cy} (pixels per unit and the pixel
	 * position of the area's centre). Width {@code min(w, h * 4/3)}, height 3/4 of that, bottom centre.
	 */
	public static float[] viewport(float w, float h) {
		float vw = Math.min(w, h * 4f / 3f);
		float vh = vw * 3f / 4f;
		return new float[] {vw / 2f, vh / 2f, w / 2f, h - vh / 2f};
	}

	public static ArmaScreen of(GuiGraphicsExtractor g) {
		return new ArmaScreen(g);
	}

	public float sx(double x) {
		return (float) (cx + x * unitX);
	}

	public float sy(double y) {
		return (float) (cy - y * unitY);
	}

	/** Factor converting a whole-window x coordinate (-1..1 across the window) into this 4:3 space. */
	public double windowToX() {
		return (w / 2f) / unitX;
	}

	/** Factor converting a whole-window y extent (-1..1 across the window height) into this 4:3 space. */
	public double windowToY() {
		return (h / 2f) / unitY;
	}

	/**
	 * This space's y for a height in the whole window (-1 bottom edge, 0 centre, 1 top edge). The identity while the
	 * window is 4:3 or wider; in a narrower window the 4:3 area only covers the bottom of the window.
	 */
	public double windowY(double wy) {
		return (cy - (h / 2f - wy * h / 2f)) / unitY;
	}

	/** rTextField-style text: top-left at (x,y), glyph cell cw x ch, all in Armagetron screen units. */
	public void text(String s, double x, double y, double cw, double ch, int rgb, float alpha) {
		float px = sx(x), py = sy(y);
		float gw = (float) (cw * unitX), gh = (float) (ch * unitY);
		int color = ((int) (Math.max(0, Math.min(1, alpha)) * 255) << 24) | (rgb & 0xFFFFFF);
		Matrix3x2fStack pose = g.pose();
		for (int i = 0; i < s.length(); i++) {
			int c = s.charAt(i);
			if (c < 32 || c > 127) {
				c = '?';
			}
			if (c != ' ') {
				pose.pushMatrix();
				pose.translate(px + i * gw, py);
				pose.scale(gw / 16f, gh / 32f);
				g.blit(RenderPipelines.GUI_TEXTURED, ArmaTextures.FONT, 0, 0, (c % 16) * 16, (c / 16) * 32, 16, 32, 16, 32, 256, 256, color);
				pose.popMatrix();
			}
		}
	}

	/** Line between two points in Armagetron screen units, {@code thickness} in GUI pixels. */
	public void line(double x1, double y1, double x2, double y2, int argb, float thickness) {
		float ax = sx(x1), ay = sy(y1), bx = sx(x2), by = sy(y2);
		float dx = bx - ax, dy = by - ay;
		float len = (float) Math.sqrt(dx * dx + dy * dy);
		if (len < 0.01f) {
			return;
		}
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(ax, ay);
		pose.rotate((float) Math.atan2(dy, dx));
		pose.scale(len / 64f, thickness / 4f);
		g.fill(0, -2, 64, 2, argb);
		pose.popMatrix();
	}
}

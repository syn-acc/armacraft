package gl.syn.armacraft.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Armagetron textures prepared the way Armagetron prepares them, uploaded with full mip chains and trilinear
 * filtering (Armagetron builds mipmaps with gluBuild2DMipmaps):
 * <ul>
 *   <li>cycle_body / cycle_wheel per cycle colour: {@code gTextureCycle::ProcessImage} blends the transparent
 *       parts with the player colour, {@code (a*p + (255-a)*c) >> 8}, and makes the result opaque.</li>
 *   <li>dir_wall: luminance texture, repeat in u, clamped in v ({@code rFileTexture(...,1,0,1)}). Its alpha is
 *       a Minecraft-side addition for the requested translucent walls: the dim body gets
 *       {@code wallBodyAlpha}, the bright stripes stay opaque; the body brightness (102/255 upstream) is
 *       configurable for the same reason.</li>
 *   <li>rim_wall: as is, repeat both ways.</li>
 *   <li>floor grid: floor_a (lines of constant y) + floor_b (lines of constant x), i.e. what
 *       rFLOOR_TWOTEXTURE produces on a black background, baked into one repeat texture.</li>
 * </ul>
 */
public final class ArmaTextures {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft_client/textures");
	public static final String NS = "armacraft_client";

	public static final Identifier DIR_WALL = id("dyn/dir_wall");
	public static final Identifier RIM_WALL = id("dyn/rim_wall");
	public static final Identifier FLOOR_GRID = id("dyn/floor_grid");
	/** Plain white (vertex colour only): the ride light pools. */
	public static final Identifier WHITE = id("dyn/white");
	/** The HUD uses the PNG directly. */
	public static final Identifier FONT = id("textures/arma/font.png");

	private static final Map<Integer, Identifier[]> CYCLE = new HashMap<>();
	private static int[][] bodyPx, wheelPx;
	private static int bodyW, bodyH, wheelW, wheelH;
	private static double builtWallAlpha = -1;
	private static double builtWallBright = -1;
	private static boolean baseBuilt;

	private ArmaTextures() {
	}

	static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(NS, path);
	}

	/** An uploaded texture with explicit mip chain and sampler. */
	static final class ArmaTexture extends AbstractTexture {
		ArmaTexture(String label, NativeImage[] mips, AddressMode u, AddressMode v) {
			GpuDevice device = RenderSystem.getDevice();
			this.texture = device.createTexture(label, 5, GpuFormat.RGBA8_UNORM, mips[0].getWidth(), mips[0].getHeight(), 1, mips.length);
			this.textureView = device.createTextureView(this.texture);
			for (int level = 0; level < mips.length; level++) {
				device.createCommandEncoder().writeToTexture(this.texture, mips[level], level, 0, 0, 0);
			}
			this.sampler = RenderSystem.getSamplerCache().getSampler(u, v, FilterMode.LINEAR, FilterMode.LINEAR, mips.length > 1);
		}
	}

	private static BufferedImage read(String path) throws IOException {
		Identifier rid = id(path);
		Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(rid);
		if (res.isEmpty()) {
			throw new IOException("missing resource " + rid);
		}
		try (InputStream in = res.get().open()) {
			BufferedImage img = ImageIO.read(in);
			if (img == null) {
				throw new IOException("unreadable image " + rid);
			}
			return img;
		}
	}

	/** Raw RGBA of a PNG: grey/LA images expand to R=G=B=L. Returns [y][x] as 0xAARRGGBB. */
	private static int[][] argb(BufferedImage img) {
		int w = img.getWidth(), h = img.getHeight();
		int[][] out = new int[h][w];
		boolean grey = img.getColorModel().getNumColorComponents() == 1;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int p;
				if (grey) {
					// avoid sRGB gamma conversion of grey images by reading the raster directly
					int l = img.getRaster().getSample(x, y, 0);
					int a = img.getColorModel().hasAlpha() ? img.getRaster().getSample(x, y, 1) : 255;
					p = (a << 24) | (l << 16) | (l << 8) | l;
				} else {
					p = img.getRGB(x, y);
					if (!img.getColorModel().hasAlpha()) {
						p |= 0xFF000000;
					}
				}
				out[y][x] = p;
			}
		}
		return out;
	}

	/** Box-filtered mip chain down to 1x1 (straight alpha). */
	static NativeImage[] mips(int[][] px) {
		java.util.List<NativeImage> levels = new java.util.ArrayList<>();
		int[][] cur = px;
		while (true) {
			int h = cur.length, w = cur[0].length;
			NativeImage img = new NativeImage(w, h, false);
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					img.setPixel(x, y, cur[y][x]);
				}
			}
			levels.add(img);
			if (w == 1 && h == 1) {
				break;
			}
			int nw = Math.max(1, w / 2), nh = Math.max(1, h / 2);
			int[][] next = new int[nh][nw];
			for (int y = 0; y < nh; y++) {
				for (int x = 0; x < nw; x++) {
					int a = 0, r = 0, g = 0, b = 0, n = 0;
					for (int dy = 0; dy < 2; dy++) {
						for (int dx = 0; dx < 2; dx++) {
							int sx = Math.min(w - 1, x * 2 + dx), sy = Math.min(h - 1, y * 2 + dy);
							int p = cur[sy][sx];
							a += p >>> 24;
							r += (p >> 16) & 255;
							g += (p >> 8) & 255;
							b += p & 255;
							n++;
						}
					}
					next[y][x] = ((a / n) << 24) | ((r / n) << 16) | ((g / n) << 8) | (b / n);
				}
			}
			cur = next;
		}
		return levels.toArray(new NativeImage[0]);
	}

	private static void register(Identifier id, int[][] px, AddressMode u, AddressMode v) {
		NativeImage[] m = mips(px);
		try {
			Minecraft.getInstance().getTextureManager().register(id, new ArmaTexture(id.toString(), m, u, v));
		} finally {
			for (NativeImage img : m) {
				img.close();
			}
		}
	}

	/** (Re)build the shared textures. Render thread only. */
	public static synchronized void ensureBase(double wallBodyAlpha, double wallBodyBrightness) {
		if (baseBuilt && Math.abs(builtWallAlpha - wallBodyAlpha) < 1e-6 && Math.abs(builtWallBright - wallBodyBrightness) < 1e-6) {
			return;
		}
		try {
			if (!baseBuilt) {
				int[][] body = argb(read("textures/arma/cycle_body.png"));
				int[][] wheel = argb(read("textures/arma/cycle_wheel.png"));
				bodyPx = body;
				wheelPx = wheel;
				bodyW = body[0].length;
				bodyH = body.length;
				wheelW = wheel[0].length;
				wheelH = wheel.length;

				register(RIM_WALL, argb(read("textures/arma/rim_wall.png")), AddressMode.REPEAT, AddressMode.REPEAT);

				int[][] fa = argb(read("textures/arma/floor_a.png"));   // 1 x 256
				int[][] fb = argb(read("textures/arma/floor_b.png"));   // 256 x 1
				int size = 256;
				int[][] floor = new int[size][size];
				for (int y = 0; y < size; y++) {
					int la = fa[y % fa.length][0] & 255;
					for (int x = 0; x < size; x++) {
						int lb = fb[0][x % fb[0].length] & 255;
						int l = Math.min(255, la + lb);
						floor[y][x] = 0xFF000000 | (l << 16) | (l << 8) | l;
					}
				}
				register(FLOOR_GRID, floor, AddressMode.REPEAT, AddressMode.REPEAT);
				int[][] white = new int[4][4];
				for (int[] row : white) {
					java.util.Arrays.fill(row, 0xFFFFFFFF);
				}
				register(WHITE, white, AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE);
			}
			int[][] wall = argb(read("textures/arma/dir_wall.png"));
			for (int[] row : wall) {
				for (int x = 0; x < row.length; x++) {
					int l = row[x] & 255;
					double t = Math.max(0, Math.min(1, (l - 102) / 153.0));
					int a = (int) Math.round(255 * (wallBodyAlpha + (1 - wallBodyAlpha) * t));
					// keep dir_wall's stripe/body contrast pattern, rescaled so the body sits at wallBodyBrightness
					int lv = (int) Math.round(255 * (wallBodyBrightness + (1 - wallBodyBrightness) * t));
					row[x] = (a << 24) | (lv << 16) | (lv << 8) | lv;
				}
			}
			register(DIR_WALL, wall, AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE);
			builtWallAlpha = wallBodyAlpha;
			builtWallBright = wallBodyBrightness;
			baseBuilt = true;
		} catch (IOException | RuntimeException e) {
			LOG.error("could not build Armagetron textures", e);
		}
	}

	private static int[][] tint(int[][] src, float r, float g, float b) {
		int R = (int) (r * 255), G = (int) (g * 255), B = (int) (b * 255);
		R = Math.max(0, Math.min(255, R));
		G = Math.max(0, Math.min(255, G));
		B = Math.max(0, Math.min(255, B));
		int h = src.length, w = src[0].length;
		int[][] out = new int[h][w];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int p = src[y][x];
				int a = p >>> 24;
				int pr = (a * ((p >> 16) & 255) + (255 - a) * R) >> 8;
				int pg = (a * ((p >> 8) & 255) + (255 - a) * G) >> 8;
				int pb = (a * (p & 255) + (255 - a) * B) >> 8;
				out[y][x] = 0xFF000000 | (pr << 16) | (pg << 8) | pb;
			}
		}
		return out;
	}

	/** {body, wheel} texture ids for a cycle colour, created on first use. Render thread only. */
	public static synchronized Identifier[] cycle(float r, float g, float b) {
		int key = ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
		Identifier[] ids = CYCLE.get(key);
		if (ids != null) {
			return ids;
		}
		if (bodyPx == null) {
			// ride mode may draw bikes without the arena renderer having built the shared textures
			try {
				bodyPx = argb(read("textures/arma/cycle_body.png"));
				wheelPx = argb(read("textures/arma/cycle_wheel.png"));
			} catch (IOException | RuntimeException e) {
				LOG.error("could not load cycle textures", e);
				return null;
			}
		}
		String hex = String.format("%06x", key & 0xFFFFFF);
		ids = new Identifier[] {id("dyn/cycle_body_" + hex), id("dyn/cycle_wheel_" + hex)};
		register(ids[0], tint(bodyPx, r, g, b), AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE);
		register(ids[1], tint(wheelPx, r, g, b), AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE);
		CYCLE.put(key, ids);
		return ids;
	}

	/** Forget everything (resource reload drops registered textures). */
	public static synchronized void invalidate() {
		CYCLE.clear();
		baseBuilt = false;
		builtWallAlpha = -1;
		builtWallBright = -1;
	}
}

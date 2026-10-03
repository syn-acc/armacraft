package gl.syn.armacraft.render;

import gl.syn.armacraft.logic.WallLayout;
import com.mojang.math.Transformation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Desired state for one display entity. Specs are records so an unchanged spec compares equal and costs
 * nothing; {@link #update} only touches entity data that actually differs, which keeps packets minimal.
 */
public sealed interface Specs {

	double x();

	double y();

	double z();

	Display create(ServerLevel level);

	void update(Display e, Specs previous);

	/** A lightcycle: coloured block, ~0.8 wide x 1 tall x 2 long, oriented by entity yaw. */
	record CycleSpec(double x, double y, double z, float yaw, BlockState block, int glowRgb, int interp, float viewRange) implements Specs {
		static final float WIDTH = 0.8f;
		static final float HEIGHT = 1.0f;
		static final float LENGTH = 2.0f;
		/** Body extends this far behind the reported position (the wall attaches at the position). */
		static final float BACK = 1.5f;

		static Transformation transformation() {
			return new Transformation(new Vector3f(-WIDTH / 2f, 0f, -BACK), null, new Vector3f(WIDTH, HEIGHT, LENGTH), null);
		}

		@Override
		public Display create(ServerLevel level) {
			Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
			d.setBlockState(block);
			d.setTransformation(transformation());
			common(d, viewRange);
			d.setPosRotInterpolationDuration(interp);
			glow(d, glowRgb);
			d.snapTo(x, y, z, yaw, 0f);
			return d;
		}

		@Override
		public void update(Display e, Specs previous) {
			CycleSpec p = (CycleSpec) previous;
			if (e instanceof Display.BlockDisplay bd && !p.block.equals(block)) {
				bd.setBlockState(block);
			}
			if (p.glowRgb != glowRgb) {
				glow(e, glowRgb);
			}
			if (p.interp != interp) {
				e.setPosRotInterpolationDuration(interp);
			}
			if (p.x != x || p.y != y || p.z != z || p.yaw != yaw) {
				e.snapTo(x, y, z, yaw, 0f);
			}
		}
	}

	/** One straight piece of light wall (player wall or static rim). Rotation lives in the transformation for float precision. */
	record WallSpec(double x, double y, double z, WallLayout.Transform t, BlockState block, int glowRgb, int interp, float viewRange) implements Specs {

		Transformation transformation() {
			return new Transformation(
				new Vector3f(t.tx(), t.ty(), t.tz()),
				new Quaternionf().rotationY(t.angle()),
				new Vector3f(t.sx(), t.sy(), t.sz()),
				null
			);
		}

		@Override
		public Display create(ServerLevel level) {
			Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
			d.setBlockState(block);
			d.setTransformation(transformation());
			common(d, viewRange);
			glow(d, glowRgb);
			d.snapTo(x, y, z, 0f, 0f);
			return d;
		}

		@Override
		public void update(Display e, Specs previous) {
			WallSpec p = (WallSpec) previous;
			if (e instanceof Display.BlockDisplay bd && !p.block.equals(block)) {
				bd.setBlockState(block);
			}
			if (p.glowRgb != glowRgb) {
				glow(e, glowRgb);
			}
			if (!p.t.equals(t)) {
				e.setTransformation(transformation());
				e.setTransformationInterpolationDuration(interp);
				// forced set: restarts client-side interpolation from the current visual state
				e.setTransformationInterpolationDelay(0);
			}
			if (p.x != x || p.y != y || p.z != z) {
				e.snapTo(x, y, z, 0f, 0f);
			}
		}
	}

	/** Floating name above a cycle. */
	record NameSpec(double x, double y, double z, String text, int rgb, int interp, float viewRange) implements Specs {
		@Override
		public Display create(ServerLevel level) {
			Display.TextDisplay d = new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level);
			d.setText(Component.literal(text).withColor(rgb));
			d.setBillboardConstraints(Display.BillboardConstraints.CENTER);
			d.setBackgroundColor(0x40000000);
			common(d, viewRange);
			d.setPosRotInterpolationDuration(interp);
			d.snapTo(x, y, z, 0f, 0f);
			return d;
		}

		@Override
		public void update(Display e, Specs previous) {
			NameSpec p = (NameSpec) previous;
			if ((!p.text.equals(text) || p.rgb != rgb) && e instanceof Display.TextDisplay td) {
				td.setText(Component.literal(text).withColor(rgb));
			}
			if (p.x != x || p.y != y || p.z != z) {
				e.snapTo(x, y, z, 0f, 0f);
			}
		}
	}

	/** Invisible camera anchor (block display of air) used as a spectator camera entity. */
	record CamSpec(double x, double y, double z, float yaw, float pitch, int interp) implements Specs {
		@Override
		public Display create(ServerLevel level) {
			Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
			d.setBlockState(Blocks.AIR.defaultBlockState());
			d.setPosRotInterpolationDuration(interp);
			d.setViewRange(0.5f);
			d.snapTo(x, y, z, yaw, pitch);
			return d;
		}

		@Override
		public void update(Display e, Specs previous) {
			CamSpec p = (CamSpec) previous;
			if (p.interp != interp) {
				e.setPosRotInterpolationDuration(interp);
			}
			if (p.x != x || p.y != y || p.z != z || p.yaw != yaw || p.pitch != pitch) {
				e.snapTo(x, y, z, yaw, pitch);
			}
		}
	}

	private static void common(Display d, float viewRange) {
		d.setBrightnessOverride(Brightness.FULL_BRIGHT);
		d.setViewRange(viewRange);
		d.setShadowRadius(0f);
		d.setNoGravity(true);
		d.setSilent(true);
	}

	private static void glow(Display d, int rgb) {
		if (rgb >= 0) {
			d.setGlowingTag(true);
			d.setGlowColorOverride(rgb);
		} else {
			d.setGlowingTag(false);
			d.setGlowColorOverride(-1);
		}
	}
}

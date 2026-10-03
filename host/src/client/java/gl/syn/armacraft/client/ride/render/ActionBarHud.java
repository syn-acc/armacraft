package gl.syn.armacraft.client.ride.render;

import gl.syn.armacraft.client.ride.api.RideHud;
import gl.syn.armacraft.ride.core.Heading;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Placeholder HUD on the action bar: {@code 18.0 b/s  RUBBER ▮▮▮▮▮▮▮▯▯▯  BRAKE ▮▮▮▮▮  BOOST ▮▮▮▮▮  N}. */
public final class ActionBarHud implements RideHud {

	@Override
	public void update(State s) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui == null) {
			return;
		}
		MutableComponent c = Component.literal(String.format("%5.1f b/s", s.speed()))
			.withStyle(s.speed() > s.baseSpeed() + 0.5 ? ChatFormatting.AQUA : ChatFormatting.WHITE);
		c.append(Component.literal("   RUBBER ").withStyle(ChatFormatting.GRAY));
		ChatFormatting rc = s.rubberLeft() > 0.5 ? ChatFormatting.GREEN : s.rubberLeft() > 0.2 ? ChatFormatting.YELLOW : ChatFormatting.RED;
		c.append(Component.literal(bar(s.rubberLeft(), 10)).withStyle(rc));
		c.append(Component.literal("   BRAKE ").withStyle(ChatFormatting.GRAY));
		c.append(Component.literal(bar(s.brake(), 5)).withStyle(s.braking() ? ChatFormatting.GOLD : ChatFormatting.DARK_AQUA));
		c.append(Component.literal("   BOOST ").withStyle(ChatFormatting.GRAY));
		c.append(Component.literal(bar(s.boost(), 5)).withStyle(s.boosting() ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.DARK_PURPLE));
		c.append(Component.literal("   " + Heading.name(s.heading()).substring(0, 1).toUpperCase()).withStyle(ChatFormatting.GRAY));
		if (s.warning()) {
			c.append(Component.literal("   WALL").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		}
		mc.gui.hud.setOverlayMessage(c, false);
	}

	@Override
	public void dead(String cause, double redeployIn) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui == null) {
			return;
		}
		MutableComponent c = Component.literal(cause.isEmpty() ? "DEREZZED" : cause.toUpperCase(java.util.Locale.ROOT)).withStyle(ChatFormatting.RED,
			ChatFormatting.BOLD);
		c.append(Component.literal("   turn to ride again" + (Double.isNaN(redeployIn) ? "" : String.format(" (auto in %.1f s)", redeployIn)))
			.withStyle(ChatFormatting.GRAY));
		mc.gui.hud.setOverlayMessage(c, false);
	}

	@Override
	public void clear() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui != null) {
			mc.gui.hud.setOverlayMessage(Component.empty(), false);
		}
	}

	static String bar(double v, int n) {
		int full = (int) Math.round(Math.max(0, Math.min(1, v)) * n);
		return "▮".repeat(full) + "▯".repeat(n - full);
	}
}

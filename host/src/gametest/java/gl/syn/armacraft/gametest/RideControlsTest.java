package gl.syn.armacraft.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import gl.syn.armacraft.Armacraft;
import gl.syn.armacraft.client.ride.RideClientMod;
import gl.syn.armacraft.client.ride.RideKeys;
import gl.syn.armacraft.gametest.mixin.GameRendererInvoker;
import gl.syn.armacraft.ride.core.CycleMovement;
import gl.syn.armacraft.ride.core.Heading;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.friends.FriendsOverlayScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Cycle mode as an interface swap, with real key presses (raw key events). The user's Armagetron binds:
 * turn left D/E/F/S/W, right I/J/K/L/O, glance left A/Q, right P/;, back V, Space brake toggle, Down brake hold,
 * Left Shift boost, Backspace exit. Every one of them is pressed in cycle mode and must open no screen and reach no
 * vanilla key mapping (O is vanilla's Friends List key, P social interactions, E inventory, Q drop, F hand swap, W
 * walk, S/A/D walk, Space jump, Left Shift sneak). Esc and Enter still reach the pause menu and chat. After a crash
 * the mode stays: death cam, a turn key redeploys facing the camera, or the auto delay does. Backspace restores
 * vanilla, and held keys do not leak across the swap in either direction.
 */
final class RideControlsTest {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft/ride-controls");
	private static final int SECOND = 20;
	private static final int Y = -60;
	private static final String[] USER_KEYS = {"d", "e", "f", "s", "w", "i", "j", "k", "l", "o", "a", "q", "p", "semicolon", "v", "space", "down",
		"left.shift"};

	private final ClientGameTestContext ctx;
	private final List<String> results = new ArrayList<>();
	private TestSingleplayerContext sp;

	RideControlsTest(ClientGameTestContext ctx) {
		this.ctx = ctx;
	}

	void run() {
		try (TestSingleplayerContext s = ctx.worldBuilder().adjustSettings(c -> c.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL)).create()) {
			this.sp = s;
			sp.getConnection().waitForChunksRender();
			// this test is about keys: trails off, so its loops cannot crash it
			sp.getServer().runOnServer(srv -> Armacraft.ride().config().lethalTrails = false);
			sp.getServer().runOnServer(srv -> {
				var p = srv.getPlayerList().getPlayers().get(0);
				p.getInventory().clearContent();
				p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 5));
				p.teleportTo(srv.overworld(), 0.5, Y, 0.5, Set.of(), -90f, 10f, true);
			});
			ctx.waitTicks(10);
			boolean offlineDev = ctx.computeOnClient(mc -> mc.isOfflineDeveloperMode());
			boolean friendsOn = ctx.computeOnClient(mc -> mc.friendsEnabled());
			LOG.info("client: offline developer mode {}, friends enabled {}", offlineDev, friendsOn);

			// control: outside cycle mode, O reaches vanilla's Friends List key
			press("o");
			ctx.waitTicks(2);
			boolean friendsVanilla = ctx.computeOnClient(mc -> friendsScreen(mc.gui.screen()) || mc.options.keyFriends.consumeClick());
			ctx.runOnClient(mc -> {
				if (mc.gui.screen() != null) {
					mc.gui.setScreen(null);
				}
			});
			check(friendsVanilla, "control: outside cycle mode O reaches vanilla's Friends List key (key.friends, default scancode 18 = O)");

			// enter with W held (walking): nothing leaks into cycle mode
			ctx.getInput().holdKey(key("w"));
			ctx.waitTicks(3);
			check(ctx.computeOnClient(mc -> mc.options.keyUp.isDown()), "control: W walks outside cycle mode");
			ctx.getInput().pressKey(RideKeys.RIDE);
			ctx.waitFor(mc -> RideClientMod.client().riding(), 5 * SECOND);
			check(ctx.computeOnClient(mc -> RideClientMod.client().cycleMode() && !mc.options.keyUp.isDown()),
				"R entered cycle mode; the held W was released (no walking leaks in)");
			ctx.getInput().releaseKey(key("w"));
			ctx.waitTicks(4);

			// R toggles back out on one press (no exit-and-re-enter), and back in
			ctx.getInput().pressKey(RideKeys.RIDE);
			ctx.waitFor(mc -> !RideClientMod.client().cycleMode(), 3 * SECOND);
			ctx.waitTicks(10);
			boolean stayedOut = ctx.computeOnClient(mc -> !RideClientMod.client().cycleMode() && !RideClientMod.client().riding()
				&& !RideKeys.RIDE.consumeClick() && !RideKeys.RIDE.isDown());
			check(stayedOut, "R in cycle mode leaves it; one press does not re-enter, and the press reached no vanilla key mapping");
			sp.getServer().waitFor(srv -> Armacraft.ride().riders().isEmpty(), 3 * SECOND);
			check(ctx.computeOnClient(mc -> mc.gui.screen() == null), "R exit opened no screen");
			ctx.getInput().pressKey(RideKeys.RIDE);
			ctx.waitFor(mc -> RideClientMod.client().riding(), 5 * SECOND);
			ctx.waitTicks(10);
			check(ctx.computeOnClient(mc -> RideClientMod.client().cycleMode() && RideClientMod.client().riding()), "R enters cycle mode again (and stays in)");

			// every user key: no screen, no vanilla key mapping, no inventory change
			int turns0 = turns();
			int vanillaClicks = 0;
			List<String> leaked = new ArrayList<>();
			for (String k : USER_KEYS) {
				press(k);
				ctx.waitTicks(3);
				String screen = ctx.computeOnClient(mc -> mc.gui.screen() == null ? null : mc.gui.screen().getClass().getSimpleName());
				List<String> hit = ctx.computeOnClient(mc -> {
					List<String> h = new ArrayList<>();
					for (KeyMapping m : mc.options.keyMappings) {
						if (m != RideKeys.RIDE && (m.isDown() || m.consumeClick())) {
							h.add(m.getName());
						}
					}
					return h;
				});
				if (screen != null) {
					leaked.add(k + " opened " + screen);
					ctx.runOnClient(mc -> mc.gui.setScreen(null));
				}
				if (!hit.isEmpty()) {
					leaked.add(k + " reached " + hit);
					vanillaClicks++;
				}
			}
			if (brakingNow()) {
				press("space"); // the loop pressed Space once: toggle the brake back off
				ctx.waitTicks(2);
			}
			int turned = turns() - turns0;
			check(leaked.isEmpty(), "all " + USER_KEYS.length + " user keys in cycle mode: no screen, no vanilla key mapping " + leaked);
			check(turned == 10, "the 10 turn keys turned 10 times (got " + turned + ")");
			ItemStack main = ctx.computeOnClient(mc -> mc.player.getMainHandItem().copy());
			int slot = ctx.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
			check(main.is(Items.STONE) && main.getCount() == 5 && slot == 0, "Q dropped nothing, F swapped nothing, hotbar untouched (" + main + ", slot " + slot + ")");
			press("1");
			press("5");
			ctx.waitTicks(2);
			check(ctx.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot()) == 0, "hotbar keys 1-9 swallowed");
			int friends = RideClientMod.friendsBlocked;
			LOG.info("swallowed unbound keys {}, Friends toggles refused {}", ctx.computeOnClient(mc -> RideClientMod.client().input().swallowed), friends);

			// brake toggle (Space) and hold (Down), boost (Left Shift), glances
			press("space");
			ctx.waitTicks(2);
			check(brakingNow(), "Space toggled the brake on (CYCLE_BRAKE_TOGGLE)");
			press("space");
			ctx.waitTicks(2);
			check(!brakingNow(), "Space toggled it off");
			ctx.getInput().holdKey(key("down"));
			ctx.waitTicks(2);
			boolean downHeld = brakingNow();
			ctx.getInput().releaseKey(key("down"));
			ctx.waitTicks(2);
			check(downHeld && !brakingNow(), "Down brakes while held (CYCLE_BRAKE)");
			double v0 = speed();
			ctx.getInput().holdKey(key("left.shift"));
			ctx.waitTicks(10);
			double vBoost = speed();
			ctx.getInput().releaseKey(key("left.shift"));
			check(vBoost > v0 + 2, "Left Shift boosts (" + fmt(v0) + " -> " + fmt(vBoost) + " b/s)");
			ctx.waitTicks(20);
			float yaw0 = camYaw();
			float[] g = new float[3];
			String[] glance = {"v", "a", "semicolon"};
			for (int i = 0; i < 3; i++) {
				ctx.getInput().holdKey(key(glance[i]));
				ctx.waitTicks(6);
				g[i] = Mth.wrapDegrees(camYaw() - yaw0);
				ctx.getInput().releaseKey(key(glance[i]));
				ctx.waitTicks(8);
			}
			check(Math.abs(Math.abs(g[0]) - 180) < 2, "V glances back (" + g[0] + " deg)");
			check(g[1] < -45 && g[2] > 45, "A glances left (" + g[1] + "), ; glances right (" + g[2] + ")");
			long hidden = RideClientMod.hudHidden;
			check(hidden > 0, "vanilla hotbar/crosshair/bars hidden in cycle mode (" + hidden + " draws skipped)");
			check(RideClientMod.handHidden > 0, "first-person hand hidden in cycle mode (" + RideClientMod.handHidden + " frames)");
			ctx.runOnClient(mc -> mc.player.setXRot(70f)); // look at the ground: a block is targeted
			ctx.waitTicks(3);
			String outlineIn = ctx.computeOnClient(mc -> (mc.hitResult == null ? "none" : mc.hitResult.getType().name()) + " outline "
				+ ((GameRendererInvoker) mc.gameRenderer).armacraft$shouldRenderBlockOutline());
			check(outlineIn.equals("BLOCK outline false") && RideClientMod.outlineHidden > 0,
				"no block hit outline in cycle mode (" + outlineIn + ", " + RideClientMod.outlineHidden + " frames suppressed)");
			ctx.runOnClient(mc -> mc.player.setXRot(10f));
			ctx.takeScreenshot("cycle-mode-riding");

			// /arma preset: live, no redeploy
			double rubber0 = ctx.computeOnClient(mc -> RideClientMod.client().movement().params().rubber);
			sp.getServer().runCommand("arma preset armagetron");
			ctx.waitFor(mc -> RideClientMod.client().movement().params().rubber < rubber0 - 1, 2 * SECOND);
			double rubber1 = ctx.computeOnClient(mc -> RideClientMod.client().movement().params().rubber);
			sp.getServer().runCommand("arma preset rcl");
			ctx.waitFor(mc -> Math.abs(RideClientMod.client().movement().params().rubber - rubber0) < 1e-9, 2 * SECOND);
			check(ctx.computeOnClient(mc -> RideClientMod.client().riding()) && Math.abs(rubber1 - 0.6) < 1e-9,
				"/arma preset switched live while riding (CYCLE_RUBBER " + fmt(rubber0) + " -> " + fmt(rubber1) + " -> back, blocks)");

			// Esc: pause menu, cycle mode stays; Enter: chat
			press("escape");
			ctx.waitTicks(2);
			check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof PauseScreen && RideClientMod.client().cycleMode()), "Esc opens the pause menu, cycle mode stays");
			// vanilla allows the Friends List from the pause menu; cycle mode refuses it there too
			int refused = RideClientMod.friendsBlocked;
			press("o");
			ctx.waitTicks(2);
			check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof PauseScreen) && RideClientMod.friendsBlocked == refused + 1,
				"O in the pause menu: Friends List refused (toggleFriendsScreen guard), pause menu still open");
			press("escape");
			ctx.waitTicks(2);
			press("enter");
			ctx.waitTicks(2);
			check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof ChatScreen), "Enter opens chat (Armagetron CHAT)");
			press("escape");
			ctx.waitTicks(2);
			check(ctx.computeOnClient(mc -> mc.gui.screen() == null && RideClientMod.client().cycleMode() && RideClientMod.client().riding()),
				"back from chat: still riding in cycle mode");

			// crash: death cam, early turn key ignored, later turn key redeploys facing the camera
			wallAhead();
			ctx.waitFor(mc -> !RideClientMod.client().riding(), 6 * SECOND);
			press("j");
			ctx.waitTicks(2);
			check(!ctx.computeOnClient(mc -> RideClientMod.client().riding()), "a turn key within 0.6 s does not redeploy (no instant re-crash)");
			check(ctx.computeOnClient(mc -> RideClientMod.client().cycleMode()), "crashed: still in cycle mode (death cam)");
			double camDist = ctx.computeOnClient(mc -> mc.gameRenderer.mainCamera().position().distanceTo(mc.player.position()));
			check(camDist > 3, "death cam pulled back from the crash (" + fmt(camDist) + " blocks)");
			ctx.takeScreenshot("cycle-mode-death-cam");
			int back = ctx.computeOnClient(mc -> Heading.turn(Heading.turn(RideClientMod.client().movement().heading(), 1), 1));
			ctx.runOnClient(mc -> mc.player.setYRot(Heading.yaw(back)));
			ctx.waitTicks(14);
			press("j");
			ctx.waitFor(mc -> RideClientMod.client().riding(), 5 * SECOND);
			int h = heading();
			check(h == back, "turn key redeployed facing the camera (" + Heading.name(h) + ", wanted " + Heading.name(back) + ")");

			// auto redeploy, turned to the freest side (not back into the wall)
			wallAhead();
			ctx.waitFor(mc -> !RideClientMod.client().riding(), 6 * SECOND);
			int crashedHeading = heading();
			long t0 = ctx.computeOnClient(mc -> mc.level.getGameTime());
			ctx.waitFor(mc -> RideClientMod.client().riding(), 8 * SECOND);
			double after = (ctx.computeOnClient(mc -> mc.level.getGameTime()) - t0) / 20.0;
			int newHeading = heading();
			check(after > 2.7 && after < 3.6, "auto redeploy after the configured 3 s (" + fmt(after) + " s of game time)");
			check(newHeading != crashedHeading, "redeployed turned away from the wall (" + Heading.name(crashedHeading) + " -> " + Heading.name(newHeading) + ")");
			ctx.waitTicks(20);
			check(ctx.computeOnClient(mc -> RideClientMod.client().riding()), "still riding a second after the auto redeploy");

			// exit with W held (a turn key): nothing leaks out
			ctx.getInput().holdKey(key("w"));
			ctx.waitTicks(2);
			press("backspace");
			ctx.waitFor(mc -> !RideClientMod.client().cycleMode(), 3 * SECOND);
			ctx.waitTicks(4);
			boolean wDown = ctx.computeOnClient(mc -> mc.options.keyUp.isDown());
			double hv = ctx.computeOnClient(mc -> mc.player.getDeltaMovement().horizontalDistance());
			ctx.getInput().releaseKey(key("w"));
			check(!wDown && hv < 0.05, "Backspace left cycle mode; the W held as a turn key does not walk (keyUp down " + wDown + ", speed " + fmt(hv) + ")");
			sp.getServer().waitFor(srv -> Armacraft.ride().riders().isEmpty(), 3 * SECOND);
			check(true, "server ride ended");

			// vanilla is back exactly
			long hiddenNow = RideClientMod.hudHidden;
			ctx.waitTicks(5);
			long handNow = RideClientMod.handHidden;
			ctx.waitTicks(2);
			check(RideClientMod.hudHidden == hiddenNow && RideClientMod.handHidden == handNow, "vanilla HUD and hand drawn again");
			ctx.runOnClient(mc -> mc.player.setXRot(70f));
			ctx.waitTicks(3);
			String outlineOut = ctx.computeOnClient(mc -> (mc.hitResult == null ? "none" : mc.hitResult.getType().name()) + " outline "
				+ ((GameRendererInvoker) mc.gameRenderer).armacraft$shouldRenderBlockOutline());
			check(outlineOut.equals("BLOCK outline true"), "block hit outline back after exit (" + outlineOut + ")");
			press("e");
			ctx.waitTicks(3);
			check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof InventoryScreen), "E opens the inventory again");
			ctx.runOnClient(mc -> mc.gui.setScreen(null));
			ctx.waitTicks(2);
			press("o");
			ctx.waitTicks(2);
			boolean friendsBack = ctx.computeOnClient(mc -> friendsScreen(mc.gui.screen()) || mc.options.keyFriends.consumeClick());
			ctx.runOnClient(mc -> {
				if (mc.gui.screen() != null) {
					mc.gui.setScreen(null);
				}
			});
			check(friendsBack, "O reaches vanilla's Friends List key again");
			ctx.takeScreenshot("cycle-mode-off");
			sp.getServer().runOnServer(srv -> Armacraft.ride().config().lethalTrails = true);
			// /arma preset saved the config while trails were off for this test: save it again as it should be
			sp.getServer().runCommand("arma preset rcl");
			LOG.info("controls results:{}  {}", System.lineSeparator(), String.join(System.lineSeparator() + "  ", results));
		}
	}

	/** The user's popup: FriendsListConfirmScreen ("Turn ON / Turn OFF") when the list is off, else FriendsOverlayScreen. */
	private static boolean friendsScreen(net.minecraft.client.gui.screens.Screen s) {
		if (s != null) {
			LOG.info("screen open: {} ({})", s.getClass().getSimpleName(), s.getTitle().getString());
		}
		return s instanceof FriendsOverlayScreen || s instanceof net.minecraft.client.gui.screens.friends.FriendsListConfirmScreen;
	}

	/** A 3-high stone wall across the lane 8 blocks ahead of the bike. */
	private void wallAhead() {
		double[] p = ctx.computeOnClient(mc -> new double[] {mc.player.getX(), mc.player.getY(), mc.player.getZ(), RideClientMod.client().movement().heading()});
		int h = (int) p[3];
		sp.getServer().runOnServer(srv -> {
			var l = srv.overworld();
			int cx = (int) Math.floor(p[0]) + Heading.dx(h) * 8;
			int cz = (int) Math.floor(p[2]) + Heading.dz(h) * 8;
			for (int s = -3; s <= 3; s++) {
				for (int y = 0; y < 3; y++) {
					int x = cx + (Heading.dx(h) == 0 ? s : 0);
					int z = cz + (Heading.dz(h) == 0 ? s : 0);
					l.setBlockAndUpdate(new BlockPos(x, (int) Math.floor(p[1]) + y, z), Blocks.STONE.defaultBlockState());
				}
			}
		});
	}

	private static InputConstants.Key key(String name) {
		return InputConstants.getKey("key.keyboard." + name);
	}

	private void press(String name) {
		ctx.getInput().pressKey(key(name));
	}

	private int turns() {
		return ctx.computeOnClient(mc -> RideClientMod.client().stats.turns);
	}

	private int heading() {
		return ctx.computeOnClient(mc -> {
			CycleMovement m = RideClientMod.client().movement();
			return m == null ? -1 : m.heading();
		});
	}

	private boolean brakingNow() {
		return ctx.computeOnClient(mc -> RideClientMod.client().movement().braking());
	}

	private double speed() {
		return ctx.computeOnClient(mc -> RideClientMod.client().movement().speed());
	}

	private float camYaw() {
		return ctx.computeOnClient(mc -> RideClientMod.visuals().camera().yaw());
	}

	private void check(boolean ok, String what) {
		results.add((ok ? "PASS " : "FAIL ") + what);
		LOG.info("{} {}", ok ? "PASS" : "FAIL", what);
		if (!ok) {
			ctx.takeScreenshot("ride-controls-FAIL");
			throw new AssertionError(what + System.lineSeparator() + String.join(System.lineSeparator(), results));
		}
	}

	private static String fmt(double v) {
		return String.format("%.2f", v);
	}
}

package gl.syn.armacraft.client.ride.mixin;

import gl.syn.armacraft.client.ride.RideClientMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The player is the bike: a riding player's body is not drawn on modded clients (the bike visual is). */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherRideMixin {

	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private <E extends Entity> void armacraft$hideRiders(E entity, Frustum culler, double camX, double camY, double camZ, float partialTicks,
		CallbackInfoReturnable<Boolean> cir) {
		if (!(entity instanceof Player)) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (entity == mc.player ? RideClientMod.client().cycleMode() : RideClientMod.remote().isRidingEntity(entity.getId())) {
			cir.setReturnValue(false);
		}
	}
}

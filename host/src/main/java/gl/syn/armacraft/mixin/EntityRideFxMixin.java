package gl.syn.armacraft.mixin;

import gl.syn.armacraft.ride.RideFallback;
import gl.syn.armacraft.ride.RideService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ride-mode stand-in displays are for clients without the mod. 26.3's {@code ChunkMap.TrackedEntity.updatePlayer}
 * only pairs an entity with a player when {@code entity.broadcastToPlayer(player)}; modded clients (they registered
 * the ride channels) draw their own bikes and trails, so they never receive these entities.
 */
@Mixin(Entity.class)
public abstract class EntityRideFxMixin {

	@Inject(method = "broadcastToPlayer", at = @At("HEAD"), cancellable = true)
	private void armacraft$hideRideFallback(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
		Entity self = (Entity) (Object) this;
		if (self instanceof Display && self.entityTags().contains(RideFallback.TAG) && RideService.hasClientMod(player)) {
			cir.setReturnValue(false);
		}
	}
}

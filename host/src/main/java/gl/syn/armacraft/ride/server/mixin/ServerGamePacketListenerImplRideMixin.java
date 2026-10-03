package gl.syn.armacraft.ride.server.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import gl.syn.armacraft.ride.server.RideServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Riders only ({@code RideRegistry.isRiding}): widen the three movement checks in 26.3's
 * {@code ServerGamePacketListenerImpl} to what a bike at the configured max speed needs. Everyone else gets the
 * vanilla values unchanged. Thresholds: {@link gl.syn.armacraft.ride.server.MovementAllowance}.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplRideMixin {

	@Shadow
	public ServerPlayer player;

	@Shadow
	private int receivedMovePacketCount;

	@Shadow
	private int knownMovePacketCount;

	/** "moved too quickly": {@code float metersPerTick = isFallFlying ? 300.0F : 100.0F;} (the 100 only). */
	@ModifyExpressionValue(method = "handlePlayerPositionChange", at = @At(value = "CONSTANT", args = "floatValue=100.0"))
	private float armacraftRide$tooQuickly(float original) {
		return RideServer.tooQuicklyMetersPerTick(this.player, original, this.receivedMovePacketCount - this.knownMovePacketCount);
	}

	/** "moved wrongly": {@code movedDist > 0.0625}. */
	@ModifyExpressionValue(method = "handlePlayerPositionChange", at = @At(value = "CONSTANT", args = "doubleValue=0.0625"))
	private double armacraftRide$movedWrongly(double original) {
		return RideServer.movedWronglyThresholdSqr(this.player, original);
	}

	/** Floating kick: {@code ++aboveGroundTickCount > getMaximumFlyingTicks(player)} (not the vehicle call). */
	@Inject(method = "getMaximumFlyingTicks", at = @At("RETURN"), cancellable = true)
	private void armacraftRide$floating(Entity entity, CallbackInfoReturnable<Integer> cir) {
		if (entity == this.player) {
			int vanilla = cir.getReturnValueI();
			int relaxed = RideServer.maxFlyingTicks(this.player, vanilla);
			if (relaxed != vanilla) {
				cir.setReturnValue(relaxed);
			}
		}
	}
}

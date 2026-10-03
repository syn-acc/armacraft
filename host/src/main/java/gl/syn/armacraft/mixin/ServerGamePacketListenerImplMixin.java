package gl.syn.armacraft.mixin;

import gl.syn.armacraft.ArmaController;
import gl.syn.armacraft.Armacraft;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Input hook. handlePlayerInput first re-schedules itself onto the server thread
 * (PacketUtils.ensureRunningOnSameThread throws on the netty thread), so TAIL only runs on the server thread,
 * once per packet. The vanilla client sends this packet only when its key state changes.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

	@Shadow
	public ServerPlayer player;

	@Inject(method = "handlePlayerInput", at = @At("TAIL"))
	private void armacraft$onPlayerInput(ServerboundPlayerInputPacket packet, CallbackInfo ci) {
		ArmaController c = Armacraft.controller();
		if (c != null) {
			c.onPlayerInput(this.player, packet.input());
		}
	}
}

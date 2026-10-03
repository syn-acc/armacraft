package gl.syn.armacraft.client.mixin;

import gl.syn.armacraft.client.ArenaClient;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The Minecraft player stands still while riding (the free spectator camera reads the keys itself). */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {

	@Inject(method = "tick", at = @At("TAIL"))
	private void armacraft$suppress(CallbackInfo ci) {
		ArenaClient client = ArenaClient.get();
		if (client != null && client.riding() && !gl.syn.armacraft.client.input.ArmaKeys.hostCycleMode()) {
			this.keyPresses = Input.EMPTY;
			this.moveVector = Vec2.ZERO;
		}
	}
}

package gl.syn.armacraft.client.mixin;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Dev only (armacraft.devWorld): press "Create New World" programmatically. */
@Mixin(CreateWorldScreen.class)
public interface CreateWorldScreenInvoker {
	@Invoker("onCreate")
	void armacraft$onCreate();
}

package net.eenda.rvladdons.mixins;

import net.eenda.rvladdons.client.party.LocalPartyController;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screen.class)
public abstract class ScreenPartyCloseMixin {
    @Inject(method = "keyPressed(III)Z", at = @At("HEAD"))
    private void rvladdons$partyEscape(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            LocalPartyController.INSTANCE.onPartyMenuEscape();
        }
    }
}

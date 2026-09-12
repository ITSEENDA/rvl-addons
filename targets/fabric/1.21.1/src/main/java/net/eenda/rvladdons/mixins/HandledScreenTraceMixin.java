package net.eenda.rvladdons.mixins;

import net.eenda.rvladdons.network.RvlAddonsTraceBridge;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HandledScreen.class)
public abstract class HandledScreenTraceMixin {
    @Inject(
        method = "onMouseClick(Lnet/minecraft/screen/slot/Slot;IILnet/minecraft/screen/slot/SlotActionType;)V",
        at = @At("HEAD")
    )
    private void rvladdons$traceSlotClick(Slot slot, int slotId, int button, SlotActionType actionType, CallbackInfo info) {
        String title = ((ScreenTraceAccessor) (Object) this).rvladdons$getTitle().getString();
        RvlAddonsTraceBridge.logSlotClick(title, slot, slotId, button, actionType);
    }
}

package net.eenda.rvladdons.mixins;

import net.eenda.rvladdons.client.party.LocalPartyController;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerPartyMixin {
    @Inject(method = "clickSlot", at = @At("HEAD"), cancellable = true)
    private void rvladdons$partyClick(
        int syncId,
        int slotId,
        int button,
        SlotActionType actionType,
        PlayerEntity player,
        CallbackInfo info
    ) {
        if (LocalPartyController.INSTANCE.onLocalSlotAction(syncId, slotId, button, actionType)) {
            info.cancel();
        }
    }
}

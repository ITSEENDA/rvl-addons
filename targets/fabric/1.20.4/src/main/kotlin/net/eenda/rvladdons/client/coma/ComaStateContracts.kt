package net.eenda.rvladdons.client.coma

import net.eenda.rvladdons.core.state.BaseState
import net.minecraft.item.ItemStack
import net.minecraft.screen.slot.SlotActionType

internal abstract class ComaState(
    protected val owner: ComaSwapController
) : BaseState() {
    internal open fun onInventorySync(sync: ComaServerSync) = Unit

    internal open fun onCaptureClick(
        title: String,
        slotId: Int,
        actionType: SlotActionType,
        stack: ItemStack?
    ) = Unit
}

internal abstract class ComaCaptureState(owner: ComaSwapController) : ComaState(owner)

internal abstract class ComaSwapState(owner: ComaSwapController) : ComaState(owner)

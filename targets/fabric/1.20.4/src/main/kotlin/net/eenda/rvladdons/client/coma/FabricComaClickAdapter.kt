package net.eenda.rvladdons.client.coma

import net.eenda.rvladdons.feature.coma.ComaClickAction
import net.minecraft.screen.slot.SlotActionType

internal fun ComaClickAction.toSlotActionType(): SlotActionType = when (this) {
    ComaClickAction.QUICK_MOVE -> SlotActionType.QUICK_MOVE
}

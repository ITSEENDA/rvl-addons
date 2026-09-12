package net.eenda.rvladdons.feature.coma

import java.util.ArrayDeque

enum class ComaClickAction {
    QUICK_MOVE
}

data class ComaClickStep(
    val slot: Int,
    val role: String,
    val action: ComaClickAction
)

data class ComaSwapPlan(
    val steps: ArrayDeque<ComaClickStep>,
    val missing: List<String>
)

object ComaSwapPlanner {
    private val comaSlots = ComaRole.ordered.map { it.menuSlot }

    fun build(
        clientSnapshot: ComaInventorySnapshot,
        set: ComaSetConfig,
        serverSnapshot: ComaInventorySnapshot? = null
    ): ComaSwapPlan {
        val clearSteps = ArrayDeque<ComaClickStep>()
        val moveSteps = ArrayDeque<ComaClickStep>()
        val missing = mutableListOf<String>()
        val usedSources = mutableSetOf<Int>()

        set.rules().forEachIndexed { index, rule ->
            if (rule.isBlank()) return@forEachIndexed
            val targetSlot = comaSlots[index]
            val targetItem = serverSnapshot?.slots?.getOrNull(targetSlot)
                ?: clientSnapshot.slots.getOrNull(targetSlot)
            if (targetSlot !in clientSnapshot.slots.indices) {
                missing += roleName(index)
                return@forEachIndexed
            }
            if (ComaItemRuleMatcher.matches(targetItem, rule)) {
                return@forEachIndexed
            }

            val sourceSlot = clientSnapshot.slots.indices.firstOrNull { slot ->
                val sourceItem = serverSnapshot?.slots?.getOrNull(slot)
                    ?: clientSnapshot.slots[slot]
                slot !in comaSlots && slot !in usedSources && ComaItemRuleMatcher.matches(sourceItem, rule)
            }
            if (sourceSlot == null) {
                missing += roleName(index)
                return@forEachIndexed
            }

            usedSources += sourceSlot
            if (serverSnapshot == null || ComaItemRuleMatcher.isComaEquipment(targetItem, index)) {
                clearSteps += ComaClickStep(targetSlot, "remove-${roleName(index)}", ComaClickAction.QUICK_MOVE)
            }
            moveSteps += ComaClickStep(sourceSlot, "move-${roleName(index)}", ComaClickAction.QUICK_MOVE)
        }

        val steps = ArrayDeque<ComaClickStep>()
        steps.addAll(clearSteps)
        steps.addAll(moveSteps)
        return ComaSwapPlan(steps, missing)
    }

    private fun roleName(index: Int): String = ComaRole.fromIndex(index)?.key ?: "unknown"
}

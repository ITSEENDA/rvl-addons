package net.eenda.rvladdons.client.cooldown

import net.eenda.rvladdons.feature.cooldown.PendingSkillAction
import net.eenda.rvladdons.feature.cooldown.SkillHand
import net.eenda.rvladdons.feature.cooldown.SkillTrigger
import net.minecraft.item.ItemStack

/** Owns pending input actions and their temporary icon snapshots. */
internal class CooldownPendingActionStore {
    val actions = mutableListOf<PendingSkillAction>()
    val icons = mutableMapOf<PendingSkillAction, ItemStack>()

    fun clear() {
        actions.clear()
        icons.clear()
    }

    fun prune(now: Long) {
        actions.removeIf { now - it.at > 2_000L }
    }

    fun remove(action: PendingSkillAction) {
        actions.remove(action)
        icons.remove(action)
    }

    fun removeIf(predicate: (PendingSkillAction) -> Boolean) {
        actions.filter(predicate).forEach(::remove)
    }

    fun queue(
        action: PendingSkillAction,
        icon: ItemStack?,
        sourceStack: (PendingSkillAction) -> ItemStack?
    ): List<PendingSkillAction> {
        val removed = mutableListOf<PendingSkillAction>()
        val sameGesture = actions.filter {
            action.at - it.at in 0..CooldownActionTracker.HAND_DUPLICATE_WINDOW_MS && it.trigger == action.trigger
        }
        if (action.hand == SkillHand.OFF && sameGesture.any { it.hand == SkillHand.MAIN && it.source?.skillKey != null }) {
            return removed
        }
        if (action.hand == SkillHand.MAIN && action.source?.skillKey != null) {
            sameGesture.filter { it.hand == SkillHand.OFF }.forEach { old ->
                remove(old)
                removed += old
            }
        }
        if (actions.none { it.at == action.at && it.trigger == action.trigger && it.source?.skillKey == action.source?.skillKey }) {
            actions += action
            if (action.source != null) {
                val stack = icon ?: sourceStack(action)
                if (stack != null && !stack.isEmpty) icons[action] = stack.copy()
            }
        }
        return removed
    }

    fun cancelGesture(trigger: SkillTrigger, now: Long): List<PendingSkillAction> {
        val removed = actions.filter {
            it.trigger == trigger && now - it.at in 0..CooldownActionTracker.HAND_DUPLICATE_WINDOW_MS
        }
        removed.forEach(::remove)
        return removed
    }

    fun select(now: Long, remainingMs: Long): PendingSkillAction? =
        actions.filter { now - it.at in 0..900L }
            .maxByOrNull { action ->
                var score = if (action.source?.skillKey != null) 100.0 else 0.0
                if (action.hand == SkillHand.MAIN) score += 20.0
                action.source?.cooldownHintMs?.let { hint ->
                    score -= minOf(80.0, kotlin.math.abs(hint - remainingMs).toDouble() / 1000.0)
                }
                score - (now - action.at) / 1000.0
            }
}

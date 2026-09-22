package net.eenda.rvladdons.client.cooldown

import net.eenda.rvladdons.core.RvlAddonsTrace
import net.eenda.rvladdons.feature.cooldown.CooldownConfidence
import net.eenda.rvladdons.feature.cooldown.CooldownEntry
import net.eenda.rvladdons.feature.cooldown.CooldownRegistry
import net.eenda.rvladdons.feature.cooldown.SkillProfileStore
import net.eenda.rvladdons.feature.cooldown.SkillSourceSnapshot
import net.eenda.rvladdons.feature.cooldown.SkillSourceType
import net.eenda.rvladdons.feature.cooldown.SkillTrigger
import net.minecraft.item.ItemStack
import kotlin.math.abs
import kotlin.math.roundToInt

/** Correlates server-wide CD messages with pending or existing cooldown entries. */
internal class CooldownServerCorrelation(
    private val registry: CooldownRegistry,
    private val pending: CooldownPendingActionStore,
    private val icons: MutableMap<String, ItemStack>,
    private val stackForSource: (SkillSourceSnapshot) -> ItemStack?,
    private val lastSkillKey: () -> String?,
    private val setLastSkillKey: (String?) -> Unit
) {
    private val cooldownPattern = Regex("(?i)\\bCD\\b\\D{0,40}(\\d+(?:[.,]\\d+)?)\\s*s?")
    private val cooldownClearPattern = Regex("(?i)^\\s*CD\\s*[:=]?\\s*(?:0+(?:[.,]0+)?\\s*s?|RESET|CLEAR|READY)\\b")

    fun observe(message: String, actionBar: Boolean) {
        if (!actionBar) return
        if (cooldownClearPattern.containsMatchIn(message)) {
            clearServerCooldown(message)
            return
        }
        val match = cooldownPattern.find(message) ?: return
        val remainingMs = ((match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return) * 1000.0)
            .roundToInt().toLong()
        if (remainingMs <= 0L) return

        val now = System.currentTimeMillis()
        pending.prune(now)
        val action = pending.select(now, remainingMs)
        val existing = action?.source?.skillKey?.let(registry::get)
            ?: if (action == null) selectExistingEntry(now, remainingMs) else null
        if (action?.source?.skillKey == null && existing == null) {
            RvlAddonsTrace.log("cooldown", "server-cd-ignored unmatched remainingMs=$remainingMs text=$message")
            return
        }

        val source = action?.source ?: existing?.source
        val trigger = action?.trigger ?: existing?.trigger ?: SkillTrigger.UNKNOWN
        if (isPassiveTrigger(trigger)) {
            action?.let(pending::remove)
            return
        }
        val key = action?.source?.skillKey ?: existing?.skillKey ?: return
        val label = action?.source?.displayName?.ifBlank { null }
            ?: existing?.displayName ?: "Server cooldown"
        val cooldownHint = action?.source?.cooldownHintMs
            ?: existing?.source?.cooldownHintMs
            ?: SkillProfileStore.find(key)?.cooldownHintMs
        if (cooldownHint != null && remainingMs > cooldownHint) {
            RvlAddonsTrace.log("cooldown", "server-cd-ignored-too-large key=$key remainingMs=$remainingMs hintMs=$cooldownHint text=$message")
            return
        }

        val profile = if (action?.source != null) {
            SkillProfileStore.register(
                skillKey = key,
                label = label,
                setId = source?.setId,
                trigger = trigger,
                sourceFingerprint = source?.fingerprint.orEmpty(),
                cooldownHintMs = source?.cooldownHintMs,
                sourceType = source?.sourceType ?: SkillSourceType.UNKNOWN,
                skillType = source?.skillType ?: net.eenda.rvladdons.feature.cooldown.SkillType.UNKNOWN,
                setBonusLevel = source?.setBonusLevel
            )
        } else SkillProfileStore.find(key)

        if (profile?.enabled == false) {
            action?.let(pending::remove)
            return
        }
        val effectiveHint = profile?.cooldownHintMs ?: source?.cooldownHintMs
        if (effectiveHint == null || effectiveHint <= 0L) {
            RvlAddonsTrace.log("cooldown", "server-cd-ignored-no-hint key=$key text=$message")
            action?.let(pending::remove)
            return
        }

        if (existing != null) {
            if (!icons.containsKey(key)) {
                val icon = action?.let { pending.icons[it] }
                    ?: source?.let { stackForSource(it) ?: net.eenda.rvladdons.client.coma.FabricComaItemAdapter.iconStack(it.itemId) }
                if (icon != null && !icon.isEmpty) icons[key] = icon.copy()
            }
            existing.endAt = now + remainingMs
            existing.lastServerRemainingMs = remainingMs
            existing.lastServerUpdateAt = now
            existing.confidence = CooldownConfidence.CONFIRMED
            registry.upsert(existing)
        } else {
            registry.upsert(
                CooldownEntry(
                    skillKey = key,
                    displayName = profile?.label ?: label,
                    trigger = trigger,
                    source = source,
                    startedAt = action?.at ?: now,
                    endAt = now + remainingMs,
                    lastServerRemainingMs = remainingMs,
                    lastServerUpdateAt = now,
                    confidence = if (action != null) CooldownConfidence.CONFIRMED else CooldownConfidence.ESTIMATED
                )
            )
        }

        action?.let { selected ->
            pending.icons.remove(selected)?.let { stack -> icons[key] = stack.copy() }
            pending.remove(selected)
        }
        setLastSkillKey(key)
        RvlAddonsTrace.log("cooldown", "server-cd remainingMs=$remainingMs key=$key matched=${action != null} confidence=${if (action != null) "CONFIRMED" else "ESTIMATED"} text=$message")
    }

    private fun clearServerCooldown(message: String) {
        val now = System.currentTimeMillis()
        pending.prune(now)
        if (Regex("(?i)^\\s*CD\\s*(?::|=)?\\s*(?:RESET|CLEAR)\\b").containsMatchIn(message)) {
            registry.clear()
            icons.clear()
            setLastSkillKey(null)
            pending.clear()
            RvlAddonsTrace.log("cooldown", "server-cd-clear all text=$message")
            return
        }
        val action = pending.select(now, 0L)
        if (action != null) {
            pending.remove(action)
            RvlAddonsTrace.log("cooldown", "server-cd-clear-ignored pending-action text=$message")
            return
        }
        val key = lastSkillKey()?.let(registry::get)?.skillKey
            ?: registry.active(now).singleOrNull()?.skillKey
        if (key == null) {
            RvlAddonsTrace.log("cooldown", "server-cd-clear-ignored unmatched text=$message")
        } else {
            registry.remove(key)
            icons.remove(key)
            RvlAddonsTrace.log("cooldown", "server-cd-clear key=$key text=$message")
        }
        if (key == lastSkillKey()) setLastSkillKey(null)
    }

    private fun selectExistingEntry(now: Long, remainingMs: Long): CooldownEntry? {
        val active = registry.active(now)
        val last = lastSkillKey()?.let(registry::get)?.takeIf { it in active }
        return active.minWithOrNull(
            compareBy<CooldownEntry> { abs(it.remainingMs(now) - remainingMs) }
                .thenByDescending { if (it.skillKey == last?.skillKey) 1 else 0 }
        )
    }

    private fun isPassiveTrigger(trigger: SkillTrigger): Boolean =
        trigger == SkillTrigger.PASSIVE_DAMAGED || trigger == SkillTrigger.PASSIVE
}

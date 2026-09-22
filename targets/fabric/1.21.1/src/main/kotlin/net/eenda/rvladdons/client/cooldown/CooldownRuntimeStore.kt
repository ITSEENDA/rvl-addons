package net.eenda.rvladdons.client.cooldown

import net.eenda.rvladdons.core.RvlAddonsTrace
import net.eenda.rvladdons.feature.cooldown.CooldownConfidence
import net.eenda.rvladdons.feature.cooldown.CooldownEntry
import net.eenda.rvladdons.feature.cooldown.CooldownRegistry
import net.eenda.rvladdons.feature.cooldown.PendingSkillAction
import net.eenda.rvladdons.feature.cooldown.SkillProfileStore
import net.eenda.rvladdons.feature.cooldown.SkillTrigger
import net.minecraft.item.ItemStack

/** Owns active cooldown entries, estimated starts and runtime icon snapshots. */
internal class CooldownRuntimeStore(private val pending: CooldownPendingActionStore) {
    internal val registry = CooldownRegistry()
    internal val icons = mutableMapOf<String, ItemStack>()
    internal var lastSkillKey: String? = null

    fun clear() {
        pending.clear()
        icons.clear()
        lastSkillKey = null
        registry.clear()
    }

    fun removeProfile(skillKey: String) {
        registry.remove(skillKey)
        icons.remove(skillKey)
        pending.removeIf { it.source?.skillKey == skillKey }
        if (lastSkillKey == skillKey) lastSkillKey = null
    }

    fun active(now: Long = System.currentTimeMillis()): List<CooldownEntry> {
        registry.active(now).filter {
            isPassive(it.trigger) || SkillProfileStore.find(it.skillKey)?.cooldownHintMs == null
        }.forEach { registry.remove(it.skillKey) }
        return registry.active(now)
    }

    fun hasActive(now: Long = System.currentTimeMillis()): Boolean = active(now).isNotEmpty()

    fun iconFor(skillKey: String): ItemStack? = icons[skillKey]?.copy()

    fun removeEstimated(skillKey: String) {
        val entry = registry.get(skillKey)
        if (entry?.confidence == CooldownConfidence.ESTIMATED) {
            registry.remove(skillKey)
            icons.remove(skillKey)
        }
    }

    fun startEstimated(action: PendingSkillAction) {
        if (isPassive(action.trigger)) return
        val source = action.source ?: return
        val key = source.skillKey ?: return
        val profile = SkillProfileStore.find(key) ?: SkillProfileStore.register(
            skillKey = key,
            label = source.displayName,
            setId = source.setId,
            trigger = action.trigger,
            sourceFingerprint = source.fingerprint,
            cooldownHintMs = source.cooldownHintMs,
            sourceType = source.sourceType,
            skillType = source.skillType,
            setBonusLevel = source.setBonusLevel
        )
        if (!profile.enabled) return
        val hint = profile.cooldownHintMs ?: source.cooldownHintMs ?: return
        pending.icons[action]?.let { icon -> if (!icon.isEmpty) icons.putIfAbsent(key, icon.copy()) }
        val existing = registry.get(key)
        if (existing != null && existing.endAt > action.at) {
            lastSkillKey = key
            return
        }
        registry.upsert(
            CooldownEntry(
                skillKey = key,
                displayName = profile.label,
                trigger = action.trigger,
                source = source,
                startedAt = action.at,
                endAt = action.at + hint,
                lastServerRemainingMs = hint,
                lastServerUpdateAt = action.at,
                confidence = CooldownConfidence.ESTIMATED
            )
        )
        lastSkillKey = key
        RvlAddonsTrace.log("cooldown", "estimated-start key=$key hintMs=$hint")
    }

    private fun isPassive(trigger: SkillTrigger): Boolean =
        trigger == SkillTrigger.PASSIVE_DAMAGED || trigger == SkillTrigger.PASSIVE
}

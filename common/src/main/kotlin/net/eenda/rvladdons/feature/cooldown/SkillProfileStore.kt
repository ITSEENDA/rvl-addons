package net.eenda.rvladdons.feature.cooldown

import net.eenda.rvladdons.core.RvlTextMatcher
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.Locale

data class SkillProfile(
    val skillKey: String,
    var label: String,
    val setId: String?,
    val trigger: SkillTrigger,
    val sourceFingerprint: String,
    var sourceType: SkillSourceType = SkillSourceType.ITEM,
    var enabled: Boolean = true,
    var cooldownHintMs: Long? = null,
    var skillType: SkillType = SkillType.UNKNOWN,
    var setBonusLevel: Int? = null
)

object SkillProfileStore {
    private val profiles = LinkedHashMap<String, SkillProfile>()
    private var path: Path? = null

    fun load(profilePath: Path) {
        path = profilePath
        profiles.clear()
        if (!Files.exists(profilePath)) return
        val properties = Properties()
        Files.newInputStream(profilePath).use(properties::load)
        val count = properties.getProperty("profile.count")?.toIntOrNull() ?: 0
        repeat(count) { index ->
            val key = properties.getProperty("profile.$index.key") ?: return@repeat
            val trigger = properties.getProperty("profile.$index.trigger")
                ?.let { runCatching { SkillTrigger.valueOf(it) }.getOrNull() }
                ?: SkillTrigger.UNKNOWN
            profiles[key] = SkillProfile(
                skillKey = key,
                label = properties.getProperty("profile.$index.label") ?: key,
                setId = properties.getProperty("profile.$index.setId")?.ifBlank { null },
                trigger = trigger,
                sourceFingerprint = properties.getProperty("profile.$index.fingerprint").orEmpty(),
                sourceType = properties.getProperty("profile.$index.sourceType")
                    ?.let { runCatching { SkillSourceType.valueOf(it) }.getOrNull() }
                    ?: SkillSourceType.ITEM,
                skillType = properties.getProperty("profile.$index.skillType")
                    ?.let { runCatching { SkillType.valueOf(it) }.getOrNull() }
                    ?: SkillType.UNKNOWN,
                setBonusLevel = properties.getProperty("profile.$index.setBonusLevel")?.toIntOrNull(),
                enabled = properties.getProperty("profile.$index.enabled")?.toBooleanStrictOrNull() ?: true,
                cooldownHintMs = properties.getProperty("profile.$index.cooldownMs")?.toLongOrNull()
            )
        }
        if (deduplicateInternal()) save()
    }

    fun register(descriptor: SkillDescriptor, sourceType: SkillSourceType = SkillSourceType.ITEM): SkillProfile = register(
        skillKey = descriptor.skillKey,
        label = descriptor.displayName,
        setId = descriptor.setId,
        trigger = descriptor.trigger,
        sourceFingerprint = descriptor.sourceFingerprint,
        cooldownHintMs = descriptor.cooldownHintMs,
        sourceType = sourceType,
        skillType = descriptor.skillType,
        setBonusLevel = descriptor.setBonusLevel
    )

    fun register(
        skillKey: String,
        label: String,
        setId: String?,
        trigger: SkillTrigger,
        sourceFingerprint: String,
        cooldownHintMs: Long? = null,
        sourceType: SkillSourceType = SkillSourceType.ITEM,
        skillType: SkillType = SkillType.UNKNOWN,
        setBonusLevel: Int? = null
    ): SkillProfile {
        val existing = profiles[skillKey]
        if (existing != null) {
            val changed = merge(existing, label, cooldownHintMs, sourceType, skillType, setBonusLevel)
            if (changed) save()
            return existing
        }

        // A changed item fingerprint must not create another visible profile for the same skill.
        val duplicate = profiles.values.firstOrNull {
            profileIdentity(it) == profileIdentity(
                label,
                setId,
                trigger,
                sourceFingerprint,
                sourceType,
                skillType,
                setBonusLevel
            )
        }
        if (duplicate != null) {
            val changed = merge(duplicate, label, cooldownHintMs, sourceType, skillType, setBonusLevel)
            if (changed) save()
            return duplicate
        }

        return SkillProfile(
            skillKey = skillKey,
            label = label,
            setId = setId,
            trigger = trigger,
            sourceFingerprint = sourceFingerprint,
            sourceType = sourceType,
            cooldownHintMs = cooldownHintMs,
            skillType = skillType,
            setBonusLevel = setBonusLevel
        ).also {
            profiles[skillKey] = it
            save()
        }
    }

    fun all(): List<SkillProfile> = profiles.values.toList()

    fun find(skillKey: String): SkillProfile? = profiles[skillKey]

    fun setLabel(skillKey: String, label: String) {
        profiles[skillKey]?.label = label.trim().ifBlank { profiles[skillKey]?.label ?: skillKey }
    }

    fun setEnabled(skillKey: String, enabled: Boolean) {
        profiles[skillKey]?.enabled = enabled
    }

    fun remove(skillKey: String): Boolean = profiles.remove(skillKey) != null

    fun removeAndSave(skillKey: String): Boolean {
        val removed = remove(skillKey)
        if (removed) save()
        return removed
    }

    fun removeSetProfilesExcept(setId: String, keepKeys: Set<String>) {
        val changed = profiles.entries.removeIf { (key, profile) ->
            profile.setId == setId && profile.sourceType != SkillSourceType.ITEM && key !in keepKeys
        }
        if (changed) save()
    }

    fun removeLegacyItemProfiles(fingerprints: Set<String>) {
        if (fingerprints.isEmpty()) return
        val changed = profiles.entries.removeIf { (key, profile) ->
            profile.sourceType == SkillSourceType.ITEM && profile.sourceFingerprint in fingerprints
        }
        if (changed) save()
    }

    /** Removes armor ITEM profiles created before armor was represented by a set profile. */
    fun removeLegacyArmorItemProfiles(itemNames: Set<String>) {
        val normalizedNames = itemNames.map(::normalize).filter(String::isNotBlank).toSet()
        if (normalizedNames.isEmpty()) return
        val changed = profiles.entries.removeIf { (_, profile) ->
            profile.sourceType == SkillSourceType.ITEM && normalizedNames.any { name ->
                val label = normalize(profile.label)
                label == name || label.startsWith("$name ")
            }
        }
        if (changed) save()
    }

    /** Removes stale equipped-set profiles when the same set is now recognized as a configured coma set. */
    fun removeLegacyEquippedSetProfiles(setIds: Set<String>) {
        val normalizedIds = setIds.map(::normalize).filter(String::isNotBlank).toSet()
        if (normalizedIds.isEmpty()) return
        val changed = profiles.entries.removeIf { (_, profile) ->
            profile.sourceType == SkillSourceType.EQUIPPED_SET &&
                normalize(profile.setId.orEmpty()) in normalizedIds
        }
        if (changed) save()
    }

    fun deduplicate() {
        if (deduplicateInternal()) save()
    }

    fun save() {
        val profilePath = path ?: return
        Files.createDirectories(profilePath.parent)
        val properties = Properties()
        properties["profile.count"] = profiles.size.toString()
        profiles.values.forEachIndexed { index, profile ->
            properties["profile.$index.key"] = profile.skillKey
            properties["profile.$index.label"] = profile.label
            properties["profile.$index.setId"] = profile.setId.orEmpty()
            properties["profile.$index.trigger"] = profile.trigger.name
            properties["profile.$index.fingerprint"] = profile.sourceFingerprint
            properties["profile.$index.sourceType"] = profile.sourceType.name
            properties["profile.$index.skillType"] = profile.skillType.name
            profile.setBonusLevel?.let { properties["profile.$index.setBonusLevel"] = it.toString() }
            properties["profile.$index.enabled"] = profile.enabled.toString()
            profile.cooldownHintMs?.let { properties["profile.$index.cooldownMs"] = it.toString() }
        }
        Files.newOutputStream(profilePath).use { properties.store(it, "RVL Addons skill profiles") }
    }

    private fun SkillSourceType.rank(): Int = when (this) {
        SkillSourceType.ITEM -> 1
        SkillSourceType.MAIN_HAND_WEAPON, SkillSourceType.OFF_HAND_WEAPON -> 2
        SkillSourceType.EQUIPPED_SET -> 3
        SkillSourceType.COMA_SET -> 4
        SkillSourceType.UNKNOWN -> 0
    }

    private fun merge(
        profile: SkillProfile,
        label: String?,
        cooldownHintMs: Long?,
        sourceType: SkillSourceType,
        skillType: SkillType,
        setBonusLevel: Int?
    ): Boolean {
        var changed = false
        val cleanedLabel = label?.let(SkillTextParser::cleanLegacyProfileLabel)
        if (profile.cooldownHintMs == null && cooldownHintMs != null) {
            profile.cooldownHintMs = cooldownHintMs
            changed = true
        }
        if (sourceType.rank() > profile.sourceType.rank()) {
            profile.sourceType = sourceType
            if (!cleanedLabel.isNullOrBlank()) profile.label = cleanedLabel
            changed = true
        }
        if (profile.skillType == SkillType.UNKNOWN && skillType != SkillType.UNKNOWN) {
            profile.skillType = skillType
            changed = true
        }
        if (profile.setBonusLevel == null && setBonusLevel != null) {
            profile.setBonusLevel = setBonusLevel
            changed = true
        }
        return changed
    }

    private fun deduplicateInternal(): Boolean {
        val survivors = LinkedHashMap<String, SkillProfile>()
        var changed = false
        profiles.values.toList().forEach { profile ->
            val cleanedLabel = SkillTextParser.cleanLegacyProfileLabel(profile.label)
            if (cleanedLabel != profile.label) {
                profile.label = cleanedLabel
                changed = true
            }
            val identity = profileIdentity(profile)
            val survivor = survivors[identity]
            if (survivor == null) {
                survivors[identity] = profile
                return@forEach
            }
            merge(
                survivor,
                profile.label,
                profile.cooldownHintMs,
                profile.sourceType,
                profile.skillType,
                profile.setBonusLevel
            )
            changed = true
        }
        if (changed) {
            profiles.clear()
            profiles.putAll(survivors)
        }
        return changed
    }

    private fun profileIdentity(profile: SkillProfile): String = profileIdentity(
        profile.label,
        profile.setId,
        profile.trigger,
        profile.sourceFingerprint,
        profile.sourceType,
        profile.skillType,
        profile.setBonusLevel
    )

    private fun profileIdentity(
        label: String,
        setId: String?,
        trigger: SkillTrigger,
        sourceFingerprint: String,
        sourceType: SkillSourceType,
        skillType: SkillType,
        setBonusLevel: Int?
    ): String {
        val isSet = sourceType == SkillSourceType.COMA_SET ||
            sourceType == SkillSourceType.EQUIPPED_SET ||
            sourceFingerprint.startsWith("set:")
        val owner = if (isSet) normalizeSetOwner(label.ifBlank { setId ?: sourceFingerprint })
        else normalizeItemOwner(label)
        return listOf(
            if (isSet) "SET" else "ITEM",
            owner,
            trigger.name,
            skillType.name,
            setBonusLevel?.toString().orEmpty()
        ).joinToString("|")
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun normalizeItemOwner(value: String): String = normalize(
        SkillTextParser.cleanLegacyProfileLabel(value).substringBefore(" - ")
    )

    private fun normalizeSetOwner(value: String): String = RvlTextMatcher.normalize(value)
        .replace(Regex("^CO MA\\s+"), "")
        .replace(Regex("[^A-Z0-9 ]"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() && it !in setOf("EX", "OVERLOAD") }
        .joinToString(" ")
}

package net.eenda.rvladdons.feature.cooldown

import net.eenda.rvladdons.core.RvlTextMatcher

object SkillTextParser {
    private val cooldownPattern = Regex("\\bCD\\b[^\\d]{0,40}(\\d+(?:[.,]\\d+)?)\\s*S?", RegexOption.IGNORE_CASE)
    private val bonusMarker = Regex("^\\[?\\s*(?:BO|KY NANG (?:MOC|KICH) BO)\\s+(\\d+)\\s*\\]?")
    private val itemTierPrefix = Regex("^(?:I|II|III|IV|V|VI|VII|VIII|IX|X)(?:\\s|$)", RegexOption.IGNORE_CASE)

    fun parse(
        itemId: String,
        itemDisplayName: String,
        itemFingerprint: String,
        setId: String?,
        loreLines: List<String>
    ): List<SkillDescriptor> {
        if (loreLines.isEmpty()) return emptyList()
        return splitSections(loreLines).mapNotNull { section ->
            val normalized = section.joinToString(" ") { RvlTextMatcher.normalize(it) }
            val trigger = detectTrigger(section)
            // A cooldown line without an activation condition is not a skill profile.
            if (trigger == SkillTrigger.UNKNOWN) return@mapNotNull null

            val cooldown = cooldownPattern.find(normalized)?.groupValues?.getOrNull(1)
                ?.replace(',', '.')?.toDoubleOrNull()?.let { (it * 1000.0).toLong() }
            val bonusLevel = section.asSequence()
                .map { bonusMarker.find(RvlTextMatcher.normalize(it))?.groupValues?.getOrNull(1)?.toIntOrNull() }
                .firstOrNull { it != null }
            val skillType = detectType(normalized, trigger)
            val sectionFingerprint = section.joinToString("|") { RvlTextMatcher.normalize(it) }.hashCode()
            SkillDescriptor(
                skillKey = listOf(
                    setId ?: "unknown",
                    itemFingerprint,
                    trigger.name,
                    skillType.name,
                    bonusLevel ?: "unknown",
                    sectionFingerprint
                ).joinToString(":"),
                displayName = sectionName(section, cleanItemDisplayName(itemDisplayName), trigger, bonusLevel),
                setId = setId,
                sourceFingerprint = itemFingerprint,
                trigger = trigger,
                cooldownHintMs = cooldown,
                rawLines = section,
                confidence = SkillConfidence.MEDIUM,
                skillType = skillType,
                setBonusLevel = bonusLevel
            )
        }
    }

    /** Removes the forge/prefix segment from names such as `Nhiem Hon - I ...`. */
    fun cleanItemDisplayName(value: String): String {
        val parts = value.trim().split(" - ", limit = 2)
        if (parts.size == 2 && itemTierPrefix.containsMatchIn(parts[1].trim())) {
            return parts[1].trim()
        }
        return value.trim()
    }

    /** Migrates labels written before forge prefixes were excluded from skill names. */
    fun cleanLegacyProfileLabel(value: String): String {
        val parts = value.trim().split(" - ")
        if (parts.size >= 3 && itemTierPrefix.containsMatchIn(parts[1].trim())) {
            return parts.drop(1).joinToString(" - ").trim()
        }
        return value.trim()
    }

    private fun splitSections(loreLines: List<String>): List<List<String>> {
        val sections = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var currentBonus: Int? = null

        fun flush() {
            if (current.isNotEmpty()) sections += current
            current = mutableListOf()
            currentBonus = null
        }

        loreLines.forEach { line ->
            val normalized = RvlTextMatcher.normalize(line)
            val bonus = bonusMarker.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (bonus != null) {
                if (currentBonus == null && current.isNotEmpty()) flush()
                else if (currentBonus != null && currentBonus != bonus) flush()
                currentBonus = bonus
                current += line
                return@forEach
            }

            // This is a set header, not a skill section by itself.
            if (normalized.startsWith("KICH HOAT BO") && !containsActivationMarker(normalized)) {
                flush()
                return@forEach
            }

            val startsSection = normalized.startsWith("KY NANG") || normalized.startsWith("COMBO")
            val startsActivation = currentBonus == null && !isComboDetailLine(normalized) &&
                containsActivationMarker(normalized) &&
                current.any { containsActivationMarker(RvlTextMatcher.normalize(it)) }
            if ((startsSection || startsActivation) && current.isNotEmpty()) flush()
            current += line
        }
        flush()
        return sections
    }

    private fun detectTrigger(section: List<String>): SkillTrigger {
        val text = section.joinToString(" ") { RvlTextMatcher.normalize(it) }
        return when {
        text.contains("DOUBLE SHIFT") || text.contains("2 LAN SHIFT") || text.contains("NHAN SHIFT 2 LAN") ->
            SkillTrigger.DOUBLE_SNEAK
        isExplicitJump(text) && (text.contains("CHUOT PHAI") || text.contains("RIGHT CLICK") || text.contains("RMB")) ->
            SkillTrigger.JUMP_RIGHT
        isExplicitJump(text) && (text.contains("CHUOT TRAI") || text.contains("LEFT CLICK") || text.contains("LMB")) ->
            SkillTrigger.JUMP_LEFT
        text.contains("SHIFT") && (text.contains("CHUOT PHAI") || text.contains("RIGHT CLICK") || text.contains("RMB")) ->
            SkillTrigger.SHIFT_RIGHT
        text.contains("SHIFT") && (text.contains("CHUOT TRAI") || text.contains("LEFT CLICK") || text.contains("LMB")) ->
            SkillTrigger.SHIFT_LEFT
        text.contains("CHUOT PHAI") || text.contains("RIGHT CLICK") || text.contains("RMB") -> SkillTrigger.RIGHT_CLICK
        text.contains("CHUOT TRAI") || text.contains("LEFT CLICK") || text.contains("LMB") -> SkillTrigger.LEFT_CLICK
        isExplicitJump(text) -> SkillTrigger.JUMP
        isExplicitSneakHold(text) -> SkillTrigger.SNEAK_HOLD
        text.contains("KHI BI TAN CONG") || text.contains("PASSIVE") -> SkillTrigger.PASSIVE_DAMAGED
        text.contains("COMBO") -> SkillTrigger.COMBO
        else -> SkillTrigger.UNKNOWN
        }
    }

    private fun isExplicitJump(text: String): Boolean =
        text.contains("JUMP") || text.contains("KHI NHAY") || text.contains("AN NHAY") ||
            text.contains("NHAN PHIM NHAY") || text.contains("NHAN NHAY") || text.contains("GIU NHAY")

    private fun isExplicitSneakHold(text: String): Boolean =
        text.contains("GIU SHIFT") || text.contains("GIU PHIM SHIFT") ||
            text.contains("GIU NUT SHIFT") || text.contains("HOLD SHIFT") ||
            text.contains("SNEAK HOLD") || text.contains("HOLD SNEAK")

    private fun isComboDetailLine(text: String): Boolean =
        text.startsWith("+ COMBO") || text.startsWith("COMBO") ||
            text.startsWith("- COMBO")

    private fun detectType(text: String, trigger: SkillTrigger): SkillType = when {
        trigger == SkillTrigger.PASSIVE_DAMAGED || trigger == SkillTrigger.PASSIVE -> SkillType.PASSIVE
        text.contains("COMBO") -> SkillType.COMBO
        trigger != SkillTrigger.UNKNOWN -> SkillType.ACTIVE
        else -> SkillType.SET_BONUS
    }

    private fun containsActivationMarker(text: String): Boolean =
        text.contains("CHUOT TRAI") || text.contains("CHUOT PHAI") ||
            text.contains("LEFT CLICK") || text.contains("RIGHT CLICK") ||
            text.contains("LMB") || text.contains("RMB") || text.contains("NHAY") ||
            text.contains("JUMP") || text.contains("SHIFT") || isExplicitSneakHold(text) ||
            text.contains("KHI BI TAN CONG") || text.contains("PASSIVE")

    private fun sectionName(section: List<String>, fallback: String, trigger: SkillTrigger, bonusLevel: Int?): String {
        val first = section.firstOrNull()?.trim().orEmpty()
        val name = first.substringBefore(':').substringBefore('-').trim()
        val normalizedName = RvlTextMatcher.normalize(name)
        val generic = setOf(
            "KY NANG", "KICH HOAT", "KICH HOAT KY NANG", "COMBO", "SKILL", "ABILITY", "DESCRIPTION", "MO TA"
        )
        val triggerLabel = when (trigger) {
            SkillTrigger.LEFT_CLICK -> "Left Click"
            SkillTrigger.RIGHT_CLICK -> "Right Click"
            SkillTrigger.SHIFT_LEFT -> "Shift + Left Click"
            SkillTrigger.SHIFT_RIGHT -> "Shift + Right Click"
            SkillTrigger.JUMP_LEFT -> "Jump + Left Click"
            SkillTrigger.JUMP_RIGHT -> "Jump + Right Click"
            SkillTrigger.JUMP -> "Jump"
            SkillTrigger.SNEAK_HOLD -> "Shift Hold"
            SkillTrigger.DOUBLE_SNEAK -> "Double Shift"
            SkillTrigger.PASSIVE_DAMAGED -> "On Damage"
            SkillTrigger.PASSIVE -> "Passive"
            SkillTrigger.COMBO -> "Combo"
            SkillTrigger.UNKNOWN -> "Skill"
        }
        val bonusSuffix = bonusLevel?.let { " - Set $it" }.orEmpty()
        return name.takeIf {
            it.length >= 2 && normalizedName !in generic &&
                generic.none { prefix -> normalizedName.startsWith("$prefix ") } &&
                !normalizedName.startsWith("CD")
        }?.let { "$fallback - $it" } ?: "$fallback - $triggerLabel$bonusSuffix"
    }
}

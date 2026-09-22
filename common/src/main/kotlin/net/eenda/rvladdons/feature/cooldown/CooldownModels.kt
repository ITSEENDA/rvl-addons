package net.eenda.rvladdons.feature.cooldown

enum class CooldownConfidence {
    CONFIRMED,
    ESTIMATED
}

enum class SkillSourceType {
    ITEM,
    MAIN_HAND_WEAPON,
    OFF_HAND_WEAPON,
    EQUIPPED_SET,
    COMA_SET,
    UNKNOWN
}

enum class SkillHand {
    MAIN,
    OFF,
    UNKNOWN
}

data class SkillSourceSnapshot(
    val itemId: String,
    val displayName: String,
    val fingerprint: String,
    val setId: String? = null,
    val skillKey: String? = null,
    val cooldownHintMs: Long? = null,
    val sourceType: SkillSourceType = SkillSourceType.ITEM,
    val hand: SkillHand = SkillHand.UNKNOWN,
    val skillType: SkillType = SkillType.UNKNOWN,
    val setBonusLevel: Int? = null
)

enum class SkillConfidence {
    HIGH,
    MEDIUM,
    LOW
}

enum class SkillType {
    ACTIVE,
    PASSIVE,
    COMBO,
    SET_BONUS,
    UNKNOWN
}

data class SkillDescriptor(
    val skillKey: String,
    val displayName: String,
    val setId: String?,
    val sourceFingerprint: String,
    val trigger: SkillTrigger,
    val cooldownHintMs: Long?,
    val rawLines: List<String>,
    val confidence: SkillConfidence,
    val skillType: SkillType = SkillType.UNKNOWN,
    val setBonusLevel: Int? = null
)

data class CooldownEntry(
    val skillKey: String,
    val displayName: String,
    val trigger: SkillTrigger,
    val source: SkillSourceSnapshot?,
    var startedAt: Long,
    var endAt: Long,
    var lastServerRemainingMs: Long,
    var lastServerUpdateAt: Long,
    var confidence: CooldownConfidence = CooldownConfidence.CONFIRMED
) {
    fun remainingMs(now: Long): Long = (endAt - now).coerceAtLeast(0L)
}

data class PendingSkillAction(
    val at: Long,
    val trigger: SkillTrigger,
    val source: SkillSourceSnapshot?,
    val hand: SkillHand = SkillHand.UNKNOWN
)

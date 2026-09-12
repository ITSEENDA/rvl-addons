package net.eenda.rvladdons.feature.coma

import net.eenda.rvladdons.core.RvlTextMatcher

object ComaItemRuleMatcher {
    fun matches(item: ComaItemSnapshot?, rule: String): Boolean {
        if (item == null || item.itemId.isBlank()) return false

        ComaItemRuleCodec.decode(rule)?.let { registered ->
            if (item.itemId != registered.itemId ||
                (registered.armorType.isNotBlank() && item.armorType != registered.armorType)
            ) {
                return false
            }
            if (registered.fingerprintVersion >= 2) {
                if (registered.fingerprint.isBlank() || item.fingerprint == registered.fingerprint) {
                    return true
                }
                // Preserve matching when a server changes volatile item data.
                return registered.displayName.isNotBlank() && item.displayName == registered.displayName
            }
            return registered.displayName.isBlank() || item.displayName == registered.displayName
        }

        return RvlTextMatcher.contains(item.itemId, rule) ||
            RvlTextMatcher.contains(item.displayName, rule) ||
            RvlTextMatcher.contains(item.armorType, rule)
    }

    fun isComaEquipment(item: ComaItemSnapshot?, roleIndex: Int): Boolean {
        val role = ComaRole.fromIndex(roleIndex) ?: return false
        return item?.itemId == role.equipmentId
    }
}

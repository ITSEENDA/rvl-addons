package net.eenda.rvladdons.feature.coma

/** Stable item data used by COMA logic without depending on Minecraft classes. */
data class ComaItemSnapshot(
    val itemId: String,
    val displayName: String = "",
    val armorType: String = "",
    val fingerprint: String = ""
)

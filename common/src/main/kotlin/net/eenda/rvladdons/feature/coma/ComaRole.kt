package net.eenda.rvladdons.feature.coma

/** Stable COMA role metadata shared by the feature and its UI. */
enum class ComaRole(
    val index: Int,
    val key: String,
    val label: String,
    val equipmentId: String,
    val menuSlot: Int
) {
    HELMET(0, "helmet", "Helmet", "minecraft:chainmail_helmet", 19),
    CHESTPLATE(1, "chestplate", "Chestplate", "minecraft:chainmail_chestplate", 20),
    LEGGINGS(2, "leggings", "Leggings", "minecraft:chainmail_leggings", 21),
    BOOTS(3, "boots", "Boots", "minecraft:chainmail_boots", 22),
//    WEAPON(3, "boots", "Boots", "minecraft:chainmail_boots", 22)
    ;

    companion object {
        val ordered: List<ComaRole> = entries

        fun fromIndex(index: Int): ComaRole? = ordered.getOrNull(index)
    }
}

package net.eenda.rvladdons.feature.coma

data class ComaInventorySnapshot(
    val syncId: Int,
    val revision: Int,
    val slots: List<ComaItemSnapshot?>
)

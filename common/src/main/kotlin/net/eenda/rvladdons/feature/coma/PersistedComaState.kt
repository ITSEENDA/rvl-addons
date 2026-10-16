package net.eenda.rvladdons.feature.coma

data class PersistedComaState(
    val serverKey: String = "",
    val rules: List<String> = emptyList(),
    val iconItemIds: List<String> = emptyList()
)

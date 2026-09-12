package net.eenda.rvladdons.feature.coma

data class ComaSetConfig(
    var name: String = "Set 1",
    var enabled: Boolean = true,
    var helmet: String = "",
    var chestplate: String = "",
    var leggings: String = "",
    var boots: String = ""
) {
    fun rules(): List<String> = ComaRole.ordered.map(::rule)

    fun rule(role: ComaRole): String = when (role) {
        ComaRole.HELMET -> helmet
        ComaRole.CHESTPLATE -> chestplate
        ComaRole.LEGGINGS -> leggings
        ComaRole.BOOTS -> boots
    }

    fun setRule(role: ComaRole, value: String) {
        when (role) {
            ComaRole.HELMET -> helmet = value
            ComaRole.CHESTPLATE -> chestplate = value
            ComaRole.LEGGINGS -> leggings = value
            ComaRole.BOOTS -> boots = value
        }
    }
}

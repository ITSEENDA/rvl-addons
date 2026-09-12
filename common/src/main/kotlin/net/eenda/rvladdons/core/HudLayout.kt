package net.eenda.rvladdons.core

enum class HudAnchor {
    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    CENTER_LEFT,
    CENTER,
    CENTER_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_CENTER,
    BOTTOM_RIGHT
}

enum class HudComponent {
    STATUS,
    TRACE,
    COOLDOWN,
    COMA
}

data class HudLayout(
    var anchor: HudAnchor = HudAnchor.TOP_LEFT,
    var offsetX: Int = 8,
    var offsetY: Int = 8,
    var scale: Float = 1f
)

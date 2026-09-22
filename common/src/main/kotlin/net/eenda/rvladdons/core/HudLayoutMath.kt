package net.eenda.rvladdons.core

data class HudRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

object HudLayoutMath {
    fun resolve(layout: HudLayout, screenWidth: Int, screenHeight: Int, width: Int, height: Int): HudRect {
        val x = when (layout.anchor) {
            HudAnchor.TOP_LEFT, HudAnchor.CENTER_LEFT, HudAnchor.BOTTOM_LEFT -> layout.offsetX
            HudAnchor.TOP_CENTER, HudAnchor.CENTER, HudAnchor.BOTTOM_CENTER ->
                screenWidth / 2 - width / 2 + layout.offsetX
            HudAnchor.TOP_RIGHT, HudAnchor.CENTER_RIGHT, HudAnchor.BOTTOM_RIGHT ->
                screenWidth - width - layout.offsetX
        }
        val y = when (layout.anchor) {
            HudAnchor.TOP_LEFT, HudAnchor.TOP_CENTER, HudAnchor.TOP_RIGHT -> layout.offsetY
            HudAnchor.CENTER_LEFT, HudAnchor.CENTER, HudAnchor.CENTER_RIGHT ->
                screenHeight / 2 - height / 2 + layout.offsetY
            HudAnchor.BOTTOM_LEFT, HudAnchor.BOTTOM_CENTER, HudAnchor.BOTTOM_RIGHT ->
                screenHeight - height - layout.offsetY
        }
        return HudRect(x, y, width, height)
    }

    fun offsetXFor(anchor: HudAnchor, screenX: Int, screenWidth: Int, width: Int): Int = when (anchor) {
        HudAnchor.TOP_LEFT, HudAnchor.CENTER_LEFT, HudAnchor.BOTTOM_LEFT -> screenX
        HudAnchor.TOP_CENTER, HudAnchor.CENTER, HudAnchor.BOTTOM_CENTER ->
            screenX - (screenWidth / 2 - width / 2)
        HudAnchor.TOP_RIGHT, HudAnchor.CENTER_RIGHT, HudAnchor.BOTTOM_RIGHT ->
            screenWidth - width - screenX
    }

    fun offsetYFor(anchor: HudAnchor, screenY: Int, screenHeight: Int, height: Int): Int = when (anchor) {
        HudAnchor.TOP_LEFT, HudAnchor.TOP_CENTER, HudAnchor.TOP_RIGHT -> screenY
        HudAnchor.CENTER_LEFT, HudAnchor.CENTER, HudAnchor.CENTER_RIGHT ->
            screenY - (screenHeight / 2 - height / 2)
        HudAnchor.BOTTOM_LEFT, HudAnchor.BOTTOM_CENTER, HudAnchor.BOTTOM_RIGHT ->
            screenHeight - height - screenY
    }
}

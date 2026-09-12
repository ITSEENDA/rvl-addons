package net.eenda.rvladdons.core

object RvlServerDetector {
    private val rvlDimension = Regex("(?i)\\bminecraft:rvl[a-z0-9_]*\\b")

    fun isRvlServer(signal: String): Boolean = rvlDimension.containsMatchIn(signal)
}

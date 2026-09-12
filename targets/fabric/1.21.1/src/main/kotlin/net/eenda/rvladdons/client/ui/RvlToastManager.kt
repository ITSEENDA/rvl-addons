package net.eenda.rvladdons.client.ui

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

object RvlToastManager {
    private data class ToastMessage(
        val text: String,
        val expiresAt: Long
    )

    private val lock = Any()
    private val messages = mutableListOf<ToastMessage>()
    private const val DISPLAY_MS = 5_000L
    private const val MAX_MESSAGES = 6
    private const val HEIGHT = 24
    private const val GAP = 4

    fun show(message: String, durationMs: Long = DISPLAY_MS) {
        synchronized(lock) {
            messages += ToastMessage(message, System.currentTimeMillis() + durationMs)
            while (messages.size > MAX_MESSAGES) messages.removeAt(0)
        }
    }

    fun render(context: DrawContext, client: MinecraftClient) {
        val active = synchronized(lock) {
            val now = System.currentTimeMillis()
            messages.removeAll { it.expiresAt <= now }
            messages.toList()
        }
        if (active.isEmpty()) return

        active.forEachIndexed { index, message ->
            val text = Text.literal(message.text)
            val width = (client.textRenderer.getWidth(text) + 20).coerceAtLeast(180)
            val x = client.window.scaledWidth - width - 8
            val y = 8 + index * (HEIGHT + GAP)
            context.fill(x, y, x + width, y + HEIGHT, 0xE8202020.toInt())
            context.fill(x, y, x + 3, y + HEIGHT, 0xFF55AAFF.toInt())
            context.drawTextWithShadow(client.textRenderer, text, x + 10, y + 8, 0xFFFFFF)
        }
    }
}

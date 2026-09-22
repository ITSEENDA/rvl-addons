package net.eenda.rvladdons.client.cooldown

import net.eenda.rvladdons.feature.cooldown.CooldownConfidence
import net.eenda.rvladdons.feature.cooldown.SkillTrigger
import dev.tako.libs.client.ui.core.TakoScreenChrome
import dev.tako.libs.client.ui.core.TakoTheme
import dev.tako.libs.client.ui.widget.TakoProgressBar
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import kotlin.math.roundToInt

/** Renders cooldown entries without owning cooldown state. */
object CooldownHudRenderer {
    private const val MIN_PANEL_WIDTH = 210
    private const val MAX_PANEL_WIDTH = 600
    private const val MAX_CARD_WIDTH = 300
    private const val ROW_HEIGHT = 34

    fun panelSize(client: MinecraftClient, now: Long = System.currentTimeMillis()): Pair<Int, Int> {
        val entries = CooldownController.active(now)
        if (entries.isEmpty()) return 260 to 54
        val cardWidth = entries.maxOf { client.textRenderer.getWidth(it.displayName) + 70 }
            .coerceIn(MIN_PANEL_WIDTH, MAX_CARD_WIDTH)
        val columns = columnsFor(entries.size)
        return (cardWidth * columns).coerceAtMost(MAX_PANEL_WIDTH) to
            ((entries.size + columns - 1) / columns) * ROW_HEIGHT
    }

    fun render(context: DrawContext, client: MinecraftClient, now: Long = System.currentTimeMillis()) {
        val entries = CooldownController.active(now)
        val width = panelSize(client, now).first
        val columns = columnsFor(entries.size)
        val cardWidth = width / columns
        entries.forEachIndexed { index, entry ->
            val remaining = entry.remainingMs(now)
            val seconds = remaining / 1000.0
            val progress = (remaining.toFloat() / entry.lastServerRemainingMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            val x = (index % columns) * cardWidth
            val y = (index / columns) * ROW_HEIGHT
            TakoScreenChrome.drawPanel(context, x, y, cardWidth - 2, ROW_HEIGHT - 2, TakoTheme.PANEL_CARD)
            TakoProgressBar.draw(context, x, y + ROW_HEIGHT - 4, cardWidth - 2, 2, progress, 0x00000000, TakoTheme.ACCENT)
            CooldownController.iconFor(entry.skillKey)?.let { context.drawItem(it, x + 2, y + 6) }
            context.drawTextWithShadow(
                client.textRenderer,
                trimLabel(client, entry.displayName, cardWidth - 28),
                x + 22,
                y + 3,
                if (entry.confidence == CooldownConfidence.CONFIRMED) TakoTheme.TEXT_PRIMARY else 0xFFCC66
            )
            context.drawTextWithShadow(
                client.textRenderer,
                "[${triggerLabel(entry.trigger)}]  ${"%.1f".format(seconds)}s",
                x + 22,
                y + 17,
                TakoTheme.TEXT_SECONDARY
            )
        }
    }

    private fun columnsFor(entryCount: Int): Int = if (entryCount > 1) 2 else 1

    private fun trimLabel(client: MinecraftClient, value: String, maxWidth: Int): String {
        if (client.textRenderer.getWidth(value) <= maxWidth) return value
        var result = value
        while (result.isNotEmpty() && client.textRenderer.getWidth("$result...") > maxWidth) result = result.dropLast(1)
        return "$result..."
    }

    private fun triggerLabel(trigger: SkillTrigger): String = when (trigger) {
        SkillTrigger.LEFT_CLICK -> "LMB"
        SkillTrigger.RIGHT_CLICK -> "RMB"
        SkillTrigger.SHIFT_LEFT -> "SHIFT + LMB"
        SkillTrigger.SHIFT_RIGHT -> "SHIFT + RMB"
        SkillTrigger.JUMP_LEFT -> "JUMP + LMB"
        SkillTrigger.JUMP_RIGHT -> "JUMP + RMB"
        SkillTrigger.JUMP -> "JUMP"
        SkillTrigger.SNEAK_HOLD -> "SHIFT HOLD"
        SkillTrigger.DOUBLE_SNEAK -> "DOUBLE SHIFT"
        SkillTrigger.PASSIVE_DAMAGED -> "ON DAMAGE"
        SkillTrigger.PASSIVE -> "PASSIVE"
        SkillTrigger.COMBO -> "COMBO"
        SkillTrigger.UNKNOWN -> "SKILL"
    }
}

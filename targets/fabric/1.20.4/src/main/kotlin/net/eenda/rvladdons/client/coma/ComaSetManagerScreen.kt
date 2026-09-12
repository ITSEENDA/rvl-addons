package net.eenda.rvladdons.client.coma

import net.eenda.rvladdons.feature.coma.ComaSetConfig
import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import kotlin.math.min

class ComaSetManagerScreen(
    private val parent: Screen?,
    private val page: Int = 0
) : Screen(Text.literal("RVL COMA Sets")) {
    private companion object {
        const val PAGE_SIZE = 5
        const val ROW_TOP = 52
        const val ROW_HEIGHT = 48
        const val ITEM_START_X = 20
        const val ITEM_SPACING = 24
        const val ITEM_TEXT_GAP = 8
    }

    override fun init() {
        val config = RvlAddonsConfigStore.config
        val pageCount = pageCount()
        val currentPage = page.coerceIn(0, pageCount - 1)
        val start = currentPage * PAGE_SIZE
        val end = min(start + PAGE_SIZE, config.comaSets.size)

        for (index in start until end) {
            val y = ROW_TOP + (index - start) * ROW_HEIGHT
            addDrawableChild(ButtonWidget.builder(Text.literal("Edit")) {
                client?.setScreen(ComaSetEditorScreen(this, index))
            }.dimensions(width - 300, y - 2, 48, 20).build())

            addDrawableChild(ButtonWidget.builder(Text.literal("Capture")) {
                val minecraft = client ?: return@builder
                ComaSwapController.requestCapture(minecraft, index, this)
                minecraft.setScreen(null)
            }.dimensions(width - 246, y - 2, 68, 20).build())

            val up = ButtonWidget.builder(Text.literal("Up")) {
                moveSet(index, -1)
            }.dimensions(width - 172, y - 2, 38, 20).build().also { it.active = index > 0 }
            addDrawableChild(up)

            val down = ButtonWidget.builder(Text.literal("Down")) {
                moveSet(index, 1)
            }.dimensions(width - 130, y - 2, 38, 20).build().also {
                it.active = index + 1 < config.comaSets.size
            }
            addDrawableChild(down)

            addDrawableChild(ButtonWidget.builder(Text.literal(if (config.comaSets[index].enabled) "ON" else "OFF")) {
                config.comaSets[index].enabled = !config.comaSets[index].enabled
                RvlAddonsConfigStore.save()
                client?.setScreen(ComaSetManagerScreen(parent, currentPage))
            }.dimensions(width - 86, y - 2, 40, 20).build())
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Add set")) {
            val newIndex = config.comaSets.size
            config.comaSets += ComaSetConfig(name = "Set ${newIndex + 1}")
            RvlAddonsConfigStore.save()
            client?.setScreen(ComaSetEditorScreen(this, newIndex))
        }.dimensions(14, height - 34, 80, 20).build())

        addDrawableChild(ButtonWidget.builder(Text.literal("Previous")) {
            if (currentPage > 0) client?.setScreen(ComaSetManagerScreen(parent, currentPage - 1))
        }.dimensions(width / 2 - 110, height - 34, 95, 20).build())

        addDrawableChild(ButtonWidget.builder(Text.literal("Next")) {
            if (currentPage + 1 < pageCount) client?.setScreen(ComaSetManagerScreen(parent, currentPage + 1))
        }.dimensions(width / 2 + 15, height - 34, 95, 20).build())

        addDrawableChild(ButtonWidget.builder(Text.literal("Back")) {
            client?.setScreen(parent)
        }.dimensions(width - 84, height - 34, 70, 20).build())
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        renderBackground(context, mouseX, mouseY, delta)
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 18, 0xFFFFFF)
        context.drawTextWithShadow(
            textRenderer,
            Text.literal("Capture with shift-click or pick up and place. K cycles enabled sets."),
            14,
            34,
            0xCCCCCC
        )

        val config = RvlAddonsConfigStore.config
        val currentPage = page.coerceIn(0, pageCount() - 1)
        val start = currentPage * PAGE_SIZE
        val end = min(start + PAGE_SIZE, config.comaSets.size)
        for (index in start until end) {
            val y = ROW_TOP + (index - start) * ROW_HEIGHT
            context.fill(12, y - 8, width - 12, y + 38, 0xDD181818.toInt())
            context.fill(12, y - 8, width - 12, y - 7, 0xEE555555.toInt())
            context.fill(12, y + 37, width - 12, y + 38, 0xEE080808.toInt())
        }

        children().forEach { (it as? Drawable)?.render(context, mouseX, mouseY, delta) }

        for (index in start until end) {
            val y = ROW_TOP + (index - start) * ROW_HEIGHT
            val set = config.comaSets[index]
            val captured = ComaSwapController.capturedCount(index)
            val found = client?.let { ComaSwapController.inventoryMatchedCount(it, index) } ?: 0
            val stacks = client?.let { ComaSwapController.inventoryStacks(it, index) }
                ?: List(4) { null }
            context.drawTextWithShadow(
                textRenderer,
                Text.literal("${index + 1}. ${set.name.ifBlank { "Unnamed" }}"),
                20,
                y - 3,
                if (set.enabled) 0xFFFFFF else 0x888888
            )
            set.rules().forEachIndexed { role, _ ->
                val itemX = ITEM_START_X + role * ITEM_SPACING
                val itemY = y + 10
                context.fill(itemX - 1, itemY - 1, itemX + 17, itemY + 17, 0xAA101010.toInt())
                val stack = stacks.getOrNull(role)
                if (stack != null && !stack.isEmpty) {
                    context.drawItem(stack, itemX, itemY)
                }
            }
            context.drawTextWithShadow(
                textRenderer,
                Text.literal("Captured $captured/4  |  In inventory $found/4"),
                ITEM_START_X + stacks.size * ITEM_SPACING + ITEM_TEXT_GAP,
                y + 15,
                if (captured == 4) 0x55FF55 else 0xFFAA55
            )
        }

        context.drawCenteredTextWithShadow(
            textRenderer,
            Text.literal("Page ${currentPage + 1}/${pageCount()}"),
            width / 2,
            height - 48,
            0xCCCCCC
        )
    }

    override fun close() {
        client?.setScreen(parent)
    }

    private fun pageCount(): Int =
        maxOf(1, (RvlAddonsConfigStore.config.comaSets.size + PAGE_SIZE - 1) / PAGE_SIZE)

    private fun moveSet(index: Int, direction: Int) {
        val config = RvlAddonsConfigStore.config
        val target = index + direction
        if (target !in config.comaSets.indices) return
        ComaSwapController.onSetReordered(index, target)
        val set = config.comaSets.removeAt(index)
        config.comaSets.add(target, set)
        RvlAddonsConfigStore.save()
        client?.setScreen(ComaSetManagerScreen(parent, target / PAGE_SIZE))
    }
}

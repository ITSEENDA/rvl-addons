package net.eenda.rvladdons.client.coma

import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.eenda.rvladdons.feature.coma.ComaItemRuleCodec
import net.eenda.rvladdons.feature.coma.ComaRole
import net.eenda.rvladdons.feature.coma.ComaSetConfig
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text

class ComaSetEditorScreen(
    private val parent: Screen?,
    private val setIndex: Int
) : Screen(Text.literal("RVL COMA Set")) {
    private val roles = ComaRole.ordered
    private var nameField: TextFieldWidget? = null
    private var enabledButton: ButtonWidget? = null

    override fun init() {
        val set = RvlAddonsConfigStore.config.comaSets.getOrNull(setIndex) ?: ComaSetConfig()
        nameField = TextFieldWidget(textRenderer, 180, 42, 260, 20, Text.literal("Set name")).also {
            it.text = set.name
            it.setMaxLength(128)
            addDrawableChild(it)
        }

        enabledButton = ButtonWidget.builder(enabledText(set)) {
            val current = RvlAddonsConfigStore.config.comaSets.getOrNull(setIndex) ?: return@builder
            current.enabled = !current.enabled
            enabledButton?.message = enabledText(current)
        }.dimensions(180, 225, 100, 20).build().also(::addDrawableChild)

        addDrawableChild(ButtonWidget.builder(Text.literal("Capture from Cờ Ma")) {
            val minecraft = client ?: return@builder
            saveFields()
            RvlAddonsConfigStore.save()
            ComaSwapController.requestCapture(minecraft, setIndex, this)
            minecraft.setScreen(null)
        }.dimensions(286, 225, 154, 20).build())

        roles.forEachIndexed { index, _ ->
            addDrawableChild(ButtonWidget.builder(Text.literal("Clear")) {
                clearRole(index)
            }.dimensions(width - 98, 82 + index * 35, 70, 20).build())
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Save")) {
            saveFields()
            RvlAddonsConfigStore.save()
            client?.setScreen(parent)
        }.dimensions(180, 252, 70, 20).build())

        addDrawableChild(ButtonWidget.builder(Text.literal("Delete")) {
            if (setIndex in RvlAddonsConfigStore.config.comaSets.indices) {
                RvlAddonsConfigStore.config.comaSets.removeAt(setIndex)
                RvlAddonsConfigStore.save()
            }
            client?.setScreen(parent)
        }.dimensions(256, 252, 70, 20).build())

        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel")) {
            client?.setScreen(parent)
        }.dimensions(332, 252, 70, 20).build())
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        renderBackground(context, mouseX, mouseY, delta)
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 16, 0xFFFFFF)
        context.drawTextWithShadow(textRenderer, Text.literal("Set name"), 20, 47, 0xFFFF55)
        context.drawTextWithShadow(
            textRenderer,
            Text.literal("Captured item status"),
            180,
            70,
            0xCCCCCC
        )

        val set = RvlAddonsConfigStore.config.comaSets.getOrNull(setIndex)
        val stacks = client?.let { ComaSwapController.inventoryStacks(it, setIndex) }
            ?: List(4) { null }

        roles.forEachIndexed { index, role ->
            val y = 84 + index * 35
            context.fill(174, y - 5, width - 20, y + 25, 0xDD181818.toInt())
            context.fill(174, y - 5, width - 20, y - 4, 0xEE555555.toInt())
            context.fill(174, y + 24, width - 20, y + 25, 0xEE080808.toInt())
        }

        children().forEach { (it as? Drawable)?.render(context, mouseX, mouseY, delta) }

        roles.forEachIndexed { index, role ->
            val y = 84 + index * 35
            val rule = set?.rules()?.getOrNull(index).orEmpty()
            val registered = ComaItemRuleCodec.decode(rule)
            val found = stacks.getOrNull(index) != null

            val stack = stacks.getOrNull(index)
            if (stack != null && !stack.isEmpty) {
                context.drawItem(stack, 181, y)
            }
            context.drawTextWithShadow(textRenderer, Text.literal(role.label), 20, y + 3, 0xFFFF55)
            context.drawTextWithShadow(
                textRenderer,
                Text.literal(
                    when {
                        registered == null -> "Not captured"
                        found -> "Found in inventory"
                        else -> "Missing from inventory"
                    }
                ),
                207,
                y + 14,
                when {
                    registered == null -> 0xFFAA55
                    found -> 0x55FF55
                    else -> 0xFFAA55
                }
            )
        }

        context.drawTextWithShadow(
            textRenderer,
            Text.literal("Capture records the exact item identity; raw fingerprints are hidden."),
            20,
            295,
            0xCCCCCC
        )
    }

    override fun close() {
        client?.setScreen(parent)
    }

    fun refreshNameFromConfig() {
        nameField?.text = RvlAddonsConfigStore.config.comaSets.getOrNull(setIndex)?.name.orEmpty()
    }

    private fun saveFields() {
        val set = RvlAddonsConfigStore.config.comaSets.getOrNull(setIndex) ?: return
        set.name = nameField?.text ?: set.name
    }

    private fun clearRole(index: Int) {
        val set = RvlAddonsConfigStore.config.comaSets.getOrNull(setIndex) ?: return
        ComaRole.fromIndex(index)?.let { set.setRule(it, "") }
        RvlAddonsConfigStore.save()
    }

    private fun enabledText(set: ComaSetConfig): Text =
        Text.literal(if (set.enabled) "Enabled" else "Disabled")
}

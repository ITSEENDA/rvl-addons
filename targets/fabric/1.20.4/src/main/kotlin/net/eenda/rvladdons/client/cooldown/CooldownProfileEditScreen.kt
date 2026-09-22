package net.eenda.rvladdons.client.cooldown

import net.eenda.rvladdons.feature.cooldown.SkillProfile
import net.eenda.rvladdons.feature.cooldown.SkillProfileStore
import dev.tako.libs.client.ui.core.TakoScreenChrome
import dev.tako.libs.client.ui.widget.TakoButton
import dev.tako.libs.client.ui.widget.TakoTextField
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

class CooldownProfileEditScreen(
    private val profile: SkillProfile,
    private val parent: Screen?
) : Screen(Text.literal("Edit Skill Profile")) {
    private lateinit var labelField: TakoTextField
    private lateinit var cooldownField: TakoTextField

    override fun init() {
        labelField = TakoTextField(textRenderer, 24, 58, 360, 20, "Skill name", profile.label).also { addDrawableChild(it.widget) }
        cooldownField = TakoTextField(
            textRenderer,
            24,
            102,
            120,
            20,
            "CD seconds",
            profile.cooldownHintMs?.let { ms -> "%.1f".format(ms / 1000.0) }.orEmpty(),
            12
        ).also { addDrawableChild(it.widget) }
        addDrawableChild(TakoButton("Save", width - 230, height - 30, 70, onPress = {
            save()
            client?.setScreen(parent)
        }, textRenderer = textRenderer))
        addDrawableChild(TakoButton("Cancel", width - 150, height - 30, 70, onPress = {
            client?.setScreen(parent)
        }, textRenderer = textRenderer))
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        TakoScreenChrome.drawTitle(context, textRenderer, title, width, 20)
        context.drawTextWithShadow(textRenderer, "${profile.sourceType.name} | ${profile.trigger.name}", 24, 38, 0xAAAAAA)
        context.drawTextWithShadow(textRenderer, Text.literal("Skill name"), 24, 48, 0xFFFFFF)
        context.drawTextWithShadow(textRenderer, Text.literal("Cooldown seconds (empty disables HUD tracking)"), 24, 92, 0xFFFFFF)
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.renderBackground(context, mouseX, mouseY, delta)
        TakoScreenChrome.drawPanel(context, 16, 14, width - 32, height - 52)
    }

    override fun close() { client?.setScreen(parent) }

    private fun save() {
        SkillProfileStore.setLabel(profile.skillKey, labelField.text)
        profile.cooldownHintMs = cooldownField.text.trim()
            .replace(',', '.')
            .toDoubleOrNull()
            ?.takeIf { it > 0.0 }
            ?.let { (it * 1000.0).toLong() }
        SkillProfileStore.save()
    }
}

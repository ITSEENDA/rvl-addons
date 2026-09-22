package net.eenda.rvladdons.client.cooldown

import net.eenda.rvladdons.feature.cooldown.SkillProfile
import net.eenda.rvladdons.feature.cooldown.SkillProfileStore
import dev.tako.libs.client.ui.core.TakoScreenChrome
import dev.tako.libs.client.ui.core.TakoTheme
import dev.tako.libs.client.ui.input.TakoDropdown
import dev.tako.libs.client.ui.input.TakoSearchField
import dev.tako.libs.client.ui.layout.TakoAxis
import dev.tako.libs.client.ui.layout.TakoBounds
import dev.tako.libs.client.ui.layout.TakoContainer
import dev.tako.libs.client.ui.layout.TakoLayoutable
import dev.tako.libs.client.ui.layout.TakoScrollState
import dev.tako.libs.client.ui.layout.TakoSizeSpec
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import kotlin.math.roundToInt

class CooldownProfileScreen(private val parent: Screen?) : Screen(Text.literal("Cooldown Profiles")) {
    private companion object {
        const val CONTENT_TOP = 70
        const val CONTENT_BOTTOM_MARGIN = 38
        const val GROUP_HEADER_HEIGHT = 22
        const val ROW_HEIGHT = 48
        const val SCROLL_STEP = 32.0
    }

    private data class ProfileRow(
        val profile: SkillProfile,
        val edit: ButtonWidget,
        val toggle: ButtonWidget,
        val delete: ButtonWidget
    )

    private data class ProfileGroup(
        val title: String,
        val rows: List<ProfileRow>,
        var headerY: Int = 0,
        var collapsed: Boolean = false
    )

    private val groups = mutableListOf<ProfileGroup>()
    private val scrollState = TakoScrollState(SCROLL_STEP)
    private var query = ""
    private var sourceFilter = "ALL"
    private lateinit var searchField: TakoSearchField
    private lateinit var sourceDropdown: TakoDropdown<String>

    override fun init() {
        CooldownController.refreshProfiles()
        searchField = TakoSearchField(textRenderer, 24, 42, width - 190, 20, query) {
            query = it
            refreshFilteredView()
        }
        sourceDropdown = TakoDropdown(
            textRenderer,
            sourceOptions(),
            { it },
            sourceOptions().indexOf(sourceFilter).coerceAtLeast(0)
        ) {
            sourceFilter = it
            refreshFilteredView()
        }
        addDrawableChild(searchField.widget)
        TakoContainer<TakoLayoutable>(axis = TakoAxis.ROW, gap = 8)
            .add(searchField, TakoSizeSpec.Fixed(width - 190), TakoSizeSpec.Fixed(20))
            .add(sourceDropdown, TakoSizeSpec.Fixed(132), TakoSizeSpec.Fixed(20))
            .layout(TakoBounds(24, 42, width - 48, 20))
        groups.clear()
        SkillProfileStore.all().groupBy { groupKey(it) }.forEach { (_, profiles) ->
            groups += ProfileGroup(groupTitle(profiles.first()), profiles.map(::createRow))
        }
        updateScrollBounds()
        layoutRows()
        addDrawableChild(ButtonWidget.builder(Text.literal("Save")) {
            SkillProfileStore.save(); client?.setScreen(parent)
        }.dimensions(width - 230, height - 30, 70, 20).build())
        addDrawableChild(ButtonWidget.builder(Text.literal("Close")) {
            SkillProfileStore.save(); client?.setScreen(parent)
        }.dimensions(width - 150, height - 30, 70, 20).build())
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        sourceDropdown.render(context)
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 16, 0xFFFFFF)
        context.drawTextWithShadow(textRenderer, Text.literal("Search, filter source, or click a group to collapse it."), 24, 28, 0xAAAAAA)
        groups.forEach { group ->
            val visibleRows = group.rows.filter { matches(it.profile) }
            if (visibleRows.isEmpty()) return@forEach
            if (group.headerY >= CONTENT_TOP && group.headerY + GROUP_HEADER_HEIGHT <= contentBottom()) {
                val marker = if (group.collapsed) "+" else "-"
                context.drawTextWithShadow(textRenderer, "[$marker] ${group.title} (${visibleRows.size})", 24, group.headerY + 5, 0xFFCC66)
                TakoScreenChrome.drawDivider(context, 24, group.headerY + GROUP_HEADER_HEIGHT - 2, width - 48)
            }
            if (group.collapsed) return@forEach
            visibleRows.forEach { row ->
                val y = row.edit.y
                if (y >= CONTENT_TOP && y + ROW_HEIGHT <= contentBottom()) {
                    TakoScreenChrome.drawPanel(context, 20, y, width - 40, ROW_HEIGHT - 4, TakoTheme.PANEL_CARD)
                    context.drawTextWithShadow(textRenderer, trimSummary(row.profile.label, editX() - 32), 24, y + 5, 0xFFFFFF)
                    context.drawTextWithShadow(textRenderer, profileSummary(row.profile), 24, y + 24, 0xAAAAAA)
                }
            }
        }
        if (groups.none { group -> group.rows.any { matches(it.profile) } }) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("No matching cooldown profiles."), width / 2, CONTENT_TOP + 28, 0xAAAAAA)
        }
        drawScrollbar(context)
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.renderBackground(context, mouseX, mouseY, delta)
        TakoScreenChrome.drawPanel(context, 16, CONTENT_TOP - 2, width - 32, contentBottom() - CONTENT_TOP + 2)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (sourceDropdown.mouseClicked(mouseX, mouseY)) return true
        groups.firstOrNull {
            mouseY >= it.headerY && mouseY < it.headerY + GROUP_HEADER_HEIGHT &&
                mouseY >= CONTENT_TOP && mouseY <= contentBottom()
        }?.let { group ->
            group.collapsed = !group.collapsed
            updateScrollBounds()
            layoutRows()
            return true
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (mouseY >= CONTENT_TOP && mouseY <= contentBottom() && scrollState.maxOffset > 0.0) {
            scrollState.scroll(verticalAmount)
            layoutRows()
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun close() { SkillProfileStore.save(); client?.setScreen(parent) }

    private fun createRow(profile: SkillProfile): ProfileRow {
        val edit = ButtonWidget.builder(Text.literal("EDIT")) {
            client?.setScreen(CooldownProfileEditScreen(profile, this))
        }.dimensions(0, CONTENT_TOP, 48, 20).build()
        val toggle = ButtonWidget.builder(Text.literal(if (profile.enabled) "ON" else "OFF")) {
            profile.enabled = !profile.enabled
            if (!profile.enabled) CooldownController.removeProfile(profile.skillKey)
            SkillProfileStore.save()
            client?.setScreen(reopen().also { it.scrollState.setOffset(scrollState.offset) })
        }.dimensions(0, CONTENT_TOP, 48, 20).build()
        val delete = ButtonWidget.builder(Text.literal("DEL")) {
            CooldownController.removeProfile(profile.skillKey)
            SkillProfileStore.removeAndSave(profile.skillKey)
            client?.setScreen(reopen().also { it.scrollState.setOffset(scrollState.offset) })
        }.dimensions(0, CONTENT_TOP, 42, 20).build()
        addDrawableChild(edit); addDrawableChild(toggle); addDrawableChild(delete)
        return ProfileRow(profile, edit, toggle, delete)
    }

    private fun layoutRows() {
        var cursor = CONTENT_TOP - scrollState.offset.roundToInt()
        groups.forEach { group ->
            val visibleRows = group.rows.filter { matches(it.profile) }
            if (visibleRows.isEmpty()) {
                group.headerY = Int.MIN_VALUE
                group.rows.forEach { row ->
                    row.edit.visible = false
                    row.toggle.visible = false
                    row.delete.visible = false
                }
                return@forEach
            }
            group.headerY = cursor
            cursor += GROUP_HEADER_HEIGHT
            if (group.collapsed) {
                group.rows.forEach { row ->
                    row.edit.visible = false
                    row.toggle.visible = false
                    row.delete.visible = false
                }
            } else {
                group.rows.forEach { row ->
                    if (!matches(row.profile)) {
                        row.edit.visible = false
                        row.toggle.visible = false
                        row.delete.visible = false
                        return@forEach
                    }
                    val y = cursor
                    row.edit.setDimensionsAndPosition(48, 20, editX(), y)
                    row.toggle.setDimensionsAndPosition(48, 20, toggleX(), y)
                    row.delete.setDimensionsAndPosition(42, 20, deleteX(), y)
                    val visible = y >= CONTENT_TOP && y + ROW_HEIGHT <= contentBottom()
                    row.edit.visible = visible
                    row.toggle.visible = visible
                    row.delete.visible = visible
                    cursor += ROW_HEIGHT
                }
            }
        }
    }

    private fun updateScrollBounds() {
        var contentHeight = 0
        groups.forEach {
            val visibleRows = it.rows.count { row -> matches(row.profile) }
            if (visibleRows == 0) return@forEach
            contentHeight += GROUP_HEADER_HEIGHT
            if (!it.collapsed) contentHeight += visibleRows * ROW_HEIGHT
        }
        scrollState.update(contentHeight, contentBottom() - CONTENT_TOP)
    }

    private fun contentBottom(): Int = height - CONTENT_BOTTOM_MARGIN
    private fun editX(): Int = width - 174
    private fun toggleX(): Int = width - 120
    private fun deleteX(): Int = width - 66
    private fun reopen(): CooldownProfileScreen = CooldownProfileScreen(parent).also {
        it.query = query
        it.sourceFilter = sourceFilter
    }

    private fun refreshFilteredView() {
        scrollState.reset()
        updateScrollBounds()
        layoutRows()
    }
    private fun groupKey(profile: SkillProfile): String = when (profile.sourceType) {
        net.eenda.rvladdons.feature.cooldown.SkillSourceType.COMA_SET,
        net.eenda.rvladdons.feature.cooldown.SkillSourceType.EQUIPPED_SET -> "${profile.sourceType}:${profile.setId ?: profile.label}"
        else -> "${profile.sourceType}:${profile.sourceFingerprint}"
    }
    private fun groupTitle(profile: SkillProfile): String = "${sourceLabel(profile.sourceType.name)} | ${if (profile.sourceType.name.endsWith("SET")) profile.label else profile.label.substringBefore(" - ")}"
    private fun profileSummary(profile: SkillProfile): String {
        val tier = profile.setBonusLevel?.let { "Bo $it" } ?: "No tier"
        val cd = profile.cooldownHintMs?.let { "CD ${"%.1f".format(it / 1000.0)}s" } ?: "CD disabled"
        return "${sourceLabel(profile.sourceType.name)} | $tier | ${typeLabel(profile.skillType.name)} | ${triggerLabel(profile.trigger.name)} | $cd"
    }
    private fun matches(profile: SkillProfile): Boolean {
        val sourceMatches = when (sourceFilter) {
            "COMA" -> profile.sourceType == net.eenda.rvladdons.feature.cooldown.SkillSourceType.COMA_SET
            "EQUIPPED" -> profile.sourceType == net.eenda.rvladdons.feature.cooldown.SkillSourceType.EQUIPPED_SET
            "MAIN" -> profile.sourceType == net.eenda.rvladdons.feature.cooldown.SkillSourceType.MAIN_HAND_WEAPON
            "OFFHAND" -> profile.sourceType == net.eenda.rvladdons.feature.cooldown.SkillSourceType.OFF_HAND_WEAPON
            "ITEM" -> profile.sourceType == net.eenda.rvladdons.feature.cooldown.SkillSourceType.ITEM
            else -> true
        }
        if (!sourceMatches) return false
        val value = listOf(
            profile.label,
            sourceLabel(profile.sourceType.name),
            triggerLabel(profile.trigger.name),
            typeLabel(profile.skillType.name),
            profile.setBonusLevel?.toString().orEmpty()
        ).joinToString(" ")
        return query.isBlank() || value.contains(query.trim(), ignoreCase = true)
    }
    private fun sourceOptions(): List<String> = listOf("ALL", "COMA", "EQUIPPED", "MAIN", "OFFHAND", "ITEM")
    private fun sourceLabel(value: String): String = when (value) {
        "MAIN_HAND_WEAPON" -> "MAIN WEAPON"; "OFF_HAND_WEAPON" -> "OFFHAND WEAPON"
        "EQUIPPED_SET" -> "EQUIPPED SET"; "COMA_SET" -> "COMA SET"; else -> value
    }
    private fun typeLabel(value: String): String = if (value == "SET_BONUS") "SET BONUS" else value
    private fun triggerLabel(value: String): String = when (value) {
        "LEFT_CLICK" -> "LMB"; "RIGHT_CLICK" -> "RMB"; "SHIFT_LEFT" -> "SHIFT + LMB"; "SHIFT_RIGHT" -> "SHIFT + RMB"
        "JUMP_LEFT" -> "JUMP + LMB"; "JUMP_RIGHT" -> "JUMP + RMB"; "JUMP" -> "JUMP"; "SNEAK_HOLD" -> "SHIFT HOLD"
        "DOUBLE_SNEAK" -> "DOUBLE SHIFT"; "PASSIVE_DAMAGED" -> "ON DAMAGE"; "PASSIVE" -> "PASSIVE"; "COMBO" -> "COMBO"; else -> value
    }
    private fun trimSummary(value: String, maxWidth: Int): String {
        if (textRenderer.getWidth(value) <= maxWidth) return value
        var result = value
        while (result.isNotEmpty() && textRenderer.getWidth("$result...") > maxWidth) result = result.dropLast(1)
        return "$result..."
    }
    private fun drawScrollbar(context: DrawContext) {
        if (scrollState.maxOffset <= 0.0) return
        val top = CONTENT_TOP; val bottom = contentBottom(); val h = bottom - top
        val thumb = (h * h / (h + scrollState.maxOffset)).roundToInt().coerceAtLeast(12)
        val y = top + ((h - thumb) * (scrollState.offset / scrollState.maxOffset)).roundToInt()
        context.fill(width - 12, top, width - 8, bottom, 0x66333333)
        context.fill(width - 12, y, width - 8, y + thumb, 0xFFAAAAAA.toInt())
    }
}

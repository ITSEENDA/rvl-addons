package net.eenda.rvladdons.client.party

import dev.tako.libs.client.ui.core.TakoScreenChrome
import dev.tako.libs.client.ui.core.TakoTheme
import net.eenda.rvladdons.feature.party.PartyPresenceState
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

class PartyScreen(private val parent: Screen?) : Screen(Text.literal("Local Clone Party")) {
    private companion object {
        const val PANEL_X = 24
        const val PANEL_Y = 42
        const val ROW_HEIGHT = 22
    }

    override fun init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("Select eligible")) {
            client?.let(LocalPartyController::selectAllEligible)
        }.dimensions(24, height - 30, 108, 20).build())
        addDrawableChild(ButtonWidget.builder(Text.literal("Start draft")) {
            client?.let(LocalPartyController::startDraft)
        }.dimensions(138, height - 30, 90, 20).build())
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel")) {
            client?.let(LocalPartyController::cancelDraft)
        }.dimensions(234, height - 30, 70, 20).build())
        addDrawableChild(ButtonWidget.builder(Text.literal("Close")) {
            close()
        }.dimensions(width - 94, height - 30, 70, 20).build())
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val minecraft = client ?: return
        super.render(context, mouseX, mouseY, delta)
        TakoScreenChrome.drawTitle(context, textRenderer, title, width, 16)
        TakoScreenChrome.drawPanel(context, PANEL_X, PANEL_Y, width - 48, height - 84, TakoTheme.PANEL)
        context.drawTextWithShadow(textRenderer, Text.literal("Loopback coordinator: ${if (LocalPartyController.isCoordinator()) "yes" else "client"}"), PANEL_X + 8, PANEL_Y + 8, TakoTheme.TEXT_SECONDARY)
        context.drawTextWithShadow(textRenderer, Text.literal("Click a client row to select it"), PANEL_X + 8, PANEL_Y + 24, TakoTheme.TEXT_SECONDARY)

        LocalPartyController.clients().forEachIndexed { index, snapshot ->
            val rowY = PANEL_Y + 44 + index * ROW_HEIGHT
            val selected = snapshot.selected
            val eligible = LocalPartyController.isEligible(snapshot, minecraft)
            val stateColor = if (eligible) TakoTheme.ACCENT else 0xFFCC8866.toInt()
            context.fill(PANEL_X + 6, rowY, width - 54, rowY + ROW_HEIGHT - 2, if (selected) 0xCC25384A.toInt() else TakoTheme.PANEL_CARD)
            context.drawTextWithShadow(textRenderer, Text.literal(if (selected && eligible) "[x]" else "[ ]"), PANEL_X + 12, rowY + 6, if (eligible) TakoTheme.TEXT_PRIMARY else TakoTheme.TEXT_SECONDARY)
            context.drawTextWithShadow(textRenderer, Text.literal(snapshot.name), PANEL_X + 42, rowY + 6, if (eligible) TakoTheme.TEXT_PRIMARY else TakoTheme.TEXT_SECONDARY)
            context.drawTextWithShadow(textRenderer, Text.literal(snapshot.state.name), width - 170, rowY + 6, stateColor)
            context.drawTextWithShadow(textRenderer, Text.literal(snapshot.serverKey.ifBlank { "No server" }), width - 92, rowY + 6, TakoTheme.TEXT_SECONDARY)
        }

        if (LocalPartyController.clients().isEmpty()) {
            context.drawTextWithShadow(textRenderer, Text.literal("No local clients connected yet."), PANEL_X + 12, PANEL_Y + 54, TakoTheme.TEXT_SECONDARY)
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (super.mouseClicked(mouseX, mouseY, button)) return true
        if (button != 0 || mouseX < PANEL_X || mouseX > width - 24) return false
        val index = ((mouseY - (PANEL_Y + 44)) / ROW_HEIGHT).toInt()
        val snapshot = LocalPartyController.clients().getOrNull(index) ?: return false
        val minecraft = client ?: return false
        if (!LocalPartyController.isEligible(snapshot, minecraft)) return true
        LocalPartyController.toggleSelection(snapshot.clientId)
        return true
    }

    override fun close() {
        client?.setScreen(parent)
    }
}

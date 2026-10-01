package net.eenda.rvladdons.client.party

import dev.tako.libs.client.ui.core.TakoScreenChrome
import dev.tako.libs.client.ui.core.TakoTheme
import net.eenda.rvladdons.core.RvlAddonsConfigStore
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
        const val CLIENT_LIST_TOP = PANEL_Y + 92
    }

    override fun init() {
        if (LocalPartyController.isCoordinator()) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Capture Type + Room Slots")) {
                client?.setScreen(parent)
                LocalPartyController.startAutomaticSlotCapture()
            }.dimensions(PANEL_X + 8, PANEL_Y + 42, 184, 20).build())
            addDrawableChild(ButtonWidget.builder(Text.literal("Select eligible")) {
                client?.let(LocalPartyController::selectAllEligible)
            }.dimensions(24, height - 30, 108, 20).build())
            addDrawableChild(ButtonWidget.builder(Text.literal("Prepare party")) {
                client?.let(LocalPartyController::startPreparation)
            }.dimensions(138, height - 30, 90, 20).build())
            addDrawableChild(ButtonWidget.builder(Text.literal("Cancel")) {
                client?.let(LocalPartyController::cancelPreparation)
            }.dimensions(234, height - 30, 70, 20).build())
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Close")) {
            close()
        }.dimensions(width - 94, height - 30, 70, 20).build())
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val minecraft = client ?: return
        super.render(context, mouseX, mouseY, delta)
        TakoScreenChrome.drawTitle(context, textRenderer, title, width, 16)
        TakoScreenChrome.drawPanel(context, PANEL_X, PANEL_Y, width - 48, height - 84, TakoTheme.PANEL)
        val coordinator = LocalPartyController.isCoordinator()
        val header = if (coordinator) {
            "Loopback coordinator: primary"
        } else {
            "Joined coordinator: ${LocalPartyController.coordinatorName()}"
        }
        context.drawTextWithShadow(textRenderer, Text.literal(header), PANEL_X + 8, PANEL_Y + 8, TakoTheme.TEXT_SECONDARY)
        context.drawTextWithShadow(
            textRenderer,
            Text.literal(if (coordinator) "Click row to select; SET PRIMARY transfers coordinator" else "Read-only party view"),
            PANEL_X + 8,
            PANEL_Y + 24,
            TakoTheme.TEXT_SECONDARY
        )
        val config = RvlAddonsConfigStore.config
        context.drawTextWithShadow(
            textRenderer,
            Text.literal("Auto slots: Type #${config.partyAutomaticTypeSlot} / Room #${config.partyAutomaticRoomSlot}"),
            PANEL_X + 8,
            PANEL_Y + 34,
            TakoTheme.TEXT_SECONDARY
        )

        val clients = LocalPartyController.clients()
        clients.forEachIndexed { index, snapshot ->
            val rowY = CLIENT_LIST_TOP + index * ROW_HEIGHT
            val selected = snapshot.selected
            val eligible = LocalPartyController.isEligible(snapshot, minecraft)
            val stateColor = if (eligible) TakoTheme.ACCENT else 0xFFCC8866.toInt()
            val actionX = width - 104
            val rowRight = width - 24
            val marker = when {
                !coordinator && snapshot.isCoordinator -> "PRIMARY"
                !coordinator -> "-"
                selected && eligible -> "[x]"
                else -> "[ ]"
            }
            context.fill(PANEL_X + 6, rowY, rowRight, rowY + ROW_HEIGHT - 2, if (selected && coordinator) 0xCC25384A.toInt() else TakoTheme.PANEL_CARD)
            context.drawTextWithShadow(textRenderer, Text.literal(marker), PANEL_X + 12, rowY + 6, if (eligible) TakoTheme.TEXT_PRIMARY else TakoTheme.TEXT_SECONDARY)
            context.drawTextWithShadow(textRenderer, Text.literal(snapshot.name), PANEL_X + 42, rowY + 6, if (eligible) TakoTheme.TEXT_PRIMARY else TakoTheme.TEXT_SECONDARY)
            context.drawTextWithShadow(textRenderer, Text.literal(snapshot.state.name), width - 220, rowY + 6, stateColor)
            context.drawTextWithShadow(textRenderer, Text.literal(snapshot.serverKey.ifBlank { "No server" }.take(16)), width - 330, rowY + 6, TakoTheme.TEXT_SECONDARY)
            if (coordinator) {
                context.fill(actionX, rowY + 2, rowRight - 4, rowY + ROW_HEIGHT - 4, 0xAA345A70.toInt())
                context.drawTextWithShadow(textRenderer, Text.literal("SET PRIMARY"), actionX + 3, rowY + 6, TakoTheme.TEXT_PRIMARY)
            }
        }

        if (clients.isEmpty()) {
            context.drawTextWithShadow(
                textRenderer,
                Text.literal(if (coordinator) "No local clients connected yet." else "Waiting for other party clients..."),
                PANEL_X + 12,
                CLIENT_LIST_TOP + 10,
                TakoTheme.TEXT_SECONDARY
            )
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (super.mouseClicked(mouseX, mouseY, button)) return true
        if (button != 0 || mouseX < PANEL_X || mouseX > width - 24) return false
        val index = ((mouseY - CLIENT_LIST_TOP) / ROW_HEIGHT).toInt()
        val snapshot = LocalPartyController.clients().getOrNull(index) ?: return false
        val minecraft = client ?: return false
        if (!LocalPartyController.isCoordinator()) return true
        if (mouseX >= width - 104) {
            LocalPartyController.promoteClient(snapshot.clientId)
            return true
        }
        if (!LocalPartyController.isEligible(snapshot, minecraft)) return true
        LocalPartyController.toggleSelection(snapshot.clientId)
        return true
    }

    override fun close() {
        client?.setScreen(parent)
    }
}


package net.eenda.rvladdons.client.party

import dev.tako.libs.client.ui.core.TakoScreenChrome
import dev.tako.libs.client.ui.core.TakoTheme
import dev.tako.libs.client.ui.layout.*
import dev.tako.libs.client.ui.widget.TakoButton
import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.eenda.rvladdons.feature.party.PartyClientSnapshot
import net.eenda.rvladdons.feature.party.PartyJoinExecutionMode
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.ClickableWidget
import net.minecraft.text.Text

class PartyScreen(private val parent: Screen?) : Screen(Text.literal("Local Clone Party")) {
    private class LayoutCell : TakoLayoutable {
        var bounds = TakoBounds(0, 0, 0, 0)
            private set

        override fun layout(bounds: TakoBounds) {
            this.bounds = bounds
        }
    }

    private data class PrimaryHitBox(val clientId: String, val bounds: TakoBounds)

    private var scrollState = TakoScrollState(ROW_HEIGHT.toDouble())
    private var primaryHitBoxes = emptyList<PrimaryHitBox>()

    private companion object {
        const val PANEL_X = 24
        const val PANEL_Y = 42
        const val ROW_HEIGHT = 32
        const val FOOTER_HEIGHT = 48
        const val COMPACT_FOOTER_HEIGHT = 76
        const val ROW_GAP_X = 6
    }

    override fun init() {
        scrollState.reset()
        val coordinator = LocalPartyController.isCoordinator()
        if (showCaptureButtons(coordinator)) {
            val captureDchienLabel = if (isCompact()) "Capture Dchien" else "Capture doan chien room"
            val captureTlLabel = if (isCompact()) "Capture TL" else "Capture thanh lan room"
            val captureGap = 8
            val captureDchien = TakoButton.wrapContent(
                captureDchienLabel,
                0,
                0,
                onPress = {
                    client?.setScreen(parent)
                    LocalPartyController.startAutomaticSlotCapture("dchien")
                },
                textRenderer = textRenderer
            )
            val captureTl = TakoButton.wrapContent(
                captureTlLabel,
                0,
                0,
                onPress = {
                    client?.setScreen(parent)
                    LocalPartyController.startAutomaticSlotCapture("tl")
                },
                textRenderer = textRenderer
            )
            addFlexRow(
                TakoBounds(
                    PANEL_X + 8,
                    PANEL_Y + 50,
                    captureDchien.width + captureTl.width + captureGap,
                    20
                ),
                captureGap,
                listOf(captureDchien, captureTl)
            )

            val actionsY = height - if (isCompact()) 54 else 30
            val select = ButtonWidget.builder(Text.literal("Select eligible")) {
                client?.let(LocalPartyController::selectAllEligible)
            }.dimensions(0, 0, 108, 20).build()
            val prepare = ButtonWidget.builder(Text.literal("Prepare party")) {
                client?.let(LocalPartyController::startPreparation)
            }.dimensions(0, 0, 90, 20).build()
            val cancel = ButtonWidget.builder(Text.literal("Cancel")) {
                client?.let(LocalPartyController::cancelPreparation)
            }.dimensions(0, 0, 70, 20).build()
            addFlexRow(
                TakoBounds(16, actionsY, width - 32, 20),
                4,
                listOf(select, prepare, cancel)
            )
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Close")) {
            close()
        }.dimensions(width - 94, height - 30, 70, 20).build())
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val minecraft = client ?: return
        renderBackground(context, mouseX, mouseY, delta)

        val coordinator = LocalPartyController.isCoordinator()
        val clients = LocalPartyController.clients()
        val listTop = clientListTop(coordinator)
        val visibleCount = visibleRowCount(clients.size, coordinator)
        scrollState.update(clients.size * ROW_HEIGHT, visibleCount * ROW_HEIGHT)
        val startIndex = (scrollState.offset / ROW_HEIGHT).toInt()
        val visibleClients = clients.drop(startIndex).take(visibleCount)
        val rowsHeight = if (clients.isEmpty()) 28 else visibleClients.size * ROW_HEIGHT
        val panelHeight = (listTop - PANEL_Y + rowsHeight + 8)
            .coerceAtMost(height - PANEL_Y - footerHeight(coordinator))

        TakoScreenChrome.drawTitle(context, textRenderer, title, width, 16)
        TakoScreenChrome.drawPanel(
            context,
            PANEL_X,
            PANEL_Y,
            width - PANEL_X * 2,
            panelHeight,
            TakoTheme.PANEL
        )

        val header = if (coordinator) {
            "Loopback coordinator: primary"
        } else {
            "Joined coordinator: ${LocalPartyController.coordinatorName()}"
        }
        val hint = (if (coordinator) {
            "Click row to select; SET PRIMARY transfers coordinator"
        } else {
            "Client party view"
        }) + if (clients.size > visibleCount) " | Scroll to browse" else ""
        drawLabel(context, header, PANEL_X + 8, PANEL_Y + 8, width - PANEL_X * 2 - 16, TakoTheme.TEXT_SECONDARY)
        drawLabel(context, hint, PANEL_X + 8, PANEL_Y + 24, width - PANEL_X * 2 - 16, TakoTheme.TEXT_SECONDARY)

        val (dchienRoom, tlRoom) = LocalPartyController.capturedRoomNames()
        val rooms = "Doan chien room: ${dchienRoom.ifBlank { "Not captured" }}  |  " +
                "Thanh lan room: ${tlRoom.ifBlank { "Not captured" }}"
        drawLabel(context, rooms, PANEL_X + 8, PANEL_Y + 34, width - PANEL_X * 2 - 16, TakoTheme.TEXT_SECONDARY)

        primaryHitBoxes = emptyList()
        if (clients.isEmpty()) {
            val emptyText = if (coordinator) "No local clients connected yet." else "Waiting for other party clients..."
            context.drawTextWithShadow(
                textRenderer,
                Text.literal(emptyText),
                PANEL_X + 12,
                listTop + 8,
                TakoTheme.TEXT_SECONDARY
            )
        } else {
            val hitBoxes = ArrayList<PrimaryHitBox>()
            visibleClients.forEachIndexed { visibleIndex, snapshot ->
                val rowY = listTop + visibleIndex * ROW_HEIGHT
                val rowRight = width - PANEL_X - 8
                val selected = snapshot.selected
                val eligible = LocalPartyController.isEligible(snapshot, minecraft)
                val stateColor = if (eligible) TakoTheme.ACCENT else 0xFFCC8866.toInt()
                val compact = isCompact()
                val resourceState = resourceState(snapshot, compact)
                val marker = when {
                    !coordinator && snapshot.isCoordinator -> "PRIMARY"
                    !coordinator -> "-"
                    selected && eligible -> "[x]"
                    else -> "[ ]"
                }

                val rowBounds = TakoBounds(PANEL_X + 6, rowY, rowRight - PANEL_X - 6, ROW_HEIGHT - 2)
                val serverColumnMinWidth = if (coordinator) 560 else 460
                val showServer = width >= serverColumnMinWidth
                val markerTrack = textRenderer.getWidth(if (coordinator) "[x]" else "PRIMARY") + 8
                val stateTrack = textRenderer.getWidth(
                    if (compact) "DCHIEN 0" else "DISCONNECTED"
                ) + 8
                val actionLabel = if (compact) "PRIMARY" else "SET PRIMARY"
                val actionTrack = textRenderer.getWidth(actionLabel) + 12

                val markerCell = LayoutCell()
                val nameCell = LayoutCell()
                val stateCell = LayoutCell()
                val serverCell = LayoutCell()
                val actionCell = LayoutCell()
                val grid = TakoGridContainer<TakoLayoutable>(
                    columns = buildList {
                        add(TakoSizeSpec.Fixed(markerTrack))
                        add(TakoSizeSpec.Weight(2f))
                        add(TakoSizeSpec.Fixed(stateTrack))
                        if (showServer) add(TakoSizeSpec.Weight())
                        if (coordinator) add(TakoSizeSpec.Fixed(actionTrack))
                    },
                    rows = listOf(TakoSizeSpec.Weight()),
                    columnGap = ROW_GAP_X,
                    padding = TakoInsets(left = 6, top = 1, right = 6, bottom = 1)
                )
                    .add(markerCell, row = 0, column = 0)
                    .add(nameCell, row = 0, column = 1)
                    .add(stateCell, row = 0, column = 2)
                var nextColumn = 3
                if (showServer) grid.add(serverCell, row = 0, column = nextColumn++)
                if (coordinator) grid.add(actionCell, row = 0, column = nextColumn)
                grid.layout(rowBounds)

                context.fill(
                    rowBounds.x,
                    rowBounds.y,
                    rowBounds.x + rowBounds.width,
                    rowBounds.y + rowBounds.height,
                    if (selected && coordinator) 0xCC25384A.toInt() else TakoTheme.PANEL_CARD
                )
                drawCellText(
                    context,
                    marker,
                    markerCell.bounds,
                    if (eligible) TakoTheme.TEXT_PRIMARY else TakoTheme.TEXT_SECONDARY
                )
                drawCellText(
                    context,
                    snapshot.name,
                    nameCell.bounds,
                    if (eligible) TakoTheme.TEXT_PRIMARY else TakoTheme.TEXT_SECONDARY
                )
                drawCellText(context, resourceState, stateCell.bounds, stateColor, alignEnd = true)
                if (showServer) {
                    drawCellText(
                        context,
                        snapshot.serverKey.ifBlank { "No server" }.take(32),
                        serverCell.bounds,
                        TakoTheme.TEXT_SECONDARY
                    )
                }

                if (coordinator) {
                    context.fill(
                        actionCell.bounds.x + 1,
                        actionCell.bounds.y + 1,
                        actionCell.bounds.x + actionCell.bounds.width - 1,
                        actionCell.bounds.y + actionCell.bounds.height - 1,
                        0xAA345A70.toInt()
                    )
                    drawCellText(context, actionLabel, actionCell.bounds, TakoTheme.TEXT_PRIMARY, centered = true)
                    hitBoxes += PrimaryHitBox(snapshot.clientId, actionCell.bounds)
                }
            }
            primaryHitBoxes = hitBoxes
        }

        children().forEach { (it as? Drawable)?.render(context, mouseX, mouseY, delta) }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (super.mouseClicked(mouseX, mouseY, button)) return true
        if (button != 0 || mouseX < PANEL_X || mouseX > width - PANEL_X) return false
        val coordinator = LocalPartyController.isCoordinator()
        val listTop = clientListTop(coordinator)
        val clients = LocalPartyController.clients()
        val visibleCount = visibleRowCount(clients.size, coordinator)
        val rowOffset = (mouseY - listTop).toInt()
        if (rowOffset < 0 || rowOffset >= visibleCount * ROW_HEIGHT) return false

        val startIndex = (scrollState.offset / ROW_HEIGHT).toInt()
        val snapshot = clients.getOrNull(startIndex + rowOffset / ROW_HEIGHT) ?: return false
        val minecraft = client ?: return false
        if (!coordinator) return true
        primaryHitBoxes.firstOrNull {
            it.clientId == snapshot.clientId && contains(it.bounds, mouseX, mouseY)
        }?.let {
            LocalPartyController.promoteClient(it.clientId)
            return true
        }
        if (!LocalPartyController.isEligible(snapshot, minecraft)) return true
        LocalPartyController.toggleSelection(snapshot.clientId)
        return true
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double
    ): Boolean {
        val coordinator = LocalPartyController.isCoordinator()
        val clients = LocalPartyController.clients()
        val listTop = clientListTop(coordinator)
        val listBottom = listTop + visibleRowCount(clients.size, coordinator) * ROW_HEIGHT
        if (mouseX in PANEL_X.toDouble()..(width - PANEL_X).toDouble() &&
            mouseY >= listTop && mouseY < listBottom && scrollState.maxOffset > 0.0
        ) {
            val previous = scrollState.offset
            scrollState.scroll(verticalAmount)
            return scrollState.offset != previous
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun close() {
        client?.setScreen(parent)
    }

    private fun addFlexRow(bounds: TakoBounds, gap: Int, buttons: List<ClickableWidget>) {
        val row = TakoFlexContainer<TakoLayoutable>(
            direction = TakoFlexDirection.ROW,
            gap = gap,
            alignItems = TakoCrossAlignment.CENTER
        )
        buttons.forEach { button ->
            addDrawableChild(button)
            val layoutable = button as? TakoLayoutable ?: TakoWidgetLayoutAdapter(button)
            row.add(
                layoutable,
                TakoSizeSpec.Fixed(button.width),
                TakoSizeSpec.Fixed(button.height)
            )
        }
        row.layout(bounds)
    }

    private fun isCompact(): Boolean = width < 390

    private fun footerHeight(coordinator: Boolean): Int =
        if (coordinator && isCompact()) COMPACT_FOOTER_HEIGHT else FOOTER_HEIGHT

    private fun showCaptureButtons(coordinator: Boolean): Boolean =
        coordinator && RvlAddonsConfigStore.config.partyJoinExecutionMode == PartyJoinExecutionMode.AUTOMATIC

    private fun clientListTop(coordinator: Boolean): Int = PANEL_Y + if (coordinator) 76 else 64

    private fun visibleRowCount(total: Int, coordinator: Boolean): Int {
        if (total == 0) return 0
        val capacity = ((height - footerHeight(coordinator) - clientListTop(coordinator)) / ROW_HEIGHT)
            .coerceAtLeast(1)
        return minOf(total, capacity)
    }

    private fun drawCellText(
        context: DrawContext,
        value: String,
        bounds: TakoBounds,
        color: Int,
        alignEnd: Boolean = false,
        centered: Boolean = false
    ) {
        val clipped = textRenderer.trimToWidth(value, bounds.width.coerceAtLeast(0))
        val width = textRenderer.getWidth(clipped)
        val x = when {
            centered -> bounds.x + (bounds.width - width) / 2
            alignEnd -> bounds.x + bounds.width - width
            else -> bounds.x
        }
        val y = bounds.y + (bounds.height - textRenderer.fontHeight) / 2
        context.drawTextWithShadow(textRenderer, Text.literal(clipped), x, y, color)
    }

    private fun resourceState(snapshot: PartyClientSnapshot, compact: Boolean): String = when {
        snapshot.dchienAllowanceFresh && snapshot.dchienRunsRemaining == 0 &&
            snapshot.tlAllowanceFresh && snapshot.tlTicketsRemaining == 0 -> "EXHAUSTED"
        snapshot.dchienAllowanceFresh && snapshot.dchienRunsRemaining == 0 -> if (compact) "DCHIEN 0" else "NO DCHIEN"
        snapshot.tlAllowanceFresh && snapshot.tlTicketsRemaining == 0 -> if (compact) "TL 0" else "NO TL"
        else -> when (snapshot.state.name) {
            "IN_SERVER" -> if (compact) "ONLINE" else "IN SERVER"
            "MAIN_MENU" -> if (compact) "MENU" else "MAIN MENU"
            "DISCONNECTED" -> if (compact) "OFFLINE" else "DISCONNECTED"
            else -> "CONNECTED"
        }
    }

    private fun contains(bounds: TakoBounds, x: Double, y: Double): Boolean =
        x >= bounds.x && x < bounds.x + bounds.width && y >= bounds.y && y < bounds.y + bounds.height

    private fun drawLabel(context: DrawContext, value: String, x: Int, y: Int, maxWidth: Int, color: Int) {
        val visible = textRenderer.trimToWidth(value, maxWidth.coerceAtLeast(0))
        context.drawTextWithShadow(textRenderer, Text.literal(visible), x, y, color)
    }
}

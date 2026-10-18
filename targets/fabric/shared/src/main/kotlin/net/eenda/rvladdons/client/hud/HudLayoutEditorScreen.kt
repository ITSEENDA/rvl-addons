package net.eenda.rvladdons.client.hud

import dev.tako.libs.client.ui.editor.TakoEditCommand
import dev.tako.libs.client.ui.editor.TakoUndoRedoHistory
import dev.tako.libs.client.ui.input.TakoDropdown
import dev.tako.libs.client.ui.widget.TakoButtonIcon
import dev.tako.libs.client.ui.widget.TakoIconButton
import net.eenda.rvladdons.core.*
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class HudLayoutEditorScreen(private val parent: Screen?) : Screen(Text.literal("RVL HUD Editor")) {
    private enum class ResizeHandle {
        NORTH_WEST,
        NORTH_EAST,
        SOUTH_WEST,
        SOUTH_EAST
    }

    private companion object {
        const val GRID_SIZE = 4
        const val SNAP_PADDING = 8
        const val SNAP_THRESHOLD = 6
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2f
    }

    private var selectedComponent = HudComponent.STATUS
    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0
    private var resizeHandle: ResizeHandle? = null
    private var resizeStartRect = HudRect(0, 0, 0, 0)
    private var resizeStartScale = 1f
    private var resizeBaseWidth = 0
    private var resizeBaseHeight = 0
    private val history = TakoUndoRedoHistory()
    private val panelIconButtons = mutableListOf<TakoIconButton>()
    private var undoButton: TakoIconButton? = null
    private var redoButton: TakoIconButton? = null
    private var horizontalAnchorDropdown: TakoDropdown<String>? = null
    private var verticalAnchorDropdown: TakoDropdown<String>? = null
    private var editComponent: HudComponent? = null
    private var editStartLayout: HudLayout? = null

    override fun init() {
        val panelX = width - 210
        val panelY = 44
        val zoomOut = TakoIconButton(TakoButtonIcon.ZOOM_OUT, Text.literal("Zoom out"), width - 78, 92) {
            adjustScale(-0.05f)
        }
        val zoomIn = TakoIconButton(TakoButtonIcon.ZOOM_IN, Text.literal("Zoom in"), width - 48, 92) {
            adjustScale(0.05f)
        }
        panelIconButtons += listOf(zoomOut, zoomIn)
        addDrawableChild(zoomOut)
        addDrawableChild(zoomIn)

        val undo = TakoIconButton(TakoButtonIcon.UNDO, Text.literal("Undo"), panelX + 146, panelY + 4) { history.undo() }
        val redo = TakoIconButton(TakoButtonIcon.REDO, Text.literal("Redo"), panelX + 172, panelY + 4) { history.redo() }
        undoButton = undo
        redoButton = redo
        panelIconButtons += listOf(undo, redo)
        addDrawableChild(undo)
        addDrawableChild(redo)

        val anchor = layoutFor(selectedComponent).anchor
        horizontalAnchorDropdown = TakoDropdown(
            textRenderer,
            listOf("Left", "Center", "Right"),
            { it },
            anchor.ordinal % 3,
            ::setHorizontalAnchor
        )
        verticalAnchorDropdown = TakoDropdown(
            textRenderer,
            listOf("Top", "Center", "Bottom"),
            { it },
            anchor.ordinal / 3,
            ::setVerticalAnchor
        )

        addDrawableChild(ButtonWidget.builder(Text.literal("Save")) {
            RvlAddonsConfigStore.save()
            client?.setScreen(parent)
        }.dimensions(width - 150, height - 32, 70, 20).build())

        addDrawableChild(TakoIconButton(TakoButtonIcon.RESET, Text.literal("Reset selected"), width - 72, height - 32) {
            reset(selectedComponent)
        })

        addDrawableChild(TakoIconButton(TakoButtonIcon.RESET_ALL, Text.literal("Reset all"), width - 42, height - 32) {
            resetAll()
        })
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val minecraft = client ?: return
        undoButton?.active = history.canUndo
        redoButton?.active = history.canRedo
        val selectedAnchor = layoutFor(selectedComponent).anchor
        horizontalAnchorDropdown?.selectedIndex = selectedAnchor.ordinal % 3
        verticalAnchorDropdown?.selectedIndex = selectedAnchor.ordinal / 3
        super.render(context, mouseX, mouseY, delta)
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 20, 0xFFFFFF)

        drawComponent(context, minecraft, HudComponent.STATUS)
        drawComponent(context, minecraft, HudComponent.TRACE)
        drawComponent(context, minecraft, HudComponent.COOLDOWN)
        drawComponent(context, minecraft, HudComponent.COMA)
        drawComponent(context, minecraft, HudComponent.PARTY)
        drawPropertiesPanel(context)
        panelIconButtons.forEach { it.render(context, mouseX, mouseY, delta) }
        renderAnchorDropdowns(context)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && handleAnchorDropdownClick(mouseX, mouseY)) return true
        if (super.mouseClicked(mouseX, mouseY, button)) return true
        if (button != 0) return false

        val selectedRect = componentRect(selectedComponent)
        val selectedHandle = resizeHandleAt(selectedRect, mouseX, mouseY)
        if (selectedHandle != null) {
            editComponent = selectedComponent
            editStartLayout = layoutFor(selectedComponent).copy()
            startResize(selectedHandle, selectedRect)
            return true
        }

        val hit = listOf(
            HudComponent.PARTY,
            HudComponent.COMA,
            HudComponent.COOLDOWN,
            HudComponent.TRACE,
            HudComponent.STATUS
        )
            .firstOrNull { contains(componentRect(it), mouseX, mouseY) }
        if (hit != null) {
            selectedComponent = hit
            editComponent = hit
            editStartLayout = layoutFor(hit).copy()
            val rect = componentRect(hit)
            dragOffsetX = mouseX.toInt() - rect.x
            dragOffsetY = mouseY.toInt() - rect.y
            dragging = true
            return true
        }
        return false
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (button != 0) return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)

        val activeResizeHandle = resizeHandle
        if (activeResizeHandle != null) {
            resize(activeResizeHandle, mouseX.toInt(), mouseY.toInt())
            return true
        }

        if (dragging) {
            val layout = layoutFor(selectedComponent)
            val rect = componentRect(selectedComponent)
            val proposedX = mouseX.toInt() - dragOffsetX
            val proposedY = mouseY.toInt() - dragOffsetY
            val snappedX = snapPosition(proposedX, rect.width, width)
            val snappedY = snapPosition(proposedY, rect.height, height)
            layout.offsetX = HudLayoutMath.offsetXFor(layout.anchor, snappedX, width, rect.width)
            layout.offsetY = HudLayoutMath.offsetYFor(layout.anchor, snappedY, height, rect.height)
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        commitInteraction()
        dragging = false
        resizeHandle = null
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (hasControlDown()) {
            if (keyCode == GLFW.GLFW_KEY_Z) {
                if (hasShiftDown()) history.redo() else history.undo()
                return true
            }
            if (keyCode == GLFW.GLFW_KEY_Y) {
                history.redo()
                return true
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun close() {
        RvlAddonsConfigStore.save()
        client?.setScreen(parent)
    }

    private fun drawComponent(context: DrawContext, minecraft: MinecraftClient, component: HudComponent) {
        val rect = RvlAddonsHudRenderer.drawEditorComponent(
            context,
            minecraft,
            component,
            component == selectedComponent,
            width,
            height
        )
        if (component == selectedComponent) drawResizeHandles(context, rect)
    }

    private fun drawResizeHandles(context: DrawContext, rect: HudRect) {
        val color = 0xFFFFFFFF.toInt()
        val handleSize = 6
        val half = handleSize / 2
        listOf(
            rect.x to rect.y,
            rect.x + rect.width to rect.y,
            rect.x to rect.y + rect.height,
            rect.x + rect.width to rect.y + rect.height
        ).forEach { (x, y) ->
            context.fill(x - half, y - half, x - half + handleSize, y - half + handleSize, color)
        }
    }

    private fun drawPropertiesPanel(context: DrawContext) {
        val layout = layoutFor(selectedComponent)
        val panelX = width - 210
        val panelY = 44
        val panelWidth = 198
        context.fill(panelX, panelY, panelX + panelWidth, panelY + 230, 0xCC101010.toInt())
        context.drawTextWithShadow(
            textRenderer,
            Text.literal(componentName(selectedComponent)),
            panelX + 8,
            panelY + 8,
            0xFFFFFF
        )
        context.drawTextWithShadow(
            textRenderer,
            Text.literal("X: ${layout.offsetX}  Y: ${layout.offsetY}"),
            panelX + 8,
            panelY + 25,
            0xCCCCCC
        )
        context.drawTextWithShadow(
            textRenderer,
            Text.literal("Scale: ${"%.2f".format(layout.scale)}x"),
            panelX + 8,
            panelY + 42,
            0xCCCCCC
        )
        context.drawTextWithShadow(
            textRenderer,
            Text.literal("Anchor: ${anchorLabel(layout.anchor)}"),
            panelX + 8,
            panelY + 59,
            0x999999
        )
        context.drawTextWithShadow(textRenderer, Text.literal("Horizontal"), panelX + 8, panelY + 92, 0x999999)
        context.drawTextWithShadow(textRenderer, Text.literal("Vertical"), panelX + 8, panelY + 130, 0x999999)
        context.drawTextWithShadow(textRenderer, Text.literal("Preview"), panelX + 108, panelY + 92, 0x999999)
        drawAnchorPreview(context, layout.anchor, panelX + 108, panelY + 104)
    }

    private fun drawAnchorPreview(context: DrawContext, anchor: HudAnchor, x: Int, y: Int) {
        val frameWidth = 78
        val frameHeight = 58
        val childWidth = 26
        val childHeight = 12
        val column = anchor.ordinal % 3
        val row = anchor.ordinal / 3
        val childX = when (column) {
            0 -> x + 8
            1 -> x + frameWidth / 2 - childWidth / 2
            else -> x + frameWidth - 8 - childWidth
        }
        val childY = when (row) {
            0 -> y + 7
            1 -> y + frameHeight / 2 - childHeight / 2
            else -> y + frameHeight - 7 - childHeight
        }
        val childCenterX = childX + childWidth / 2
        val childCenterY = childY + childHeight / 2
        val horizontalPin = when (column) {
            0 -> x + 1
            1 -> x + frameWidth / 2
            else -> x + frameWidth - 1
        }
        val childHorizontalPin = when (column) {
            0 -> childX
            1 -> childCenterX
            else -> childX + childWidth
        }
        val verticalPin = when (row) {
            0 -> y + 1
            1 -> y + frameHeight / 2
            else -> y + frameHeight - 1
        }
        val childVerticalPin = when (row) {
            0 -> childY
            1 -> childCenterY
            else -> childY + childHeight
        }
        val pinColor = 0xFF55A9FF.toInt()
        val frameColor = 0xFF596168.toInt()
        val childColor = 0xFFB8C0C6.toInt()

        context.fill(x, y, x + frameWidth, y + frameHeight, 0xFF202428.toInt())
        context.fill(x, y, x + frameWidth, y + 1, frameColor)
        context.fill(x, y + frameHeight - 1, x + frameWidth, y + frameHeight, frameColor)
        context.fill(x, y, x + 1, y + frameHeight, frameColor)
        context.fill(x + frameWidth - 1, y, x + frameWidth, y + frameHeight, frameColor)
        context.fill(
            minOf(horizontalPin, childHorizontalPin),
            childCenterY,
            max(horizontalPin, childHorizontalPin) + 1,
            childCenterY + 1,
            pinColor
        )
        context.fill(
            childCenterX,
            minOf(verticalPin, childVerticalPin),
            childCenterX + 1,
            max(verticalPin, childVerticalPin) + 1,
            pinColor
        )
        context.fill(childX, childY, childX + childWidth, childY + childHeight, 0xFF121619.toInt())
        context.fill(childX, childY, childX + childWidth, childY + 1, childColor)
        context.fill(childX, childY + childHeight - 1, childX + childWidth, childY + childHeight, childColor)
        context.fill(childX, childY, childX + 1, childY + childHeight, childColor)
        context.fill(childX + childWidth - 1, childY, childX + childWidth, childY + childHeight, childColor)
        drawAnchorPin(context, horizontalPin, childCenterY, pinColor)
        drawAnchorPin(context, childHorizontalPin, childCenterY, pinColor)
        drawAnchorPin(context, childCenterX, verticalPin, pinColor)
        drawAnchorPin(context, childCenterX, childVerticalPin, pinColor)
    }

    private fun drawAnchorPin(context: DrawContext, x: Int, y: Int, color: Int) {
        context.fill(x - 2, y - 2, x + 3, y + 3, color)
    }

    private fun renderAnchorDropdowns(context: DrawContext) {
        val horizontal = horizontalAnchorDropdown ?: return
        val vertical = verticalAnchorDropdown ?: return
        val panelX = width - 210
        val panelY = 44
        val x = panelX + 8
        horizontal.render(context, x, panelY + 104, 90)
        vertical.render(context, x, panelY + 142, 90)
    }

    private fun handleAnchorDropdownClick(mouseX: Double, mouseY: Double): Boolean {
        val horizontal = horizontalAnchorDropdown ?: return false
        val vertical = verticalAnchorDropdown ?: return false
        val panelX = width - 210
        val panelY = 44
        val x = panelX + 8
        val dropdownWidth = 90
        val height = 20
        val horizontalY = panelY + 104
        val verticalY = panelY + 142

        fun clickExpanded(dropdown: TakoDropdown<String>, y: Int): Boolean {
            if (!dropdown.expanded) return false
            val inDropdown = mouseX >= x && mouseX < x + dropdownWidth
            val inControl = mouseY >= y && mouseY < y + height
            val inOptions = mouseY >= y + height && mouseY < y + height * (dropdown.items.size + 1)
            if (inDropdown && inControl) {
                dropdown.expanded = false
                return true
            }
            if (inDropdown && inOptions) {
                return dropdown.mouseClicked(mouseX, mouseY, x, y, dropdownWidth, height)
            }
            dropdown.expanded = false
            return false
        }

        if (clickExpanded(horizontal, horizontalY) || clickExpanded(vertical, verticalY)) return true
        if (horizontal.mouseClicked(mouseX, mouseY, x, horizontalY, dropdownWidth, height)) {
            vertical.expanded = false
            return true
        }
        if (vertical.mouseClicked(mouseX, mouseY, x, verticalY, dropdownWidth, height)) {
            horizontal.expanded = false
            return true
        }
        return false
    }

    private fun componentRect(component: HudComponent): HudRect {
        val minecraft = client ?: return HudRect(0, 0, 0, 0)
        return RvlAddonsHudRenderer.componentRect(minecraft, component, width, height)
    }

    private fun layoutFor(component: HudComponent): HudLayout = when (component) {
        HudComponent.STATUS -> RvlAddonsConfigStore.config.hudStatus
        HudComponent.TRACE -> RvlAddonsConfigStore.config.hudTrace
        HudComponent.COOLDOWN -> RvlAddonsConfigStore.config.hudCooldown
        HudComponent.COMA -> RvlAddonsConfigStore.config.hudComa
        HudComponent.PARTY -> RvlAddonsConfigStore.config.hudParty
    }

    private fun componentName(component: HudComponent): String = when (component) {
        HudComponent.STATUS -> "RVL status"
        HudComponent.TRACE -> "Trace status"
        HudComponent.COOLDOWN -> "Cooldown"
        HudComponent.COMA -> "COMA set"
        HudComponent.PARTY -> "Party status"
    }

    private fun contains(rect: HudRect, x: Double, y: Double): Boolean =
        x >= rect.x && x <= rect.x + rect.width && y >= rect.y && y <= rect.y + rect.height

    private fun resizeHandleAt(rect: HudRect, x: Double, y: Double): ResizeHandle? {
        val handleSize = 8
        return when {
            containsHandle(rect.x, rect.y, x, y, handleSize) -> ResizeHandle.NORTH_WEST
            containsHandle(rect.x + rect.width, rect.y, x, y, handleSize) -> ResizeHandle.NORTH_EAST
            containsHandle(rect.x, rect.y + rect.height, x, y, handleSize) -> ResizeHandle.SOUTH_WEST
            containsHandle(rect.x + rect.width, rect.y + rect.height, x, y, handleSize) -> ResizeHandle.SOUTH_EAST
            else -> null
        }
    }

    private fun containsHandle(centerX: Int, centerY: Int, x: Double, y: Double, size: Int): Boolean =
        x >= centerX - size && x <= centerX + size && y >= centerY - size && y <= centerY + size

    private fun startResize(handle: ResizeHandle, rect: HudRect) {
        val layout = layoutFor(selectedComponent)
        resizeHandle = handle
        resizeStartRect = componentRect(selectedComponent)
        resizeStartScale = layout.scale
        resizeBaseWidth = (resizeStartRect.width / resizeStartScale).roundToInt().coerceAtLeast(1)
        resizeBaseHeight = (resizeStartRect.height / resizeStartScale).roundToInt().coerceAtLeast(1)
    }

    private fun resize(handle: ResizeHandle, mouseX: Int, mouseY: Int) {
        val layout = layoutFor(selectedComponent)
        val start = resizeStartRect
        val right = start.x + start.width
        val bottom = start.y + start.height
        val newWidth = when (handle) {
            ResizeHandle.NORTH_WEST, ResizeHandle.SOUTH_WEST -> max(1, right - mouseX)
            ResizeHandle.NORTH_EAST, ResizeHandle.SOUTH_EAST -> max(1, mouseX - start.x)
        }
        val newHeight = when (handle) {
            ResizeHandle.NORTH_WEST, ResizeHandle.NORTH_EAST -> max(1, bottom - mouseY)
            ResizeHandle.SOUTH_WEST, ResizeHandle.SOUTH_EAST -> max(1, mouseY - start.y)
        }
        val factor = max(
            newWidth.toFloat() / resizeBaseWidth,
            newHeight.toFloat() / resizeBaseHeight
        )
        val scale = (resizeStartScale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val componentWidth = (resizeBaseWidth * scale).roundToInt()
        val componentHeight = (resizeBaseHeight * scale).roundToInt()
        val newX = when (handle) {
            ResizeHandle.NORTH_WEST, ResizeHandle.SOUTH_WEST -> right - componentWidth
            ResizeHandle.NORTH_EAST, ResizeHandle.SOUTH_EAST -> start.x
        }
        val newY = when (handle) {
            ResizeHandle.NORTH_WEST, ResizeHandle.NORTH_EAST -> bottom - componentHeight
            ResizeHandle.SOUTH_WEST, ResizeHandle.SOUTH_EAST -> start.y
        }

        layout.scale = scale
        val snappedX = snapPosition(newX, componentWidth, width)
        val snappedY = snapPosition(newY, componentHeight, height)
        layout.offsetX = HudLayoutMath.offsetXFor(layout.anchor, snappedX, width, componentWidth)
        layout.offsetY = HudLayoutMath.offsetYFor(layout.anchor, snappedY, height, componentHeight)
    }

    private fun snapPosition(value: Int, componentSize: Int, screenSize: Int): Int {
        val candidates = listOf(
            SNAP_PADDING,
            screenSize / 2 - componentSize / 2,
            screenSize - componentSize - SNAP_PADDING,
            (value.toFloat() / GRID_SIZE).roundToInt() * GRID_SIZE
        )
        val nearest = candidates.minByOrNull { abs(it - value) } ?: value
        return if (abs(nearest - value) <= SNAP_THRESHOLD) {
            nearest.coerceIn(0, (screenSize - componentSize).coerceAtLeast(0))
        } else {
            value.coerceIn(0, (screenSize - componentSize).coerceAtLeast(0))
        }
    }

    private fun adjustScale(delta: Float) {
        editLayouts(listOf(selectedComponent)) { layout ->
            layout.scale = (layout.scale + delta).coerceIn(MIN_SCALE, MAX_SCALE)
        }
    }

    private fun setAnchor(anchor: HudAnchor) {
        val component = selectedComponent
        val rect = componentRect(component)
        editLayouts(listOf(component)) { layout ->
            layout.anchor = anchor
            layout.offsetX = HudLayoutMath.offsetXFor(anchor, rect.x, width, rect.width)
            layout.offsetY = HudLayoutMath.offsetYFor(anchor, rect.y, height, rect.height)
        }
    }

    private fun setHorizontalAnchor(alignment: String) {
        val current = layoutFor(selectedComponent).anchor
        val row = current.ordinal / 3
        val column = when (alignment) {
            "Left" -> 0
            "Center" -> 1
            else -> 2
        }
        setAnchor(HudAnchor.entries[row * 3 + column])
    }

    private fun setVerticalAnchor(alignment: String) {
        val current = layoutFor(selectedComponent).anchor
        val row = when (alignment) {
            "Top" -> 0
            "Center" -> 1
            else -> 2
        }
        val column = current.ordinal % 3
        setAnchor(HudAnchor.entries[row * 3 + column])
    }

    private fun horizontalAnchorLabel(anchor: HudAnchor): String = when (anchor.ordinal % 3) {
        0 -> "Left"
        1 -> "Center"
        else -> "Right"
    }

    private fun verticalAnchorLabel(anchor: HudAnchor): String = when (anchor.ordinal / 3) {
        0 -> "Top"
        1 -> "Center"
        else -> "Bottom"
    }

    private fun anchorLabel(anchor: HudAnchor): String =
        "${verticalAnchorLabel(anchor)} ${horizontalAnchorLabel(anchor)}"

    private fun reset(component: HudComponent) {
        editLayouts(listOf(component)) { layout ->
            layout.anchor = HudAnchor.TOP_LEFT
            layout.offsetX = 8
            layout.offsetY = defaultOffsetY(component)
            layout.scale = 1f
        }
    }

    private fun resetAll() {
        editLayouts(HudComponent.entries) { component, layout ->
            layout.anchor = HudAnchor.TOP_LEFT
            layout.offsetX = 8
            layout.offsetY = defaultOffsetY(component)
            layout.scale = 1f
        }
    }

    private fun defaultOffsetY(component: HudComponent): Int = when (component) {
        HudComponent.STATUS -> 8
        HudComponent.TRACE -> 26
        HudComponent.COOLDOWN -> 46
        HudComponent.COMA -> 70
        HudComponent.PARTY -> 94
    }

    private fun editLayouts(
        components: Iterable<HudComponent>,
        change: (HudLayout) -> Unit
    ) = editLayouts(components) { _, layout -> change(layout) }

    private fun editLayouts(
        components: Iterable<HudComponent>,
        change: (HudComponent, HudLayout) -> Unit
    ) {
        val componentList = components.toList()
        val before = componentList.associateWith { layoutFor(it).copy() }
        componentList.forEach { change(it, layoutFor(it)) }
        val after = componentList.associateWith { layoutFor(it).copy() }
        recordLayoutChange(before, after)
    }

    private fun commitInteraction() {
        val component = editComponent ?: return
        val before = editStartLayout ?: return
        recordLayoutChange(
            mapOf(component to before),
            mapOf(component to layoutFor(component).copy())
        )
        editComponent = null
        editStartLayout = null
    }

    private fun recordLayoutChange(
        before: Map<HudComponent, HudLayout>,
        after: Map<HudComponent, HudLayout>
    ) {
        if (before == after) return
        applyLayouts(before)
        history.execute(
            TakoEditCommand(
                redoAction = { applyLayouts(after) },
                undoAction = { applyLayouts(before) }
            ))
    }

    private fun applyLayouts(snapshot: Map<HudComponent, HudLayout>) {
        snapshot.forEach { (component, source) ->
            val target = layoutFor(component)
            target.anchor = source.anchor
            target.offsetX = source.offsetX
            target.offsetY = source.offsetY
            target.scale = source.scale
        }
    }
}


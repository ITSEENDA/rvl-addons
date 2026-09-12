package net.eenda.rvladdons.client.hud

import net.eenda.rvladdons.core.HudAnchor
import net.eenda.rvladdons.core.HudComponent
import net.eenda.rvladdons.core.HudLayout
import net.eenda.rvladdons.core.HudRect
import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
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

    override fun init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("-")) {
            adjustScale(-0.05f)
        }.dimensions(width - 78, 92, 24, 20).build())

        addDrawableChild(ButtonWidget.builder(Text.literal("+")) {
            adjustScale(0.05f)
        }.dimensions(width - 48, 92, 24, 20).build())

        addDrawableChild(ButtonWidget.builder(Text.literal("Save")) {
            RvlAddonsConfigStore.save()
            client?.setScreen(parent)
        }.dimensions(width - 260, height - 32, 70, 20).build())

        addDrawableChild(ButtonWidget.builder(Text.literal("Reset selected")) {
            reset(selectedComponent)
        }.dimensions(width - 185, height - 32, 90, 20).build())

        addDrawableChild(ButtonWidget.builder(Text.literal("Reset all")) {
            reset(HudComponent.STATUS)
            reset(HudComponent.TRACE)
            reset(HudComponent.COOLDOWN)
            reset(HudComponent.COMA)
        }.dimensions(width - 90, height - 32, 78, 20).build())
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val minecraft = client ?: return
        super.render(context, mouseX, mouseY, delta)
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 20, 0xFFFFFF)

        drawComponent(context, minecraft, HudComponent.STATUS)
        drawComponent(context, minecraft, HudComponent.TRACE)
        drawComponent(context, minecraft, HudComponent.COOLDOWN)
        drawComponent(context, minecraft, HudComponent.COMA)
        drawPropertiesPanel(context)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (super.mouseClicked(mouseX, mouseY, button)) return true
        if (button != 0) return false

        val selectedRect = componentRect(selectedComponent)
        val selectedHandle = resizeHandleAt(selectedRect, mouseX, mouseY)
        if (selectedHandle != null) {
            startResize(selectedHandle, selectedRect)
            return true
        }

        val hit = listOf(HudComponent.COMA, HudComponent.COOLDOWN, HudComponent.TRACE, HudComponent.STATUS)
            .firstOrNull { contains(componentRect(it), mouseX, mouseY) }
        if (hit != null) {
            selectedComponent = hit
            val rect = componentRect(hit)
            dragOffsetX = mouseX.toInt() - rect.x
            dragOffsetY = mouseY.toInt() - rect.y
            dragging = true
            layoutFor(hit).anchor = HudAnchor.TOP_LEFT
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
            layout.offsetX = snapPosition(proposedX, rect.width, width)
            layout.offsetY = snapPosition(proposedY, rect.height, height)
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        dragging = false
        resizeHandle = null
        return super.mouseReleased(mouseX, mouseY, button)
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
        context.fill(panelX, panelY, panelX + panelWidth, panelY + 76, 0xCC101010.toInt())
        context.drawTextWithShadow(textRenderer, Text.literal(componentName(selectedComponent)), panelX + 8, panelY + 8, 0xFFFFFF)
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
            Text.literal("Anchor: ${layout.anchor.name}"),
            panelX + 8,
            panelY + 59,
            0x999999
        )
        context.drawTextWithShadow(textRenderer, Text.literal("- / +"), panelX + 132, panelY + 78, 0x999999)
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
    }

    private fun componentName(component: HudComponent): String = when (component) {
        HudComponent.STATUS -> "RVL status"
        HudComponent.TRACE -> "Trace status"
        HudComponent.COOLDOWN -> "Cooldown"
        HudComponent.COMA -> "COMA set"
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
        layout.anchor = HudAnchor.TOP_LEFT
        layout.offsetX = rect.x
        layout.offsetY = rect.y
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
        layout.offsetX = snapPosition(newX, componentWidth, width)
        layout.offsetY = snapPosition(newY, componentHeight, height)
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
        val layout = layoutFor(selectedComponent)
        layout.scale = (layout.scale + delta).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    private fun reset(component: HudComponent) {
        val layout = layoutFor(component)
        layout.anchor = HudAnchor.TOP_LEFT
        layout.offsetX = 8
        layout.offsetY = when (component) {
            HudComponent.STATUS -> 8
            HudComponent.TRACE -> 26
            HudComponent.COOLDOWN -> 46
            HudComponent.COMA -> 70
        }
        layout.scale = 1f
    }
}

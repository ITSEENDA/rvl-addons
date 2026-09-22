package net.eenda.rvladdons.client.hud

import net.eenda.rvladdons.RvlAddonsClient
import net.eenda.rvladdons.client.coma.ComaSwapController
import net.eenda.rvladdons.client.cooldown.CooldownController
import net.eenda.rvladdons.client.cooldown.CooldownHudRenderer
import dev.tako.libs.client.ui.feedback.TakoToastManager
import net.eenda.rvladdons.core.HudComponent
import net.eenda.rvladdons.core.HudLayout
import net.eenda.rvladdons.core.HudLayoutMath
import net.eenda.rvladdons.core.HudRect
import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.eenda.rvladdons.core.RvlAddonsTrace
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import kotlin.math.roundToInt

object RvlAddonsHudRenderer {
    private const val COOLDOWN_WIDTH = 260
    private const val COOLDOWN_HEIGHT = 34
    private const val COMA_ITEM_SIZE = 18
    private const val COMA_ITEM_GAP = 2

    fun register() {
        HudRenderCallback.EVENT.register { context, _ ->
            val client = MinecraftClient.getInstance()
            val config = RvlAddonsConfigStore.config
            if (client.world == null) return@register
            TakoToastManager.render(context, client)

            if (config.hudStatusVisible && RvlAddonsClient.isGameplayServerActive()) {
                drawComponent(context, client, HudComponent.STATUS)
            }

            if (config.hudTraceVisible) {
                drawComponent(context, client, HudComponent.TRACE)
            }

            if (config.hudCooldownVisible && RvlAddonsClient.isGameplayServerActive() && CooldownController.hasActive()) {
                drawComponent(context, client, HudComponent.COOLDOWN)
            }

            if (config.hudComaVisible && RvlAddonsClient.isGameplayServerActive()) {
                drawComponent(context, client, HudComponent.COMA)
            }
        }
    }

    fun drawEditorComponent(
        context: DrawContext,
        client: MinecraftClient,
        component: HudComponent,
        selected: Boolean,
        screenWidth: Int = client.window.scaledWidth,
        screenHeight: Int = client.window.scaledHeight
    ): HudRect {
        val rect = componentRect(client, component, screenWidth, screenHeight)
        if (selected) {
            val color = 0xFFFFFF00.toInt()
            context.fill(rect.x - 3, rect.y - 3, rect.x + rect.width + 3, rect.y - 1, color)
            context.fill(rect.x - 3, rect.y + rect.height + 1, rect.x + rect.width + 3, rect.y + rect.height + 3, color)
            context.fill(rect.x - 3, rect.y - 1, rect.x - 1, rect.y + rect.height + 1, color)
            context.fill(rect.x + rect.width + 1, rect.y - 1, rect.x + rect.width + 3, rect.y + rect.height + 1, color)
        }
        drawComponentAt(context, client, component, rect)
        return rect
    }

    fun componentRect(client: MinecraftClient, component: HudComponent, screenWidth: Int = client.window.scaledWidth, screenHeight: Int = client.window.scaledHeight): HudRect {
        val layout = layoutFor(component)
        val (baseWidth, baseHeight) = baseSize(client, component)
        return HudLayoutMath.resolve(
            layout,
            screenWidth,
            screenHeight,
            (baseWidth * layout.scale).roundToInt(),
            (baseHeight * layout.scale).roundToInt()
        )
    }

    private fun drawComponent(context: DrawContext, client: MinecraftClient, component: HudComponent) {
        drawComponentAt(context, client, component, componentRect(client, component))
    }

    private fun drawComponentAt(context: DrawContext, client: MinecraftClient, component: HudComponent, rect: HudRect) {
        val layout = layoutFor(component)
        context.matrices.push()
        context.matrices.translate(rect.x.toFloat(), rect.y.toFloat(), 0f)
        context.matrices.scale(layout.scale, layout.scale, 1f)

        when (component) {
            HudComponent.STATUS -> context.drawTextWithShadow(client.textRenderer, Text.literal("RVL: ACTIVE"), 0, 0, 0x55FF55)
            HudComponent.TRACE -> {
                val text = Text.literal(if (RvlAddonsTrace.isEnabled()) "TRACE: ON" else "TRACE: OFF")
                val color = if (RvlAddonsTrace.isEnabled()) 0x55FF55 else 0xFFAA55
                context.drawTextWithShadow(client.textRenderer, text, 0, 0, color)
            }
            HudComponent.COOLDOWN -> {
                if (CooldownController.hasActive()) {
                    CooldownHudRenderer.render(context, client)
                } else {
                    drawCooldownPreview(context, client)
                }
            }
            HudComponent.COMA -> {
                context.drawTextWithShadow(client.textRenderer, Text.literal(ComaSwapController.hudText()), 0, 0, 0xFFAA55)
                val stacks = ComaSwapController.hudStacks(client)
                val itemY = client.textRenderer.fontHeight + 2
                stacks.forEachIndexed { index, stack ->
                    val x = index * (COMA_ITEM_SIZE + COMA_ITEM_GAP)
                    context.fill(x, itemY, x + COMA_ITEM_SIZE, itemY + COMA_ITEM_SIZE, 0xAA202020.toInt())
                    if (stack != null && !stack.isEmpty) {
                        context.drawItem(stack, x + 1, itemY + 1)
                    }
                }
            }
        }

        context.matrices.pop()
    }

    private fun drawCooldownPreview(context: DrawContext, client: MinecraftClient) {
        val progress = 0.8f
        context.fill(0, 0, COOLDOWN_WIDTH, COOLDOWN_HEIGHT, 0xAA101010.toInt())
        context.fill(0, 0, (COOLDOWN_WIDTH * progress).roundToInt(), 3, 0xFF55AAFF.toInt())
        context.drawTextWithShadow(client.textRenderer, Text.literal("Crimson Katana"), 6, 7, 0xFFFFFF)
        context.drawTextWithShadow(client.textRenderer, Text.literal("[RMB]  0.6s"), 6, 20, 0xAAAAAA)
    }

    private fun baseSize(client: MinecraftClient, component: HudComponent): Pair<Int, Int> = when (component) {
        HudComponent.STATUS -> client.textRenderer.getWidth("RVL: ACTIVE") to client.textRenderer.fontHeight
        HudComponent.TRACE -> client.textRenderer.getWidth("TRACE: ON") to client.textRenderer.fontHeight
        HudComponent.COOLDOWN -> if (CooldownController.hasActive()) {
            CooldownHudRenderer.panelSize(client)
        } else {
            COOLDOWN_WIDTH to COOLDOWN_HEIGHT
        }
        HudComponent.COMA -> {
            val stacks = ComaSwapController.hudStacks(client)
            val itemWidth = if (stacks.isEmpty()) 0 else
                stacks.size * COMA_ITEM_SIZE + (stacks.size - 1) * COMA_ITEM_GAP
            maxOf(itemWidth, client.textRenderer.getWidth(ComaSwapController.hudText())) to
                (client.textRenderer.fontHeight + COMA_ITEM_SIZE + 2)
        }
    }

    private fun layoutFor(component: HudComponent): HudLayout = when (component) {
        HudComponent.STATUS -> RvlAddonsConfigStore.config.hudStatus
        HudComponent.TRACE -> RvlAddonsConfigStore.config.hudTrace
        HudComponent.COOLDOWN -> RvlAddonsConfigStore.config.hudCooldown
        HudComponent.COMA -> RvlAddonsConfigStore.config.hudComa
    }
}

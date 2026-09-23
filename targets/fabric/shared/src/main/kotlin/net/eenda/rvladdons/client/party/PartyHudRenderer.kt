package net.eenda.rvladdons.client.party

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

object PartyHudRenderer {
    fun register() {
        HudRenderCallback.EVENT.register { context, _ ->
            val client = MinecraftClient.getInstance()
            if (client.world == null) return@register
            val status = LocalPartyController.statusText()
            if (status.isBlank()) return@register
            context.fill(8, 8, client.window.scaledWidth.coerceAtMost(330), 27, 0xAA101010.toInt())
            context.drawTextWithShadow(client.textRenderer, Text.literal(status), 14, 14, 0xFF66CCFF.toInt())
        }
    }
}


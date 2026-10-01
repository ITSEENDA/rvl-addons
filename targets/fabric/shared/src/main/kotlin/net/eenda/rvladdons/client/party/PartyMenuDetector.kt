package net.eenda.rvladdons.client.party

import net.eenda.rvladdons.feature.party.PartyMenuStage
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.item.ItemStack
import net.minecraft.screen.ScreenHandler

/** Reads menu identity and stable item targets without owning join state. */
internal class PartyMenuDetector(private val client: () -> MinecraftClient?) {
    fun handler(): ScreenHandler? {
        val current = client() ?: return null
        if (current.currentScreen !is HandledScreen<*>) return null
        return current.player?.currentScreenHandler
    }

    fun fingerprint(): String? {
        val current = client() ?: return null
        val screen = current.currentScreen as? HandledScreen<*> ?: return null
        val handler = handler() ?: return null
        val menuSlots = menuSlots(handler)
        val items = menuSlots.joinToString("|") { slot ->
            val stack = slot.stack
            if (stack.isEmpty) "empty" else "${stack.name.string}:${stack.count}"
        }
        return "${screen.title.string}|${menuSlots.size}|$items"
    }

    fun stage(typeFingerprint: String?): PartyMenuStage? {
        val current = fingerprint() ?: return null
        return if (typeFingerprint == null || current == typeFingerprint) {
            PartyMenuStage.TYPE_MENU
        } else {
            PartyMenuStage.ROOM_MENU
        }
    }

    fun itemSignature(slot: Int): String {
        val stack = handler()?.slots?.getOrNull(slot)?.stack ?: return ""
        return stackSignature(stack)
    }

    fun hasMenuItem(slot: Int): Boolean =
        menuSlots(handler()).getOrNull(slot)?.stack?.isEmpty == false

    fun findMenuItem(signature: String, fallback: Int): Int {
        if (signature.isBlank()) return fallback
        return menuSlots(handler()).indexOfFirst {
            stackSignature(it.stack) == signature
        }.takeIf { it >= 0 } ?: fallback
    }

    fun slotCount(): Int = handler()?.slots?.size ?: 0

    private fun menuSlots(handler: ScreenHandler?): List<net.minecraft.screen.slot.Slot> {
        if (handler == null) return emptyList()
        return if (handler.slots.size > 36) handler.slots.dropLast(36) else handler.slots
    }

    private fun stackSignature(stack: ItemStack): String =
        if (stack.isEmpty) "" else stack.name.string.trim().lowercase()
}

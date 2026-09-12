package net.eenda.rvladdons

import net.eenda.rvladdons.core.GameplayServerState
import net.eenda.rvladdons.core.ModEnabledState
import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.eenda.rvladdons.core.RvlAddonsTrace
import net.eenda.rvladdons.client.coma.ComaSwapController
import net.eenda.rvladdons.client.config.RvlAddonsConfigScreen
import net.eenda.rvladdons.client.hud.RvlAddonsHudRenderer
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

object RvlAddonsClient : ClientModInitializer {
    private val logger = LoggerFactory.getLogger("rvl-addons/gameplay-detector")
    private var lastReportedState: Boolean? = null
    private lateinit var traceKey: KeyBinding
    private lateinit var modToggleKey: KeyBinding
    private lateinit var settingsKey: KeyBinding
    private lateinit var comaSwapKey: KeyBinding

    override fun onInitializeClient() {
        RvlAddonsConfigStore.load(FabricLoader.getInstance().configDir.resolve("rvl-addons.properties"))
        ModEnabledState.set(RvlAddonsConfigStore.config.enabled)
        RvlAddonsHudRenderer.register()

        traceKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.rvl-addons.toggle_trace",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                "category.rvl-addons"
            )
        )
        modToggleKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.rvl-addons.toggle_mod",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F9,
                "category.rvl-addons"
            )
        )
        settingsKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.rvl-addons.open_settings",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F7,
                "category.rvl-addons"
            )
        )
        comaSwapKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.rvl-addons.swap_coma_set",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "category.rvl-addons"
            )
        )

        ClientSendMessageEvents.CHAT.register { message ->
            RvlAddonsTrace.log("chat-out", message)
        }

        ClientSendMessageEvents.COMMAND.register { command ->
            RvlAddonsTrace.log("command-out", command)
        }

        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            ComaSwapController.reset()
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            ComaSwapController.reset()
            updateRvlServerState(false)
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (traceKey.wasPressed()) {
                toggleTrace()
            }
            while (modToggleKey.wasPressed()) {
                toggleMod()
            }
            while (settingsKey.wasPressed()) {
                client.setScreen(RvlAddonsConfigScreen.create(client.currentScreen))
            }
            while (comaSwapKey.wasPressed()) {
                ComaSwapController.requestSwap(client)
            }
            ComaSwapController.tick(client)
        }
    }

    fun isGameplayServerActive(): Boolean =
        ModEnabledState.isEnabled() && GameplayServerState.isActive()

    fun updateRvlServerState(active: Boolean) {
        MinecraftClient.getInstance().execute {
            setGameplayServerActive(active)
        }
    }

    private fun setGameplayServerActive(active: Boolean) {
        if (lastReportedState != active) {
            logger.info("Gameplay server active: {}", active)
            RvlAddonsTrace.log("state", "gameplayServerActive=$active")
            MinecraftClient.getInstance().inGameHud.setOverlayMessage(
                Text.literal(if (active) "RVL server detected" else "Non-RVL server - inactive"),
                false
            )
            lastReportedState = active
        }
        GameplayServerState.update(active)
    }

    private fun toggleMod() {
        val enabled = ModEnabledState.toggle()
        RvlAddonsConfigStore.config.enabled = enabled
        RvlAddonsConfigStore.save()
        logger.info("RVL Addons enabled: {}", enabled)
        RvlAddonsTrace.log("mod-state", "enabled=$enabled")
        MinecraftClient.getInstance().inGameHud.setOverlayMessage(
            Text.literal(if (enabled) "RVL Addons: ENABLED" else "RVL Addons: DISABLED"),
            false
        )
    }

    private fun toggleTrace() {
        try {
            if (RvlAddonsTrace.isEnabled()) {
                RvlAddonsTrace.disable()
                logger.info("Packet trace disabled")
            } else {
                RvlAddonsTrace.enable(FabricLoader.getInstance().gameDir)
                logger.info("Packet trace enabled: logs/rvl-addons-trace.log")
            }
            MinecraftClient.getInstance().inGameHud.setOverlayMessage(
                Text.literal(if (RvlAddonsTrace.isEnabled()) "RVL Trace: ON" else "RVL Trace: OFF"),
                false
            )
        } catch (error: Exception) {
            logger.error("Could not toggle packet trace", error)
        }
    }

}

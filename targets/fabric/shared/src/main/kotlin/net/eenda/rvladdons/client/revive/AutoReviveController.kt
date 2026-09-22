package net.eenda.rvladdons.client.revive

import net.eenda.rvladdons.core.GameplayServerState
import net.eenda.rvladdons.core.ModEnabledState
import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.eenda.rvladdons.feature.revive.AutoReviveMode
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.text.Text
import net.minecraft.util.math.Vec3d
import org.lwjgl.glfw.GLFW

object AutoReviveController {
    private enum class Phase {
        IDLE,
        WAITING_RESPAWN_DELAY,
        WAITING_RESPAWN,
        WAITING_BACK_DELAY,
        WAITING_BACK
    }

    private const val BACK_TIMEOUT_MS = 3_000L
    private const val TELEPORT_DISTANCE_SQUARED = 1.0

    private var phase = Phase.IDLE
    private var phaseStartedAt = 0L
    private var positionBeforeBack: Vec3d? = null
    private var soundWasDead = false
    private lateinit var autoReviveKey: KeyBinding

    fun registerKeyBinding() {
        autoReviveKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                "key.rvl-addons.toggle_auto_revive",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F10,
                "category.rvl-addons"
            )
        )
    }

    fun handleKeyBinding() {
        while (autoReviveKey.wasPressed()) {
            val config = RvlAddonsConfigStore.config
            config.autoRevive.enabled = !config.autoRevive.enabled
            RvlAddonsConfigStore.save()
            resetAutoOnly()
            MinecraftClient.getInstance().inGameHud.setOverlayMessage(
                Text.literal(if (config.autoRevive.enabled) "Fast revive: ENABLED" else "Fast revive: DISABLED"),
                false
            )
        }
    }

    private fun gameplayServerActive(): Boolean =
        ModEnabledState.isEnabled() && GameplayServerState.isActive()

    fun tick(client: MinecraftClient) {
        val config = RvlAddonsConfigStore.config
        val player = client.player ?: run {
            reset()
            return
        }
        val now = System.currentTimeMillis()
        val dead = player.isDead || player.health <= 0f
        observeRespawnSound(client, config, dead)

        if (!config.autoRevive.enabled || !gameplayServerActive()) {
            resetAutoState()
            return
        }

        if (dead) {
            when (phase) {
                Phase.IDLE -> {
                    phaseStartedAt = now
                    if (config.autoRevive.respawnDelayMs > 0) {
                        phase = Phase.WAITING_RESPAWN_DELAY
                    } else {
                        player.requestRespawn()
                        phase = Phase.WAITING_RESPAWN
                    }
                }
                Phase.WAITING_RESPAWN_DELAY -> {
                    if (now - phaseStartedAt >= config.autoRevive.respawnDelayMs) {
                        player.requestRespawn()
                        phase = Phase.WAITING_RESPAWN
                        phaseStartedAt = now
                    }
                }
                else -> Unit
            }
            return
        }

        if (phase == Phase.IDLE) return

        when (phase) {
            Phase.WAITING_RESPAWN -> {
                if (config.autoRevive.mode == AutoReviveMode.INSTANT) {
                    playAudioIfEnabled(client)
                    resetPhase()
                } else {
                    positionBeforeBack = player.pos
                    if (config.autoRevive.backDelayMs > 0) {
                        phase = Phase.WAITING_BACK_DELAY
                        phaseStartedAt = now
                    } else {
                        sendBack(player, now)
                    }
                }
            }
            Phase.WAITING_BACK_DELAY -> {
                if (now - phaseStartedAt >= config.autoRevive.backDelayMs) {
                    sendBack(player, now)
                }
            }
            Phase.WAITING_BACK -> {
                val moved = positionBeforeBack?.squaredDistanceTo(player.pos)
                    ?.let { it >= TELEPORT_DISTANCE_SQUARED }
                    ?: false
                if (moved) {
                    playAudioIfEnabled(client)
                    resetPhase()
                } else if (now - phaseStartedAt >= BACK_TIMEOUT_MS) {
                    resetPhase()
                }
            }
            Phase.IDLE, Phase.WAITING_RESPAWN_DELAY -> Unit
        }
    }

    fun reset() {
        resetPhase()
        soundWasDead = false
    }

    fun resetAutoOnly() {
        resetAutoState()
    }

    private fun resetPhase() {
        phase = Phase.IDLE
        phaseStartedAt = 0L
        positionBeforeBack = null
    }

    private fun resetAutoState() {
        resetPhase()
    }

    private fun sendBack(player: ClientPlayerEntity, now: Long) {
        player.networkHandler.sendChatCommand("back")
        phase = Phase.WAITING_BACK
        phaseStartedAt = now
    }

    private fun playAudioIfEnabled(client: MinecraftClient) {
        val config = RvlAddonsConfigStore.config
        if (config.autoRevive.soundEnabled) {
            client.soundManager.playNextTick(AutoReviveAudio(client, config.autoRevive.soundVolume))
        }
    }

    private fun observeRespawnSound(
        client: MinecraftClient,
        config: net.eenda.rvladdons.core.RvlAddonsConfig,
        dead: Boolean
    ) {
        if (dead) {
            soundWasDead = true
            return
        }
        if (soundWasDead) {
            soundWasDead = false
            if (!config.autoRevive.enabled && gameplayServerActive()) {
                playAudioIfEnabled(client)
            }
        }
    }
}

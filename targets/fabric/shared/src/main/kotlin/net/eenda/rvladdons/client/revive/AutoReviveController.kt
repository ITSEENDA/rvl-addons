package net.eenda.rvladdons.client.revive

import net.eenda.rvladdons.RvlAddonsClient
import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.eenda.rvladdons.feature.revive.AutoReviveMode
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.util.math.Vec3d

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

    fun tick(client: MinecraftClient) {
        val config = RvlAddonsConfigStore.config
        val player = client.player ?: run {
            reset()
            return
        }
        val now = System.currentTimeMillis()
        val dead = player.isDead || player.health <= 0f
        observeRespawnSound(client, config, dead)

        if (!config.autoReviveEnabled || !RvlAddonsClient.isGameplayServerActive()) {
            resetAutoState()
            return
        }

        if (dead) {
            when (phase) {
                Phase.IDLE -> {
                    phaseStartedAt = now
                    if (config.autoReviveRespawnDelayMs > 0) {
                        phase = Phase.WAITING_RESPAWN_DELAY
                    } else {
                        player.requestRespawn()
                        phase = Phase.WAITING_RESPAWN
                    }
                }
                Phase.WAITING_RESPAWN_DELAY -> {
                    if (now - phaseStartedAt >= config.autoReviveRespawnDelayMs) {
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
                if (config.autoReviveMode == AutoReviveMode.INSTANT) {
                    playAudioIfEnabled(client)
                    resetPhase()
                } else {
                    positionBeforeBack = player.pos
                    if (config.autoReviveBackDelayMs > 0) {
                        phase = Phase.WAITING_BACK_DELAY
                        phaseStartedAt = now
                    } else {
                        sendBack(player, now)
                    }
                }
            }
            Phase.WAITING_BACK_DELAY -> {
                if (now - phaseStartedAt >= config.autoReviveBackDelayMs) {
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
        if (config.rbdSoundEnabled) {
            client.soundManager.playNextTick(AutoReviveAudio(client, config.autoReviveSoundVolume))
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
            if (!config.autoReviveEnabled && RvlAddonsClient.isGameplayServerActive()) {
                playAudioIfEnabled(client)
            }
        }
    }
}

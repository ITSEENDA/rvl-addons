package net.eenda.rvladdons.client.revive

import net.minecraft.client.MinecraftClient
import net.minecraft.client.sound.MovingSoundInstance
import net.minecraft.client.sound.SoundInstance
import net.minecraft.sound.SoundCategory
import kotlin.math.PI
import kotlin.math.cos

class AutoReviveAudio(private val client: MinecraftClient, private val baseVolume: Float) : MovingSoundInstance(
    RvlSoundEvents.AUTO_REVIVE_RBD,
    SoundCategory.PLAYERS,
    SoundInstance.createRandom()
) {
    private companion object {
        const val DURATION_TICKS = 664
        const val FADE_START = 0.3f
    }

    private var age = 0

    init {
        repeat = false
        relative = true
        volume = baseVolume.coerceIn(0f, 1f)
        pitch = 1f
    }

    override fun tick() {
        val player = client.player ?: run {
            setDone()
            return
        }
        x = player.x
        y = player.y
        z = player.z
        age++

        val progress = (age.toFloat() / DURATION_TICKS).coerceIn(0f, 1f)
        volume = if (progress <= FADE_START) {
            baseVolume.coerceIn(0f, 1f)
        } else {
            val fadeProgress = ((progress - FADE_START) / (1f - FADE_START)).coerceIn(0f, 1f)
            (baseVolume.coerceIn(0f, 1f) * cos(fadeProgress * (PI / 2.0))).toFloat().coerceAtLeast(0f)
        }
        if (age >= DURATION_TICKS) setDone()
    }
}

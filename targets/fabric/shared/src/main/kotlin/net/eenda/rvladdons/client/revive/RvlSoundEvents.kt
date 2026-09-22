package net.eenda.rvladdons.client.revive

import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.sound.SoundEvent
import net.minecraft.util.Identifier

object RvlSoundEvents {
    private val AUTO_REVIVE_ID = Identifier.tryParse("rvl-addons:auto_revive_rbd")
        ?: error("Invalid auto revive sound id")

    lateinit var AUTO_REVIVE_RBD: SoundEvent
        private set

    fun register() {
        if (!::AUTO_REVIVE_RBD.isInitialized) {
            AUTO_REVIVE_RBD = Registry.register(
                Registries.SOUND_EVENT,
                AUTO_REVIVE_ID,
                SoundEvent.of(AUTO_REVIVE_ID)
            )
        }
    }
}

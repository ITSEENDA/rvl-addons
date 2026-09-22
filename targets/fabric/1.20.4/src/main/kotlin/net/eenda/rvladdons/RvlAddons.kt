package net.eenda.rvladdons

import net.eenda.rvladdons.core.RvlAddonsCore
import net.eenda.rvladdons.client.revive.RvlSoundEvents
import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

object RvlAddons : ModInitializer {
    private val logger = LoggerFactory.getLogger(RvlAddonsCore.MOD_ID)

	override fun onInitialize() {
		RvlSoundEvents.register()
		logger.info("Hello Fabric world!")
	}
}

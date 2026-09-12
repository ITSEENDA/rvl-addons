package net.eenda.rvladdons

import net.eenda.rvladdons.core.RvlAddonsCore
import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

object RvlAddons : ModInitializer {
    private val logger = LoggerFactory.getLogger(RvlAddonsCore.MOD_ID)

	override fun onInitialize() {
		logger.info("Hello Fabric world!")
	}
}

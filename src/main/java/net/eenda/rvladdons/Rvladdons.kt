package net.eenda.rvladdons

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

object Rvladdons : ModInitializer {
    private val logger = LoggerFactory.getLogger("rvl-addons")

	override fun onInitialize() {
		logger.info("Hello Fabric world!")
	}
}
package net.eenda.rvladdons.integration.modmenu

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import net.eenda.rvladdons.client.config.RvlAddonsConfigScreen
import net.minecraft.client.gui.screen.Screen

object RvlAddonsModMenu : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<Screen> =
        ConfigScreenFactory { parent -> RvlAddonsConfigScreen.create(parent) }
}

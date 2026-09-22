package net.eenda.rvladdons.client.config

import net.eenda.rvladdons.client.coma.ComaSetManagerScreen
import net.eenda.rvladdons.client.cooldown.CooldownProfileScreen
import net.eenda.rvladdons.client.hud.HudLayoutEditorScreen
import dev.isxander.yacl3.api.ButtonOption
import dev.isxander.yacl3.api.ConfigCategory
import dev.isxander.yacl3.api.OptionGroup
import dev.isxander.yacl3.api.Option
import dev.isxander.yacl3.api.OptionDescription
import dev.isxander.yacl3.api.YetAnotherConfigLib
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder
import net.eenda.rvladdons.core.ModEnabledState
import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

object RvlAddonsConfigScreen {
    fun create(parent: Screen?): Screen {
        val config = RvlAddonsConfigStore.config
        val general = ConfigCategory.createBuilder()
            .name(Text.literal("General"))
            .option(
                Option.createBuilder<Boolean>()
                    .name(Text.literal("Enable RVL Addons"))
                    .description(OptionDescription.of(Text.literal("Enable mod features when an RVL server is detected.")))
                    .binding(config.enabled, { config.enabled }, { config.enabled = it })
                    .controller { BooleanControllerBuilder.create(it).coloured(true).onOffFormatter() }
                    .build()
            )
            .option(
                ButtonOption.createBuilder()
                    .name(Text.literal("Edit HUD layout"))
                    .text(Text.literal("Open"))
                    .description(OptionDescription.of(Text.literal("Arrange all RVL Addons HUD components in one editor.")))
                    .action { screen ->
                        MinecraftClient.getInstance().setScreen(HudLayoutEditorScreen(screen))
                    }
                    .build()
            )
            .build()

        val hud = ConfigCategory.createBuilder()
            .name(Text.literal("HUD"))
            .option(
                Option.createBuilder<Boolean>()
                    .name(Text.literal("Show RVL status"))
                    .binding(config.hudStatusVisible, { config.hudStatusVisible }, { config.hudStatusVisible = it })
                    .controller { BooleanControllerBuilder.create(it).coloured(true).onOffFormatter() }
                    .build()
            )
            .option(
                Option.createBuilder<Boolean>()
                    .name(Text.literal("Show trace status"))
                    .binding(config.hudTraceVisible, { config.hudTraceVisible }, { config.hudTraceVisible = it })
                    .controller { BooleanControllerBuilder.create(it).coloured(true).onOffFormatter() }
                    .build()
            )
            .option(
                Option.createBuilder<Boolean>()
                    .name(Text.literal("Show cooldowns"))
                    .description(OptionDescription.of(Text.literal("Show all active server-correlated cooldowns.")))
                    .binding(config.hudCooldownVisible, { config.hudCooldownVisible }, { config.hudCooldownVisible = it })
                    .controller { BooleanControllerBuilder.create(it).coloured(true).onOffFormatter() }
                    .build()
            )
            .option(
                Option.createBuilder<Boolean>()
                    .name(Text.literal("Show COMA set"))
                    .binding(config.hudComaVisible, { config.hudComaVisible }, { config.hudComaVisible = it })
                    .controller { BooleanControllerBuilder.create(it).coloured(true).onOffFormatter() }
                    .build()
            )
            .build()

        val comaGroup = OptionGroup.createBuilder()
            .name(Text.literal("Co ma"))
            .description(OptionDescription.of(Text.literal("COMA set capture, swapping and timing settings.")))
            .option(
                Option.createBuilder<Int>()
                    .name(Text.literal("Local open delay (ms)"))
                    .description(OptionDescription.of(Text.literal("Delay after the COMA menu is detected before local swap actions.")))
                    .binding(config.comaTiming.openMs, { config.comaTiming.openMs }, { config.comaTiming.openMs = it.coerceIn(0, 5000) })
                    .controller { IntegerFieldControllerBuilder.create(it).range(0, 5000) }
                    .build()
            )
            .option(
                Option.createBuilder<Int>()
                    .name(Text.literal("Local clear interval (ms)"))
                    .description(OptionDescription.of(Text.literal("Delay between local clear clicks. 0 sends the clear burst immediately.")))
                    .binding(config.comaTiming.clearIntervalMs, { config.comaTiming.clearIntervalMs }, { config.comaTiming.clearIntervalMs = it.coerceIn(0, 5000) })
                    .controller { IntegerFieldControllerBuilder.create(it).range(0, 5000) }
                    .build()
            )
            .option(
                Option.createBuilder<Int>()
                    .name(Text.literal("Local clear to move delay (ms)"))
                    .description(OptionDescription.of(Text.literal("Delay between the local clear burst and move burst.")))
                    .binding(config.comaTiming.clearToMoveMs, { config.comaTiming.clearToMoveMs }, { config.comaTiming.clearToMoveMs = it.coerceIn(0, 5000) })
                    .controller { IntegerFieldControllerBuilder.create(it).range(0, 5000) }
                    .build()
            )
            .option(
                Option.createBuilder<Int>()
                    .name(Text.literal("Local move interval (ms)"))
                    .description(OptionDescription.of(Text.literal("Delay between local move clicks. 0 sends the move burst immediately.")))
                    .binding(config.comaTiming.moveIntervalMs, { config.comaTiming.moveIntervalMs }, { config.comaTiming.moveIntervalMs = it.coerceIn(0, 5000) })
                    .controller { IntegerFieldControllerBuilder.create(it).range(0, 5000) }
                    .build()
            )
            .option(
                Option.createBuilder<Int>()
                    .name(Text.literal("Local close delay (ms)"))
                    .description(OptionDescription.of(Text.literal("Delay after the local move burst before closing the menu.")))
                    .binding(config.comaTiming.closeMs, { config.comaTiming.closeMs }, { config.comaTiming.closeMs = it.coerceIn(0, 5000) })
                    .controller { IntegerFieldControllerBuilder.create(it).range(0, 5000) }
                    .build()
            )
            .option(
                Option.createBuilder<Boolean>()
                    .name(Text.literal("Sync with server"))
                    .description(OptionDescription.of(Text.literal("Wait for server container updates and verify every COMA swap. Disable for local burst swapping.")))
                    .binding(config.comaSwapServerSync, { config.comaSwapServerSync }, { config.comaSwapServerSync = it })
                    .controller { BooleanControllerBuilder.create(it).coloured(true).onOffFormatter() }
                    .build()
            )
            .option(
                ButtonOption.createBuilder()
                    .name(Text.literal("Manage Co ma sets"))
                    .text(Text.literal("Open"))
                    .description(OptionDescription.of(Text.literal("View captured items, inventory status and set actions.")))
                    .action { screen ->
                        MinecraftClient.getInstance().setScreen(ComaSetManagerScreen(screen))
                    }
                    .build()
            )
            .build()

        val featuresCategory = ConfigCategory.createBuilder()
            .name(Text.literal("Features"))
            .group(comaGroup)
            .group(
                OptionGroup.createBuilder()
                    .name(Text.literal("Cooldown"))
                    .description(OptionDescription.of(Text.literal("Manage discovered skill labels and tracking overrides.")))
                    .option(
                        ButtonOption.createBuilder()
                            .name(Text.literal("Manage skill profiles"))
                            .text(Text.literal("Open"))
                            .action { screen ->
                                MinecraftClient.getInstance().setScreen(CooldownProfileScreen(screen))
                            }
                            .build()
                    )
                    .build()
            )
            .build()

        return YetAnotherConfigLib.createBuilder()
            .title(Text.literal("RVL Addons"))
            .category(general)
            .category(hud)
            .category(featuresCategory)
            .save {
                RvlAddonsConfigStore.save()
                ModEnabledState.set(config.enabled)
            }
            .build()
            .generateScreen(parent)
    }
}

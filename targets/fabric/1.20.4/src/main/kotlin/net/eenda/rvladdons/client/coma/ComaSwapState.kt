package net.eenda.rvladdons.client.coma

import net.minecraft.client.gui.screen.Screen
import net.minecraft.item.ItemStack
import net.eenda.rvladdons.feature.coma.ComaClickStep
import java.util.ArrayDeque

internal data class CaptureRequest(
    val setIndex: Int,
    val startedAt: Long,
    val returnScreen: Screen?,
    var syncId: Int = -1,
    var lastRevision: Int = -1,
    var previousContents: List<ItemStack> = emptyList(),
    val capturedRoles: MutableSet<Int> = mutableSetOf(),
    val capturedItemNames: MutableMap<Int, String> = mutableMapOf(),
    var detectedSetName: String? = null,
    val clearSlots: ArrayDeque<Int> = ArrayDeque(),
    var clearExpectedRevision: Int = -1,
    var clearStepStartedAt: Long = 0L,
    var clearRole: String = "item",
    var clearAttempts: Int = 0,
    var clearWaitingForServerSync: Boolean = false,
    var clearBatchSent: Boolean = false,
    var clearNextAttemptAt: Long = 0L
)

internal data class SwapRequest(
    val setIndex: Int,
    val startedAt: Long,
    val verifyWithServer: Boolean,
    var syncId: Int = -1,
    var baselineServerRevision: Int = -1,
    var expectedServerRevision: Int = -1,
    var requiresServerSync: Boolean = false,
    var expectedRules: List<String> = emptyList(),
    val steps: ArrayDeque<ComaClickStep> = ArrayDeque()
)

package net.eenda.rvladdons.client.coma

import net.eenda.rvladdons.RvlAddonsClient
import dev.tako.libs.client.ui.feedback.TakoToastManager
import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.eenda.rvladdons.core.RvlAddonsTrace
import net.eenda.rvladdons.core.RvlTextMatcher
import net.eenda.rvladdons.core.state.StateMachine
import net.eenda.rvladdons.feature.coma.ComaItemRuleCodec
import net.eenda.rvladdons.client.item.FabricItemAdapter
import net.eenda.rvladdons.feature.coma.ComaInventorySnapshot
import net.eenda.rvladdons.feature.coma.ComaRole
import net.eenda.rvladdons.feature.coma.ComaSetConfig
import net.eenda.rvladdons.feature.coma.ComaStateStore
import net.eenda.rvladdons.mixins.ScreenTraceAccessor
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.client.gui.screen.ingame.ScreenHandlerProvider
import net.minecraft.item.ItemStack
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.slot.SlotActionType
import java.util.concurrent.ConcurrentLinkedQueue

object ComaSwapController {
    internal const val COMA_TITLE_TOKEN = "CO MA"
    internal const val OPEN_TIMEOUT_MS = 5_000L
    internal const val VERIFY_DELAY_MS = 60L
    internal const val VERIFY_TIMEOUT_MS = 1_500L
    internal const val CLICK_INTERVAL_MS = 40L
    internal const val STEP_SYNC_TIMEOUT_MS = 1_500L
    internal const val CAPTURE_CLEAR_INITIAL_DELAY_MS = 150L
    internal const val CAPTURE_CLEAR_RETRY_DELAY_MS = 100L
    internal const val CAPTURE_CLEAR_NEXT_SLOT_DELAY_MS = 75L
    internal const val PLAN_RETRY_INTERVAL_MS = 50L
    internal const val PLAN_READY_TIMEOUT_MS = 350L
    internal const val SWAP_REARM_MS = 250L
    internal val comaRoles = ComaRole.ordered
    internal val comaSlots = comaRoles.map { it.menuSlot }

    internal fun localOpenDelayMs(): Long = RvlAddonsConfigStore.config.comaTiming.openMs.toLong()
    internal fun localClearClickIntervalMs(): Long = RvlAddonsConfigStore.config.comaTiming.clearIntervalMs.toLong()
    internal fun localClearToMoveDelayMs(): Long = RvlAddonsConfigStore.config.comaTiming.clearToMoveMs.toLong()
    internal fun localMoveClickIntervalMs(): Long = RvlAddonsConfigStore.config.comaTiming.moveIntervalMs.toLong()
    internal fun localCloseDelayMs(): Long = RvlAddonsConfigStore.config.comaTiming.closeMs.toLong()

    private var selectedSetIndex = -1
    private var confirmedSetIndex = -1
    private val stateMachine = StateMachine()
    internal val idleState = ComaIdleState(this)
    internal val captureOpeningState = ComaCaptureOpeningState(this)
    internal val captureClearingState = ComaCaptureClearingState(this)
    internal val captureActiveState = ComaCaptureActiveState(this)
    internal val swapOpeningState = ComaSwapOpeningState(this)
    internal val swapPlanningState = ComaSwapPlanningState(this)
    internal val localClearingState = ComaLocalClearingState(this)
    internal val localWaitingForMoveState = ComaLocalWaitingForMoveState(this)
    internal val localMovingState = ComaLocalMovingState(this)
    internal val localFinishingState = ComaLocalFinishingState(this)
    internal val serverSwappingState = ComaServerSwappingState(this)
    internal val swapVerifyingState = ComaSwapVerifyingState(this)
    private var activeCapture: CaptureRequest? = null
    private var activeSwap: SwapRequest? = null
    private var restorePending = false
    private var restoreSetIndex = -1
    private var restoreDeadline = 0L
    internal var abortReason: String? = null
    internal val captureOperation: CaptureRequest?
        get() = activeCapture
    internal val swapOperation: SwapRequest?
        get() = activeSwap
    private val request: SwapRequest?
        get() = activeSwap
    private val capture: CaptureRequest?
        get() = activeCapture
    internal var nextSwapAllowedAt = 0L
    private val pendingNotifications = ConcurrentLinkedQueue<String>()
    internal var currentComaStacks: List<ItemStack?> = List(4) { null }
    internal var pendingHudSetIndex = -1
    internal var pendingHudSyncId = -1
    internal var pendingHudRules: List<String> = emptyList()
    internal val serverSyncs = ComaInventorySyncCache()

    internal fun enqueueNotification(message: String) {
        pendingNotifications += message
    }

    init {
        stateMachine.setState(idleState)
        stateMachine.addTransitionFrom(captureOpeningState, captureClearingState, { captureOpeningState.menuReady && captureOpeningState.needsClearing })
        stateMachine.addTransitionFrom(captureOpeningState, captureActiveState, { captureOpeningState.menuReady && !captureOpeningState.needsClearing })
        stateMachine.addTransitionFrom(captureClearingState, captureActiveState, { captureClearingState.clearFinished })
        stateMachine.addTransitionFrom(captureActiveState, idleState, { captureActiveState.completed })
        stateMachine.addTransitionFrom(swapOpeningState, swapPlanningState, { swapOpeningState.menuReady })
        stateMachine.addTransitionFrom(swapPlanningState, localClearingState, { swapPlanningState.canEnterLocalClearing() })
        stateMachine.addTransitionFrom(swapPlanningState, serverSwappingState, { swapPlanningState.canEnterServerSwapping() })
        stateMachine.addTransitionFrom(swapPlanningState, swapVerifyingState, { swapPlanningState.canEnterVerifying() })
        stateMachine.addTransitionFrom(swapPlanningState, localFinishingState, { swapPlanningState.canEnterLocalFinishing() })
        stateMachine.addTransitionFrom(localClearingState, localWaitingForMoveState, { localClearingState.readyForMove && localClearingState.waitForMove })
        stateMachine.addTransitionFrom(localClearingState, localMovingState, { localClearingState.readyForMove && !localClearingState.waitForMove })
        stateMachine.addTransitionFrom(localClearingState, localFinishingState, { localClearingState.readyForFinish })
        stateMachine.addTransitionFrom(localWaitingForMoveState, localMovingState, { localWaitingForMoveState.readyForMove })
        stateMachine.addTransitionFrom(localMovingState, localFinishingState, { localMovingState.readyForFinish })
        stateMachine.addTransitionFrom(localFinishingState, idleState, { localFinishingState.completed })
        stateMachine.addTransitionFrom(serverSwappingState, swapVerifyingState, { serverSwappingState.readyForVerification })
        stateMachine.addTransitionFrom(swapVerifyingState, idleState, { swapVerifyingState.verified })
        stateMachine.addTransition(idleState, {
            abortReason != null && stateMachine.currentState !== idleState
        })
    }

    fun requestSwap(client: MinecraftClient) {
        if (!RvlAddonsClient.isGameplayServerActive()) {
            notify(client, "RVL COMA: inactive outside RVL gameplay")
            return
        }
        if (request != null) {
            notify(client, "RVL COMA: swap already in progress")
            return
        }
        val now = System.currentTimeMillis()
        if (now < nextSwapAllowedAt) {
            RvlAddonsTrace.log("coma", "swap-throttled remaining=${nextSwapAllowedAt - now}ms")
            return
        }

        val candidates = RvlAddonsConfigStore.config.comaSets
            .withIndex()
            .filter { it.value.enabled }
        if (candidates.isEmpty()) {
            notify(client, "RVL COMA: no enabled set")
            return
        }

        val currentPosition = candidates.indexOfFirst { it.index == selectedSetIndex }
        val next = if (currentPosition < 0) candidates.first() else candidates[(currentPosition + 1) % candidates.size]
        if (next.value.rules().all(String::isBlank)) {
            notify(client, "RVL COMA: selected set has no item rules")
            return
        }

        selectedSetIndex = next.index
        abortReason = null
        activeSwap = SwapRequest(
            next.index,
            System.currentTimeMillis(),
            RvlAddonsConfigStore.config.comaSwapServerSync
        )
        clearPendingHud()
        serverSyncs.clear()
        stateMachine.changeState(swapOpeningState)
    }

    fun requestCapture(client: MinecraftClient, setIndex: Int, returnScreen: Screen? = null) {
        if (!RvlAddonsClient.isGameplayServerActive()) {
            notify(client, "RVL COMA: inactive outside RVL gameplay")
            return
        }
        if (request != null || capture != null) {
            notify(client, "RVL COMA: another operation is in progress")
            return
        }
        if (RvlAddonsConfigStore.config.comaSets.getOrNull(setIndex) == null) {
            notify(client, "RVL COMA: set no longer exists")
            return
        }
        selectedSetIndex = setIndex
        abortReason = null
        serverSyncs.clear()
        activeCapture = CaptureRequest(setIndex, System.currentTimeMillis(), returnScreen)
        stateMachine.changeState(captureOpeningState)
    }

    fun tick(client: MinecraftClient) {
        while (true) {
            val message = pendingNotifications.poll() ?: break
            notify(client, message)
        }
        if (restorePending) tryRestorePersistedState(client)
        stateMachine.update()
    }

    fun reset() {
        activeCapture = null
        activeSwap = null
        abortReason = null
        stateMachine.changeState(idleState)
        nextSwapAllowedAt = 0L
        serverSyncs.clear()
        confirmedSetIndex = -1
        currentComaStacks = List(4) { null }
        restorePending = false
        restoreSetIndex = -1
        restoreDeadline = 0L
        clearPendingHud()
    }

    fun persistCurrentState(client: MinecraftClient) {
        val set = RvlAddonsConfigStore.config.comaSets.getOrNull(confirmedSetIndex) ?: return
        ComaStateStore.save(serverKey(client), set.rules(), iconItemIds())
        RvlAddonsTrace.log("coma", "persisted confirmed setIndex=$confirmedSetIndex")
    }

    fun restorePersistedState(client: MinecraftClient) {
        restorePending = false
        restoreSetIndex = -1
        restoreDeadline = 0L
        val persisted = ComaStateStore.current()
        if (persisted.rules.isEmpty()) return
        val currentServer = serverKey(client)
        if (persisted.serverKey.isNotBlank() && currentServer.isNotBlank() &&
            !persisted.serverKey.equals(currentServer, ignoreCase = true)
        ) {
            RvlAddonsTrace.log("coma", "persist-skip server-mismatch saved=${persisted.serverKey} current=$currentServer")
            return
        }
        val setIndex = RvlAddonsConfigStore.config.comaSets.indexOfFirst { it.rules() == persisted.rules }
        if (setIndex < 0) {
            RvlAddonsTrace.log("coma", "persist-skip configured-set-not-found")
            return
        }
        restorePending = true
        restoreSetIndex = setIndex
        restoreDeadline = System.currentTimeMillis() + 5_000L
        tryRestorePersistedState(client)
    }

    internal fun rememberConfirmedSet(setIndex: Int) {
        val set = RvlAddonsConfigStore.config.comaSets.getOrNull(setIndex) ?: return
        val client = MinecraftClient.getInstance()
        ComaStateStore.save(serverKey(client), set.rules(), iconItemIds())
        RvlAddonsTrace.log("coma", "persist confirmed setIndex=$setIndex")
    }

    internal fun confirmLocalSet(setIndex: Int, stacks: List<ItemStack?>) {
        currentComaStacks = stacks.map { it?.copy() }
        confirmedSetIndex = setIndex
        selectedSetIndex = setIndex
        rememberConfirmedSet(setIndex)
    }

    fun onSetReordered(from: Int, to: Int) {
        when {
            selectedSetIndex == from -> {
                selectedSetIndex = to
            }
            from < to && selectedSetIndex in (from + 1)..to -> {
                selectedSetIndex--
            }
            to < from && selectedSetIndex in to until from -> {
                selectedSetIndex++
            }
        }
    }

    fun hudText(): String {
        val set = RvlAddonsConfigStore.config.comaSets.getOrNull(confirmedSetIndex)
            ?: return "COMA: NOT VERIFIED"
        return "Current set: ${set.name.ifBlank { "Set ${confirmedSetIndex + 1}" }}"
    }

    fun hudLines(): List<String> {
        val set = RvlAddonsConfigStore.config.comaSets.getOrNull(selectedSetIndex)
            ?: return listOf("COMA: NONE")
        val labels = comaRoles.map { it.label }
        return listOf(hudText()) + set.rules().mapIndexed { index, rule ->
            "${labels[index]}: ${displayRule(rule)}"
        }
    }

    private fun displayRule(rule: String): String {
        if (rule.isBlank()) return "-"
        val registered = ComaItemRuleCodec.decode(rule)
        val value = registered?.displayName?.ifBlank { registered.itemId } ?: rule
        return value.take(36)
    }

    fun hudStacks(client: MinecraftClient): List<ItemStack?> {
        return currentComaStacks.map { it?.copy() }
    }

    internal fun confirmedSetIndex(): Int = confirmedSetIndex

    fun inventoryStacks(client: MinecraftClient, setIndex: Int): List<ItemStack?> =
        findInventoryStacks(client, setIndex)

    fun capturedCount(setIndex: Int): Int =
        RvlAddonsConfigStore.config.comaSets.getOrNull(setIndex)?.rules()?.count(String::isNotBlank) ?: 0

    fun inventoryMatchedCount(client: MinecraftClient, setIndex: Int): Int =
        inventoryStacks(client, setIndex).count { it != null && !it.isEmpty }

    private fun findInventoryStacks(
        client: MinecraftClient,
        setIndex: Int
    ): List<ItemStack?> {
        val set = RvlAddonsConfigStore.config.comaSets.getOrNull(setIndex)
            ?: return List(4) { null }
        val inventory = client.player?.inventory?.let { it.main + it.armor + it.offHand } ?: emptyList()
        return set.rules().mapIndexed { _, rule ->
            if (rule.isBlank()) null
            else inventory.firstOrNull { FabricComaItemAdapter.matches(it, rule) }?.copy()
        }
    }

    fun observeInventorySync(syncId: Int, revision: Int, contents: List<ItemStack>) {
        val sync = serverSyncs.observeInventory(syncId, revision, contents) ?: return
        tryConfirmPendingHud(syncId, sync.contents)
        capture?.let { current ->
            if (stateMachine.currentState === captureActiveState && current.syncId == syncId && revision > current.lastRevision) {
                captureActiveState.onInventorySync(sync)
                current.lastRevision = revision
                current.previousContents = sync.contents
            }
        }
    }

    fun observeCaptureClick(title: String, slotId: Int, actionType: SlotActionType, stack: ItemStack?) {
        (stateMachine.currentState as? ComaState)?.onCaptureClick(title, slotId, actionType, stack)
    }

    fun observeSlotSync(syncId: Int, revision: Int, slot: Int, stack: ItemStack) {
        val sync = serverSyncs.observeSlot(syncId, revision, slot, stack) ?: return
        tryConfirmPendingHud(syncId, sync.contents)
        capture?.let { current ->
            if (stateMachine.currentState === captureActiveState &&
                current.syncId == syncId &&
                revision >= current.lastRevision
            ) {
                captureActiveState.onInventorySync(sync)
                current.lastRevision = maxOf(current.lastRevision, revision)
                current.previousContents = sync.contents
            }
        }
    }

    internal fun clearPendingHud() {
        pendingHudSetIndex = -1
        pendingHudSyncId = -1
        pendingHudRules = emptyList()
    }

    internal fun tryConfirmPendingHud(syncId: Int, contents: List<ItemStack>) {
        if (pendingHudSetIndex < 0 || syncId != pendingHudSyncId || pendingHudRules.isEmpty()) return
        val ready = pendingHudRules.withIndex().all { (index, rule) ->
            if (rule.isBlank()) return@all true
            val stack = contents.getOrNull(comaSlots[index]) ?: return@all false
            FabricComaItemAdapter.matches(stack, rule)
        }
        if (!ready) return

        currentComaStacks = pendingHudRules.mapIndexed { index, rule ->
            if (rule.isBlank()) null else contents.getOrNull(comaSlots[index])?.copy()
        }
        confirmedSetIndex = pendingHudSetIndex
        selectedSetIndex = pendingHudSetIndex
        rememberConfirmedSet(pendingHudSetIndex)
        RvlAddonsTrace.log("coma", "hud-server-confirmed syncId=$syncId setIndex=$pendingHudSetIndex")
        clearPendingHud()
    }


    internal fun failCapture(client: MinecraftClient, reason: String) {
        val returnScreen = capture?.returnScreen
        RvlAddonsTrace.log("coma", "capture-failed reason=$reason")
        notify(client, "RVL COMA: capture failed ($reason)")
        abortReason = reason
        client.setScreen(returnScreen)
    }

    internal fun screenHandler(screen: HandledScreen<*>): ScreenHandler? =
        (screen as? ScreenHandlerProvider<*>)?.getScreenHandler()

    internal fun handlerSnapshot(handler: ScreenHandler): ComaInventorySnapshot =
        ComaInventorySnapshot(
            handler.syncId,
            -1,
            handler.slots.map { slot ->
                slot.stack.takeUnless(ItemStack::isEmpty)?.let(FabricComaItemAdapter::snapshot)
            }
        )

    internal fun isComaScreen(screen: HandledScreen<*>): Boolean {
        val title = (screen as ScreenTraceAccessor).`rvladdons$getTitle`().string
        val normalized = RvlTextMatcher.normalize(title)
        return normalized.contains(COMA_TITLE_TOKEN) || normalized.contains("COMA")
    }

    internal fun displayName(set: ComaSetConfig): String = set.name.ifBlank { "Set ${selectedSetIndex + 1}" }

    internal fun roleName(index: Int): String = ComaRole.fromIndex(index)?.key ?: "unknown"

    internal fun fail(client: MinecraftClient, reason: String) {
        RvlAddonsTrace.log("coma", "failed reason=$reason")
        notify(client, "RVL COMA: swap failed ($reason)")
        if (activeSwap?.setIndex == selectedSetIndex) {
            selectedSetIndex = confirmedSetIndex
        }
        abortReason = reason
    }

    internal fun onIdleEntered() {
        activeCapture = null
        activeSwap = null
        abortReason = null
    }

    internal fun notify(client: MinecraftClient, message: String) {
        TakoToastManager.show(message)
    }

    private fun tryRestorePersistedState(client: MinecraftClient) {
        if (!RvlAddonsClient.isGameplayServerActive()) return
        if (System.currentTimeMillis() > restoreDeadline) {
            restorePending = false
            RvlAddonsTrace.log("coma", "persist-restore-timeout setIndex=$restoreSetIndex")
            return
        }
        val set = RvlAddonsConfigStore.config.comaSets.getOrNull(restoreSetIndex) ?: run {
            restorePending = false
            return
        }
        val stacks = inventoryStacks(client, restoreSetIndex)
        val inventoryMatches = set.rules().withIndex().all { (index, rule) ->
            rule.isBlank() || stacks.getOrNull(index)?.let { FabricComaItemAdapter.matches(it, rule) } == true
        }
        currentComaStacks = if (inventoryMatches) stacks else {
            val persisted = ComaStateStore.current()
            val iconIds = persisted.iconItemIds.ifEmpty {
                persisted.rules.map { ComaItemRuleCodec.decode(it)?.itemId.orEmpty() }
            }
            iconIds.map { FabricItemAdapter.iconStack(it) }
                .let { ids -> ids + List(4 - ids.size) { null } }
                .take(4)
        }
        confirmedSetIndex = restoreSetIndex
        selectedSetIndex = restoreSetIndex
        restorePending = false
        RvlAddonsTrace.log(
            "coma",
            "persist-restored setIndex=$restoreSetIndex inventoryMatched=$inventoryMatches"
        )
    }

    private fun serverKey(client: MinecraftClient): String =
        client.currentServerEntry?.address?.trim()?.lowercase().orEmpty()

    private fun iconItemIds(): List<String> = currentComaStacks.map { stack ->
        stack?.takeUnless(ItemStack::isEmpty)?.let(FabricItemAdapter::itemId).orEmpty()
    }

}


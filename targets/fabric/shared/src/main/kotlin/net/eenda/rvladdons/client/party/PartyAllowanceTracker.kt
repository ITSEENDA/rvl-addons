package net.eenda.rvladdons.client.party

import net.eenda.rvladdons.client.item.FabricItemAdapter
import net.eenda.rvladdons.core.RvlAddonsTrace
import net.eenda.rvladdons.core.RvlTextMatcher
import net.eenda.rvladdons.feature.party.PartyAllowanceParser
import net.eenda.rvladdons.feature.party.PartyStateStore
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.item.ItemStack
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.slot.Slot
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.LinkedHashSet

internal data class PartyAllowanceCost(val commandRoot: String, val amount: Int)

/** Caches server balances and reconciles them from menus opened by the normal join flow. */
internal class PartyAllowanceTracker {
    private data class PendingRead(
        val root: String,
        var syncId: Int,
        val deadline: Long,
        var nextReadAt: Long
    )

    private var storagePath: Path? = null
    private var accountId = ""
    private var serverKey = ""
    private var scope = ""
    private var connected = false
    private var pendingRead: PendingRead? = null
    private var scanningRoot: String? = null
    private val refreshQueue = ArrayDeque<String>()
    private var handledMenu: Pair<String, Int>? = null
    private val committedAttempts = LinkedHashSet<String>()

    var dchienRemaining: Int? = null
        private set
    var tlTicketsRemaining: Int? = null
        private set
    var dchienFresh = false
        private set
    var tlFresh = false
        private set
    var dchienObservedAt: Long? = null
        private set
    var tlObservedAt: Long? = null
        private set

    fun load(path: Path, accountId: String, serverKey: String) {
        val normalizedServer = serverKey.trim().lowercase()
        if (normalizedServer.isBlank()) return
        val normalizedAccount = accountId.trim()
        val newScope = "$normalizedAccount|$normalizedServer"
        if (scope == newScope && storagePath == path) return

        this.storagePath = path
        this.accountId = normalizedAccount
        this.serverKey = normalizedServer
        scope = newScope
        pendingRead = null
        scanningRoot = null
        refreshQueue.clear()
        handledMenu = null
        connected = false
        committedAttempts.clear()

        val dchien = PartyStateStore.loadAllowance(path, normalizedAccount, normalizedServer, "dchien")
        val tl = PartyStateStore.loadAllowance(path, normalizedAccount, normalizedServer, "tl")
        dchienRemaining = dchien.remaining
        dchienObservedAt = dchien.observedAt
        tlTicketsRemaining = tl.remaining
        tlObservedAt = tl.observedAt
        dchienFresh = false
        tlFresh = false
    }

    fun onConnected(path: Path, accountId: String, serverKey: String, now: Long = System.currentTimeMillis()) {
        load(path, accountId, serverKey)
        if (scope.isBlank() || connected) return
        connected = true
        dchienFresh = dchienRemaining != null &&
            !PartyStateStore.allowanceNeedsRefresh(dchienObservedAt, now)
        tlFresh = tlTicketsRemaining != null &&
            !PartyStateStore.allowanceNeedsRefresh(tlObservedAt, now)
        if (!dchienFresh) refreshQueue.addLast("dchien")
        if (!tlFresh) refreshQueue.addLast("tl")
    }

    fun remaining(commandRoot: String): Int? =
        if (normalizeRoot(commandRoot) == "tl") tlTicketsRemaining else dchienRemaining

    fun freshRemaining(commandRoot: String): Int? =
        remaining(commandRoot).takeIf { isFresh(commandRoot) }

    fun isFresh(commandRoot: String): Boolean =
        if (normalizeRoot(commandRoot) == "tl") tlFresh else dchienFresh

    fun commit(cost: PartyAllowanceCost?, attemptId: String) {
        cost ?: return
        if (attemptId.isBlank() || !committedAttempts.add(attemptId)) return
        while (committedAttempts.size > MAX_REMEMBERED_ATTEMPTS) {
            committedAttempts.remove(committedAttempts.first())
        }

        when (normalizeRoot(cost.commandRoot)) {
            "dchien" -> dchienRemaining = dchienRemaining?.minus(cost.amount)?.coerceAtLeast(0)
            "tl" -> tlTicketsRemaining = tlTicketsRemaining?.minus(cost.amount)?.coerceAtLeast(0)
            else -> return
        }
        persist(cost.commandRoot)
    }

    /** Refreshes expired caches on connect; otherwise only observes menus already opened by the player. */
    fun tick(
        client: MinecraftClient,
        sendCommand: (String) -> Boolean,
        canStartScan: Boolean
    ): Boolean {
        val current = currentMenu(client)
        val now = System.currentTimeMillis()

        scanningRoot?.let { root ->
            val pending = pendingRead ?: return false
            val matchingMenu = current?.takeIf { it.first == root }
            if (now >= pending.deadline) {
                matchingMenu?.second?.let { handler ->
                    handledMenu = root to handler.syncId
                    client.player?.closeHandledScreen()
                }
                scanningRoot = null
                pendingRead = null
                return false
            }
            if (matchingMenu == null) return false
            val handler = matchingMenu.second
            if (pending.syncId < 0) pending.syncId = handler.syncId
            if (pending.syncId != handler.syncId) return false
            if (now < pending.nextReadAt) return false
            pending.nextReadAt = now + MENU_READ_INTERVAL_MS
            val count = parseBalance(root, menuSlots(handler)) ?: return false
            observe(root, count, now)
            handledMenu = root to handler.syncId
            scanningRoot = null
            pendingRead = null
            client.player?.closeHandledScreen()
            RvlAddonsTrace.log("party-allowance", "join refresh root=$root count=$count")
            return true
        }

        if (current != null) {
            val (root, handler) = current
            val menuKey = root to handler.syncId
            if (handledMenu != menuKey) {
                var pending = pendingRead
                if (pending == null || pending.root != root || pending.syncId != handler.syncId) {
                    pending = PendingRead(root, handler.syncId, now + MENU_READ_TIMEOUT_MS, now)
                    pendingRead = pending
                }
                if (now >= pending.deadline) {
                    handledMenu = menuKey
                    pendingRead = null
                    refreshQueue.remove(root)
                } else if (now >= pending.nextReadAt) {
                    pending.nextReadAt = now + MENU_READ_INTERVAL_MS
                    val count = parseBalance(root, menuSlots(handler))
                    if (count != null) {
                        observe(root, count, now)
                        handledMenu = menuKey
                        pendingRead = null
                        refreshQueue.remove(root)
                        RvlAddonsTrace.log("party-allowance", "observed root=$root count=$count")
                        return true
                    }
                }
            }
            return false
        }

        pendingRead = null
        handledMenu = null
        if (!canStartScan || refreshQueue.isEmpty()) return false
        val root = refreshQueue.removeFirst()
        scanningRoot = root
        pendingRead = PendingRead(root, -1, now + MENU_READ_TIMEOUT_MS, now)
        if (!sendCommand(root)) {
            scanningRoot = null
            pendingRead = null
        } else {
            RvlAddonsTrace.log("party-allowance", "join refresh requested root=$root")
        }
        return false
    }

    fun markStale() {
        dchienFresh = false
        tlFresh = false
        connected = false
        pendingRead = null
        scanningRoot = null
        refreshQueue.clear()
        handledMenu = null
    }

    private fun currentMenu(client: MinecraftClient): Pair<String, ScreenHandler>? {
        if (client.currentScreen !is HandledScreen<*>) return null
        val handler = client.player?.currentScreenHandler ?: return null
        val title = client.currentScreen?.title?.string
            ?.replace('Đ', 'D')
            ?.replace('đ', 'd')
            ?: return null
        val root = when {
            RvlTextMatcher.contains(title, "THANH LAN") -> "tl"
            RvlTextMatcher.contains(title, "DOAN CHIEN") -> "dchien"
            else -> return null
        }
        return root to handler
    }

    private fun setBalance(root: String, count: Int, observedAt: Long) {
        when (root) {
            "dchien" -> {
                dchienRemaining = count
                dchienObservedAt = observedAt
                dchienFresh = true
            }
            "tl" -> {
                tlTicketsRemaining = count
                tlObservedAt = observedAt
                tlFresh = true
            }
        }
    }

    private fun observe(root: String, count: Int, observedAt: Long) {
        setBalance(root, count, observedAt)
        persist(root)
    }

    private fun persist(root: String) {
        val path = storagePath ?: return
        if (scope.isBlank()) return
        val (remaining, observedAt) = if (normalizeRoot(root) == "tl") {
            tlTicketsRemaining to tlObservedAt
        } else {
            dchienRemaining to dchienObservedAt
        }
        remaining ?: return
        if (!PartyStateStore.saveAllowance(path, accountId, serverKey, root, remaining, observedAt)) {
            RvlAddonsTrace.log("party-allowance", "cache write failed root=$root")
        }
    }

    private fun menuSlots(handler: ScreenHandler): List<Slot> =
        if (handler.slots.size > 36) handler.slots.dropLast(36) else handler.slots

    private fun parseBalance(root: String, slots: List<Slot>): Int? =
        slots.asSequence()
            .map { FabricItemAdapter.loreLines(it.stack) }
            .mapNotNull { PartyAllowanceParser.remaining(root, it) }
            .firstOrNull()

    private fun normalizeRoot(root: String): String =
        root.trim().removePrefix("/").substringBefore(' ').lowercase()

    companion object {
        private const val MENU_READ_TIMEOUT_MS = 5_000L
        private const val MENU_READ_INTERVAL_MS = 100L
        private const val MAX_REMEMBERED_ATTEMPTS = 128

        fun roomCost(commandRoot: String, stack: ItemStack): PartyAllowanceCost? {
            val root = normalizeRootForCost(commandRoot)
            val amount = PartyAllowanceParser.cost(commandRoot, FabricItemAdapter.loreLines(stack))
                ?: return null
            return PartyAllowanceCost(root, amount)
        }

        private fun normalizeRootForCost(root: String): String =
            root.trim().removePrefix("/").substringBefore(' ').lowercase()
    }
}

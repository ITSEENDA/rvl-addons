package net.eenda.rvladdons.client.party

import net.eenda.rvladdons.feature.party.PartyManualAction
import net.eenda.rvladdons.feature.party.PartyManualActionType
import net.eenda.rvladdons.feature.party.PartyMenuStage
import net.minecraft.screen.slot.SlotActionType

/** Stores coordinator actions and rejects stale or duplicate room clicks. */
internal class PartyActionHistory {
    private val actions = LinkedHashMap<Int, PartyManualAction>()
    private var nextSequence = 0

    var intentVersion: Int = 0
        private set

    private var roomActionPending = false

    fun reset() {
        actions.clear()
        nextSequence = 0
        intentVersion = 0
        roomActionPending = false
    }

    fun record(
        slot: Int,
        button: Int,
        action: SlotActionType,
        type: PartyManualActionType,
        targetSignature: String
    ): PartyManualAction? {
        if (type == PartyManualActionType.SELECT_ROOM && roomActionPending) return null
        if (type == PartyManualActionType.SELECT_TYPE) intentVersion++

        val value = PartyManualAction(
            sequence = ++nextSequence,
            slot = slot,
            button = button,
            action = action.name,
            intentVersion = intentVersion,
            type = type,
            targetSignature = targetSignature
        )
        actions[value.sequence] = value
        if (type == PartyManualActionType.SELECT_ROOM) roomActionPending = true
        return value
    }

    fun latestTypeAction(): PartyManualAction? = actions.values.lastOrNull {
        it.type == PartyManualActionType.SELECT_TYPE && it.intentVersion == intentVersion
    }

    fun onMenuStage(stage: PartyMenuStage) {
        if (stage == PartyMenuStage.TYPE_MENU) roomActionPending = false
    }

    fun add(action: PartyManualAction) {
        actions[action.sequence] = action
        nextSequence = maxOf(nextSequence, action.sequence)
        intentVersion = maxOf(intentVersion, action.intentVersion)
        if (action.type == PartyManualActionType.SELECT_ROOM) roomActionPending = true
    }

    fun all(): List<PartyManualAction> = actions.values.toList()

    fun after(sequence: Int): List<PartyManualAction> =
        actions.values.filter { it.sequence > sequence }
}

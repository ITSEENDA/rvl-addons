package net.eenda.rvladdons.feature.party

data class StoredAllowance(val remaining: Int? = null, val observedAt: Long? = null)

data class PartyAllowanceRecord(
    val accountId: String = "",
    val serverKey: String = "",
    val commandRoot: String = "",
    val remaining: Int = 0,
    val observedAt: Long? = null
)

data class PartyPersistentState(
    var clientId: String = "",
    val allowances: MutableList<PartyAllowanceRecord> = mutableListOf()
)

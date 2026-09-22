package net.eenda.rvladdons.feature.cooldown

class CooldownRegistry {
    private val entries = LinkedHashMap<String, CooldownEntry>()

    fun upsert(entry: CooldownEntry) {
        entries[entry.skillKey] = entry
    }

    fun get(skillKey: String): CooldownEntry? = entries[skillKey]

    fun remove(skillKey: String): CooldownEntry? = entries.remove(skillKey)

    fun active(now: Long): List<CooldownEntry> {
        entries.values.removeIf { it.endAt <= now }
        return entries.values.toList()
    }

    fun clear() {
        entries.clear()
    }
}

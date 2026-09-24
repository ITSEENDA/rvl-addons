package net.eenda.rvladdons.feature.cooldown

class CooldownRegistry {
    private val entries = LinkedHashMap<String, CooldownEntry>()

    @Synchronized
    fun upsert(entry: CooldownEntry) {
        entries[entry.skillKey] = entry
    }

    @Synchronized
    fun get(skillKey: String): CooldownEntry? = entries[skillKey]

    @Synchronized
    fun remove(skillKey: String): CooldownEntry? = entries.remove(skillKey)

    @Synchronized
    fun active(now: Long): List<CooldownEntry> {
        entries.values.removeIf { it.endAt <= now }
        return entries.values.toList()
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}

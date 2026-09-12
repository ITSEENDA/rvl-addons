package net.eenda.rvladdons.feature.coma

data class RegisteredComaItemRule(
    val itemId: String,
    val displayName: String,
    val armorType: String,
    val fingerprint: String,
    val fingerprintVersion: Int = 1
)

object ComaItemRuleCodec {
    private const val PREFIX = "@rvl-coma;"

    fun encode(rule: RegisteredComaItemRule): String =
        PREFIX + listOf(
            "v=${rule.fingerprintVersion}",
            "id=${clean(rule.itemId)}",
            "name=${clean(rule.displayName)}",
            "type=${clean(rule.armorType)}",
            "fp=${clean(rule.fingerprint)}"
        ).joinToString(";")

    fun decode(value: String?): RegisteredComaItemRule? {
        if (value == null || !value.startsWith(PREFIX)) return null
        val fields = value.removePrefix(PREFIX)
            .split(';')
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) null else part.substring(0, separator) to part.substring(separator + 1)
            }
            .toMap()
        val id = fields["id"].orEmpty()
        if (id.isBlank()) return null
        return RegisteredComaItemRule(
            itemId = id,
            displayName = fields["name"].orEmpty(),
            armorType = fields["type"].orEmpty(),
            fingerprint = fields["fp"].orEmpty(),
            fingerprintVersion = fields["v"]?.toIntOrNull() ?: 1
        )
    }

    private fun clean(value: String): String =
        value.replace(';', ' ').replace('=', ' ').replace('\n', ' ').replace('\r', ' ').trim()
}

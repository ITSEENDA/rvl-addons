package net.eenda.rvladdons.core

import java.io.BufferedWriter
import java.lang.reflect.Array
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

object RvlAddonsTrace {
    private val lock = Any()

    @Volatile
    private var enabled = false
    private var writer: BufferedWriter? = null

    @JvmStatic
    fun isEnabled(): Boolean = enabled

    @JvmStatic
    fun enable(gameDir: Path) {
        synchronized(lock) {
            if (enabled) return

            val logsDir = gameDir.resolve("logs")
            Files.createDirectories(logsDir)
            writer = Files.newBufferedWriter(
                logsDir.resolve("rvl-addons-trace.log"),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
            enabled = true
            writeLine("trace", "enabled")
        }
    }

    @JvmStatic
    fun disable() {
        synchronized(lock) {
            if (!enabled) return

            writeLine("trace", "disabled")
            enabled = false
            writer?.close()
            writer = null
        }
    }

    @JvmStatic
    fun log(category: String, message: String) {
        synchronized(lock) {
            if (enabled) writeLine(category, message)
        }
    }

    @JvmStatic
    fun logPacket(direction: String, packet: Any?) {
        if (!enabled) return
        log("packet-$direction", describePacket(packet))
    }

    @JvmStatic
    fun describe(value: Any?): String {
        return formatValue(value, 0, identitySet())
    }

    private fun describePacket(packet: Any?): String {
        if (packet == null) return "class=null"

        val summary = runCatching { sanitize(packet.toString()) }.getOrDefault("<toString failed>")
        val fields = formatObject(packet, 0, identitySet())
        return "class=${packet.javaClass.name} text=$summary fields={$fields}"
    }

    private fun identitySet(): MutableSet<Any> {
        return Collections.newSetFromMap(IdentityHashMap())
    }

    private fun formatValue(value: Any?, depth: Int, seen: MutableSet<Any>): String {
        if (value == null) return "null"
        if (value is String || value is Number || value is Boolean || value is Enum<*>) {
            return sanitize(value.toString())
        }
        if (depth >= 4) return "<${value.javaClass.simpleName}>"
        if (value.javaClass.name.contains("ByteBuf")) return sanitize(value.toString())

        if (value is Map<*, *>) {
            return value.entries.take(12).joinToString(prefix = "{", postfix = "}") {
                "${formatValue(it.key, depth + 1, seen)}=${formatValue(it.value, depth + 1, seen)}"
            }
        }
        if (value is Iterable<*>) {
            return value.take(12).joinToString(prefix = "[", postfix = "]") {
                formatValue(it, depth + 1, seen)
            }
        }
        if (value.javaClass.isArray) {
            val length = Array.getLength(value)
            return (0 until minOf(length, 12)).joinToString(prefix = "[", postfix = "]") {
                formatValue(Array.get(value, it), depth + 1, seen)
            }
        }

        return if (seen.add(value)) {
            formatObject(value, depth, seen)
        } else {
            "<cycle:${value.javaClass.simpleName}>"
        }
    }

    private fun formatObject(value: Any, depth: Int, seen: MutableSet<Any>): String {
        val fields = generateSequence(value.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .take(24)
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    "${field.name}=${formatValue(field.get(value), depth + 1, seen)}"
                }.getOrNull()
            }
            .joinToString(", ")
        return fields.ifEmpty { sanitize(value.toString()) }
    }

    private fun sanitize(value: String): String {
        return value
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .take(500)
    }

    private fun writeLine(category: String, message: String) {
        writer?.apply {
            write("${Instant.now()} [$category] ${message.replace('\n', ' ').replace('\r', ' ')}")
            newLine()
            flush()
        }
    }
}

package net.eenda.rvladdons.client.storage

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal object JsonFileStore {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun <T> read(path: Path, type: Class<T>): T? {
        if (!Files.exists(path)) return null
        return runCatching { Files.newBufferedReader(path).use { gson.fromJson(it, type) } }.getOrNull()
    }

    fun write(path: Path, value: Any) {
        val target = path.toAbsolutePath()
        val parent = target.parent
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, "${target.fileName}-", ".tmp")
        try {
            Files.newBufferedWriter(temp).use { gson.toJson(value, it) }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}

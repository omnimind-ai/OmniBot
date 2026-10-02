package cn.com.omnimind.bot.agent

import android.content.Context
import java.util.Base64

/**
 * Native adapter for the memory center page. `WorkspaceMemoryService` keeps the
 * workspace files (daily short-memory files and MEMORY.md); this class adds the
 * page's bullet-line projection and id scheme, matching the Flutter
 * `Mem0MemoryService` format exactly (`base64url(index|memory)`).
 */
internal class NativeMemoryCenterRepository(context: Context) {
    private val memoryService = WorkspaceMemoryService(context.applicationContext)

    fun listShortMemories(): List<WorkspaceShortMemoryEntry> =
        memoryService.listShortMemoryEntries()

    fun deleteShortMemories(entries: List<WorkspaceShortMemoryEntry>): Int =
        memoryService.deleteShortMemoryEntries(entries)

    fun listLongTermMemories(): List<NativeLongMemoryItem> =
        parseLongTermMemories(memoryService.readLongTermMemory())

    fun appendLongTermMemory(memory: String) {
        val trimmed = memory.trim()
        require(trimmed.isNotEmpty()) { "Memory content cannot be empty" }
        val lines = memoryService.readLongTermMemory().split('\n').toMutableList()
        lines.add("- $trimmed")
        memoryService.writeLongTermMemory(lines.joinToString("\n"))
    }

    fun updateLongTermMemory(memoryId: String, memory: String) {
        val trimmed = memory.trim()
        require(trimmed.isNotEmpty()) { "Memory content cannot be empty" }
        val lines = memoryService.readLongTermMemory().split('\n').toMutableList()
        val bullets = bulletLines(lines)
        bullets.forEachIndexed { index, (lineIndex, value) ->
            if (longTermMemoryId(index, value) == memoryId) {
                lines[lineIndex] = "- $trimmed"
                memoryService.writeLongTermMemory(lines.joinToString("\n"))
                return
            }
        }
        throw IllegalStateException("Memory not found")
    }

    fun deleteLongTermMemory(memoryId: String) {
        val lines = memoryService.readLongTermMemory().split('\n').toMutableList()
        val bullets = bulletLines(lines)
        bullets.forEachIndexed { index, (lineIndex, value) ->
            if (longTermMemoryId(index, value) == memoryId) {
                lines.removeAt(lineIndex)
                memoryService.writeLongTermMemory(lines.joinToString("\n"))
                return
            }
        }
        throw IllegalStateException("Memory not found")
    }

    companion object {
        /** Bullet lines of MEMORY.md: trimmed `- ` entries, in file order. */
        fun parseLongTermMemories(content: String): List<NativeLongMemoryItem> {
            val bullets = content.split('\n')
                .map { it.trim() }
                .filter { it.startsWith("- ") }
                .map { it.substring(2).trim() }
                .filter { it.isNotEmpty() }
            return bullets.mapIndexed { index, memory ->
                NativeLongMemoryItem(id = longTermMemoryId(index, memory), memory = memory)
            }
        }

        fun longTermMemoryId(index: Int, memory: String): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString("$index|$memory".toByteArray(Charsets.UTF_8))

        private fun bulletLines(lines: List<String>): List<Pair<Int, String>> =
            lines.mapIndexedNotNull { index, line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("- ")) return@mapIndexedNotNull null
                val memory = trimmed.substring(2).trim()
                if (memory.isEmpty()) null else index to memory
            }
    }
}

internal data class NativeLongMemoryItem(val id: String, val memory: String) {
    override fun toString(): String = "NativeLongMemoryItem(id=$id)"
}

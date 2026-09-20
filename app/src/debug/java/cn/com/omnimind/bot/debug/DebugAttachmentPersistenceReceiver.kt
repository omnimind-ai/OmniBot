package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.com.omnimind.baselib.database.Conversation
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.bot.agent.AgentAttachmentPreparationException
import cn.com.omnimind.bot.agent.AgentConversationHistoryRepository
import cn.com.omnimind.bot.agent.AgentWorkspaceAttachmentSupport
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.*

/** Synthetic attachment regression only; not registered in production builds. */
class DebugAttachmentPersistenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                withTimeout(20000) { verify(app, intent?.getStringExtra("phase") ?: "seed") }
                mapOf("passed" to true, "phase" to intent?.getStringExtra("phase"))
            }.getOrElse { mapOf("passed" to false, "error" to it.toString()) }
            try { File(app.filesDir, "attachment-regression-result.json").writeText(Gson().toJson(result)) }
            finally { pending.finish() }
        }
    }

    private fun payload(index: Int): ByteArray = if (index == 0) {
        android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAJklEQVR4nO3NMQ0AAAwDoPo33arYsQQMkB6LQCAQCAQCgUAg+BIMi1X0pjxKe0gAAAAASUVORK5CYII=", android.util.Base64.DEFAULT)
    } else "synthetic-attachment-$index".toByteArray()

    private suspend fun verify(context: Context, phase: String) {
        val state = File(context.filesDir, "attachment-regression-state.json")
        val repository = AgentConversationHistoryRepository(context)
        if (phase == "seed") {
            check(!state.exists()) { "Existing regression state must be verified/cleaned first" }
            val id = DatabaseHelper.insertConversation(Conversation(title = "Synthetic attachment regression", mode = "agent", isArchived = true))
            val cache = File(context.cacheDir, "attachment-regression").apply { mkdirs() }
            val attachments = listOf("photo.png", "document.txt").mapIndexed { index, name ->
                val source = File(cache, name).apply { writeBytes(payload(index)) }
                mapOf<String, Any?>("id" to "attachment-$index", "name" to name,
                    "path" to source.path, "isImage" to (index == 0),
                    "mimeType" to if (index == 0) "image/png" else "text/plain")
            }
            state.writeText(Gson().toJson(mapOf("conversationId" to id, "attachments" to attachments)))
            repository.upsertUserMessage(id, "agent", "synthetic-message", "fixture", attachments)
            check(cache.deleteRecursively())
        }
        @Suppress("UNCHECKED_CAST")
        val saved = Gson().fromJson(state.readText(), Map::class.java) as Map<String, Any?>
        val id = (saved["conversationId"] as Number).toLong()
        @Suppress("UNCHECKED_CAST")
        val attachments = saved["attachments"] as List<Map<String, Any?>>
        val args = mapOf("conversationId" to id, "conversationMode" to "agent",
            "_meta" to mapOf("dev.omnimind/clientMessageId" to "synthetic-message"), "attachments" to attachments)
        if (phase == "cleanup") {
            @Suppress("UNCHECKED_CAST")
            val durable = repository.restorePromptAttachmentReferences(args)["attachments"] as List<Map<String, Any?>>
            durable.forEach { File(it["path"].toString()).parentFile?.deleteRecursively() }
            DatabaseHelper.deleteConversationById(id)
            File(context.cacheDir, "attachment-regression").deleteRecursively()
            state.delete()
            return
        }
        repository.replaceThreadMessagesFromUiSnapshot(id, "agent", listOf(mapOf(
            "id" to "synthetic-message", "type" to 1, "user" to 1,
            "content" to mapOf("text" to "fixture", "attachments" to attachments))))
        // Duplicate native upsert must also keep the copied bytes after cache eviction.
        repository.upsertUserMessage(id, "agent", "synthetic-message", "fixture", attachments)
        @Suppress("UNCHECKED_CAST")
        val restored = repository.restorePromptAttachmentReferences(args)["attachments"] as List<Map<String, Any?>>
        restored.forEachIndexed { index, item ->
            check(item["path"] != attachments[index]["path"])
            check(File(item["path"].toString()).readBytes().contentEquals(payload(index)))
        }
        AgentWorkspaceAttachmentSupport.prepareAttachmentsForRuntime(context, "regression-retry", restored)
        if (phase == "missing-and-recover") {
            restored.forEachIndexed { index, item ->
                val file = File(item["path"].toString())
                check(file.delete())
                try {
                    val ownedFailure = runCatching {
                        AgentWorkspaceAttachmentSupport.prepareAttachmentsForRuntime(
                            context, "missing-owned", listOf(item))
                    }.exceptionOrNull()
                    check(ownedFailure is AgentAttachmentPreparationException)
                    val pickerFailure = runCatching {
                        AgentWorkspaceAttachmentSupport.prepareAttachmentsForRuntime(
                            context, "missing-picker", listOf(attachments[index]))
                    }.exceptionOrNull()
                    check(pickerFailure is AgentAttachmentPreparationException)
                } finally {
                    file.writeBytes(payload(index))
                }
                val recovered = AgentWorkspaceAttachmentSupport.prepareAttachmentsForRuntime(
                    context, "recovered", listOf(item))
                check(File(recovered.single()["path"].toString()).readBytes().contentEquals(payload(index)))
            }
        }
    }
}

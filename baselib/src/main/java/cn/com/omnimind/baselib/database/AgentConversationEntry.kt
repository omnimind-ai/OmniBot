package cn.com.omnimind.baselib.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

@Entity(
    tableName = "agent_conversation_entries",
    indices = [
        Index(
            value = ["conversationId", "conversationMode", "entryId"],
            unique = true
        ),
        Index(value = ["conversationId", "conversationMode", "updatedAt"]),
        // One chronological index spans canonical and legacy mode buckets.
        Index(value = ["conversationId", "createdAt", "id"])
    ]
)
data class AgentConversationEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conversationId: Long,
    val conversationMode: String,
    val entryId: String,
    val entryType: String,
    val status: String,
    val summary: String,
    val payloadJson: String,
    val createdAt: Long = Date().time,
    val updatedAt: Long = Date().time
)

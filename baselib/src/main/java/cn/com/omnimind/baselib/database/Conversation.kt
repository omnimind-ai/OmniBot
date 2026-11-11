package cn.com.omnimind.baselib.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // 对话标题（用户可编辑）
    val title: String,

    // Title ownership is independent of message/history snapshots.
    @androidx.room.ColumnInfo(defaultValue = "'legacy'")
    val titleSource: String = "temporary",
    @androidx.room.ColumnInfo(defaultValue = "1")
    val titleGenerationAttempted: Boolean = false,

    // 对话模式
    val mode: String = "normal",

    // 是否已归档
    val isArchived: Boolean = false,

    // 是否置顶
    val isPinned: Boolean = false,

    // 定时任务子会话对应的主会话
    val parentConversationId: Long? = null,

    // 定时任务子会话对应的主会话模式
    val parentConversationMode: String? = null,

    // 触发该子会话的定时任务 ID
    val scheduledTaskId: String? = null,

    // 对话摘要（AI生成，6个字左右）
    val summary: String? = null,

    // 历史上下文压缩总结（不作为普通聊天消息展示）
    val contextSummary: String? = null,

    // 已被压缩进 contextSummary 的最后一条 entry 数据库 ID
    val contextSummaryCutoffEntryDbId: Long? = null,

    // 最近一次更新 contextSummary 的时间
    val contextSummaryUpdatedAt: Long = 0,

    // 对话状态
    val status: Int = 0, // 0: 进行中, 1: 已完成

    // 最后一条消息内容
    val lastMessage: String? = null,

    // 消息数量
    val messageCount: Int = 0,

    // 最近一次主模型调用的 prompt token 数
    val latestPromptTokens: Int = 0,

    // 当前会话采用的 prompt token 压缩阈值
    val promptTokenThreshold: Int = 128_000,

    // 最近一次更新 latestPromptTokens 的时间
    val latestPromptTokensUpdatedAt: Long = 0,

    // 创建时间
    val createdAt: Long = Date().time,

    // 更新时间
    val updatedAt: Long = Date().time
)

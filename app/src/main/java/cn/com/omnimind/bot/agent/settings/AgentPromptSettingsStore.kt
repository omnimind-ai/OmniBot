package cn.com.omnimind.bot.agent

import android.content.Context
import cn.com.omnimind.bot.jackery.JackeryCesProvisioning
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.i18n.LocalizedText
import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.util.OmniLog
import com.tencent.mmkv.MMKV
import java.io.File

private val defaultSoulSetting = LocalizedText(
    zhCN = """
        # SOUL

        ## 身份
        - 你是小万，值得信赖的智能助手，优先帮助用户把事情做完。
        - 你会基于事实与工具结果回答，不编造不可验证信息。

        ## 语气
        - 简洁、温和、可执行。
        - 优先给出结论，再补充必要细节。

        ## 行为边界
        - 涉及隐私、删除、支付、外发信息时先确认。
        - 不擅自泄露密钥、个人信息或工作区敏感文件。
        - 不使用破坏性命令，除非用户明确授权。

        ## 记忆协作
        - 当轮就把值得跨会话记住的信息写入短期记忆 `.omnibot/memory/short-memories/YY-MM-DD.md`（用 `memory_write_daily`），宁可多写。
        - 只有跨会话稳定、可复用的结论才写入长期记忆 `.omnibot/memory/MEMORY.md`；其余交给夜间整理蒸馏。
        - 需要时用 `memory_search` 检索既有记忆，避免重复记录。

        ## 自我更新规则
        - 只有在用户明确同意“更新灵魂/SOUL”时，才能更改此设置。
        - 更新时保留“身份、语气、边界”三部分结构，避免漂移。
        - 每次更新应可解释：为什么改、改了什么、预期影响。
    """.trimIndent(),
    enUS = """
        # SOUL

        ## Identity
        - You are Omnibot, a trustworthy assistant focused on helping the user get things done.
        - Base your answers on facts and tool results, and do not invent unverifiable information.

        ## Tone
        - Be concise, warm, and actionable.
        - Lead with the conclusion, then add only the necessary detail.

        ## Boundaries
        - Ask for confirmation before actions involving privacy, deletion, payments, or sending information out.
        - Do not expose secrets, personal data, or sensitive workspace files without permission.
        - Do not use destructive commands unless the user explicitly authorizes them.

        ## Memory Collaboration
        - Record anything worth remembering across sessions into short-term memory `.omnibot/memory/short-memories/YY-MM-DD.md` this very turn (via `memory_write_daily`); when in doubt, write it.
        - Only cross-session, reusable conclusions go into long-term memory `.omnibot/memory/MEMORY.md`; leave the rest for the nightly rollup to distill.
        - Use `memory_search` to check existing memory when relevant, and avoid duplicate entries.

        ## Self-Update Rules
        - Only change this setting when the user explicitly agrees to update the soul/SOUL.
        - Keep the Identity, Tone, and Boundaries sections to avoid drift.
        - Every update must be explainable: why it changed, what changed, and the expected impact.
    """.trimIndent()
)

private val defaultChatPromptSetting = LocalizedText(
    zhCN = "你是一个 AI 助手。",
    enUS = "You are an AI assistant."
)

/**
 * Stores Agent prompt settings directly in the application's settings store.
 *
 * Older releases exposed these values as workspace files. During upgrade, the
 * old values are imported once and the retired files are removed. Reads and
 * writes after migration never depend on workspace files.
 */
object AgentPromptSettingsStore {
    private const val TAG = "AgentPromptSettings"
    private const val KEY_SOUL = "agent_soul_setting_v1"
    private const val KEY_CHAT_PROMPT = "agent_chat_prompt_setting_v1"
    private const val LEGACY_AGENT_RELATIVE_PATH = "workspace/.omnibot/agent"
    private const val LEGACY_EXTERNAL_AGENT_PATH =
        "${AgentWorkspaceManager.LEGACY_EXTERNAL_ROOT_PATH}/.omnibot/agent"

    @Synchronized
    fun initializeAndCleanupLegacyFiles(context: Context) {
        runCatching {
            val appContext = context.applicationContext
            val mmkv = runCatching { MMKV.defaultMMKV() }.getOrNull()
            val legacyDirectories = legacyAgentDirectories(appContext)
            legacyDirectories.forEach { directory ->
                deleteLegacyFile(File(directory, "config.json"))
            }
            val soulReady = ensureSetting(
                mmkv = mmkv,
                key = KEY_SOUL,
                legacyFiles = legacyDirectories.map { File(it, "SOUL.md") },
                defaultValue = defaultSoul(appContext)
            )
            val chatReady = ensureSetting(
                mmkv = mmkv,
                key = KEY_CHAT_PROMPT,
                legacyFiles = legacyDirectories.map { File(it, "CHAT.md") },
                defaultValue = defaultChatPrompt(appContext)
            )

            legacyDirectories.forEach { directory ->
                if (soulReady) {
                    deleteLegacyFile(File(directory, "SOUL.md"))
                }
                if (chatReady) {
                    deleteLegacyFile(File(directory, "CHAT.md"))
                }
                runCatching {
                    if (directory.isDirectory && directory.listFiles().isNullOrEmpty()) {
                        directory.delete()
                    }
                }
            }
        }.onFailure {
            OmniLog.w(TAG, "legacy Agent settings migration failed: ${it.message}")
        }
    }

    fun readSoul(context: Context): String {
        if (JackeryCesProvisioning.enabled) return JackeryCesProvisioning.CHAT_PROMPT
        initializeAndCleanupLegacyFiles(context)
        return runCatching { MMKV.defaultMMKV().decodeString(KEY_SOUL) }
            .getOrNull()
            ?: defaultSoul(context)
    }

    fun readChatPrompt(context: Context): String {
        if (JackeryCesProvisioning.enabled) return JackeryCesProvisioning.CHAT_PROMPT
        initializeAndCleanupLegacyFiles(context)
        return runCatching { MMKV.defaultMMKV().decodeString(KEY_CHAT_PROMPT) }
            .getOrNull()
            ?: defaultChatPrompt(context)
    }

    fun writeSoul(context: Context, content: String): String {
        return writeSetting(context, KEY_SOUL, content)
    }

    fun writeChatPrompt(context: Context, content: String): String {
        return writeSetting(context, KEY_CHAT_PROMPT, content)
    }

    private fun writeSetting(context: Context, key: String, content: String): String {
        val normalized = normalizeContent(content)
        val mmkv = requireNotNull(
            runCatching { MMKV.defaultMMKV() }.getOrNull()
        ) {
            "default MMKV is unavailable"
        }
        check(mmkv.encode(key, normalized)) {
            "failed to persist Agent prompt setting"
        }
        initializeAndCleanupLegacyFiles(context)
        return normalized
    }

    private fun ensureSetting(
        mmkv: MMKV?,
        key: String,
        legacyFiles: List<File>,
        defaultValue: String
    ): Boolean {
        mmkv ?: return false
        if (mmkv.containsKey(key)) {
            return true
        }
        val value = firstReadableLegacyValue(legacyFiles) ?: defaultValue
        return mmkv.encode(key, normalizeContent(value))
    }

    private fun firstReadableLegacyValue(files: List<File>): String? {
        files.forEach { file ->
            if (!file.isFile) {
                return@forEach
            }
            val content = runCatching { file.readText() }
                .onFailure {
                    OmniLog.w(TAG, "failed to import ${file.absolutePath}: ${it.message}")
                }
                .getOrNull()
            if (content != null) {
                return content
            }
        }
        return null
    }

    private fun legacyAgentDirectories(context: Context): List<File> {
        return listOf(
            File(context.applicationInfo.dataDir, LEGACY_AGENT_RELATIVE_PATH),
            File(context.filesDir, LEGACY_AGENT_RELATIVE_PATH),
            File(LEGACY_EXTERNAL_AGENT_PATH)
        ).distinctBy { it.absolutePath }
    }

    private fun deleteLegacyFile(file: File) {
        if (!file.exists()) {
            return
        }
        runCatching {
            check(file.delete() || !file.exists()) {
                "delete returned false"
            }
        }.onFailure {
            OmniLog.w(TAG, "failed to delete ${file.absolutePath}: ${it.message}")
        }
    }

    private fun defaultSoul(context: Context): String {
        return normalizeContent(
            defaultSoulSetting.resolve(AppLocaleManager.resolvePromptLocale(context))
        )
    }

    private fun defaultChatPrompt(context: Context): String {
        return normalizeContent(
            defaultChatPromptSetting.resolve(AppLocaleManager.resolvePromptLocale(context))
        )
    }

    private fun normalizeContent(content: String): String {
        val trimmed = content.trimEnd()
        return if (trimmed.isEmpty()) "" else "$trimmed\n"
    }
}

package cn.com.omnimind.bot.webchat

import android.content.Context
import cn.com.omnimind.bot.agent.BrowserUseAction
import cn.com.omnimind.bot.agent.BrowserUseOutcome
import cn.com.omnimind.bot.agent.BrowserUseRequest
import cn.com.omnimind.bot.agent.BrowserUserAgentProfile
import cn.com.omnimind.bot.agent.LiveAgentBrowserSessionManager

class BrowserMirrorService(
    @Suppress("UNUSED_PARAMETER") context: Context
) {
    fun snapshot(): Map<String, Any?> {
        return LiveAgentBrowserSessionManager.currentSnapshot()
    }

    suspend fun frameBytes(): ByteArray? {
        return LiveAgentBrowserSessionManager.captureCurrentFramePng()
    }

    suspend fun executeAction(arguments: Map<String, Any?>): Map<String, Any?> {
        val request = buildRequest(arguments)
        val outcome = LiveAgentBrowserSessionManager.executeCurrent(request)
            ?: throw IllegalStateException("浏览器会话当前不可用")
        val snapshot = snapshot()
        val payload = outcome.toPayload() + mapOf("snapshot" to snapshot)
        RealtimeHub.publish(
            "browser_snapshot_updated",
            mapOf("snapshot" to snapshot, "result" to payload)
        )
        FlutterChatSyncBridge.dispatchBrowserSnapshotUpdated(snapshot)
        return payload
    }

    internal fun buildRequest(arguments: Map<String, Any?>): BrowserUseRequest {
        val action = BrowserUseAction.fromWire(arguments["action"]?.toString())
            ?: throw IllegalArgumentException("缺少合法的 browser action")
        val request = BrowserUseRequest(
            toolTitle = arguments["tool_title"]?.toString()?.trim()?.ifEmpty {
                "Web Chat Browser"
            } ?: "Web Chat Browser",
            action = action,
            text = arguments["text"]?.toString(),
            url = arguments["url"]?.toString(),
            userAgent = BrowserUserAgentProfile.fromWire(
                arguments["user_agent"]?.toString() ?: arguments["userAgent"]?.toString()
            ),
            script = arguments["script"]?.toString(),
            coordinateX = arguments.readInt("coordinate_x") ?: arguments.readInt("coordinateX"),
            coordinateY = arguments.readInt("coordinate_y") ?: arguments.readInt("coordinateY"),
            amount = arguments.readInt("amount"),
            keywords = arguments.readStringList("keywords"),
            itemSelector = arguments["item_selector"]?.toString() ?: arguments["itemSelector"]?.toString(),
            direction = arguments["direction"]?.toString(),
            tabId = arguments.readInt("tab_id") ?: arguments.readInt("tabId"),
            selector = arguments["selector"]?.toString(),
            fuzzy = arguments.readBoolean("fuzzy") ?: true,
            maxDepth = arguments.readInt("max_depth") ?: arguments.readInt("maxDepth"),
            scrollCount = arguments.readInt("scroll_count") ?: arguments.readInt("scrollCount")
        )
        validateRequest(request)
        return request
    }

    private fun validateRequest(request: BrowserUseRequest) {
        when (request.action) {
            BrowserUseAction.NAVIGATE -> require(!request.url.isNullOrBlank()) { "navigate 缺少 url" }
            BrowserUseAction.CLICK,
            BrowserUseAction.HOVER -> require(request.hasSelectorOrCoordinates()) {
                "${request.action.wireName} 需要 selector 或坐标"
            }
            BrowserUseAction.TYPE -> {
                require(!request.text.isNullOrBlank()) { "type 缺少 text" }
                require(request.hasSelectorOrCoordinates()) { "type 需要 selector 或坐标" }
            }
            BrowserUseAction.EXECUTE_JS -> require(!request.script.isNullOrBlank()) { "execute_js 缺少 script" }
            BrowserUseAction.SET_USER_AGENT -> require(request.userAgent != null) { "set_user_agent 缺少 user_agent" }
            BrowserUseAction.FETCH -> require(!request.url.isNullOrBlank()) { "fetch 缺少 url" }
            else -> Unit
        }
    }

    private fun BrowserUseOutcome.toPayload(): Map<String, Any?> {
        return linkedMapOf(
            "summaryText" to summaryText,
            "payload" to payload,
            "artifacts" to artifacts.map { it.toPayload() },
            "actions" to actions.map { it.toPayload() }
        )
    }

    private fun Map<String, Any?>.readInt(key: String): Int? {
        return when (val raw = this[key]) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
    }

    private fun Map<String, Any?>.readBoolean(key: String): Boolean? {
        return when (val raw = this[key]) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> raw.trim().lowercase().let {
                when (it) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun Map<String, Any?>.readStringList(key: String): List<String> {
        val raw = this[key] ?: return emptyList()
        return when (raw) {
            is List<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            is String -> raw.split(Regex("\\s+"))
                .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            else -> emptyList()
        }
    }
}

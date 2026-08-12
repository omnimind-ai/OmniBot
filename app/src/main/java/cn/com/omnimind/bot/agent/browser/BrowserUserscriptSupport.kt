package cn.com.omnimind.bot.agent

import org.json.JSONObject
import java.util.Locale

data class BrowserUserscriptMetadata(
    val name: String,
    val description: String = "",
    val version: String = "",
    val matches: List<String> = emptyList(),
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val runAt: String = "document-end",
    val grants: List<String> = emptyList(),
    val updateUrl: String? = null,
    val downloadUrl: String? = null
)

data class BrowserUserscriptInstallPreview(
    val metadata: BrowserUserscriptMetadata,
    val source: String,
    val sourceUrl: String? = null,
    val blockedGrants: List<String> = emptyList()
)

data class BrowserUserscriptMenuCommand(
    val commandId: String,
    val scriptId: Long,
    val title: String
)

object BrowserUserscriptSupport {
    val supportedGrants: Set<String> = setOf(
        "GM_addStyle",
        "GM_getValue",
        "GM_setValue",
        "GM_deleteValue",
        "GM_listValues",
        "GM_registerMenuCommand"
    )

    fun parseSource(
        source: String,
        sourceUrl: String? = null
    ): BrowserUserscriptInstallPreview {
        val metadataBlock = Regex(
            "//\\s*==UserScript==([\\s\\S]*?)//\\s*==/UserScript==",
            RegexOption.IGNORE_CASE
        ).find(source)?.groupValues?.getOrNull(1).orEmpty()
        val lines = metadataBlock.lines()
        val values = linkedMapOf<String, MutableList<String>>()
        lines.forEach { rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith("//")) {
                return@forEach
            }
            val content = line.removePrefix("//").trim()
            if (!content.startsWith("@")) {
                return@forEach
            }
            val key = content.substringAfter("@").substringBefore(" ").trim()
            val value = content.substringAfter(" ", "").trim()
            if (key.isBlank()) {
                return@forEach
            }
            values.getOrPut(key) { mutableListOf() }.add(value)
        }
        val grants = values["grant"].orEmpty().filter { it.isNotBlank() }
        val blockedGrants = grants.filterNot { supportedGrants.contains(it) }
        val metadata = BrowserUserscriptMetadata(
            name = values["name"].orEmpty().firstOrNull().orEmpty().ifBlank {
                sourceUrl?.substringAfterLast('/')?.ifBlank { "Userscript" } ?: "Userscript"
            },
            description = values["description"].orEmpty().firstOrNull().orEmpty(),
            version = values["version"].orEmpty().firstOrNull().orEmpty(),
            matches = values["match"].orEmpty().filter { it.isNotBlank() },
            includes = values["include"].orEmpty().filter { it.isNotBlank() },
            excludes = values["exclude"].orEmpty().filter { it.isNotBlank() },
            runAt = values["run-at"].orEmpty().firstOrNull().orEmpty().ifBlank { "document-end" },
            grants = grants,
            updateUrl = values["updateURL"].orEmpty().firstOrNull()
                ?: values["updateUrl"].orEmpty().firstOrNull(),
            downloadUrl = values["downloadURL"].orEmpty().firstOrNull()
                ?: values["downloadUrl"].orEmpty().firstOrNull()
        )
        return BrowserUserscriptInstallPreview(
            metadata = metadata,
            source = source,
            sourceUrl = sourceUrl,
            blockedGrants = blockedGrants
        )
    }

    fun matchesUrl(
        script: BrowserUserscriptRecord,
        url: String
    ): Boolean {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) {
            return false
        }
        if (script.excludes.any { wildcardMatches(it, normalizedUrl) || matchRuleMatches(it, normalizedUrl) }) {
            return false
        }
        if (script.matches.any { matchRuleMatches(it, normalizedUrl) }) {
            return true
        }
        if (script.includes.any { wildcardMatches(it, normalizedUrl) }) {
            return true
        }
        return script.matches.isEmpty() && script.includes.isEmpty()
    }

    fun buildWrapperScript(
        script: BrowserUserscriptRecord,
        bridgeToken: String,
    ): String {
        val scriptIdLiteral = script.id.toString()
        val sourceLiteral = script.source
        val bridgeTokenLiteral = JSONObject.quote(bridgeToken)
        return """
            (function() {
                const source = ${JSONObject.quote(sourceLiteral)};
                const scriptId = $scriptIdLiteral;
                const bridgeToken = $bridgeTokenLiteral;
                if (!window.__omniUserscriptRuntime) {
                    window.__omniUserscriptRuntime = {
                        menus: {},
                        invokeMenu(commandId) {
                            const entry = this.menus[commandId];
                            if (entry && typeof entry.callback === 'function') {
                                entry.callback();
                                return true;
                            }
                            return false;
                        }
                    };
                    window.__omniInvokeUserscriptMenu = function(commandId) {
                        return window.__omniUserscriptRuntime.invokeMenu(commandId);
                    };
                }
                const bridge = window.OmniBrowserUserscriptBridge;
                const GM_addStyle = function(cssText) {
                    const style = document.createElement('style');
                    style.textContent = String(cssText || '');
                    (document.head || document.documentElement || document.body).appendChild(style);
                    return style;
                };
                const GM_getValue = function(key, defaultValue) {
                    const raw = bridge ? bridge.getValue(bridgeToken, String(scriptId), String(key || '')) : null;
                    if (raw === null || raw === undefined || raw === '') {
                        return defaultValue;
                    }
                    try {
                        return JSON.parse(String(raw));
                    } catch (_) {
                        return raw;
                    }
                };
                const GM_setValue = function(key, value) {
                    if (!bridge) return;
                    bridge.setValue(bridgeToken, String(scriptId), String(key || ''), JSON.stringify(value));
                };
                const GM_deleteValue = function(key) {
                    if (!bridge) return;
                    bridge.deleteValue(bridgeToken, String(scriptId), String(key || ''));
                };
                const GM_listValues = function() {
                    if (!bridge) return [];
                    const raw = bridge.listValues(bridgeToken, String(scriptId));
                    try {
                        return JSON.parse(String(raw || '[]'));
                    } catch (_) {
                        return [];
                    }
                };
                const GM_registerMenuCommand = function(title, callback) {
                    if (!bridge) return '';
                    const commandId = String(bridge.registerMenuCommand(bridgeToken, String(scriptId), String(title || 'Menu')));
                    window.__omniUserscriptRuntime.menus[commandId] = {
                        title: String(title || 'Menu'),
                        callback: callback
                    };
                    return commandId;
                };
                try {
                    const runner = new Function(
                        'GM_addStyle',
                        'GM_getValue',
                        'GM_setValue',
                        'GM_deleteValue',
                        'GM_listValues',
                        'GM_registerMenuCommand',
                        source
                    );
                    runner(
                        GM_addStyle,
                        GM_getValue,
                        GM_setValue,
                        GM_deleteValue,
                        GM_listValues,
                        GM_registerMenuCommand
                    );
                } catch (error) {
                    if (bridge) {
                        bridge.log(bridgeToken, String(scriptId), String(error && error.message ? error.message : error));
                    }
                }
            })();
        """.trimIndent()
    }

    private fun wildcardMatches(
        pattern: String,
        url: String
    ): Boolean {
        val normalizedPattern = pattern.trim()
        if (normalizedPattern.isBlank()) {
            return false
        }
        val regex = buildString {
            append("^")
            normalizedPattern.forEach { ch ->
                if (ch == '*') {
                    append(".*")
                } else {
                    append(Regex.escape(ch.toString()))
                }
            }
            append("$")
        }
        return Regex(regex, RegexOption.IGNORE_CASE).matches(url)
    }

    private fun matchRuleMatches(
        pattern: String,
        url: String
    ): Boolean {
        val value = pattern.trim()
        if (value.isBlank()) {
            return false
        }
        val normalized = value.replace("://", "§scheme§")
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("§scheme§", "://")
        return Regex("^$normalized$", RegexOption.IGNORE_CASE).matches(url)
    }

    fun downloadUrlForUpdate(script: BrowserUserscriptRecord): String? {
        return script.updateUrl?.takeIf { it.isNotBlank() }
            ?: script.downloadUrl?.takeIf { it.isNotBlank() }
            ?: script.sourceUrl?.takeIf { it.isNotBlank() }
    }

    fun toRecord(
        preview: BrowserUserscriptInstallPreview,
        scriptId: Long,
        now: Long,
        enabled: Boolean = true
    ): BrowserUserscriptRecord {
        return BrowserUserscriptRecord(
            id = scriptId,
            name = preview.metadata.name,
            description = preview.metadata.description,
            version = preview.metadata.version,
            source = preview.source,
            sourceUrl = preview.sourceUrl,
            updateUrl = preview.metadata.updateUrl,
            downloadUrl = preview.metadata.downloadUrl,
            matches = preview.metadata.matches,
            includes = preview.metadata.includes,
            excludes = preview.metadata.excludes,
            runAt = preview.metadata.runAt,
            grants = preview.metadata.grants,
            blockedGrants = preview.blockedGrants,
            enabled = enabled,
            createdAt = now,
            updatedAt = now
        )
    }

    fun isSupportedRunAt(value: String?): Boolean {
        val normalized = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return normalized == "document-end" || normalized.isBlank()
    }
}

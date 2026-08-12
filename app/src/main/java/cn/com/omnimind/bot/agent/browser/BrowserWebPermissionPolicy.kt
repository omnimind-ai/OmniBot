package cn.com.omnimind.bot.agent

import java.net.IDN
import java.net.URI
import java.util.Locale

internal object BrowserWebPermissionPolicy {
    const val CAPABILITY_CAMERA = "camera"
    const val CAPABILITY_MICROPHONE = "microphone"
    const val CAPABILITY_LOCATION = "location"

    const val RESOURCE_VIDEO_CAPTURE = "android.webkit.resource.VIDEO_CAPTURE"
    const val RESOURCE_AUDIO_CAPTURE = "android.webkit.resource.AUDIO_CAPTURE"

    data class ApprovedOrigin(
        val normalizedOrigin: String,
        val displayHost: String,
    )

    data class WebPermissionDecision(
        val origin: ApprovedOrigin,
        val resources: List<String>,
        val capabilities: List<String>,
    )

    fun evaluateWebPermission(
        rawOrigin: String?,
        requestedResources: Array<out String>?,
    ): WebPermissionDecision? {
        val origin = approveOrigin(rawOrigin) ?: return null
        val resources = requestedResources
            ?.map { it.trim() }
            ?.takeIf { it.isNotEmpty() && it.none { resource -> resource.isBlank() } }
            ?: return null
        if (resources.any { it != RESOURCE_VIDEO_CAPTURE && it != RESOURCE_AUDIO_CAPTURE }) {
            return null
        }
        val approvedResources = resources.distinct()
        if (approvedResources.isEmpty()) return null
        return WebPermissionDecision(
            origin = origin,
            resources = approvedResources,
            capabilities = approvedResources.map { resource ->
                when (resource) {
                    RESOURCE_VIDEO_CAPTURE -> CAPABILITY_CAMERA
                    RESOURCE_AUDIO_CAPTURE -> CAPABILITY_MICROPHONE
                    else -> error("Unreachable web permission resource: $resource")
                }
            },
        )
    }

    fun evaluateGeolocation(rawOrigin: String?): ApprovedOrigin? = approveOrigin(rawOrigin)

    fun approvalMatchesCurrentRequest(
        expectedRequestId: String,
        responseRequestId: String?,
        expectedTabId: Int,
        currentTabId: Int?,
        expectedNavigationGeneration: Long,
        currentNavigationGeneration: Long?,
    ): Boolean {
        return expectedRequestId.isNotBlank() &&
            responseRequestId == expectedRequestId &&
            currentTabId == expectedTabId &&
            currentNavigationGeneration == expectedNavigationGeneration
    }

    private fun approveOrigin(rawOrigin: String?): ApprovedOrigin? {
        val value = rawOrigin?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.any { it.isISOControl() }) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.isOpaque || !uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        if (uri.rawPath != null && uri.rawPath.isNotEmpty() && uri.rawPath != "/") return null
        val port = uri.port
        if (port != -1 && port !in 1..65_535) return null
        val normalizedHost = normalizeHost(uri.host) ?: return null
        val portSuffix = if (port == -1 || port == 443) "" else ":$port"
        val displayHost = "$normalizedHost$portSuffix"
        return ApprovedOrigin(
            normalizedOrigin = "https://$displayHost",
            displayHost = displayHost,
        )
    }

    private fun normalizeHost(rawHost: String?): String? {
        val host = rawHost?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (host.endsWith('.')) return null
        val unwrapped = host.removePrefix("[").removeSuffix("]")
        if (':' in unwrapped) {
            if (!unwrapped.matches(Regex("[0-9a-fA-F:.]+")) || unwrapped.count { it == ':' } < 2) {
                return null
            }
            return "[${unwrapped.lowercase(Locale.ROOT)}]"
        }
        val ascii = runCatching {
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
        }.getOrNull()?.lowercase(Locale.ROOT) ?: return null
        if (ascii.length !in 1..253) return null
        val labels = ascii.split('.')
        if (labels.any { label ->
                label.isEmpty() || label.length > 63 || label.startsWith('-') ||
                    label.endsWith('-') || !label.matches(Regex("[a-z0-9-]+"))
            }
        ) {
            return null
        }
        return ascii
    }
}

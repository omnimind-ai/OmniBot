package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController

data class AgentToolImageContinuationPolicy(
    val supportsToolImageContinuation: Boolean,
    val routeLabel: String = "unknown"
) {
    companion object {
        val DEFAULT = AgentToolImageContinuationPolicy(
            supportsToolImageContinuation = true
        )
    }
}

object AgentToolImageContinuationPolicyResolver {
    fun resolve(
        routeInfo: HttpController.ChatCompletionRouteInfo?
    ): AgentToolImageContinuationPolicy {
        if (routeInfo == null) {
            return AgentToolImageContinuationPolicy.DEFAULT
        }
        val routeLabel = buildRouteLabel(routeInfo)
        return AgentToolImageContinuationPolicy(
            // The resolved Provider capability is the only source of truth.
            // Model/vendor-name guesses made this adapter disagree with the
            // actual route and silently removed valid image continuations.
            supportsToolImageContinuation = routeInfo.providerCapabilities.supportsVisionInput != false,
            routeLabel = routeLabel
        )
    }

    private fun buildRouteLabel(
        routeInfo: HttpController.ChatCompletionRouteInfo
    ): String {
        return buildList {
            add("model=${routeInfo.resolvedModel}")
            add("protocol=${routeInfo.protocolType}")
            routeInfo.providerProfileId?.takeIf { it.isNotBlank() }?.let {
                add("profile=$it")
            }
            routeInfo.routeTag?.takeIf { it.isNotBlank() }?.let {
                add("route=$it")
            }
        }.joinToString(separator = ",")
    }
}

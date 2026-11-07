package cn.com.omnimind.baselib.llm

import android.content.Context
import com.google.gson.JsonParser
import java.net.URI

/** Offline capability fallback for an exact model on its declared upstream API route. */
object ProviderReasoningCatalog {
    internal const val ASSET_PATH = "provider_reasoning_catalog.json"
    @Volatile private var bundled: Snapshot? = null

    fun enrich(
        context: Context,
        profile: ModelProviderProfile,
        model: ProviderModelOption,
    ): ProviderModelOption = if (profile.protocolType == ProviderProtocol.GEMINI) {
        GeminiProvider.enrich(model)
    } else snapshot(context)?.enrich(profile, model) ?: model

    private fun snapshot(context: Context): Snapshot? = bundled ?: synchronized(this) {
        bundled ?: runCatching {
            context.assets.open(ASSET_PATH).bufferedReader().use { parse(it.readText()) }
        }.getOrNull()?.also { bundled = it }
    }

    internal class Snapshot internal constructor(private val routes: List<Route>) {
        fun enrich(profile: ModelProviderProfile, model: ProviderModelOption): ProviderModelOption {
            if (model.reasoning == false) return model
            val oauth = ChatGptOAuthProvider.isOAuth(profile.sourceType)
            val api = when {
                profile.protocolType == "anthropic" -> "anthropic-messages"
                OpenAiWireApi.isResponses(profile.wireApi) -> "openai-responses"
                else -> "openai-completions"
            }
            val endpoint = canonicalEndpoint(profile.baseUrl, api) ?: return model
            val route = if (oauth) {
                if (api != "openai-responses" ||
                    endpoint != canonicalEndpoint(ChatGptOAuthProvider.BASE_URL, api)) return model
                routes.firstOrNull { it.sourceType == ChatGptOAuthProvider.SOURCE_TYPE }
            } else {
                routes.firstOrNull { it.sourceType == null && it.api == api && it.endpoint == endpoint }
            } ?: return model
            val entry = route.models[model.id] ?: return model
            val reasoning = model.reasoning ?: entry.reasoning
            return model.copy(
                reasoning = reasoning,
                supportedReasoningLevels = model.supportedReasoningLevels.orEmpty().ifEmpty {
                    if (reasoning == true) entry.levels else emptyList()
                },
                reasoningCanDisable = model.reasoningCanDisable
                    ?: entry.canDisable.takeIf { reasoning == true },
                reasoningSupportsAdaptive = model.reasoningSupportsAdaptive ?: entry.supportsAdaptive,
                reasoningWireFormat = model.reasoningWireFormat ?: entry.wireFormat,
                reasoningEffortSupported = model.reasoningEffortSupported ?: entry.effortSupported,
                reasoningContentRequired = model.reasoningContentRequired ?: entry.contentRequired,
                reasoningCapabilitySource = model.reasoningCapabilitySource ?: "bundled_catalog",
            )
        }
    }

    internal data class Route(
        val api: String,
        val endpoint: String,
        val sourceType: String?,
        val models: Map<String, Entry>,
    )

    internal data class Entry(
        val reasoning: Boolean,
        val levels: List<String>,
        val canDisable: Boolean?,
        val supportsAdaptive: Boolean?,
        val wireFormat: String?,
        val effortSupported: Boolean?,
        val contentRequired: Boolean?,
    )

    internal fun parse(content: String): Snapshot {
        val root = JsonParser.parseString(content).asJsonObject
        require(root["schemaVersion"].asInt == 1) { "Unsupported Provider capability catalog." }
        val routes = root.getAsJsonArray("routes").map { value ->
            val route = value.asJsonObject
            val api = route["api"].asString
            val endpoint = requireNotNull(canonicalEndpoint(route["baseUrl"].asString, api))
            Route(
                api = api,
                endpoint = endpoint,
                sourceType = route["sourceType"]?.asString,
                models = route.getAsJsonObject("models").entrySet().associate { (id, raw) ->
                    val entry = raw.asJsonObject
                    id to Entry(
                        reasoning = entry["reasoning"].asBoolean,
                        levels = entry.getAsJsonArray("supportedReasoningLevels")?.map { it.asString }.orEmpty(),
                        canDisable = entry["reasoningCanDisable"]?.asBoolean,
                        supportsAdaptive = entry["reasoningSupportsAdaptive"]?.asBoolean,
                        wireFormat = entry["reasoningWireFormat"]?.asString,
                        effortSupported = entry["reasoningEffortSupported"]?.asBoolean,
                        contentRequired = entry["reasoningContentRequired"]?.asBoolean,
                    )
                },
            )
        }
        return Snapshot(routes)
    }

    /** Compare actual HTTPS routes, never model names, URL substrings or provider display names. */
    private fun canonicalEndpoint(value: String, api: String): String? {
        val direct = value.trim().endsWith('#')
        val uri = runCatching { URI(value.trim().removeSuffix("#")) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty() ||
            uri.port !in setOf(-1, 443) || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null) return null
        val suffix = when (api) {
            "anthropic-messages" -> "/messages"
            "openai-completions" -> "/chat/completions"
            "openai-responses", "openai-codex-responses" -> "/responses"
            else -> return null
        }
        var path = uri.rawPath.orEmpty().trimEnd('/')
        if (direct && !path.endsWith(suffix)) return null
        path = path.removeSuffix(suffix)
        if (api == "anthropic-messages") path = path.removeSuffix("/v1")
        else if (path == "/v1") path = ""
        return "${uri.host.lowercase()}$path"
    }
}

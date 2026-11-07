package cn.com.omnimind.assists.controller.http

import cn.com.omnimind.baselib.llm.*
import kotlinx.serialization.json.*

object GeminiModelCatalog {
    data class Page(val models: List<ProviderModelOption>, val nextPageToken: String?)

    fun parse(body: String): Page {
        val root = Json.parseToJsonElement(body).jsonObject
        require(root["error"] == null || root["error"] == JsonNull) { "Gemini model discovery failed" }
        val entries = root["models"] as? JsonArray ?: error("Gemini model directory is missing models")
        val models = entries.mapNotNull { raw ->
            val model = raw.jsonObject
            val id = GeminiProvider.modelId(model.text("name"))
            require(id.isNotBlank()) { "Gemini model directory is missing a model name" }
            val methods = (model["supportedGenerationMethods"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
            if (methods.isNotEmpty() && methods.none { it in setOf("generateContent", "interactions") }) return@mapNotNull null
            GeminiProvider.enrich(ProviderModelOption(
                id = id,
                displayName = model.text("displayName").ifBlank { id },
                ownedBy = "google",
                contextLimit = model["inputTokenLimit"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 },
                inputLimit = model["inputTokenLimit"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 },
                outputLimit = model["outputTokenLimit"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 },
            ))
        }
        return Page(models, root.text("nextPageToken").takeIf(String::isNotBlank))
    }
}

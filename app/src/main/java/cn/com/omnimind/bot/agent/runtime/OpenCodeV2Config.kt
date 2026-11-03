package cn.com.omnimind.bot.agent.runtime

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Convert only our managed Provider. OpenCode owns compatibility for unrelated user entries. */
internal fun managedOpenCodeV2Provider(root: JsonObject): JsonObject {
    val providers = root.objectOrCreate("providers")
    val legacyProviders = root.getAsJsonObject("provider")
    val legacy = legacyProviders?.getAsJsonObject(OPEN_CODE_PROVIDER_ID)
    val provider = providers.getAsJsonObject(OPEN_CODE_PROVIDER_ID) ?: (legacy?.deepCopy()?.apply {
        remove("npm")?.let { addProperty("package", openCodePackage(it.asString)) }
        getAsJsonObject("options")?.remove("setCacheKey")?.let {
            objectOrCreate("compatibility").add("supportsPromptCacheKey", it)
        }
        migrateRequestOptions(this)
        remove("api")?.let { objectOrCreate("settings").add("baseURL", it) }
        listOf("id", "whitelist", "blacklist").forEach(::remove)
        getAsJsonObject("models")?.entrySet()?.forEach { (_, entry) ->
            if (entry.isJsonObject) migrateModel(entry.asJsonObject)
        }
    } ?: JsonObject()).also { providers.add(OPEN_CODE_PROVIDER_ID, it) }
    // Native V2 wins when both already exist; never merge stale legacy values
    // over a user-edited V2 provider. Other legacy Providers remain untouched.
    legacyProviders?.remove(OPEN_CODE_PROVIDER_ID)
    if (legacyProviders?.size() == 0) root.remove("provider")
    return provider
}

private fun migrateRequestOptions(parent: JsonObject) {
    val options = parent.remove("options")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
    options.remove("headers")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (key, value) ->
        parent.objectOrCreate("headers").add(key, value)
    }
    options.remove("body")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (key, value) ->
        parent.objectOrCreate("body").add(key, value)
    }
    for ((old, native) in mapOf("store" to "store", "previousResponseId" to "previous_response_id",
        "previous_response_id" to "previous_response_id", "temperature" to "temperature", "topP" to "top_p")) {
        options.remove(old)?.let { parent.objectOrCreate("body").add(native, it) }
    }
    options.entrySet().forEach { (key, value) -> parent.objectOrCreate("settings").add(key, value) }
}

private fun migrateModel(model: JsonObject) {
    model.remove("id")?.let { model.add("modelID", it) }
    val modelProvider = model.remove("provider")?.takeIf { it.isJsonObject }?.asJsonObject
    modelProvider?.get("npm")?.let { model.addProperty("package", openCodePackage(it.asString)) }
    model.remove("npm")?.let { model.addProperty("package", openCodePackage(it.asString)) }
    model.remove("release_date")?.let { model.add("releaseDate", it) }
    model.remove("tool_call")?.let { model.objectOrCreate("capabilities").add("tools", it) }
    model.remove("reasoning")?.let { model.objectOrCreate("capabilities").objectOrCreate("reasoning").add("supported", it) }
    model.remove("temperature")?.let { model.objectOrCreate("capabilities").add("temperature", it) }
    model.remove("modalities")?.takeIf { it.isJsonObject }?.asJsonObject?.let { modalities ->
        for (key in listOf("input", "output")) modalities.get(key)?.let { model.objectOrCreate("capabilities").add(key, it) }
    }
    model.remove("status")?.let { if (it.asString == "deprecated") model.addProperty("disabled", true) }
    model.remove("interleaved")?.let { interleaved ->
        val field = if (interleaved.isJsonObject) interleaved.asJsonObject.get("field")
            else interleaved.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        field?.let { model.objectOrCreate("compatibility").add("reasoningField", it) }
    }
    // These V1 fields have no supported V2 behavior (official migration guide).
    listOf("attachment", "experimental").forEach(model::remove)
    model.getAsJsonObject("cost")?.let { cost ->
        fun migrateCache(value: JsonObject) {
            value.remove("cache_read")?.let { value.objectOrCreate("cache").add("read", it) }
            value.remove("cache_write")?.let { value.objectOrCreate("cache").add("write", it) }
        }
        migrateCache(cost)
        cost.remove("context_over_200k")?.takeIf { it.isJsonObject }?.asJsonObject?.let { tier ->
            migrateCache(tier)
            tier.add("tier", JsonObject().apply {
                addProperty("type", "context")
                addProperty("size", 200_000)
            })
            model.add("cost", JsonArray().apply { add(cost); add(tier) })
        }
    }
    migrateRequestOptions(model)
    modelProvider?.get("api")?.let { model.objectOrCreate("settings").add("baseURL", it) }
    model.get("variants")?.takeIf { it.isJsonObject }?.asJsonObject?.let { variants ->
        model.add("variants", JsonArray().apply {
            variants.entrySet().forEach { (id, entry) ->
                if (entry.isJsonObject && entry.asJsonObject.get("disabled")?.asBoolean != true) {
                    val options = entry.asJsonObject.deepCopy().apply { remove("disabled") }
                    add(JsonObject().apply {
                        addProperty("id", id)
                        add("options", options)
                        migrateRequestOptions(this)
                    })
                }
            }
        })
    }
}

internal fun JsonObject.objectOrCreate(key: String): JsonObject =
    getAsJsonObject(key) ?: JsonObject().also { add(key, it) }

private fun openCodePackage(npm: String): String =
    if (npm.startsWith("@opencode/ai/") || npm.startsWith("aisdk:")) npm else "aisdk:$npm"

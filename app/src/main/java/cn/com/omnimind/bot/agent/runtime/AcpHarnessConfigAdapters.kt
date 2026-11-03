package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import com.google.gson.JsonParser

internal object CodexConfigAdapter : AgentConfigAdapter {
    override suspend fun readConfig(
        input: AgentProviderMappingInput,
        access: AgentConfigFileAccess,
    ): Map<String, Any?> {
        val configToml = access.read(
            CODEX_CONFIG_TOML_PATH,
            "codex-agent-config-read",
        )
        val provider = input.provider
        val authJson = if (input.usesHostManagedProvider) "" else access.read(
            CODEX_AUTH_JSON_PATH,
            "codex-agent-auth-read",
        )
        return linkedMapOf(
            "agentId" to input.agentId,
            "kind" to "codex",
            "configPath" to CODEX_CONFIG_TOML_DISPLAY_PATH,
            "authPath" to CODEX_AUTH_JSON_DISPLAY_PATH,
            "baseUrl" to (if (input.usesHostManagedProvider) {
                input.providerProfile?.baseUrl.orEmpty()
            } else provider?.baseUrl ?: extractTomlString(configToml, "base_url").orEmpty()),
            "model" to input.model.orEmpty(),
            "apiKey" to extractOpenAiApiKey(authJson).orEmpty(),
        )
    }

    override fun directConfigWrites(
        input: AgentProviderMappingInput,
        args: Map<String, Any?>,
        providerModels: List<ProviderModelOption>,
    ): List<AgentConfigWrite> {
        if (input.usesHostManagedProvider) {
            val model = resolveAcpLaunchModelForDispatch(providerModels.map { it.id }, input.model)
                ?: throw IllegalArgumentException("Select a Provider model first.")
            val catalog = (providerModels + ProviderModelOption(id = model)).distinctBy { it.id }
            if (input.provider != null) {
                // The manager supplies this process's existing grant when online.
                val configured = input.copy(model = model)
                return launchConfigWrites(configured, map(configured), catalog, "")
            }
            // Saving offline settings must not manufacture a usable route or
            // fetch credentials. The next launch writes its actual relay URL.
            return listOf(AgentConfigWrite(
                path = CODEX_MODEL_CATALOG_JSON_PATH,
                content = buildCodexModelCatalogJson(catalog),
                executorKey = "codex-agent-config-write",
            ))
        }
        val baseUrl = args.agentConfigStringValue("baseUrl")
            ?: throw IllegalArgumentException("Base URL is required.")
        val model = args.agentConfigStringValue("model")
            ?: throw IllegalArgumentException("Model ID is required.")
        val apiKey = args.agentConfigStringValue("apiKey")
            ?: throw IllegalArgumentException("API Key is required.")
        val resolvedModel = resolveAcpLaunchModel(
            providerModelIds = providerModels.map(ProviderModelOption::id),
            boundModel = model,
        ) ?: throw IllegalArgumentException(
            "Model must be selected from the current Provider /models response."
        )
        // The complete Codex surface consists of three files and is emitted
        // by launchConfigWrites. This marker lets the manager use the same
        // adapter-owned path without a profile-id switch.
        val provider = input.provider ?: throw IllegalArgumentException(
            "Provider settings are required for Codex configuration."
        )
        val mapping = map(input.copy(
            provider = provider.copy(baseUrl = baseUrl, apiKey = apiKey),
            model = resolvedModel,
        ))
        return listOf(
            AgentConfigWrite(
                path = CODEX_CONFIG_TOML_PATH,
                content = buildCodexConfigToml(
                    baseUrl = mapping.codexBaseUrl ?: normalizeCodexBaseUrl(baseUrl),
                    model = mapping.codexModel ?: resolvedModel,
                    wireApi = mapping.codexWireApi ?: OpenAiWireApi.RESPONSES,
                    modelCatalogPath = CODEX_MODEL_CATALOG_JSON_PATH,
                    envHttpHeaders = mapping.codexEnvHttpHeaders,
                    authentication = provider.authentication,
                ),
                executorKey = "codex-agent-config-write",
            ),
            AgentConfigWrite(
                path = CODEX_AUTH_JSON_PATH,
                content = buildCodexAuthJson(apiKey),
                executorKey = "codex-agent-config-write",
            ),
            AgentConfigWrite(
                path = CODEX_MODEL_CATALOG_JSON_PATH,
                content = buildCodexModelCatalogJson(
                    providerModels = providerModels,
                    provider = provider,
                ),
                executorKey = "codex-agent-config-write",
            ),
        )
    }

    override fun map(input: AgentProviderMappingInput): AgentProviderMapping {
        val provider = input.provider
        require(provider?.protocolType != "anthropic") {
            "Codex requires an OpenAI Responses-compatible endpoint; the current Provider is configured as Anthropic."
        }
        val headerBindings = provider?.let { buildAcpHeaderBindings(it.customHeaders) }
        val environment = if (provider == null) {
            mapOf("CODEX_HOME" to AgentRuntimeDefaults.CODEX_HOME)
        } else {
            mapOf(
                "CODEX_HOME" to AgentRuntimeDefaults.CODEX_HOME,
                "OPENAI_BASE_URL" to normalizeCodexBaseUrl(provider.baseUrl),
                "OPENAI_API_KEY" to provider.apiKey
            ) + headerBindings?.environment.orEmpty()
        }
        return AgentProviderMapping(
            environment = environment,
            codexModel = input.model?.trim()?.takeIf { it.isNotEmpty() },
            // Current Codex ACP (1.1.x) removed the legacy Chat Completions
            // wire and rejects `wire_api = "chat"` during every request.
            // The shared Provider may still use Chat Completions for the app
            // and other agents, but Codex must receive its official Responses
            // transport setting.
            codexWireApi = provider?.let { OpenAiWireApi.RESPONSES },
            codexBaseUrl = provider?.baseUrl?.let(::normalizeCodexBaseUrl),
            codexEnvHttpHeaders = headerBindings?.envHttpHeaders.orEmpty(),
        )
    }

    override fun launchConfigWrites(
        input: AgentProviderMappingInput,
        mapping: AgentProviderMapping,
        providerModels: List<ProviderModelOption>,
        existingConfig: String,
    ): List<AgentConfigWrite> {
        val provider = input.provider ?: return emptyList()
        val model = mapping.codexModel ?: return emptyList()
        return buildList {
            add(AgentConfigWrite(
                path = CODEX_CONFIG_TOML_PATH,
                content = buildCodexConfigToml(
                    baseUrl = mapping.codexBaseUrl ?: provider.baseUrl,
                    model = model,
                    wireApi = mapping.codexWireApi ?: OpenAiWireApi.RESPONSES,
                    modelCatalogPath = CODEX_MODEL_CATALOG_JSON_PATH,
                    envHttpHeaders = mapping.codexEnvHttpHeaders,
                    authentication = provider.authentication,
                ),
                executorKey = "codex-agent-config-write",
            ))
            // env_key bypasses any earlier auth.json without deleting a user's
            // existing login. A process grant must never be persisted there.
            if (!provider.isHostManaged) add(AgentConfigWrite(
                path = CODEX_AUTH_JSON_PATH,
                content = buildCodexAuthJson(provider.apiKey),
                executorKey = "codex-agent-config-write",
            ))
            add(AgentConfigWrite(
                path = CODEX_MODEL_CATALOG_JSON_PATH,
                content = buildCodexModelCatalogJson(
                    providerModels = providerModels,
                    provider = provider,
                ),
                executorKey = "codex-agent-config-write",
            ))
        }
    }
}

internal object ClaudeCodeConfigAdapter : AgentConfigAdapter {
    override fun launchConfigWrites(
        input: AgentProviderMappingInput,
        mapping: AgentProviderMapping,
        providerModels: List<ProviderModelOption>,
        existingConfig: String,
    ): List<AgentConfigWrite> {
        val model = input.model ?: return emptyList()
        val settings = if (existingConfig.isBlank()) com.google.gson.JsonObject()
            else JsonParser.parseString(existingConfig).asJsonObject
        settings.add("availableModels", com.google.gson.Gson().toJsonTree(
            (providerModels.map { it.id } + model).filter { it.isNotBlank() }.distinct(),
        ))
        return listOf(AgentConfigWrite(
            CLAUDE_SETTINGS_CONFIG_PATH, settings.toString(), "claude-model-catalog-write",
        ))
    }

    override suspend fun readConfig(
        input: AgentProviderMappingInput,
        access: AgentConfigFileAccess,
    ): Map<String, Any?> = linkedMapOf(
        "agentId" to input.agentId,
        "kind" to "json",
        "path" to CLAUDE_SETTINGS_JSON_DISPLAY_PATH,
        "content" to access.read(
            CLAUDE_SETTINGS_CONFIG_PATH,
            "agent-config-read-${input.agentId}",
        ).ifBlank { DEFAULT_EMPTY_JSON_FILE },
    )

    override fun map(input: AgentProviderMappingInput): AgentProviderMapping {
        val provider = input.provider ?: return AgentProviderMapping()
        val model = input.model?.trim()?.takeIf { it.isNotEmpty() }
        val anthropicBaseUrl = normalizeClaudeCodeBaseUrl(provider.baseUrl)
        val customHeaders = provider.customHeaders
            .entries
            .joinToString("\n") { (name, value) -> "$name: $value" }
        // A shared gateway may expose Messages alongside its configured OpenAI
        // route. Pass the selected credentials/model to Claude; the actual
        // request owns compatibility errors, not the Provider's protocol label.
        return AgentProviderMapping(
            launchConfigPath = CLAUDE_SETTINGS_CONFIG_PATH,
            launchConfigExecutorKey = "claude-model-catalog-read",
            environment = buildMap {
                put("ANTHROPIC_BASE_URL", anthropicBaseUrl)
                put("ANTHROPIC_API_KEY", provider.apiKey)
                put("ANTHROPIC_AUTH_TOKEN", provider.apiKey)
                if (customHeaders.isNotBlank()) {
                    put("ANTHROPIC_CUSTOM_HEADERS", customHeaders)
                }
                if (model != null) {
                    // Official Claude Code model override. Without this the
                    // CLI falls back to claude-opus and a shared gateway can
                    // reject the request before it reaches the model.
                    put("ANTHROPIC_MODEL", model)
                    put("ANTHROPIC_SMALL_FAST_MODEL", model)
                }
            }
        )
    }

    override fun directConfigWrites(
        input: AgentProviderMappingInput,
        args: Map<String, Any?>,
        providerModels: List<ProviderModelOption>,
    ): List<AgentConfigWrite> {
        val content = args.agentConfigStringValuePreservingWhitespace("content")
            ?.ifBlank { DEFAULT_EMPTY_JSON_FILE }
            ?: throw IllegalArgumentException("settings.json content is required.")
        runCatching {
            require(JsonParser.parseString(content).isJsonObject)
        }.getOrElse {
            throw IllegalArgumentException(
                "Claude Code settings.json must contain a valid JSON object.",
                it,
            )
        }
        return listOf(
            AgentConfigWrite(
                path = CLAUDE_SETTINGS_CONFIG_PATH,
                content = content,
                executorKey = "agent-config-write-${input.agentId}",
            ),
        )
    }
}

private fun isAnthropicCompatibleBaseUrl(value: String): Boolean {
    return value.endsWith("/anthropic", ignoreCase = true) ||
        value.endsWith("/apps/anthropic", ignoreCase = true)
}

internal object KimiCodeConfigAdapter : AgentConfigAdapter {
    override fun launchConfigWrites(
        input: AgentProviderMappingInput,
        mapping: AgentProviderMapping,
        providerModels: List<ProviderModelOption>,
        existingConfig: String,
    ): List<AgentConfigWrite> {
        val provider = input.provider ?: return emptyList()
        val model = input.model ?: return emptyList()
        return buildKimiCodeManagedFiles(provider, model, providerModels = providerModels).map { (path, content) ->
            AgentConfigWrite(path, content, "kimi-agent-config-write")
        }
    }

    override fun map(input: AgentProviderMappingInput): AgentProviderMapping =
        AgentProviderMapping(environment = kimiCodeBaseEnvironment() +
            if (input.provider?.isHostManaged == true) {
                mapOf("OPENAI_API_KEY" to input.provider.apiKey)
            } else emptyMap())

}

internal object OpenCodeConfigAdapter : AgentConfigAdapter {
    override suspend fun readConfig(
        input: AgentProviderMappingInput,
        access: AgentConfigFileAccess,
    ): Map<String, Any?> = linkedMapOf(
        "agentId" to input.agentId,
        "kind" to "jsonc",
        "path" to OPENCODE_CONFIG_JSON_DISPLAY_PATH,
        "content" to access.read(
            OPENCODE_CONFIG_PATH,
            "agent-config-read-${input.agentId}",
        ).ifBlank { DEFAULT_EMPTY_JSON_FILE },
    )

    override fun map(input: AgentProviderMappingInput): AgentProviderMapping {
        val provider = input.provider ?: return AgentProviderMapping()
        val model = input.model?.trim()?.takeIf { it.isNotEmpty() }
        val headerBindings = buildAcpHeaderBindings(provider.customHeaders)
        return AgentProviderMapping(
            environment = mapOf(
                "OPENAI_BASE_URL" to normalizeOpenCodeBaseUrl(provider.baseUrl),
                "OPENAI_API_KEY" to provider.apiKey
            ) + headerBindings.environment,
            openCodeModel = model?.let { "$OPEN_CODE_PROVIDER_ID/$it" },
            openCodeBaseUrl = normalizeOpenCodeBaseUrl(provider.baseUrl),
            launchConfigPath = OPENCODE_CONFIG_PATH,
            launchConfigExecutorKey = "opencode-agent-config-read",
        )
    }

    override fun launchConfigWrites(
        input: AgentProviderMappingInput,
        mapping: AgentProviderMapping,
        providerModels: List<ProviderModelOption>,
        existingConfig: String,
    ): List<AgentConfigWrite> {
        val provider = input.provider ?: return emptyList()
        val model = mapping.openCodeModel ?: return emptyList()
        return listOf(
            AgentConfigWrite(
                path = OPENCODE_CONFIG_PATH,
                content = buildOpenCodeConfigJson(
                    model = model,
                    baseUrl = mapping.openCodeBaseUrl ?: provider.baseUrl,
                    existingConfigJson = existingConfig,
                    customHeaders = provider.customHeaders,
                    providerModels = providerModels,
                    protocolType = provider.protocolType,
                    wireApi = provider.wireApi,
                ).let { config ->
                    if (provider.isHostManaged) configureHostManagedOpenCode(config) else config
                },
                executorKey = "opencode-agent-config-write",
            )
        )
    }

    override fun directConfigWrites(
        input: AgentProviderMappingInput,
        args: Map<String, Any?>,
        providerModels: List<ProviderModelOption>,
    ): List<AgentConfigWrite> {
        val content = args.agentConfigStringValuePreservingWhitespace("content")
            ?.ifBlank { DEFAULT_EMPTY_JSON_FILE }
            ?: throw IllegalArgumentException("opencode.json content is required.")
        requireJsonObjectLike(content, "opencode.json")
        return listOf(
            AgentConfigWrite(
                path = OPENCODE_CONFIG_PATH,
                content = content,
                executorKey = "agent-config-write-${input.agentId}",
            ),
        )
    }
}

private fun Map<String, Any?>.agentConfigStringValue(key: String): String? =
    this[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

private fun Map<String, Any?>.agentConfigStringValuePreservingWhitespace(key: String): String? =
    this[key]?.toString()

private fun requireJsonObjectLike(content: String, name: String) {
    val trimmed = content.trim()
    require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
        "$name must contain a JSON object."
    }
}

internal fun buildCodexConfigToml(
    baseUrl: String,
    model: String,
    wireApi: String = OpenAiWireApi.RESPONSES,
    modelCatalogPath: String? = null,
    envHttpHeaders: Map<String, String> = emptyMap(),
    authentication: AgentProviderAuthentication = AgentProviderAuthentication.API_KEY,
): String {
    val hostManaged = authentication == AgentProviderAuthentication.HOST_MANAGED
    require(!hostManaged || OpenAiWireApi.isResponses(wireApi)) {
        "Host-managed Codex Provider requires Responses."
    }
    val codexWireApi = if (OpenAiWireApi.isResponses(wireApi)) {
        OpenAiWireApi.RESPONSES
    } else {
        "chat"
    }
    val lines = mutableListOf(
        "model_provider = \"omnimind\"",
        "model = ${tomlString(model.trim())}",
        "disable_response_storage = true",
        modelCatalogPath?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "model_catalog_json = ${tomlString(it)}" }
            .orEmpty(),
        "",
        "[model_providers.omnimind]",
        "name = \"omnimind\"",
        "base_url = ${tomlString(baseUrl.trim())}",
        "wire_api = \"$codexWireApi\"",
        "requires_openai_auth = ${!hostManaged}",
    )
    if (hostManaged) {
        lines += "env_key = \"OPENAI_API_KEY\""
        lines += "supports_websockets = false"
    }
    lines += envHttpHeaders.takeIf { it.isNotEmpty() }?.let { headers ->
            "env_http_headers = { " + headers.entries.joinToString(", ") {
                "${tomlString(it.key)} = ${tomlString(it.value)}"
            } + " }"
        }.orEmpty()
    return lines.joinToString(separator = "\n", postfix = "\n")
}

/** Keep the managed route stateless while retaining unrelated user settings. */
private fun configureHostManagedOpenCode(config: String): String {
    val root = JsonParser.parseString(config).asJsonObject
    val provider = root.getAsJsonObject("providers").getAsJsonObject(OPEN_CODE_PROVIDER_ID)
    fun statelessRequest(parent: com.google.gson.JsonObject) {
        for (key in listOf("settings", "body")) {
            parent.getAsJsonObject(key)?.apply {
                remove("store")
                remove("previousResponseId")
                remove("previous_response_id")
            }
        }
        parent.objectOrCreate("body").addProperty("store", false)
    }
    statelessRequest(provider)
    provider.getAsJsonObject("models").entrySet().forEach { (_, entry) ->
        if (entry.isJsonObject) {
            val model = entry.asJsonObject
            statelessRequest(model)
            model.getAsJsonArray("variants")?.forEach { variant ->
                if (variant.isJsonObject) statelessRequest(variant.asJsonObject)
            }
        }
    }
    return com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n"
}

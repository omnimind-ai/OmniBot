package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.baselib.account.OmniAccount
import cn.com.omnimind.baselib.http.OkHttpManager
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.OmniOfficialProvider
import cn.com.omnimind.baselib.llm.PlatformAiProvisioner
import cn.com.omnimind.baselib.llm.ProviderCustomHeaderUtils
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.util.ContentEndpointSecurity
import cn.com.omnimind.baselib.util.CredentialEndpointSecurity
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.media.PlatformMediaGatewayExecutor
import cn.com.omnimind.bot.media.PlatformGatewayException
import cn.com.omnimind.bot.media.PlatformMediaProtocol
import cn.com.omnimind.bot.media.awaitResponse
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ImageGenerationToolHandler(
    private val helper: SharedHelper,
    private val workspaceManager: AgentWorkspaceManager,
) : ToolHandler {
    override val toolNames: Set<String> = setOf("image_generate")

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val platformDownloadClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = Dns.SYSTEM.lookup(hostname)
                if (addresses.isEmpty() || addresses.any { !isPublicPlatformAddress(it) }) {
                    throw UnknownHostException(
                        "official image host resolved to a non-public address"
                    )
                }
                return addresses
            }
        })
        .build()
    private val platformExecutor = PlatformMediaGatewayExecutor(
        executeRequest = { request ->
            OkHttpManager.sensitiveContentCall(
                client = httpClient,
                request = request,
                allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
            ).awaitResponse()
        },
    )

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult = executeImageGenerate(args, env, callback, toolHandle)

    private suspend fun executeImageGenerate(
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        val toolName = "image_generate"
        return try {
            val workspace = env.workspaceDescriptor
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            helper.requirePublicStorageAccessIfNeeded(
                callback,
                args["outputPath"]?.jsonPrimitive?.contentOrNull,
            )?.let { return it }

            val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(prompt.isNotEmpty()) { "prompt cannot be empty" }
            require(prompt.toByteArray(Charsets.UTF_8).size <= MAX_IMAGE_PROMPT_BYTES) {
                "image prompt exceeds the 64 KB limit"
            }
            val outputPath = args["outputPath"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(outputPath.isNotEmpty()) { "outputPath cannot be empty" }

            val route = resolveRoute(args, env)
            val size = args["size"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: "1024x1024"
            val quality = args["quality"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: "auto"
            val requestedFormat = args["format"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.lowercase()
                ?.takeIf { it in SUPPORTED_OUTPUT_FORMATS }
                ?: outputPath.substringAfterLast('.', missingDelimiterValue = "")
                    .lowercase()
                    .takeIf { it in SUPPORTED_OUTPUT_FORMATS }
                ?: "png"
            val background = args["background"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: "auto"

            val file = workspaceManager.resolvePath(
                inputPath = outputPath,
                workspace = workspace,
                allowPublicStorage = true,
            )

            helper.reportToolProgress(
                callback,
                toolName,
                "Generating image",
                mapOf("model" to route.model, "outputPath" to outputPath),
                toolHandle,
            )

            val imageBytes = withContext(Dispatchers.IO) {
                requestGeneratedImage(
                    route = route,
                    prompt = prompt,
                    size = size,
                    quality = quality,
                    outputFormat = requestedFormat,
                    background = background,
                )
            }
            requireSupportedImage(imageBytes)

            file.parentFile?.mkdirs()
            file.writeBytes(imageBytes)

            val artifact = workspaceManager.buildArtifactForFile(file, toolName)
            val payload = linkedMapOf<String, Any?>(
                "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                "androidPath" to file.absolutePath,
                "uri" to artifact.uri,
                "size" to file.length(),
                "mimeType" to workspaceManager.guessMimeType(file),
                "model" to route.model,
                "providerProfileId" to route.providerProfileId,
                "providerProfileName" to route.providerProfileName,
            )
            val payloadJson = helper.encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("Generated image: ${file.name}"),
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = true,
                artifacts = listOf(artifact),
                workspaceId = workspace.id,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            helper.workspacePermissionResult(error, callback)?.let { return it }
            val safeMessage = if (error is PlatformGatewayException) {
                error.message
            } else {
                "Image generation failed (${error.javaClass.simpleName})"
            }
            helper.errorResult(toolName, safeMessage, "Image generation failed")
        }
    }

    private suspend fun resolveRoute(
        args: JsonObject,
        env: AgentExecutionEnvironment,
    ): ImageGenerationRoute {
        val profileId = args["providerProfileId"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: env.modelProviderProfileId?.trim()?.takeIf(String::isNotEmpty)
            ?: SceneModelBindingStore.getBinding("scene.dispatch.model")?.providerProfileId
        val access = OmniAccount.currentAiRequestAccess()
        if (OmniOfficialProvider.isOfficialProfile(profileId)) {
            access.unavailableReason?.let { throw IllegalStateException(it) }
            check(access.usesPlatform) { "官方 AI 账号未登录或服务暂不可用" }
            val status = PlatformAiProvisioner.ensureReadyStatus()
            val model = status.defaultImageModelId
                ?: throw IllegalStateException("官方图片生成能力暂不可用")
            return ImageGenerationRoute(
                platform = true,
                endpoint = "",
                apiKey = "",
                customHeaders = emptyMap(),
                model = model,
                providerProfileId = OmniOfficialProvider.PROFILE_ID,
                providerProfileName = OmniOfficialProvider.PROFILE_NAME,
            )
        }

        val profile = profileId?.let(ModelProviderConfigStore::getProfile)
            ?: ModelProviderConfigStore.getEditingProfile()
        val bundledImageConfig = bundledImageProviderConfig()
        val apiKey = profile.apiKey.trim()
        val useBundledImageProvider = shouldUseBundledImageProvider(
            profileApiKey = apiKey,
            bundledApiKey = bundledImageConfig.apiKey,
        )
        if (!useBundledImageProvider) {
            require(!profile.readOnly && !OmniOfficialProvider.isOfficialProfile(profile.id)) {
                "The current provider is read-only and cannot generate images. Select a BYOK provider profile."
            }
            require(profile.isConfigured()) {
                "Confirm the BYOK provider destination before generating images."
            }
        }
        val effectiveApiKey = if (useBundledImageProvider) bundledImageConfig.apiKey else apiKey
        require(effectiveApiKey.isNotEmpty()) {
            "Image provider apiKey is empty. Configure a BYOK OpenAI-compatible provider profile."
        }
        val requestedModel = normalizeImageModelId(args["model"]?.jsonPrimitive?.contentOrNull)
        return ImageGenerationRoute(
            platform = false,
            endpoint = resolveImageGenerationEndpoint(
                baseUrl = if (useBundledImageProvider) bundledImageConfig.baseUrl else profile.baseUrl,
                apiKey = effectiveApiKey,
            ),
            apiKey = effectiveApiKey,
            customHeaders = if (useBundledImageProvider) emptyMap() else profile.customHeaders,
            model = requestedModel
                ?: if (useBundledImageProvider) bundledImageConfig.model else DEFAULT_IMAGE_MODEL,
            providerProfileId = if (useBundledImageProvider) {
                BUNDLED_IMAGE_PROVIDER_ID
            } else {
                profile.id
            },
            providerProfileName = if (useBundledImageProvider) {
                BUNDLED_IMAGE_PROVIDER_NAME
            } else {
                profile.name
            },
        )
    }

    private suspend fun requestGeneratedImage(
        route: ImageGenerationRoute,
        prompt: String,
        size: String,
        quality: String,
        outputFormat: String,
        background: String,
    ): ByteArray {
        val requestJson = JSONObject().apply {
            put("model", route.model)
            put("prompt", prompt)
            put("n", 1)
            put("size", size)
            put("quality", quality)
            put("output_format", outputFormat)
            put("background", background)
            if (route.platform) {
                put("response_format", "b64_json")
            }
        }

        val response = if (route.platform) {
            platformExecutor.execute { credentials ->
                buildRequest(
                    endpoint = PlatformMediaProtocol.endpoint(
                        credentials,
                        "/v1/images/generations",
                    ),
                    apiKey = credentials.bearerToken,
                    customHeaders = emptyMap(),
                    requestJson = requestJson,
                )
            }
        } else {
            val request = buildRequest(
                endpoint = route.endpoint,
                apiKey = route.apiKey,
                customHeaders = route.customHeaders,
                requestJson = requestJson,
                allowInsecureTransport = true,
            )
            OkHttpManager.sensitiveContentCall(
                client = httpClient,
                request = request,
                allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
                allowInsecureTransport = true,
            ).awaitResponse()
        }

        response.use {
            val bytes = PlatformMediaProtocol.readBodyLimited(it, MAX_IMAGE_JSON_BYTES)
            if (route.platform) {
                PlatformMediaProtocol.requireSuccessfulResponse(it.code, bytes)
            } else if (!it.isSuccessful) {
                throw IllegalStateException("image generation request failed (${it.code})")
            }
            val payload = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }
                .getOrElse { throw IllegalStateException("image generation returned invalid JSON") }
            return extractGeneratedImage(payload, route.platform)
                ?: throw IllegalStateException(
                    "image generation response did not contain b64_json or image url"
                )
        }
    }

    private fun buildRequest(
        endpoint: String,
        apiKey: String,
        customHeaders: Map<String, String>,
        requestJson: JSONObject,
        allowInsecureTransport: Boolean = false,
    ): Request {
        val safeEndpoint = ContentEndpointSecurity.requireSafe(
            rawUrl = endpoint,
            allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
            allowInsecureTransport = allowInsecureTransport,
        )
        val mergedHeaders = ProviderCustomHeaderUtils.mergeHeaders(
            builtIn = linkedMapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "Authorization" to "Bearer $apiKey",
            ),
            custom = customHeaders,
        )
        return Request.Builder()
            .url(safeEndpoint)
            .apply {
                mergedHeaders.forEach { (key, value) -> header(key, value) }
            }
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private suspend fun extractGeneratedImage(payload: JSONObject, platform: Boolean): ByteArray? {
        val data = payload.optJSONArray("data")
        if (data != null && data.length() > 0) {
            val first = data.optJSONObject(0) ?: JSONObject()
            first.optString("b64_json").takeIf(String::isNotBlank)
                ?.let(::decodeBase64Image)?.let { return it }
            first.optString("url").takeIf(String::isNotBlank)
                ?.let { return downloadImage(it, platform) }
        }
        val responseFormat = payload.optString("format").takeIf(String::isNotBlank)
        val output = payload.optJSONArray("output") ?: return null
        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            extractImageFromOutputItem(item, responseFormat)?.let { return it }
            item.optString("url").takeIf(String::isNotBlank)
                ?.let { return downloadImage(it, platform) }
        }
        return null
    }

    private fun extractImageFromOutputItem(item: JSONObject, responseFormat: String?): ByteArray? {
        item.optString("b64_json").takeIf(String::isNotBlank)?.let(::decodeBase64Image)
            ?.let { return it }
        item.optString("result").takeIf(String::isNotBlank)?.let(::decodeBase64Image)
            ?.let { return it }
        item.optString("image_base64").takeIf(String::isNotBlank)?.let(::decodeBase64Image)
            ?.let { return it }
        val content = item.optJSONArray("content") ?: return null
        for (index in 0 until content.length()) {
            val contentItem = content.optJSONObject(index) ?: continue
            contentItem.optString("b64_json").takeIf(String::isNotBlank)
                ?.let(::decodeBase64Image)?.let { return it }
            contentItem.optString("image_base64").takeIf(String::isNotBlank)
                ?.let(::decodeBase64Image)?.let { return it }
            val dataUrl = contentItem.optString("image_url")
                .takeIf { it.startsWith("data:", ignoreCase = true) }
            dataUrl?.let(::decodeBase64Image)?.let { return it }
            if (responseFormat == "b64_json") {
                contentItem.optString("result").takeIf(String::isNotBlank)
                    ?.let(::decodeBase64Image)?.let { return it }
            }
        }
        return null
    }

    private suspend fun downloadImage(url: String, platform: Boolean): ByteArray {
        if (platform && !isSafePlatformDownloadUrl(url)) {
            throw IllegalStateException("official image response contained an unsafe download URL")
        }
        val safeUrl = ContentEndpointSecurity.requireSafe(
            rawUrl = url,
            allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
            allowInsecureTransport = !platform,
        )
        val request = Request.Builder().url(safeUrl).get().build()
        val client = if (platform) platformDownloadClient else httpClient
        return OkHttpManager.sensitiveContentCall(
            client = client,
            request = request,
            allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
            allowInsecureTransport = !platform,
        ).awaitResponse().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("image download failed (${response.code})")
            }
            PlatformMediaProtocol.readBodyLimited(response, MAX_IMAGE_BYTES).also(::requireSupportedImage)
        }
    }

    private fun normalizeImageModelId(rawModel: String?): String? =
        rawModel?.trim()?.takeIf(String::isNotEmpty)?.replace(Regex("\\s+"), "")

    private data class BundledImageProviderConfig(
        val baseUrl: String,
        val model: String,
        val apiKey: String,
    )

    private fun bundledImageProviderConfig(): BundledImageProviderConfig =
        BundledImageProviderConfig(
            baseUrl = BuildConfig.IMAGE_BASE_URL.trim().ifBlank { DEFAULT_IMAGE_BASE_URL },
            model = BuildConfig.IMAGE_MODEL.trim().ifBlank { DEFAULT_IMAGE_MODEL },
            apiKey = BuildConfig.IMAGE_API_KEY.trim(),
        )

    private data class ImageGenerationRoute(
        val platform: Boolean,
        val endpoint: String,
        val apiKey: String,
        val customHeaders: Map<String, String>,
        val model: String,
        val providerProfileId: String,
        val providerProfileName: String,
    )

    companion object {
        private const val BUNDLED_IMAGE_PROVIDER_ID = "bundled-image-provider"
        private const val BUNDLED_IMAGE_PROVIDER_NAME = "Xiaowan Image Provider"
        internal const val DEFAULT_IMAGE_BASE_URL = "https://cloud.omnimind.com.cn"
        internal const val DEFAULT_IMAGE_MODEL = "gpt-image-2"
        internal const val MAX_IMAGE_BYTES: Long = 20L * 1024L * 1024L
        internal const val MAX_IMAGE_JSON_BYTES: Long = 32L * 1024L * 1024L
        internal const val MAX_IMAGE_PROMPT_BYTES: Int = 64 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val SUPPORTED_OUTPUT_FORMATS = setOf("png", "webp", "jpeg")
        private val IMAGE_GENERATION_ENDPOINT_SUFFIXES = listOf(
            "/v1/images/generations",
            "/images/generations",
        )

        internal fun shouldUseBundledImageProvider(
            profileApiKey: String,
            bundledApiKey: String,
        ): Boolean = bundledApiKey.isNotBlank() && profileApiKey.isBlank()

        internal fun resolveImageGenerationEndpoint(baseUrl: String, apiKey: String): String {
            require(apiKey.isNotBlank()) { "Image provider apiKey is empty" }
            val raw = baseUrl.trim()
            require(raw.isNotEmpty()) { "Image provider baseUrl is empty" }
            val stripped = ModelProviderConfigStore.stripDirectRequestUrlMarker(raw).trimEnd('/')
            if (ModelProviderConfigStore.hasDirectRequestUrlMarker(raw)) {
                return stripped
            }
            if (IMAGE_GENERATION_ENDPOINT_SUFFIXES.any { stripped.endsWith(it, true) }) {
                return stripped
            }
            return if (stripped.endsWith("/v1", true)) {
                "$stripped/images/generations"
            } else {
                "$stripped/v1/images/generations"
            }
        }

        internal fun decodeBase64Image(encoded: String): ByteArray? {
            val normalized = encoded.substringAfter(',', encoded).filterNot(Char::isWhitespace)
            if (normalized.isBlank()) return null
            val maximumEncodedLength = ((MAX_IMAGE_BYTES + 2L) / 3L) * 4L + 4L
            if (normalized.length.toLong() > maximumEncodedLength) {
                throw IllegalStateException("generated image exceeds the 20 MB limit")
            }
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            val decoded = runCatching { Base64.getDecoder().decode(padded) }
                .recoverCatching { Base64.getUrlDecoder().decode(padded) }
                .getOrNull()
                ?: return null
            if (decoded.size.toLong() > MAX_IMAGE_BYTES) {
                throw IllegalStateException("generated image exceeds the 20 MB limit")
            }
            return decoded
        }

        internal fun isSafePlatformDownloadUrl(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            if (!parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
                return false
            }
            val host = parsed.host.lowercase()
            if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
                return false
            }
            val ipv4 = host.split('.').mapNotNull(String::toIntOrNull)
            if (ipv4.size == 4 && ipv4.all { it in 0..255 }) {
                val first = ipv4[0]
                val second = ipv4[1]
                return !(first == 0 || first == 10 || first == 127 ||
                    first >= 224 ||
                    (first == 100 && second in 64..127) ||
                    (first == 169 && second == 254) ||
                    (first == 172 && second in 16..31) ||
                    (first == 192 && second == 168) ||
                    (first == 198 && second in 18..19))
            }
            if (host.contains(':')) {
                return host != "::" && host != "::1" &&
                    !host.startsWith("fc") && !host.startsWith("fd") &&
                    !host.startsWith("fe8") && !host.startsWith("fe9") &&
                    !host.startsWith("fea") && !host.startsWith("feb")
            }
            return true
        }

        internal fun isPublicPlatformAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress ||
                address.isMulticastAddress
            ) {
                return false
            }
            val bytes = address.address
            if (bytes.size == 4) {
                val first = bytes[0].toInt() and 0xFF
                val second = bytes[1].toInt() and 0xFF
                return !(first == 0 || first == 10 || first == 127 || first >= 224 ||
                    (first == 100 && second in 64..127) ||
                    (first == 169 && second == 254) ||
                    (first == 172 && second in 16..31) ||
                    (first == 192 && second == 168) ||
                    (first == 198 && second in 18..19))
            }
            if (bytes.size == 16) {
                val first = bytes[0].toInt() and 0xFF
                return first != 0xFC && first != 0xFD
            }
            return false
        }

        internal fun requireSupportedImage(bytes: ByteArray) {
            require(bytes.isNotEmpty()) { "image generation returned empty image data" }
            require(bytes.size.toLong() <= MAX_IMAGE_BYTES) {
                "generated image exceeds the 20 MB limit"
            }
            val png = bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
            val jpeg = bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte()
            val webp = bytes.size >= 12 &&
                bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
            require(png || jpeg || webp) { "image generation returned an unsupported file type" }
        }
    }
}

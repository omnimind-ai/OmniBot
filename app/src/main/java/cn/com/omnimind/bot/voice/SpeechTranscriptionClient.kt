package cn.com.omnimind.bot.voice

import cn.com.omnimind.baselib.account.AiAccessMode
import cn.com.omnimind.baselib.account.AiRequestAccess
import cn.com.omnimind.baselib.account.OmniAccount
import cn.com.omnimind.baselib.http.OkHttpManager
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.OmniOfficialProvider
import cn.com.omnimind.baselib.llm.PlatformAiProvisioner
import cn.com.omnimind.baselib.llm.PlatformAiProvisioningStatus
import cn.com.omnimind.baselib.llm.ProviderCustomHeaderUtils
import cn.com.omnimind.baselib.util.ContentEndpointSecurity
import cn.com.omnimind.baselib.util.CredentialEndpointSecurity
import cn.com.omnimind.bot.media.PlatformGatewayException
import cn.com.omnimind.bot.media.PlatformMediaGatewayExecutor
import cn.com.omnimind.bot.media.PlatformMediaProtocol
import cn.com.omnimind.bot.media.awaitResponse
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

internal data class SpeechTranscriptionResult(
    val text: String,
    val modelId: String,
    val platform: Boolean,
)

internal class SpeechTranscriptionClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build(),
    private val platformExecutor: PlatformMediaGatewayExecutor =
        PlatformMediaGatewayExecutor(
            executeRequest = { request ->
                OkHttpManager.sensitiveContentCall(
                    client = httpClient,
                    request = request,
                    allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
                ).awaitResponse()
            },
        ),
    private val accessProvider: () -> AiRequestAccess = OmniAccount::currentAiRequestAccess,
    private val ensurePlatformStatus: suspend () -> PlatformAiProvisioningStatus =
        PlatformAiProvisioner::ensureReadyStatus,
    private val byokProfileProvider: () -> ModelProviderProfile =
        ModelProviderConfigStore::getEditingProfile,
) {
    suspend fun transcribe(
        audio: SpeechTranscriptionProtocol.ValidatedAudio,
        requestedModel: String? = null,
        language: String? = null,
    ): SpeechTranscriptionResult {
        val route = resolveRoute(requestedModel)
        val normalizedLanguage = language
            ?.trim()
            ?.lowercase()
            ?.substringBefore('-')
            ?.takeIf { it.matches(Regex("^[a-z]{2,3}$")) }

        return try {
            val response = if (route.platform) {
                platformExecutor.execute { credentials ->
                    buildRequest(
                        endpoint = PlatformMediaProtocol.endpoint(
                            credentials,
                            "/v1/audio/transcriptions",
                        ),
                        apiKey = credentials.bearerToken,
                        customHeaders = emptyMap(),
                        modelId = route.modelId,
                        audio = audio,
                        language = normalizedLanguage,
                    )
                }
            } else {
                val request = buildRequest(
                    endpoint = route.endpoint,
                    apiKey = route.apiKey,
                    customHeaders = route.customHeaders,
                    modelId = route.modelId,
                    audio = audio,
                    language = normalizedLanguage,
                )
                OkHttpManager.sensitiveContentCall(
                    client = httpClient,
                    request = request,
                    allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
                ).awaitResponse()
            }
            response.use {
                val contentType = it.header("Content-Type")
                val bytes = PlatformMediaProtocol.readBodyLimited(
                    response = it,
                    maxBytes = SpeechTranscriptionProtocol.MAX_RESPONSE_BYTES,
                )
                if (route.platform) {
                    PlatformMediaProtocol.requireSuccessfulResponse(it.code, bytes)
                } else if (!it.isSuccessful) {
                    throw SpeechTranscriptionException(
                        SpeechTranscriptionErrorCode.REQUEST_FAILED,
                        "BYOK 语音转写请求失败（${it.code}）",
                    )
                }
                SpeechTranscriptionResult(
                    text = SpeechTranscriptionProtocol.parseTranscription(bytes, contentType),
                    modelId = route.modelId,
                    platform = route.platform,
                )
            }
        } catch (error: SpeechTranscriptionException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: PlatformGatewayException) {
            val code = when {
                error.statusCode == 401 -> SpeechTranscriptionErrorCode.AUTH_REQUIRED
                error.errorCode in setOf(
                    "insufficient_energy",
                    "insufficient_quota",
                    "insufficient_platform_quota",
                    "quota_exceeded",
                    "quota_exhausted",
                ) -> SpeechTranscriptionErrorCode.QUOTA_EXCEEDED
                error.errorCode == "response_too_large" ->
                    SpeechTranscriptionErrorCode.INVALID_RESPONSE
                else -> SpeechTranscriptionErrorCode.PLATFORM_UNAVAILABLE
            }
            throw SpeechTranscriptionException(code, error.message.orEmpty(), error)
        } catch (error: Throwable) {
            throw SpeechTranscriptionException(
                SpeechTranscriptionErrorCode.REQUEST_FAILED,
                "语音转写请求失败，请检查网络后重试",
                error,
            )
        }
    }

    private suspend fun resolveRoute(requestedModel: String?): Route {
        val access = accessProvider()
        access.unavailableReason?.let { reason ->
            throw SpeechTranscriptionException(
                SpeechTranscriptionErrorCode.PLATFORM_UNAVAILABLE,
                reason,
            )
        }
        if (access.mode == AiAccessMode.PLATFORM) {
            if (!access.usesPlatform) {
                throw SpeechTranscriptionException(
                    SpeechTranscriptionErrorCode.PLATFORM_UNAVAILABLE,
                    access.unavailableReason ?: "平台 AI 当前不可用",
                )
            }
            val modelId = ensurePlatformStatus().defaultSttModelId
                ?: throw SpeechTranscriptionException(
                    SpeechTranscriptionErrorCode.PLATFORM_UNAVAILABLE,
                    "官方语音转写能力暂不可用",
                )
            return Route(platform = true, modelId = modelId)
        }
        if (access.mode != AiAccessMode.BYOK) {
            throw SpeechTranscriptionException(
                SpeechTranscriptionErrorCode.PLATFORM_UNAVAILABLE,
                "账号的 AI 使用方式尚未同步，请稍后重试",
            )
        }

        val profile = byokProfileProvider()
        if (profile.readOnly || OmniOfficialProvider.isOfficialProfile(profile.id) ||
            !profile.isConfigured()
        ) {
            throw SpeechTranscriptionException(
                SpeechTranscriptionErrorCode.PROVIDER_NOT_CONFIGURED,
                "请先配置可用的 BYOK OpenAI-Compatible Provider",
            )
        }
        if (!profile.protocolType.equals("openai_compatible", ignoreCase = true)) {
            throw SpeechTranscriptionException(
                SpeechTranscriptionErrorCode.PROVIDER_NOT_CONFIGURED,
                "当前 BYOK Provider 不支持标准语音转写接口",
            )
        }
        val modelId = requestedModel
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 200 }
            ?: SpeechTranscriptionProtocol.DEFAULT_BYOK_MODEL
        return Route(
            platform = false,
            endpoint = SpeechTranscriptionProtocol.resolveEndpoint(profile.baseUrl),
            apiKey = profile.apiKey.trim(),
            customHeaders = profile.customHeaders,
            modelId = modelId,
        )
    }

    private fun buildRequest(
        endpoint: String,
        apiKey: String,
        customHeaders: Map<String, String>,
        modelId: String,
        audio: SpeechTranscriptionProtocol.ValidatedAudio,
        language: String?,
    ): Request {
        val safeEndpoint = ContentEndpointSecurity.requireSafe(
            rawUrl = endpoint,
            allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
        )
        val fileName = audio.file.name
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
            .ifEmpty { "speech.m4a" }
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", modelId)
            .addFormDataPart("response_format", "json")
            .apply {
                language?.let { addFormDataPart("language", it) }
            }
            .addFormDataPart(
                "file",
                fileName,
                audio.file.asRequestBody(audio.mimeType.toMediaType()),
            )
            .build()
        val headers = ProviderCustomHeaderUtils.mergeHeaders(
            builtIn = linkedMapOf(
                "Authorization" to "Bearer $apiKey",
                "Accept" to "application/json, text/plain",
            ),
            custom = customHeaders.filterKeys {
                !it.equals("Content-Type", ignoreCase = true)
            },
        )
        return Request.Builder()
            .url(safeEndpoint)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .post(multipart)
            .build()
    }

    private data class Route(
        val platform: Boolean,
        val endpoint: String = "",
        val apiKey: String = "",
        val customHeaders: Map<String, String> = emptyMap(),
        val modelId: String,
    )
}

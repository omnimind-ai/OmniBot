package cn.com.omnimind.baselib.llm.chatgpt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal data class ChatGptTokens(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val expiresInSeconds: Long,
    val earliestRefreshAtSeconds: Long,
    val scopes: Set<String>?,
    val receivedAtSeconds: Long = 0,
) {
    override fun toString(): String = "ChatGptTokens(redacted)"
}

internal object ChatGptTokenResponseParser {
    fun parse(json: JsonObject, requireRefreshToken: Boolean, receivedAtSeconds: Long): ChatGptTokens = try {
        require(json.string("token_type").equals("Bearer", ignoreCase = true))
        val access = requireNotNull(token(json, "access_token"))
        val refresh = token(json, "refresh_token")
        // Initial consent can omit offline_access. Successful refresh must rotate the token.
        if (requireRefreshToken) requireNotNull(refresh)
        val id = token(json, "id_token")
        val expires = json.get("expires_in").asLong.also { require(it in 1..604800) }
        ChatGptTokens(access, refresh, id, expires,
            json.get("earliest_refresh_at")?.asString?.toLongOrNull() ?: 0,
            json.string("scope")?.split(Regex("\\s+"))?.filter(String::isNotEmpty)?.toSet(),
            receivedAtSeconds)
    } catch (_: Exception) {
        throw ChatGptOAuthException("invalid_response", "ChatGPT returned an invalid authorization response.")
    }

    private fun token(json: JsonObject, name: String): String? {
        val field = json.get(name)?.takeUnless { it.isJsonNull } ?: return null
        require(field.isJsonPrimitive && field.asJsonPrimitive.isString)
        return field.asString.also { value ->
            require(value.isNotBlank() && value.length <= 64 * 1024 && value.none(Char::isWhitespace))
        }
    }
}

internal interface ChatGptOAuthRemote {
    suspend fun exchange(clientId: String, code: String, verifier: String, redirectUri: String): ChatGptTokens
    suspend fun refresh(registration: ChatGptRegistration): ChatGptTokens
    suspend fun identity(
        token: String, clientId: String, nonce: String?, subject: String?, requireNonce: Boolean,
    ): ChatGptIdentity
    suspend fun revoke(registration: ChatGptRegistration): Boolean
}

/** Dedicated, redirect-free client: credentials never pass through app HTTP logging interceptors. */
internal class OpenAiChatGptOAuthRemote : ChatGptOAuthRemote {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).build()
    }

    override suspend fun exchange(
        clientId: String, code: String, verifier: String, redirectUri: String,
    ): ChatGptTokens = tokenRequest(FormBody.Builder()
        .add("grant_type", "authorization_code").add("client_id", clientId)
        .add("code", code).add("code_verifier", verifier).add("redirect_uri", redirectUri)
        .add("resource", ChatGptOAuthProtocol.RESOURCE).build())

    override suspend fun refresh(registration: ChatGptRegistration): ChatGptTokens = tokenRequest(
        FormBody.Builder().add("grant_type", "refresh_token")
            .add("client_id", registration.clientId)
            .add("refresh_token", registration.refreshToken.orEmpty())
            .add("resource", ChatGptOAuthProtocol.RESOURCE).build(),
        requireRefreshToken = true,
    )

    override suspend fun identity(
        token: String, clientId: String, nonce: String?, subject: String?, requireNonce: Boolean,
    ): ChatGptIdentity {
        val metadata = discovery()
        val jwksUrl = trustedEndpoint(metadata.string("jwks_uri"))
        val jwks = requestJson(Request.Builder().url(jwksUrl).get().build())
        return ChatGptIdTokenValidator.validate(
            token, jwks, clientId, nonce, subject, System.currentTimeMillis() / 1000, requireNonce,
        )
    }

    override suspend fun revoke(registration: ChatGptRegistration): Boolean {
        if (registration.refreshToken.isNullOrBlank()) return true
        // A bounded retry is exclusively for revocation, never an inference or prompt replay.
        repeat(2) { attempt ->
            try {
                val endpoint = trustedEndpoint(discovery().string("revocation_endpoint"))
                val request = Request.Builder().url(endpoint).post(FormBody.Builder()
                    .add("token", registration.refreshToken).add("token_type_hint", "refresh_token")
                    .add("client_id", registration.clientId).build()).build()
                val status = withContext(Dispatchers.IO) { client.newCall(request).execute().use { it.code } }
                if (status == 200) return true
                if (status < 500) return false
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The locally detached token is never restored if remote revocation fails.
            }
            if (attempt == 0) delay(300)
        }
        return false
    }

    private suspend fun discovery(): JsonObject = requestJson(
        Request.Builder().url(ChatGptOAuthProtocol.DISCOVERY_URL).get().build()
    ).also {
        if (it.string("issuer") != ChatGptOAuthProtocol.ISSUER) throw invalidResponse()
    }

    private fun trustedEndpoint(value: String?): String {
        val url = try { requireNotNull(value).toHttpUrl() } catch (_: Exception) { throw invalidResponse() }
        if (url.scheme != "https" || url.host != "auth.openai.com" || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null
        ) throw invalidResponse()
        return url.toString()
    }

    private suspend fun tokenRequest(body: FormBody, requireRefreshToken: Boolean = false): ChatGptTokens {
        val json = requestJson(Request.Builder().url(ChatGptOAuthProtocol.TOKEN_URL).post(body).build())
        return ChatGptTokenResponseParser.parse(json, requireRefreshToken, System.currentTimeMillis() / 1000)
    }

    private suspend fun requestJson(request: Request): JsonObject = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val source = response.body.source()
                if (source.request(MAX_RESPONSE_BYTES + 1)) throw invalidResponse()
                val raw = source.readUtf8()
                val parsed = try { JsonParser.parseString(raw).asJsonObject } catch (_: Exception) { null }
                if (!response.isSuccessful) {
                    val field = parsed?.get("error")
                    val code = when {
                        field?.isJsonPrimitive == true -> field.asString
                        field?.isJsonObject == true -> field.asJsonObject.string("code")
                        else -> null
                    }
                    throw endpointError(code)
                }
                parsed ?: throw invalidResponse()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ChatGptOAuthException) {
            throw error
        } catch (_: Exception) {
            throw ChatGptOAuthException("network_error", "Could not connect to ChatGPT. Check your connection and try again.")
        }
    }

    private fun endpointError(code: String?): ChatGptOAuthException = when (code) {
        in UNUSABLE_REFRESH_ERRORS -> ChatGptOAuthException(
            code!!, "ChatGPT authorization has expired or was revoked. Please sign in again.",
        )
        "invalid_client" -> ChatGptOAuthException("invalid_client", "ChatGPT did not accept this app registration. Please sign in again.")
        else -> ChatGptOAuthException("oauth_request_failed", "ChatGPT could not complete authorization. Please try again.")
    }

    private fun invalidResponse() = ChatGptOAuthException("invalid_response", "ChatGPT returned an invalid authorization response.")

    companion object {
        private const val MAX_RESPONSE_BYTES = 256L * 1024
        val UNUSABLE_REFRESH_ERRORS = setOf(
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
            "refresh_token_invalidated", "refresh_token_reused",
        )
    }
}

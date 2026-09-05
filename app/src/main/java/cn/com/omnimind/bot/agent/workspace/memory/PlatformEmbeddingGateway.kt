package cn.com.omnimind.bot.agent

import cn.com.omnimind.bot.media.PlatformMediaGatewayExecutor
import cn.com.omnimind.bot.media.PlatformMediaProtocol
import cn.com.omnimind.bot.media.awaitResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.Gson

/** Official, account-JWT-only embedding transport. */
internal class PlatformEmbeddingGateway(
    private val executor: PlatformMediaGatewayExecutor = PlatformMediaGatewayExecutor(
        executeRequest = { request -> HTTP_CLIENT.newCall(request).awaitResponse() },
    ),
) {
    suspend fun embed(modelId: String, input: String): List<Double> {
        val normalizedModel = modelId.trim()
        require(normalizedModel.isNotEmpty()) { "embedding model is empty" }
        val requestJson = GSON.toJson(
            mapOf(
                "model" to normalizedModel,
                "input" to listOf(input),
            )
        )
        return executor.execute { credentials ->
            Request.Builder()
                .url(PlatformMediaProtocol.endpoint(credentials, "/v1/embeddings"))
                .header("Authorization", "Bearer ${credentials.bearerToken}")
                .header("Content-Type", "application/json")
                .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }.use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            PlatformMediaProtocol.requireSuccessfulResponse(response.code, bytes)
            parseEmbedding(bytes)
        }
    }

    private fun parseEmbedding(bytes: ByteArray): List<Double> {
        val payload = GSON.fromJson(
            bytes.toString(Charsets.UTF_8),
            EmbeddingResponse::class.java,
        ) ?: throw IllegalStateException("official embedding response is invalid")
        val vector = payload.data.firstOrNull()?.embedding.orEmpty()
        if (vector.isEmpty()) {
            throw IllegalStateException("official embedding response has invalid dimensions")
        }
        if (vector.any { !it.isFinite() }) {
            throw IllegalStateException("official embedding response contains invalid values")
        }
        return vector
    }

    private data class EmbeddingResponse(val data: List<EmbeddingItem> = emptyList())

    private data class EmbeddingItem(val embedding: List<Double> = emptyList())

    private companion object {
        val GSON = Gson()
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val HTTP_CLIENT = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

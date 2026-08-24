package cn.com.omnimind.baselib.account

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

private fun securePlatformCatalogHttpClient(): OkHttpClient {
    return OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}

interface PlatformModelRemoteDataSource {
    suspend fun listModels(accessToken: String): List<PlatformModel>

    suspend fun getCatalog(accessToken: String): PlatformModelCatalog =
        PlatformModelCatalog(models = listModels(accessToken))
}

/**
 * Reads the public, user-scoped model catalog from the platform gateway.
 * Only model metadata is returned to callers; upstream URLs and credentials
 * are neither requested nor represented by this client.
 */
class PlatformModelApiClient(
    gatewayBaseUrl: String,
    private val callFactory: Call.Factory = securePlatformCatalogHttpClient(),
    private val gson: Gson = Gson(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    allowInsecureLoopback: Boolean = false,
) : PlatformModelRemoteDataSource {
    private val modelsUrl = OfficialEndpointSecurity.normalizeBaseUrl(
        raw = gatewayBaseUrl,
        label = "gatewayBaseUrl",
        allowInsecureLoopback = allowInsecureLoopback,
    ) + "/v1/models"

    override suspend fun listModels(accessToken: String): List<PlatformModel> =
        getCatalog(accessToken).models

    override suspend fun getCatalog(accessToken: String): PlatformModelCatalog =
        withContext(ioDispatcher) {
            val request = Request.Builder()
                .url(modelsUrl)
                .header("Authorization", "Bearer ${accessToken.trim()}")
                .header("Accept", "application/json")
                .get()
                .build()
            callFactory.newCall(request).execute().use { response ->
                val body = readCatalogBody(response.body)
                if (!response.isSuccessful) {
                    throw AccountApiException(
                        statusCode = response.code,
                        errorCode = if (response.code == 401) "invalid_access_token" else null,
                        message = "Official model catalog is temporarily unavailable",
                    )
                }
                val payload = try {
                    gson.fromJson(body, ModelsResponse::class.java)
                } catch (error: JsonParseException) {
                    throw AccountProtocolException(
                        "Official model catalog returned invalid JSON",
                        error,
                    )
                } ?: throw AccountProtocolException(
                    "Official model catalog returned an empty JSON value"
                )
                if (payload.success == false) {
                    throw AccountProtocolException("Official model catalog request failed")
                }
                val models = payload.data.orEmpty()
                    .mapNotNull { item ->
                        val id = item.id?.trim().orEmpty()
                        id.takeIf { it.isNotEmpty() }?.let {
                            PlatformModel(
                                id = it,
                                ownedBy = item.ownedBy?.trim()?.takeIf(String::isNotEmpty),
                                supportedEndpointTypes = item.supportedEndpointTypes
                                    .orEmpty()
                                    .map(String::trim)
                                    .filter(String::isNotEmpty)
                                    .distinct(),
                            )
                        }
                    }
                    .distinctBy(PlatformModel::id)
                payload.officialCatalog.toDomain(models)
            }
        }

    private data class ModelsResponse(
        @SerializedName("success") val success: Boolean? = null,
        @SerializedName("data") val data: List<ModelResponse>? = null,
        @SerializedName("official_catalog")
        val officialCatalog: OfficialCatalogResponse? = null,
    )

    private data class ModelResponse(
        @SerializedName("id") val id: String? = null,
        @SerializedName("owned_by") val ownedBy: String? = null,
        @SerializedName("supported_endpoint_types")
        val supportedEndpointTypes: List<String>? = null,
    )

    private data class OfficialCatalogResponse(
        @SerializedName("version") val version: String? = null,
        @SerializedName("defaults") val defaults: CatalogDefaultsResponse? = null,
        @SerializedName("capabilities")
        val capabilities: CatalogCapabilitiesResponse? = null,
        @SerializedName("display_names")
        val displayNames: Map<String, String>? = null,
    )

    private data class CatalogDefaultsResponse(
        @SerializedName("text") val text: String? = null,
        @SerializedName("vision") val vision: String? = null,
        @SerializedName("vision_chat") val visionChat: String? = null,
        @SerializedName("image") val image: String? = null,
        @SerializedName("image_generation") val imageGeneration: String? = null,
        @SerializedName("embedding") val embedding: String? = null,
    )

    private data class CatalogCapabilitiesResponse(
        @SerializedName("text") val text: List<String>? = null,
        @SerializedName("vision") val vision: List<String>? = null,
        @SerializedName("vision_chat") val visionChat: List<String>? = null,
        @SerializedName("image") val image: List<String>? = null,
        @SerializedName("image_generation") val imageGeneration: List<String>? = null,
        @SerializedName("embedding") val embedding: List<String>? = null,
    )

    private fun OfficialCatalogResponse?.toDomain(
        models: List<PlatformModel>,
    ): PlatformModelCatalog {
        if (this == null) {
            return PlatformModelCatalog(models = models)
        }
        val availableModelIds = models.mapTo(mutableSetOf(), PlatformModel::id)
        val safeDisplayNames = displayNames.orEmpty().mapNotNull { (rawId, rawName) ->
            val modelId = rawId.trim()
            val displayName = rawName.trim()
            if (modelId in availableModelIds && displayName.isNotEmpty()) {
                modelId to displayName
            } else {
                null
            }
        }.toMap()
        return PlatformModelCatalog(
            models = models,
            version = version.normalizedId(),
            defaults = PlatformModelDefaults(
                text = defaults?.text.normalizedId(),
                vision = firstId(defaults?.vision, defaults?.visionChat),
                image = firstId(defaults?.image, defaults?.imageGeneration),
                embedding = defaults?.embedding.normalizedId(),
            ),
            capabilities = PlatformModelCapabilities(
                text = capabilities?.text.normalizedIds(),
                vision = firstIds(capabilities?.vision, capabilities?.visionChat),
                image = firstIds(capabilities?.image, capabilities?.imageGeneration),
                embedding = capabilities?.embedding.normalizedIds(),
            ),
            displayNames = safeDisplayNames,
            hasOfficialCatalog = true,
        )
    }

    private fun String?.normalizedId(): String? =
        this?.trim()?.takeIf(String::isNotEmpty)

    private fun List<String>?.normalizedIds(): List<String> =
        orEmpty().map(String::trim).filter(String::isNotEmpty).distinct()

    private fun firstId(vararg candidates: String?): String? =
        candidates.firstNotNullOfOrNull { it.normalizedId() }

    private fun firstIds(vararg candidates: List<String>?): List<String> =
        candidates.firstOrNull { value -> value != null }.normalizedIds()

    private fun readCatalogBody(body: okhttp3.ResponseBody?): String {
        if (body == null) return ""
        val declaredLength = body.contentLength()
        if (declaredLength > MAX_CATALOG_BODY_BYTES) {
            throw AccountProtocolException("Official model catalog response is too large")
        }
        body.byteStream().use { input ->
            val output = ByteArrayOutputStream(
                declaredLength.takeIf { it in 1..MAX_CATALOG_BODY_BYTES }?.toInt() ?: 8192
            )
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_CATALOG_BODY_BYTES) {
                    throw AccountProtocolException("Official model catalog response is too large")
                }
                output.write(buffer, 0, count)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    companion object {
        internal const val MAX_CATALOG_BODY_BYTES: Long = 2L * 1024L * 1024L
    }
}

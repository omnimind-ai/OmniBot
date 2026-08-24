package cn.com.omnimind.baselib.llm

import cn.com.omnimind.baselib.account.AiSettings
import cn.com.omnimind.baselib.account.OmniAccount
import cn.com.omnimind.baselib.account.PlatformModel
import cn.com.omnimind.baselib.account.PlatformModelsUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PlatformAiProvisioningStatus(
    val ready: Boolean = false,
    val statusText: String = "正在同步官方文本模型",
    val defaultModelId: String? = null,
    val models: List<ProviderModelOption> = emptyList(),
    val catalogVersion: String? = null,
    val defaultVisionModelId: String? = null,
    val defaultImageModelId: String? = null,
    val defaultEmbeddingModelId: String? = null,
    val visionModels: List<ProviderModelOption> = emptyList(),
    val imageModels: List<ProviderModelOption> = emptyList(),
    val embeddingModels: List<ProviderModelOption> = emptyList(),
)

object PlatformModelCapability {
    const val TEXT = "text"
    const val VISION = "vision"
    const val IMAGE = "image"
    const val EMBEDDING = "embedding"
}

internal fun PlatformAiProvisioningStatus.modelsForCapability(
    capability: String?,
): List<ProviderModelOption> =
    when (capability?.trim()?.lowercase().orEmpty()) {
        "", PlatformModelCapability.TEXT -> models
        PlatformModelCapability.VISION -> visionModels
        PlatformModelCapability.IMAGE -> imageModels
        PlatformModelCapability.EMBEDDING -> embeddingModels
        else -> emptyList()
    }

internal fun PlatformAiProvisioningStatus.routingUnavailableReasonOrNull(): String? {
    if (ready) {
        return null
    }
    return statusText.takeIf { it.isNotBlank() }
        ?: "官方文本模型暂时不可用，请稍后重试"
}

internal const val EMBEDDING_CATALOG_REFRESH_COOLDOWN_MILLIS = 5L * 60L * 1_000L

internal fun shouldRefreshEmbeddingCatalog(
    lastAttemptAtMillis: Long,
    nowMillis: Long,
    cooldownMillis: Long = EMBEDDING_CATALOG_REFRESH_COOLDOWN_MILLIS,
): Boolean {
    if (lastAttemptAtMillis <= 0L) return true
    val elapsed = nowMillis - lastAttemptAtMillis
    return elapsed < 0L || elapsed >= cooldownMillis
}

internal fun PlatformAiProvisioningStatus.hasReadyTextCatalog(): Boolean =
    ready && models.isNotEmpty()

internal fun preserveLastKnownGoodCatalogOrFailure(
    previous: PlatformAiProvisioningStatus,
    failureStatusText: String,
): PlatformAiProvisioningStatus =
    if (previous.hasReadyTextCatalog()) {
        previous
    } else {
        PlatformAiProvisioningStatus(statusText = failureStatusText)
    }

/** Coalesces overlapping catalog requests instead of queuing another fetch. */
internal class SuspendRequestCoalescer<T> {
    private val gate = Any()
    private var inFlight: CompletableDeferred<T>? = null

    suspend fun run(block: suspend () -> T): T {
        lateinit var request: CompletableDeferred<T>
        var ownsRequest = false
        synchronized(gate) {
            request = inFlight ?: CompletableDeferred<T>().also {
                inFlight = it
                ownsRequest = true
            }
        }
        if (!ownsRequest) {
            return request.await()
        }

        return try {
            block().also(request::complete)
        } catch (error: Throwable) {
            request.completeExceptionally(error)
            throw error
        } finally {
            synchronized(gate) {
                if (inFlight === request) {
                    inFlight = null
                }
            }
        }
    }
}

/**
 * Keeps the official provider catalog separate from device BYOK configuration.
 * Synchronizing the catalog never mutates the user's scene bindings.
 */
object PlatformAiProvisioner {
    private val mutex = Mutex()
    private val catalogSynchronization =
        SuspendRequestCoalescer<PlatformAiProvisioningStatus>()
    private val embeddingRefreshGate = Any()

    @Volatile
    private var currentStatus = PlatformAiProvisioningStatus()

    @Volatile
    private var lastEmbeddingCatalogRefreshAttemptAtMillis = 0L

    fun status(): PlatformAiProvisioningStatus = currentStatus

    fun officialProfileOrNull(): ModelProviderProfile? =
        OmniOfficialProvider.profileOrNull(currentStatus)

    fun routingUnavailableReason(): String? {
        if (!OmniOfficialProvider.shouldExpose()) {
            return null
        }
        return currentStatus.routingUnavailableReasonOrNull()
    }

    suspend fun synchronize(
        settings: AiSettings? = null,
        forceRefresh: Boolean = settings != null,
        preserveReadyCatalogOnFailure: Boolean = false,
    ): PlatformAiProvisioningStatus =
        catalogSynchronization.run {
            synchronizeOnce(
                forceRefresh = forceRefresh,
                preserveReadyCatalogOnFailure = preserveReadyCatalogOnFailure,
            )
        }

    private suspend fun synchronizeOnce(
        forceRefresh: Boolean,
        preserveReadyCatalogOnFailure: Boolean,
    ): PlatformAiProvisioningStatus =
        mutex.withLock {
            if (!OmniOfficialProvider.shouldExpose()) {
                deactivateLocked()
                return@withLock currentStatus
            }

            val access = OmniAccount.currentAiRequestAccess()
            if (!access.usesPlatform) {
                clearEmbeddingCatalogRefreshAttempt()
                currentStatus = PlatformAiProvisioningStatus(
                    statusText = access.unavailableReason
                        ?: "平台 AI 登录状态尚未就绪，请重新登录",
                )
                return@withLock currentStatus
            }

            val previousStatus = currentStatus
            if (!forceRefresh && previousStatus.hasReadyTextCatalog()) {
                return@withLock previousStatus
            }

            if (!preserveReadyCatalogOnFailure || !previousStatus.hasReadyTextCatalog()) {
                currentStatus = PlatformAiProvisioningStatus(
                    statusText = "正在同步官方文本模型",
                )
            }
            try {
                markEmbeddingCatalogRefreshAttempt()
                val catalog = OmniAccount.repository().getPlatformModelCatalog()
                val selection = OmniOfficialProvider.selectModels(
                    catalog = catalog,
                    rememberedTextModelId = previousStatus.defaultModelId,
                )
                val selected = selection.defaultTextModel
                if (selected == null) {
                    currentStatus = PlatformAiProvisioningStatus(
                        statusText = "官方服务当前没有可用的已验证文本模型",
                    )
                    return@withLock currentStatus
                }

                currentStatus = PlatformAiProvisioningStatus(
                    ready = true,
                    statusText = "官方文本模型已就绪",
                    defaultModelId = selected.id,
                    models = selection.textModels.toOptions(catalog.displayNames),
                    catalogVersion = catalog.version,
                    defaultVisionModelId = selection.defaultVisionModel?.id,
                    defaultImageModelId = selection.defaultImageModel?.id,
                    defaultEmbeddingModelId = selection.defaultEmbeddingModel?.id,
                    visionModels = selection.visionModels.toOptions(catalog.displayNames),
                    imageModels = selection.imageModels.toOptions(catalog.displayNames),
                    embeddingModels = selection.embeddingModels.toOptions(catalog.displayNames),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                val failureStatusText = "获取官方模型失败，请检查网络后重试"
                currentStatus = if (preserveReadyCatalogOnFailure) {
                    preserveLastKnownGoodCatalogOrFailure(
                        previous = previousStatus,
                        failureStatusText = failureStatusText,
                    )
                } else {
                    PlatformAiProvisioningStatus(statusText = failureStatusText)
                }
            }
            currentStatus
        }

    suspend fun ensureReadyStatus(): PlatformAiProvisioningStatus {
        val existing = currentStatus
        if (existing.ready && existing.models.isNotEmpty()) {
            return existing
        }
        val synchronized = synchronize()
        if (!synchronized.ready || synchronized.models.isEmpty()) {
            throw PlatformModelsUnavailableException(
                synchronized.statusText.ifBlank { "官方文本模型暂时不可用" }
            )
        }
        return synchronized
    }

    /**
     * Refreshes a text-ready catalog when embedding was added server-side
     * after the process cached an older catalog. Text routing alone is not a
     * sufficient readiness signal for workspace semantic retrieval.
     */
    suspend fun ensureEmbeddingReadyStatus(): PlatformAiProvisioningStatus {
        val existing = currentStatus
        if (existing.hasReadyEmbedding()) {
            return existing
        }
        if (!reserveEmbeddingCatalogRefreshAttempt()) {
            return existing
        }
        return synchronize(
            forceRefresh = true,
            preserveReadyCatalogOnFailure = true,
        )
    }

    suspend fun ensureReadyAndGetModels(
        capability: String? = null,
    ): List<ProviderModelOption> {
        val normalizedCapability = capability?.trim()?.lowercase().orEmpty()
        val readyStatus = if (normalizedCapability == PlatformModelCapability.EMBEDDING) {
            ensureEmbeddingReadyStatus()
        } else {
            ensureReadyStatus()
        }
        return readyStatus.modelsForCapability(normalizedCapability)
    }

    suspend fun refreshAndGetModels(
        capability: String? = null,
    ): List<ProviderModelOption> {
        val normalizedCapability = capability?.trim()?.lowercase().orEmpty()
        val refreshed = synchronize(
            forceRefresh = true,
            preserveReadyCatalogOnFailure = true,
        )
        if (!refreshed.hasReadyTextCatalog()) {
            throw PlatformModelsUnavailableException(
                refreshed.statusText.ifBlank { "官方模型暂时不可用" }
            )
        }
        return refreshed.modelsForCapability(normalizedCapability)
    }

    suspend fun deactivate() {
        mutex.withLock { deactivateLocked() }
    }

    private fun deactivateLocked() {
        clearEmbeddingCatalogRefreshAttempt()
        currentStatus = PlatformAiProvisioningStatus(
            statusText = "官方 AI 账号未登录",
        )
    }

    private fun clockMillis(): Long = System.currentTimeMillis()

    private fun reserveEmbeddingCatalogRefreshAttempt(): Boolean =
        synchronized(embeddingRefreshGate) {
            val nowMillis = clockMillis()
            if (!shouldRefreshEmbeddingCatalog(
                    lastAttemptAtMillis = lastEmbeddingCatalogRefreshAttemptAtMillis,
                    nowMillis = nowMillis,
                )
            ) {
                return@synchronized false
            }
            lastEmbeddingCatalogRefreshAttemptAtMillis = nowMillis
            true
        }

    private fun markEmbeddingCatalogRefreshAttempt() {
        synchronized(embeddingRefreshGate) {
            lastEmbeddingCatalogRefreshAttemptAtMillis = clockMillis()
        }
    }

    private fun clearEmbeddingCatalogRefreshAttempt() {
        synchronized(embeddingRefreshGate) {
            lastEmbeddingCatalogRefreshAttemptAtMillis = 0L
        }
    }

    private fun List<PlatformModel>.toOptions(
        displayNames: Map<String, String>,
    ): List<ProviderModelOption> =
        map { model ->
            ProviderModelOption(
                id = model.id,
                displayName = displayNames[model.id].orEmpty().ifBlank { model.id },
                ownedBy = model.ownedBy,
            )
        }

    private fun PlatformAiProvisioningStatus.hasReadyEmbedding(): Boolean {
        val modelId = defaultEmbeddingModelId?.trim().orEmpty()
        return ready &&
            modelId.isNotEmpty() &&
            embeddingModels.any { it.id == modelId }
    }
}

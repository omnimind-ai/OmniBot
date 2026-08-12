package cn.com.omnimind.bot.distribution

import androidx.annotation.VisibleForTesting
import cn.com.omnimind.bot.BuildConfig

data class AppEditionCapabilitySnapshot(
    val edition: String,
    val installedAppsQuery: Boolean,
    val publicStorageAccess: Boolean,
) {
    fun toChannelMap(): Map<String, Any> = mapOf(
        "schemaVersion" to SCHEMA_VERSION,
        "edition" to edition,
        "installedAppsQuery" to installedAppsQuery,
        "publicStorageAccess" to publicStorageAccess,
    )

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * Distribution capabilities are compile-time properties, not permissions that
 * a user can grant later. Play builds stay fail-closed even if a build flag is
 * accidentally changed without also changing the reviewed edition.
 */
object AppEditionCapabilities {
    private const val STANDARD_EDITION = "standard"

    val snapshot: AppEditionCapabilitySnapshot by lazy {
        forBuildConfiguration(
            edition = BuildConfig.APP_EDITION,
            installedAppsQueryFlag = BuildConfig.APP_CAN_QUERY_INSTALLED_APPS,
            publicStorageAccessFlag = BuildConfig.APP_CAN_MANAGE_PUBLIC_STORAGE,
        )
    }

    val canQueryInstalledApps: Boolean
        get() = snapshot.installedAppsQuery

    val canManagePublicStorage: Boolean
        get() = snapshot.publicStorageAccess

    @VisibleForTesting
    internal fun forBuildConfiguration(
        edition: String?,
        installedAppsQueryFlag: Boolean,
        publicStorageAccessFlag: Boolean,
    ): AppEditionCapabilitySnapshot {
        val normalizedEdition = edition?.trim()?.lowercase().orEmpty()
        val isReviewedStandardEdition = normalizedEdition == STANDARD_EDITION
        return AppEditionCapabilitySnapshot(
            edition = normalizedEdition.ifBlank { "unknown" },
            installedAppsQuery = isReviewedStandardEdition && installedAppsQueryFlag,
            publicStorageAccess = isReviewedStandardEdition && publicStorageAccessFlag,
        )
    }
}

package cn.com.omnimind.bot.update

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {
    @Test
    fun updateCheckCoordinatorSharesOneFlightPerGeneration() = runBlocking {
        val coordinator = AppUpdateCheckCoordinator()
        val leader = coordinator.acquire(force = true)
        val follower = coordinator.acquire(force = false)
        val state = AppUpdateState(
            currentVersion = "1.0.0",
            latestVersion = "1.1.0",
            hasUpdate = true,
            checkedAt = 1L,
            publishedAt = 2L,
            releaseUrl = "https://example.test/release",
            releaseNotes = "",
            apkName = "OpenOmniBot-v1.1.0-standard.apk",
            apkDownloadUrl = "https://example.test/app.apk",
            apkSha256 = "a".repeat(64)
        )

        assertTrue(leader.isLeader)
        assertFalse(follower.isLeader)
        assertSame(leader.completion, follower.completion)

        coordinator.complete(leader, Result.success(state))

        assertEquals(state, coordinator.await(follower))
        assertTrue(coordinator.acquire(force = false).isLeader)
    }

    @Test
    fun updateCheckCoordinatorRejectsLateResultAfterConfigurationChange() {
        val coordinator = AppUpdateCheckCoordinator()
        val oldLease = coordinator.acquire(force = false)
        var oldResultApplied = false

        coordinator.invalidate()
        val newLease = coordinator.acquire(force = false)

        assertFalse(
            coordinator.applyIfCurrent(oldLease) {
                oldResultApplied = true
            }
        )
        assertFalse(oldResultApplied)
        assertTrue(newLease.isLeader)
        assertTrue(coordinator.applyIfCurrent(newLease) {})
    }

    @Test
    fun forcedCheckSupersedesPassiveInFlightCheck() {
        val coordinator = AppUpdateCheckCoordinator()
        val passive = coordinator.acquire(force = false)
        val forced = coordinator.acquire(force = true)

        assertTrue(passive.isLeader)
        assertTrue(forced.isLeader)
        assertFalse(coordinator.applyIfCurrent(passive) {})
        assertTrue(coordinator.applyIfCurrent(forced) {})
    }

    @Test
    fun selfUpdateIsLimitedToExplicitlyEnabledStandardDistribution() {
        assertTrue(
            AppUpdateManager.isSelfUpdateEnabledForDistribution(
                edition = "standard",
                enabled = true
            )
        )
        assertFalse(
            AppUpdateManager.isSelfUpdateEnabledForDistribution(
                edition = "play",
                enabled = true
            )
        )
        assertFalse(
            AppUpdateManager.isSelfUpdateEnabledForDistribution(
                edition = "standard",
                enabled = false
            )
        )
    }

    @Test
    fun normalizeVersionStripsLeadingV() {
        assertEquals("0.0.1", AppUpdateManager.normalizeVersion("v0.0.1"))
        assertEquals("1.2.3", AppUpdateManager.normalizeVersion("V1.2.3"))
    }

    @Test
    fun compareVersionsUsesSemanticOrdering() {
        assertEquals(1, AppUpdateManager.compareVersions("0.0.2", "0.0.1"))
        assertEquals(0, AppUpdateManager.compareVersions("v1.2.0", "1.2"))
        assertEquals(-1, AppUpdateManager.compareVersions("1.9.9", "2.0.0"))
        assertEquals(1, AppUpdateManager.compareVersions("1.6.1.2", "1.6.1"))
    }

    @Test
    fun classifyReleaseTrackTreatsFourSegmentsAsBeta() {
        assertEquals(ReleaseTrack.STABLE, AppUpdateManager.classifyReleaseTrack("1.6.1"))
        assertEquals(ReleaseTrack.BETA, AppUpdateManager.classifyReleaseTrack("1.6.1.2"))
        assertEquals(
            ReleaseTrack.BETA,
            AppUpdateManager.classifyReleaseTrack("1.6.1", prerelease = true)
        )
    }

    @Test
    fun apkDownloadSourceDefaultsLegacyCnbToWorker() {
        assertEquals(ApkDownloadSource.WORKER, ApkDownloadSource.fromValue(null))
        assertEquals(ApkDownloadSource.WORKER, ApkDownloadSource.fromValue("cnb"))
        assertEquals(ApkDownloadSource.GITHUB, ApkDownloadSource.fromValue("github"))
    }

    @Test
    fun selectLatestReleaseHonorsBetaOptIn() {
        val stable = ReleaseCandidate(
            version = "1.6.2",
            track = ReleaseTrack.STABLE,
            publishedAt = 1L,
            releaseUrl = "https://example.com/stable",
            releaseNotes = "",
            assets = emptyList()
        )
        val beta = ReleaseCandidate(
            version = "1.6.2.3",
            track = ReleaseTrack.BETA,
            publishedAt = 2L,
            releaseUrl = "https://example.com/beta",
            releaseNotes = "",
            assets = emptyList()
        )

        assertEquals(
            "1.6.2",
            AppUpdateManager.selectLatestRelease(listOf(stable, beta), includeBeta = false)?.version
        )
        assertEquals(
            "1.6.2.3",
            AppUpdateManager.selectLatestRelease(listOf(stable, beta), includeBeta = true)?.version
        )
    }

    @Test
    fun selectPreferredApkAssetPrefersReleaseNamingConvention() {
        val assets = listOf(
            ReleaseAsset(
                name = "app-production-release.apk",
                downloadUrl = "https://example.com/app-production-release.apk"
            ),
            ReleaseAsset(
                name = "OpenOmniBot-v0.0.2.apk",
                downloadUrl = "https://example.com/OpenOmniBot-v0.0.2.apk"
            )
        )

        val selected = AppUpdateManager.selectPreferredApkAsset(assets, "standard")
        assertEquals("OpenOmniBot-v0.0.2.apk", selected?.name)
    }

    @Test
    fun selectPreferredApkAssetSelectsMatchingEdition() {
        val assets = listOf(
            ReleaseAsset(
                name = "OpenOmniBot-v0.4.0-standard.apk",
                downloadUrl = "https://example.com/OpenOmniBot-v0.4.0-standard.apk"
            ),
            ReleaseAsset(
                name = "OpenOmniBot-v0.4.0-enterprise.apk",
                downloadUrl = "https://example.com/OpenOmniBot-v0.4.0-enterprise.apk"
            )
        )

        assertEquals(
            "OpenOmniBot-v0.4.0-standard.apk",
            AppUpdateManager.selectPreferredApkAsset(assets, "standard")?.name
        )
    }

    @Test
    fun selectPreferredApkAssetDoesNotCrossInstallSplitEdition() {
        val selected = AppUpdateManager.selectPreferredApkAsset(
            listOf(
                ReleaseAsset(
                    name = "OpenOmniBot-v0.4.0-enterprise.apk",
                    downloadUrl = "https://example.com/OpenOmniBot-v0.4.0-enterprise.apk"
                )
            ),
            "standard"
        )
        assertNull(selected)
    }

    @Test
    fun selectPreferredApkAssetReturnsNullWhenNoApkExists() {
        val selected = AppUpdateManager.selectPreferredApkAsset(emptyList())
        assertNull(selected)
    }

    @Test
    fun resolveApkDownloadUrlBuildsUrlForSelectedSource() {
        val asset = ReleaseAsset(
            name = "OpenOmniBot-v0.3.7.5.apk",
            downloadUrl = "https://example.com/OpenOmniBot-v0.3.7.5.apk"
        )

        assertEquals(
            "https://omni.1775885.xyz/downloads/v0.3.7.5/OpenOmniBot-v0.3.7.5.apk",
            AppUpdateManager.resolveApkDownloadUrl(ApkDownloadSource.WORKER, "0.3.7.5", asset)
        )
        assertEquals(
            "https://github.com/omnimind-ai/OpenOmniBot/releases/download/v0.3.7.5/OpenOmniBot-v0.3.7.5.apk",
            AppUpdateManager.resolveApkDownloadUrl(ApkDownloadSource.GITHUB, "0.3.7.5", asset)
        )
    }

    @Test
    fun buildWorkerCheckUrlAddsClientSelectionParameters() {
        val url = AppUpdateManager.buildWorkerCheckUrl(
            workerUrl = "https://updates.example.workers.dev",
            currentVersion = "v0.5.0.3",
            includeBeta = true,
            downloadSource = ApkDownloadSource.WORKER,
            edition = "legacy"
        )

        assertEquals("https", url?.scheme)
        assertEquals("updates.example.workers.dev", url?.host)
        assertEquals("/updates", url?.encodedPath)
        assertEquals("0.5.0.3", url?.queryParameter("currentVersion"))
        assertEquals("true", url?.queryParameter("includeBeta"))
        assertEquals("standard", url?.queryParameter("edition"))
        assertEquals("worker", url?.queryParameter("source"))
    }

    @Test
    fun buildWorkerCheckUrlOnlyAppendsAllowedOptionalStatistic() {
        val url = AppUpdateManager.buildWorkerCheckUrl(
            workerUrl = "https://updates.example.workers.dev",
            currentVersion = "1.6.1",
            includeBeta = false,
            downloadSource = ApkDownloadSource.WORKER,
            edition = "standard",
            deviceStatsParams = mapOf(
                "deviceBrand" to "OPPO",
                "deviceModel" to "PJD110",
                "osVersion" to "15",
                "sdkInt" to "35",
                "installId" to "11111111-2222-3333-4444-555555555555",
                "blankValueIsSkipped" to ""
            )
        )

        assertNull(url?.queryParameter("deviceBrand"))
        assertNull(url?.queryParameter("deviceModel"))
        assertNull(url?.queryParameter("osVersion"))
        assertNull(url?.queryParameter("sdkInt"))
        assertEquals("11111111-2222-3333-4444-555555555555", url?.queryParameter("installId"))
        assertNull(url?.queryParameter("blankValueIsSkipped"))
    }

    @Test
    fun workerResponseRejectsNonMatchingEditionEvenWithGenericApk() {
        val payload = JSONObject()
            .put("version", "1.1.0")
            .put("track", "stable")
            .put("edition", "enterprise")
            .put("apkName", "OpenOmniBot-v1.1.0.apk")
            .put("downloadUrl", "https://updates.example.test/app.apk")
            .put("sha256", "a".repeat(64))

        val state = AppUpdateManager.parseWorkerUpdateState(
            payload = payload,
            currentVersion = "1.0.0",
            includeBeta = false,
            downloadSource = ApkDownloadSource.WORKER,
            edition = "standard",
            checkedAt = 10L
        )

        assertFalse(state.hasUpdate)
        assertEquals("1.0.0", state.latestVersion)
        assertEquals("", state.apkDownloadUrl)
    }

    @Test
    fun topLevelApkFallbackCannotCrossInstallAnotherEdition() {
        val payload = JSONObject()
            .put("version", "1.1.0")
            .put("track", "stable")
            .put("edition", "standard")
            .put("apkName", "OpenOmniBot-v1.1.0-enterprise.apk")
            .put("downloadUrl", "https://updates.example.test/enterprise.apk")
            .put("sha256", "b".repeat(64))

        val state = AppUpdateManager.parseWorkerUpdateState(
            payload = payload,
            currentVersion = "1.0.0",
            includeBeta = false,
            downloadSource = ApkDownloadSource.WORKER,
            edition = "standard",
            checkedAt = 11L
        )

        assertFalse(state.hasUpdate)
        assertEquals("", state.apkName)
        assertEquals("", state.apkDownloadUrl)
    }

    @Test
    fun genericFallbackIsRejectedWhenEditionSpecificAssetsMismatch() {
        val wrongEditionAsset = JSONObject()
            .put("name", "OpenOmniBot-v1.1.0-enterprise.apk")
            .put("downloadUrl", "https://updates.example.test/enterprise.apk")
            .put("sha256", "c".repeat(64))
        val payload = JSONObject()
            .put("version", "1.1.0")
            .put("track", "stable")
            .put("edition", "standard")
            .put("assets", JSONArray().put(wrongEditionAsset))
            .put("apkName", "OpenOmniBot-v1.1.0.apk")
            .put("downloadUrl", "https://updates.example.test/generic.apk")
            .put("sha256", "d".repeat(64))

        val state = AppUpdateManager.parseWorkerUpdateState(
            payload = payload,
            currentVersion = "1.0.0",
            includeBeta = false,
            downloadSource = ApkDownloadSource.WORKER,
            edition = "standard",
            checkedAt = 12L
        )

        assertFalse(state.hasUpdate)
        assertEquals("", state.apkDownloadUrl)
    }

    @Test
    fun workerResponseAcceptsOnlyMatchingEditionAsset() {
        val matchingAsset = JSONObject()
            .put("name", "OpenOmniBot-v1.1.0-standard.apk")
            .put("downloadUrl", "https://updates.example.test/standard.apk")
            .put("sha256", "e".repeat(64))
        val payload = JSONObject()
            .put("version", "1.1.0")
            .put("track", "stable")
            .put("edition", "standard")
            .put("assets", JSONArray().put(matchingAsset))

        val state = AppUpdateManager.parseWorkerUpdateState(
            payload = payload,
            currentVersion = "1.0.0",
            includeBeta = false,
            downloadSource = ApkDownloadSource.WORKER,
            edition = "standard",
            checkedAt = 13L
        )

        assertTrue(state.hasUpdate)
        assertEquals("OpenOmniBot-v1.1.0-standard.apk", state.apkName)
        assertEquals("e".repeat(64), state.apkSha256)
    }

    @Test
    fun optionalUpdateStatisticsRequireConsentBeforeGeneratingInstallId() {
        var installIdReadCount = 0
        val withoutConsent = AppUpdateManager.buildDeviceStatsParams(
            hasOptionalTelemetryConsent = false,
            installIdProvider = {
                installIdReadCount += 1
                "should-not-be-read"
            }
        )

        assertTrue(withoutConsent.isEmpty())
        assertEquals(0, installIdReadCount)

        val withConsent = AppUpdateManager.buildDeviceStatsParams(
            hasOptionalTelemetryConsent = true,
            installIdProvider = {
                installIdReadCount += 1
                "11111111-2222-3333-4444-555555555555"
            }
        )

        assertEquals(
            mapOf("installId" to "11111111-2222-3333-4444-555555555555"),
            withConsent
        )
        assertEquals(1, installIdReadCount)
    }

    @Test
    fun buildWorkerCheckUrlRejectsCleartextEndpoint() {
        val url = AppUpdateManager.buildWorkerCheckUrl(
            workerUrl = "http://updates.example.test",
            currentVersion = "1.0.0",
            includeBeta = false,
            downloadSource = ApkDownloadSource.WORKER,
            edition = "standard",
        )

        assertNull(url)
    }

    @Test
    fun installableUpdateRequiresHttpsNewerVersionAndValidSha256() {
        val digest = "a".repeat(64)
        val validAsset = ReleaseAsset(
            name = "OpenOmniBot-v1.1.0-standard.apk",
            downloadUrl = "https://updates.example.test/app.apk",
            sha256 = digest,
        )

        assertTrue(
            AppUpdateManager.isInstallableUpdate(
                version = "1.1.0",
                currentVersion = "1.0.0",
                asset = validAsset,
                downloadUrl = validAsset.downloadUrl,
            )
        )
        assertFalse(
            AppUpdateManager.isInstallableUpdate(
                version = "1.1.0",
                currentVersion = "1.0.0",
                asset = validAsset.copy(sha256 = ""),
                downloadUrl = validAsset.downloadUrl,
            )
        )
        assertFalse(
            AppUpdateManager.isInstallableUpdate(
                version = "1.1.0",
                currentVersion = "1.0.0",
                asset = validAsset,
                downloadUrl = "http://updates.example.test/app.apk",
            )
        )
        assertFalse(
            AppUpdateManager.isInstallableUpdate(
                version = "1.0.0",
                currentVersion = "1.0.0",
                asset = validAsset,
                downloadUrl = validAsset.downloadUrl,
            )
        )
    }

    @Test
    fun normalizeSha256RejectsMalformedDigest() {
        assertEquals("b".repeat(64), AppUpdateManager.normalizeSha256("  ${"B".repeat(64)} "))
        assertEquals("", AppUpdateManager.normalizeSha256("not-a-digest"))
    }

}

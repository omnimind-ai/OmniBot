package cn.com.omnimind.bot.update

import cn.com.omnimind.baselib.llm.OpenAiWireApi
import cn.com.omnimind.baselib.llm.OfficialVlmOperationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {
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
            "https://omnibot.omnimind.com.cn/downloads/v0.3.7.5/OpenOmniBot-v0.3.7.5.apk",
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
    fun buildWorkerCheckUrlAppendsNonBlankDeviceStatsParameters() {
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

        assertEquals("OPPO", url?.queryParameter("deviceBrand"))
        assertEquals("PJD110", url?.queryParameter("deviceModel"))
        assertEquals("15", url?.queryParameter("osVersion"))
        assertEquals("35", url?.queryParameter("sdkInt"))
        assertEquals("11111111-2222-3333-4444-555555555555", url?.queryParameter("installId"))
        assertEquals(null, url?.queryParameter("blankValueIsSkipped"))
    }

    @Test
    fun parseOfficialVlmOperationConfigAcceptsChatGptPayloadAliases() {
        val config = AppUpdateManager.parseOfficialVlmOperationConfig(
            """
            {
              "official_vlm_operation": {
                "enabled": true,
                "base_url": "https://chatgpt.example/codex",
                "model_id": "gpt-5.6-sol",
                "wire_api": "responses"
              }
            }
            """.trimIndent()
        )

        assertEquals(
            OfficialVlmOperationConfig(
                enabled = true,
                apiBase = "https://chatgpt.example/codex",
                model = "gpt-5.6-sol",
                wireApi = OpenAiWireApi.RESPONSES
            ),
            config
        )
    }

    @Test
    fun parseOfficialVlmOperationConfigIgnoresMissingPayload() {
        assertNull(AppUpdateManager.parseOfficialVlmOperationConfig("{}"))
    }

    @Test
    fun freshCloudServicePolicyBlocksVersionsBelowMinimum() {
        val access = AppUpdateManager.resolveCloudServiceAccessState(
            currentVersion = "0.5.6.15",
            policyKnown = true,
            policyEnabled = true,
            minimumVersion = "0.5.7",
            message = "请升级后使用云服务",
            checkedAt = 1_000L,
            now = 1_000L,
        )

        assertTrue(access.policyKnown)
        assertFalse(access.allowed)
        assertEquals("0.5.7", access.minimumVersion)
        assertEquals("请升级后使用云服务", access.message)
    }

    @Test
    fun disabledFreshCloudServicePolicyAllowsAccountAccess() {
        val access = AppUpdateManager.resolveCloudServiceAccessState(
            currentVersion = "0.5.6.15",
            policyKnown = true,
            policyEnabled = false,
            minimumVersion = "",
            message = "",
            checkedAt = 1_000L,
            now = 1_000L,
        )

        assertTrue(access.policyKnown)
        assertTrue(access.allowed)
    }

    @Test
    fun staleCloudServicePolicyFailsClosed() {
        val access = AppUpdateManager.resolveCloudServiceAccessState(
            currentVersion = "0.5.7",
            policyKnown = true,
            policyEnabled = true,
            minimumVersion = "0.5.7",
            message = "",
            checkedAt = 1L,
            now = 24 * 60 * 60 * 1_000L + 2L,
        )

        assertFalse(access.policyKnown)
        assertFalse(access.allowed)
    }

}

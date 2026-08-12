package cn.com.omnimind.bot.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalApkInstallerTest {
    @Test
    fun updateDownloadsRequireHttps() {
        assertTrue(ExternalApkInstaller.isSecureDownloadUrl("https://updates.example.test/app.apk"))
        assertFalse(ExternalApkInstaller.isSecureDownloadUrl("http://updates.example.test/app.apk"))
        assertFalse(ExternalApkInstaller.isSecureDownloadUrl("file:///tmp/app.apk"))
    }

    @Test
    fun apkFileNameCannotEscapeDownloadDirectory() {
        assertEquals("evil.apk", ExternalApkInstaller.sanitizeApkFileName("../../evil.apk"))
        assertEquals("OpenOmniBot-update.apk", ExternalApkInstaller.sanitizeApkFileName("../not-an-apk"))
    }

    @Test
    fun digestComparisonRequiresValidMatchingSha256() {
        val digest = "c".repeat(64)
        assertTrue(ExternalApkInstaller.constantTimeHexEquals(digest, digest.uppercase()))
        assertFalse(ExternalApkInstaller.constantTimeHexEquals(digest, "d".repeat(64)))
        assertFalse(ExternalApkInstaller.constantTimeHexEquals(digest, "short"))
    }

    @Test
    fun signingLineageAllowsForwardRotationButRejectsRollback() {
        assertTrue(
            ExternalApkInstaller.signerTransitionIsAllowed(
                currentSigners = setOf("old"),
                candidateSigners = setOf("new"),
                candidateHistory = setOf("old", "new"),
                hasMultipleSigners = false,
            )
        )
        assertFalse(
            ExternalApkInstaller.signerTransitionIsAllowed(
                currentSigners = setOf("new"),
                candidateSigners = setOf("old"),
                candidateHistory = setOf("old"),
                hasMultipleSigners = false,
            )
        )
        assertFalse(
            ExternalApkInstaller.signerTransitionIsAllowed(
                currentSigners = setOf("trusted-a", "trusted-b"),
                candidateSigners = setOf("trusted-a"),
                candidateHistory = setOf("trusted-a"),
                hasMultipleSigners = true,
            )
        )
    }
}

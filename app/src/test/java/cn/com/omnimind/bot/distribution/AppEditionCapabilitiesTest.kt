package cn.com.omnimind.bot.distribution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEditionCapabilitiesTest {
    @Test
    fun standardEditionPreservesDeclaredCapabilities() {
        val snapshot = AppEditionCapabilities.forBuildConfiguration(
            edition = " standard ",
            installedAppsQueryFlag = true,
            publicStorageAccessFlag = true,
        )

        assertEquals("standard", snapshot.edition)
        assertTrue(snapshot.installedAppsQuery)
        assertTrue(snapshot.publicStorageAccess)
        assertEquals(1, snapshot.toChannelMap()["schemaVersion"])
    }

    @Test
    fun playEditionFailsClosedEvenIfFlagsAreMisconfigured() {
        val snapshot = AppEditionCapabilities.forBuildConfiguration(
            edition = "play",
            installedAppsQueryFlag = true,
            publicStorageAccessFlag = true,
        )

        assertFalse(snapshot.installedAppsQuery)
        assertFalse(snapshot.publicStorageAccess)
    }

    @Test
    fun unknownEditionFailsClosed() {
        val snapshot = AppEditionCapabilities.forBuildConfiguration(
            edition = "enterprise",
            installedAppsQueryFlag = true,
            publicStorageAccessFlag = true,
        )

        assertFalse(snapshot.installedAppsQuery)
        assertFalse(snapshot.publicStorageAccess)
    }
}

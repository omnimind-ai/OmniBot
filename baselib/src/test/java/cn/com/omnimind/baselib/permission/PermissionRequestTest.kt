package cn.com.omnimind.baselib.permission

import android.Manifest
import org.junit.Assert.assertNotNull
import org.junit.Test

class PermissionRequestTest {
    @Test
    fun sensitivePermissionDisclosuresCoverExistingAndNewCapabilities() {
        val permissions = listOf(
            Manifest.permission.ACCESS_LOCAL_NETWORK,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_SETTINGS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        )

        permissions.forEach { permission ->
            assertNotNull(permission, PermissionRequest.getPurposeForPermission(permission))
        }
    }
}

package cn.com.omnimind.baselib.permission

import android.Manifest
import android.content.Context
import android.os.Build
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Android permission only; callers retain ownership of their network operation. */
object LocalNetworkPermission {
    private val requestMutex = Mutex()

    fun isGranted(context: Context): Boolean = Build.VERSION.SDK_INT < 37 ||
        PermissionRequest.isPermissionGranted(context, Manifest.permission.ACCESS_LOCAL_NETWORK)

    suspend fun request(context: Context): Boolean = requestMutex.withLock {
        if (isGranted(context)) return@withLock true
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                PermissionRequest.requestPermissions(
                    context,
                    arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK),
                ) { granted ->
                    if (continuation.isActive) {
                        continuation.resume(granted[Manifest.permission.ACCESS_LOCAL_NETWORK] == true)
                    }
                }
            }
        }
    }

    /** Call from an explicit connection/configuration action, never from a retry. */
    suspend fun requestForEndpoint(context: Context, endpoint: String) {
        if (isGranted(context)) return
        if (withContext(Dispatchers.IO) { isLocalNetworkEndpoint(endpoint) }) {
            requireGranted(context, request(context))
        }
    }

    suspend fun requestForServer(context: Context) {
        requireGranted(context, request(context))
    }

    /** Background work must report missing permission without opening an activity. */
    suspend fun requireForEndpoint(context: Context, endpoint: String) {
        if (isGranted(context)) return
        if (withContext(Dispatchers.IO) { isLocalNetworkEndpoint(endpoint) }) {
            requireGranted(context, false)
        }
    }

    private fun requireGranted(context: Context, granted: Boolean) {
        if (!granted) throw SecurityException(
            if (AppLocaleManager.isEnglish(context)) {
                "Local network access is required for this connection. Allow Nearby devices in app permissions, then try again."
            } else {
                "此连接需要局域网权限，请在应用权限中允许「附近的设备」后重试。"
            },
        )
    }
}

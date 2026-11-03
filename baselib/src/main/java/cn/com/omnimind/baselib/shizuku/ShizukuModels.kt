package cn.com.omnimind.baselib.shizuku

import kotlinx.serialization.Serializable

typealias ShizukuBackend = cn.com.omnimind.baselib.privileged.PrivilegedBackend

@Serializable
enum class ShizukuStatusCode {
    NOT_INSTALLED,
    NOT_RUNNING,
    PERMISSION_DENIED,
    GRANTED_ADB,
    GRANTED_ROOT,
    BINDER_DEAD
}

@Serializable
data class ShizukuStatus(
    val code: ShizukuStatusCode,
    val backend: ShizukuBackend = ShizukuBackend.NONE,
    val installed: Boolean = false,
    val running: Boolean = false,
    val permissionGranted: Boolean = false,
    val binderReady: Boolean = false,
    val serviceBound: Boolean = false,
    val uid: Int? = null,
    val version: Int? = null,
    val availableActions: List<String> = emptyList(),
    val message: String = "",
) {
    fun isGranted(): Boolean {
        return code == ShizukuStatusCode.GRANTED_ADB || code == ShizukuStatusCode.GRANTED_ROOT
    }

    fun toMap(): Map<String, Any?> {
        return linkedMapOf(
            "status" to code.name,
            "backend" to backend.name,
            "installed" to installed,
            "running" to running,
            "permissionGranted" to permissionGranted,
            "binderReady" to binderReady,
            "serviceBound" to serviceBound,
            "uid" to uid,
            "version" to version,
            "availableActions" to availableActions,
            "message" to message
        )
    }
}

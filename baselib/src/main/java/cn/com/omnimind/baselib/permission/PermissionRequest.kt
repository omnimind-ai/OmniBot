package cn.com.omnimind.baselib.permission

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.util.SparseArray
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import cn.com.omnimind.baselib.R


/**
 * 透明Activity方式实现权限请求工具类
 * 通过启动一个透明的Activity来处理权限请求，避免在业务Activity中处理复杂的权限逻辑。
 * 索取敏感权限时在顶部展示使用目的说明（不可点击），并直接使用系统权限弹窗获取权限。
 */
class PermissionRequest : Activity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val EXTRA_PERMISSIONS = "extra_permissions"

        private val requestCallbacks = SparseArray<(Map<String, Boolean>) -> Unit>()
        private var requestCode = 0

        /**
         * 敏感权限使用目的说明（与华为「同步告知权限申请目的」要求一致）
         */
        private val PERMISSION_PURPOSES: Map<String, String> = mapOf(
            android.Manifest.permission.POST_NOTIFICATIONS to "为及时向您推送消息通知",
            android.Manifest.permission.READ_MEDIA_AUDIO to "用于读取您主动选择的本地 MP3，作为提醒闹钟铃声",
            android.Manifest.permission.READ_EXTERNAL_STORAGE to "用于在 Android 12 及以下读取您主动选择的本地 MP3",
            android.Manifest.permission.ACCESS_COARSE_LOCATION to "为获取大致位置信息以提供位置相关服务",
            android.Manifest.permission.ACCESS_FINE_LOCATION to "为获取精确位置信息以提供位置相关服务",
            android.Manifest.permission.CAMERA to "为实现扫码、图片转文字等功能",
            android.Manifest.permission.RECORD_AUDIO to "用于录制您主动发起的语音输入并转写为文字",
            android.Manifest.permission.READ_CALENDAR to "用于在您主动调用日历功能时读取日历和日程",
            android.Manifest.permission.WRITE_CALENDAR to "用于在您主动调用日历功能时创建或修改日程",
        )

        /**
         * 请求权限
         * @param context 上下文
         * @param permissions 需要请求的权限数组
         * @param callback 权限请求结果回调
         */
        fun requestPermissions(
            context: Context,
            permissions: Array<String>,
            callback: (Map<String, Boolean>) -> Unit
        ) {
            requestCode++
            requestCallbacks.put(requestCode, callback)

            val intent = Intent(context, PermissionRequest::class.java)
                .putExtra(EXTRA_PERMISSIONS, permissions)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        /**
         * 检查是否已授予指定权限
         * @param context 上下文
         * @param permission 权限名称
         * @return 是否已授权
         */
        fun isPermissionGranted(context: Context, permission: String): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }

        internal fun getPurposeForPermission(permission: String): String? = PERMISSION_PURPOSES[permission]
    }

    private var currentRequestCode = 0
    private var pendingPermissionsToRequest: Array<String> = emptyArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_OmnibotApp_Permission)

        val permissions = intent.getStringArrayExtra(EXTRA_PERMISSIONS)
        if (permissions == null || permissions.isEmpty()) {
            Handler().postDelayed({ finish() }, 1000)
            return
        }

        currentRequestCode = requestCode

        // 检查是否已经拥有权限
        val permissionsToRequest = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isEmpty()) {
            onRequestPermissionsResult(
                PERMISSION_REQUEST_CODE,
                permissions,
                IntArray(permissions.size) { PackageManager.PERMISSION_GRANTED })
        } else {
            pendingPermissionsToRequest = permissionsToRequest
            showPermissionPurposeDialogThenRequest(permissionsToRequest)
        }
    }

    /**
     * 在顶部展示权限使用说明（仅展示、不可点击），并直接调用系统权限请求。
     */
    private fun showPermissionPurposeDialogThenRequest(permissionsToRequest: Array<String>) {
        val purposeLines = permissionsToRequest.mapNotNull { permission ->
            getPurposeForPermission(permission)?.let { purpose ->
                "${getPermissionLabelShort(permission)}$purpose"
            }
        }
        if (purposeLines.isNotEmpty()) {
            setContentView(R.layout.permission_request_top_info)
            findViewById<TextView>(R.id.permission_info).text = purposeLines.joinToString("\n\n")
        }
        // 直接使用系统获取权限（无自定义按钮）
        window.decorView.post { doRequestPermissions(pendingPermissionsToRequest) }
    }

    private fun doRequestPermissions(permissions: Array<String>) {
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
    }

    /** 权限对应的简短标签（带冒号），用于「XXX权限使用说明：」 */
    private fun getPermissionLabelShort(permission: String): String {
        return when (permission) {
            android.Manifest.permission.POST_NOTIFICATIONS -> "通知权限使用说明："
            android.Manifest.permission.READ_MEDIA_AUDIO -> "音频权限使用说明："
            android.Manifest.permission.READ_EXTERNAL_STORAGE -> "读取存储权限使用说明："
            android.Manifest.permission.ACCESS_COARSE_LOCATION -> "大致位置权限使用说明："
            android.Manifest.permission.ACCESS_FINE_LOCATION -> "精确位置权限使用说明："
            android.Manifest.permission.CAMERA -> "相机权限使用说明："
            android.Manifest.permission.RECORD_AUDIO -> "麦克风权限使用说明："
            android.Manifest.permission.READ_CALENDAR -> "日历读取权限使用说明："
            android.Manifest.permission.WRITE_CALENDAR -> "日历写入权限使用说明："
            else -> "权限使用说明："
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        val resultMap = mutableMapOf<String, Boolean>()
        permissions.forEachIndexed { index, permission ->
            resultMap[permission] = grantResults[index] == PackageManager.PERMISSION_GRANTED
        }

        // 回调结果
        requestCallbacks.get(currentRequestCode)?.invoke(resultMap)
        requestCallbacks.remove(currentRequestCode)

        // 关闭透明Activity
        finish()
    }
}

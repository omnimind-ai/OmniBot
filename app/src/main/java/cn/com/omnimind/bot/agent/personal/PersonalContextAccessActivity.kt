package cn.com.omnimind.bot.agent.personal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import cn.com.omnimind.baselib.i18n.AppLocaleManager

/** 只承载用户在权限页发起的系统授权，工具执行不会启动这个 Activity。 */
class PersonalContextAccessActivity : ComponentActivity() {
    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { finish() }
    private val settings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { finish() }
    private val documents = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uris = buildList {
                data?.data?.let(::add)
                data?.clipData?.let { clip -> for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri) }
            }.distinct()
            for (uri in uris) {
                try {
                    PersonalContextDocuments(this).remember(uri, data?.flags ?: 0)
                } catch (_: SecurityException) {
                    // 不持有临时授权的假成功状态；返回页面后会重新读取系统授权列表。
                }
            }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(cn.com.omnimind.baselib.R.style.Theme_OmnibotApp_Permission)
        if (savedInstanceState != null) return
        val id = intent.getStringExtra("capability").orEmpty()
        if (id !in PersonalContextAccess.IDS) { finish(); return }
        val english = AppLocaleManager.isEnglish(this)
        setContentView(TextView(this).apply {
            text = if (english) "Personal context: information you allow AI to query may be sent to your configured model. Access is optional and can be revoked in Settings." else "个人上下文：允许 AI 查询的信息可能会提供给您配置的模型。授权为可选项，可随时在系统设置中撤销。"
            setPadding(32, 64, 32, 32)
        })
        try {
            when (id) {
                "notifications" -> settings.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                "app_usage" -> settings.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
                "documents" -> documents.launch(if (intent.getBooleanExtra("directory", false)) {
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                } else {
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                })
                "environment" -> finish()
                else -> requestRuntimeAccess(id)
            }
        } catch (_: android.content.ActivityNotFoundException) {
            settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun requestRuntimeAccess(id: String) {
        val access = PersonalContextAccess(this)
        if (id == "background_location" && !access.snapshot("location").granted) { finish(); return }
        val permissions = PersonalContextAccess.permissions(id)
        val preferences = getSharedPreferences("personal_context_permissions", Context.MODE_PRIVATE)
        val missing = permissions.filterNot(access::granted)
        val permanentlyDenied = missing.any { preferences.getBoolean(it, false) && !shouldShowRequestPermissionRationale(it) }
        val useSettings = access.snapshot(id).status == "restricted" ||
            (id == "background_location" && Build.VERSION.SDK_INT >= 30) ||
            missing.isEmpty() || permanentlyDenied
        if (useSettings && !(id == "media_visual" && access.snapshot(id).status == "partial")) {
            settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            preferences.edit().apply { permissions.forEach { putBoolean(it, true) } }.apply()
            requestPermissions.launch(permissions.toTypedArray())
        }
    }
}

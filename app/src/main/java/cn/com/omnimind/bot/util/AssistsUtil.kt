package cn.com.omnimind.bot.util

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import cn.com.omnimind.baselib.util.MobileManufacturerUtil
import cn.com.omnimind.uikit.UIKit

class AssistsUtil {
    object Setting {
        private const val TAG = "AssistsUtil"
        private const val OPSTR_RUN_ANY_IN_BACKGROUND = "android:run_any_in_background"
        private const val OPSTR_RUN_IN_BACKGROUND = "android:run_in_background"

        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            val powerManager =
                context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }

        fun isBackgroundRunAllowed(context: Context): Boolean {
            if (isIgnoringBatteryOptimizations(context)) return true
            if (!MobileManufacturerUtil.isXiaomiSeries()) return false
            return isXiaomiBackgroundRunAllowed(context)
        }

        private fun isXiaomiBackgroundRunAllowed(context: Context): Boolean {
            val appOpsManager =
                context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                    ?: return false
            val uid = context.applicationInfo.uid.takeIf { it > 0 } ?: Process.myUid()
            val knownModes = listOfNotNull(
                readAppOpMode(
                    appOpsManager,
                    OPSTR_RUN_ANY_IN_BACKGROUND,
                    uid,
                    context.packageName
                ),
                readAppOpMode(
                    appOpsManager,
                    OPSTR_RUN_IN_BACKGROUND,
                    uid,
                    context.packageName
                )
            )
            if (knownModes.isEmpty()) return false
            if (
                knownModes.any {
                    it == AppOpsManager.MODE_IGNORED ||
                        it == AppOpsManager.MODE_ERRORED ||
                        it == AppOpsManager.MODE_FOREGROUND
                }
            ) {
                return false
            }
            return knownModes.all {
                it == AppOpsManager.MODE_ALLOWED || it == AppOpsManager.MODE_DEFAULT
            }
        }

        private fun readAppOpMode(
            appOpsManager: AppOpsManager,
            operation: String,
            uid: Int,
            packageName: String
        ): Int? {
            return runCatching {
                appOpsManager.unsafeCheckOpNoThrow(operation, uid, packageName)
            }.getOrElse {
                Log.d(TAG, "readAppOpMode failed for $operation: ${it.message}")
                null
            }
        }

        fun openBatteryOptimizationSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:${context.packageName}".toUri()
                }
            )
        }

        fun isOverlayPermission(context: Context): Boolean {
            return Settings.canDrawOverlays(context)
        }

        fun openOverlaySettings(context: Context) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:${context.packageName}".toUri()
                )
            )
        }

        fun isInstalledAppsPermissionGranted(context: Context): Boolean {
            return try {
                context.packageManager.getInstalledApplications(0).size > 1
            } catch (_: Exception) {
                false
            }
        }

        fun openInstalledAppsSettings(context: Context) {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:${context.packageName}".toUri()
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        }

        fun openAutoStartSettings(context: Context) {
            val brand = android.os.Build.BRAND?.lowercase().orEmpty()
            when {
                brand == "honor" || brand == "荣耀" -> openHonorAutoStartSettings(context)
                brand == "huawei" || brand == "华为" -> openHuaweiAutoStartSettings(context)
                else -> openAppDetailSettings(context)
            }
        }

        private fun openHonorAutoStartSettings(context: Context) {
            if (
                tryOpenComponent(
                    context,
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            ) {
                return
            }
            if (
                tryOpenComponent(
                    context,
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupAppListActivity"
                )
            ) {
                return
            }
            openAppDetailSettings(context)
        }

        private fun openHuaweiAutoStartSettings(context: Context) {
            if (
                tryOpenComponent(
                    context,
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            ) {
                return
            }
            if (
                tryOpenComponent(
                    context,
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                )
            ) {
                return
            }
            openAppDetailSettings(context)
        }

        private fun tryOpenComponent(
            context: Context,
            packageName: String,
            className: String
        ): Boolean {
            return runCatching {
                context.startActivity(
                    Intent().apply {
                        component = ComponentName(packageName, className)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                )
                true
            }.getOrDefault(false)
        }

        private fun openAppDetailSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = "package:${context.packageName}".toUri()
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        }
    }

    object UI {
        suspend fun closeScreenDialog() {
            UIKit.uiChatEvent?.dismissHalfScreen()
        }

        suspend fun closeChatBotDialog() {
            UIKit.uiChatEvent?.closeChatBotBg()
        }

        fun isChatBotDialogShowing(): Boolean {
            return UIKit.uiChatEvent?.isChatBotHalfScreenShowing() == true
        }
    }
}

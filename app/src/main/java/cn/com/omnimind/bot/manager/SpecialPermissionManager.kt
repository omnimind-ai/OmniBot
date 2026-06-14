package cn.com.omnimind.bot.manager

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import cn.com.omnimind.baselib.permission.PermissionRequest
import cn.com.omnimind.baselib.shizuku.ShizukuCapabilityManager
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.codex.CodexAppServerManager
import cn.com.omnimind.bot.terminal.EmbeddedTerminalAutoStartManager
import cn.com.omnimind.bot.terminal.EmbeddedTerminalInitCoordinator
import cn.com.omnimind.bot.terminal.EmbeddedTerminalLaunchHelper
import cn.com.omnimind.bot.terminal.EmbeddedTerminalRuntime
import cn.com.omnimind.bot.terminal.EmbeddedTerminalSetupManager
import cn.com.omnimind.bot.termux.TermuxCommandRunner
import cn.com.omnimind.bot.util.AssistsUtil
import cn.com.omnimind.bot.workspace.PublicStorageAccess
import cn.com.omnimind.bot.workspace.WorkspaceStorageAccess
import com.rk.libcommons.OmnibotTerminalEnvironment
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SpecialPermissionManager(private val context: Context) {

    companion object {
        private const val TAG = "[PlatformManager]"
    }
    private val embeddedTerminalSetupManager = EmbeddedTerminalSetupManager(context)
    private val embeddedTerminalAutoStartManager = EmbeddedTerminalAutoStartManager(context)
    private val shizukuCapabilityManager = ShizukuCapabilityManager.get(context)

    fun isAccessibilityServiceEnabled(result: MethodChannel.Result) {
        try {
            val isEnabled = AssistsUtil.Core.isAccessibilityServiceEnabled()
            result.success(isEnabled)
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error checking accessibility service", e)
            result.error("CHECK_FAILED", "Failed to check accessibility service.", e.message)
        }
    }

    fun isIgnoringBatteryOptimizations(result: MethodChannel.Result) {
        try {
            val value = AssistsUtil.Setting.isIgnoringBatteryOptimizations(context);
            result.success(value)
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error checking battery optimization", e)
            result.error("CHECK_FAILED", "Failed to check battery optimization.", e.message)
        }
    }

    fun isBackgroundRunAllowed(result: MethodChannel.Result) {
        try {
            val value = AssistsUtil.Setting.isBackgroundRunAllowed(context)
            result.success(value)
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error checking background run permission", e)
            result.error("CHECK_FAILED", "Failed to check background run permission.", e.message)
        }
    }

    fun openBatteryOptimizationSettings(result: MethodChannel.Result) {
        try {
            AssistsUtil.Setting.openBatteryOptimizationSettings(context)
            OmniLog.v(TAG, "Requesting to ignore battery optimizations.")
            result.success(null)

        } catch (e: Exception) {
            OmniLog.e(TAG, "请求忽略电池优化时发生异常，可能没有 Activity 能处理此 Intent。", e)
            result.error(
                "INTENT_FAILED",
                "无法打开电池优化设置页面，可能没有 Activity 能处理此 Intent。",
                e.message
            )
        }

    }

    fun openAccessibilitySettings(result: MethodChannel.Result) {
        try {
            AssistsUtil.Setting.openAccessibilitySettings(context);
            OmniLog.v(TAG, "Opening accessibility settings.")
            result.success(null)

        } catch (e: Exception) {
            OmniLog.e(TAG, "请求打开辅助功能设置时发生异常，可能没有 Activity 能处理此 Intent。", e)
            result.error(
                "INTENT_FAILED",
                "无法打开辅助功能设置页面，可能没有 Activity 能处理此 Intent。",
                e.message
            )
        }
    }

    fun isOverlayPermission(result: MethodChannel.Result) {
        try {
            val value = AssistsUtil.Setting.isOverlayPermission(context);
            result.success(value)
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error checking battery optimization", e)
            result.error("CHECK_FAILED", "Failed to overlay permission.", e.message)
        }
    }

    fun openOverlaySettings(result: MethodChannel.Result) {
        try {
            AssistsUtil.Setting.openOverlaySettings(context);
            result.success(null)
            OmniLog.v(TAG, "Opening overlay settings.")
        } catch (e: Exception) {
            OmniLog.e(TAG, "请求打开悬浮窗设置时发生异常，可能没有 Activity 能处理此 Intent。", e)
            result.error(
                "INTENT_FAILED",
                "无法打开打开悬浮窗设置页面，可能没有 Activity 能处理此 Intent。",
                e.message
            )
        }

    }

    fun isInstalledAppsPermissionGranted(result: MethodChannel.Result) {
        try {
            val value = AssistsUtil.Setting.isInstalledAppsPermissionGranted(context)
            result.success(value)
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error checking installed apps permission", e)
            result.error("CHECK_FAILED", "Failed to check installed apps permission.", e.message)
        }
    }

    fun openInstalledAppsSettings(result: MethodChannel.Result) {
        try {
            AssistsUtil.Setting.openInstalledAppsSettings(context)
            result.success(null)
            OmniLog.v(TAG, "Opening installed apps settings.")
        } catch (e: Exception) {
            OmniLog.e(
                TAG,
                "请求打开已安装应用列表权限设置时发生异常，可能没有 Activity 能处理此 Intent。",
                e
            )
            result.error(
                "INTENT_FAILED",
                "无法打开已安装应用列表权限设置页面，可能没有 Activity 能处理此 Intent。",
                e.message
            )
        }
    }

    fun openAutoStartSettings(result: MethodChannel.Result) {
        try {
            AssistsUtil.Setting.openAutoStartSettings(context)
            result.success(null)
            OmniLog.v(TAG, "Opening auto start settings.")
        } catch (e: Exception) {
            OmniLog.e(
                TAG,
                "请求打开应用启动管理设置时发生异常，可能没有 Activity 能处理此 Intent。",
                e
            )
            result.error(
                "INTENT_FAILED",
                "无法打开应用启动管理设置页面，可能没有 Activity 能处理此 Intent。",
                e.message
            )
        }
    }

    fun isShizukuInstalled(result: MethodChannel.Result) {
        try {
            result.success(shizukuCapabilityManager.isShizukuInstalled())
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error checking Shizuku installation", e)
            result.error("CHECK_FAILED", "Failed to check Shizuku installation.", e.message)
        }
    }

    fun isShizukuRunning(result: MethodChannel.Result) {
        try {
            result.success(shizukuCapabilityManager.getStatus().running)
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error checking Shizuku running status", e)
            result.error("CHECK_FAILED", "Failed to check Shizuku running status.", e.message)
        }
    }

    fun openShizukuDownloadOrApp(result: MethodChannel.Result) {
        try {
            result.success(shizukuCapabilityManager.openShizukuDownloadOrApp())
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error opening Shizuku app or website", e)
            result.error("INTENT_FAILED", "Failed to open Shizuku app or website.", e.message)
        }
    }

    fun requestShizukuPermission(result: MethodChannel.Result) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val status = shizukuCapabilityManager.requestPermission()
                withContext(Dispatchers.Main) {
                    result.success(status.toMap())
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "Error requesting Shizuku permission", e)
                withContext(Dispatchers.Main) {
                    result.error("REQUEST_FAILED", "Failed to request Shizuku permission.", e.message)
                }
            }
        }
    }

    fun getShizukuStatus(result: MethodChannel.Result) {
        try {
            result.success(shizukuCapabilityManager.getStatus().toMap())
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error reading Shizuku status", e)
            result.error("READ_FAILED", "Failed to read Shizuku status.", e.message)
        }
    }

    fun runShizukuHealthCheck(result: MethodChannel.Result) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val payload = shizukuCapabilityManager.runHealthCheck()
                withContext(Dispatchers.Main) {
                    result.success(payload)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "Error running Shizuku health check", e)
                withContext(Dispatchers.Main) {
                    result.error("HEALTH_CHECK_FAILED", "Failed to run Shizuku health check.", e.message)
                }
            }
        }
    }

    fun isTermuxInstalled(result: MethodChannel.Result) {
        try {
            result.success(EmbeddedTerminalRuntime.isSupportedDevice())
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error checking embedded terminal availability", e)
            result.error("CHECK_FAILED", "Failed to check embedded terminal availability.", e.message)
        }
    }

    fun openTermuxApp(result: MethodChannel.Result) {
        try {
            if (!EmbeddedTerminalRuntime.isSupportedDevice()) {
                result.success(false)
                return
            }
            EmbeddedTerminalLaunchHelper.launch(context = context)
            result.success(true)
        } catch (e: Exception) {
            OmniLog.e(TAG, "请求打开内嵌终端时发生异常。", e)
            result.error(
                "INTENT_FAILED",
                "无法打开内嵌终端。",
                e.message
            )
        }
    }

    fun isTermuxRunCommandPermissionGranted(result: MethodChannel.Result) {
        try {
            result.success(EmbeddedTerminalRuntime.isSupportedDevice())
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error checking embedded terminal status", e)
            result.error(
                "CHECK_FAILED",
                "Failed to check embedded terminal status.",
                e.message
            )
        }
    }

    fun requestTermuxRunCommandPermission(result: MethodChannel.Result) {
        try {
            result.success(EmbeddedTerminalRuntime.isSupportedDevice())
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error preparing embedded terminal", e)
            result.error(
                "REQUEST_FAILED",
                "Failed to prepare embedded terminal.",
                e.message
            )
        }
    }

    fun openAppDetailsSettings(result: MethodChannel.Result) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            result.success(true)
        } catch (e: Exception) {
            OmniLog.e(TAG, "请求打开应用详情页时发生异常。", e)
            result.error(
                "INTENT_FAILED",
                "无法打开应用详情页，可能没有 Activity 能处理此 Intent。",
                e.message
            )
        }
    }

    fun isNotificationPermissionGranted(result: MethodChannel.Result) {
        try {
            val granted = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                true
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            }
            result.success(granted)
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error checking notification permission", e)
            result.error(
                "CHECK_FAILED",
                "Failed to check notification permission.",
                e.message
            )
        }
    }

    fun requestNotificationPermission(result: MethodChannel.Result) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                result.success(true)
                return
            }
            CoroutineScope(Dispatchers.Default).launch {
                AssistsUtil.UI.closeChatBotDialog()
            }
            PermissionRequest.requestPermissions(
                context,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                result.success(it[Manifest.permission.POST_NOTIFICATIONS] == true)
            }
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error requesting notification permission", e)
            result.error(
                "REQUEST_FAILED",
                "Failed to request notification permission.",
                e.message
            )
        }
    }

    fun isWorkspaceStorageAccessGranted(result: MethodChannel.Result) {
        try {
            result.success(WorkspaceStorageAccess.isGranted(context))
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error checking workspace storage access", e)
            result.error(
                "CHECK_FAILED",
                "Failed to check workspace storage access.",
                e.message
            )
        }
    }

    fun isPublicStorageAccessGranted(result: MethodChannel.Result) {
        try {
            result.success(PublicStorageAccess.isGranted())
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error checking public storage access", e)
            result.error(
                "CHECK_FAILED",
                "Failed to check public storage access.",
                e.message
            )
        }
    }

    fun openWorkspaceStorageSettings(result: MethodChannel.Result) {
        try {
            val primaryIntent = WorkspaceStorageAccess.buildSettingsIntent(context)
            runCatching {
                context.startActivity(primaryIntent)
            }.recoverCatching {
                context.startActivity(WorkspaceStorageAccess.buildFallbackSettingsIntent())
            }.getOrThrow()
            result.success(true)
        } catch (e: Exception) {
            OmniLog.e(TAG, "请求打开公共 workspace 存储设置页时发生异常。", e)
            result.error(
                "INTENT_FAILED",
                "无法打开公共 workspace 存储设置页，可能没有 Activity 能处理此 Intent。",
                e.message
            )
        }
    }

    fun openPublicStorageSettings(result: MethodChannel.Result) {
        try {
            val primaryIntent = PublicStorageAccess.buildSettingsIntent(context.packageName)
            runCatching {
                context.startActivity(primaryIntent)
            }.recoverCatching {
                context.startActivity(PublicStorageAccess.buildFallbackSettingsIntent())
            }.getOrThrow()
            result.success(true)
        } catch (e: Exception) {
            OmniLog.e(TAG, "请求打开公共文件访问设置页时发生异常。", e)
            result.error(
                "INTENT_FAILED",
                "无法打开公共文件访问设置页，可能没有 Activity 能处理此 Intent。",
                e.message
            )
        }
    }

    fun getWorkspacePathSnapshot(result: MethodChannel.Result) {
        try {
            result.success(AgentWorkspaceManager.workspacePathSnapshot(context))
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error getting workspace path snapshot", e)
            result.error(
                "WORKSPACE_PATH_SNAPSHOT_FAILED",
                "Failed to get workspace path snapshot.",
                e.message
            )
        }
    }

    fun getEmbeddedTerminalRuntimeStatus(result: MethodChannel.Result) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val readiness = EmbeddedTerminalRuntime.inspectRuntimeReadiness(context)
                val workspaceGranted = WorkspaceStorageAccess.isGranted(context)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "supported" to readiness.supported,
                            "runtimeReady" to readiness.runtimeReady,
                            "basePackagesReady" to readiness.basePackagesReady,
                            "allReady" to (readiness.supported && readiness.runtimeReady && readiness.basePackagesReady),
                            "missingCommands" to readiness.missingCommands,
                            "message" to readiness.message,
                            "nodeReady" to readiness.nodeReady,
                            "nodeVersion" to readiness.nodeVersion,
                            "nodeMinMajor" to readiness.nodeMinMajor,
                            "pnpmReady" to readiness.pnpmReady,
                            "pnpmVersion" to readiness.pnpmVersion,
                            "workspaceAccessGranted" to workspaceGranted
                        )
                    )
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "Error checking embedded terminal runtime status", e)
                withContext(Dispatchers.Main) {
                    result.error(
                        "CHECK_FAILED",
                        "Failed to check embedded terminal runtime status.",
                        e.message
                    )
                }
            }
        }
    }

    fun getEmbeddedTerminalSetupStatus(result: MethodChannel.Result) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val packageStatus = embeddedTerminalSetupManager.getPackageInstallStatus()
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "packages" to packageStatus
                        )
                    )
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "Error reading embedded terminal setup status", e)
                withContext(Dispatchers.Main) {
                    result.error(
                        "READ_SETUP_STATUS_FAILED",
                        "Failed to read embedded terminal setup status.",
                        e.message
                    )
                }
            }
        }
    }

    fun getEmbeddedTerminalSetupInventory(result: MethodChannel.Result) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val packageInventory = embeddedTerminalSetupManager.getPackageInventory()
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "packages" to packageInventory.mapValues { it.value.toMap() }
                        )
                    )
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "Error reading embedded terminal setup inventory", e)
                withContext(Dispatchers.Main) {
                    val message = e.message ?: "Failed to read embedded terminal setup inventory."
                    result.error(
                        "READ_SETUP_INVENTORY_FAILED",
                        message,
                        e.message
                    )
                }
            }
        }
    }

    fun installEmbeddedTerminalPackages(call: MethodCall, result: MethodChannel.Result) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val packageIds = call.argument<List<String>>("packageIds").orEmpty()
                val installResult = embeddedTerminalSetupManager.installPackages(packageIds)
                withContext(Dispatchers.Main) {
                    result.success(installResult.toMap())
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "Error installing embedded terminal packages", e)
                withContext(Dispatchers.Main) {
                    result.error(
                        "INSTALL_SETUP_PACKAGES_FAILED",
                        "Failed to install embedded terminal packages.",
                        e.message
                    )
                }
            }
        }
    }

    fun getEmbeddedTerminalSetupSessionSnapshot(result: MethodChannel.Result) {
        try {
            result.success(embeddedTerminalSetupManager.getInstallSessionSnapshot().toMap())
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error reading embedded terminal setup session snapshot", e)
            result.error(
                "READ_SETUP_SESSION_FAILED",
                "Failed to read embedded terminal setup session snapshot.",
                e.message
            )
        }
    }

    fun startEmbeddedTerminalSetupSession(call: MethodCall, result: MethodChannel.Result) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val packageIds = call.argument<List<String>>("packageIds").orEmpty()
                val snapshot = embeddedTerminalSetupManager.startInstallSession(packageIds)
                withContext(Dispatchers.Main) {
                    result.success(snapshot.toMap())
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "Error starting embedded terminal setup session", e)
                withContext(Dispatchers.Main) {
                    result.error(
                        "START_SETUP_SESSION_FAILED",
                        "Failed to start embedded terminal setup session.",
                        e.message
                    )
                }
            }
        }
    }

    fun dismissEmbeddedTerminalSetupSession(result: MethodChannel.Result) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                embeddedTerminalSetupManager.dismissInstallSession()
                withContext(Dispatchers.Main) {
                    result.success(null)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "Error dismissing embedded terminal setup session", e)
                withContext(Dispatchers.Main) {
                    result.error(
                        "DISMISS_SETUP_SESSION_FAILED",
                        "Failed to dismiss embedded terminal setup session.",
                        e.message
                    )
                }
            }
        }
    }

    fun getEmbeddedTerminalAutoStartTasks(result: MethodChannel.Result) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tasks = embeddedTerminalAutoStartManager.listTasks()
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "tasks" to tasks.map { it.toMap() }
                        )
                    )
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "Error reading embedded terminal auto-start tasks", e)
                withContext(Dispatchers.Main) {
                    result.error(
                        "READ_AUTO_START_TASKS_FAILED",
                        "Failed to read embedded terminal auto-start tasks.",
                        e.message
                    )
                }
            }
        }
    }

    fun saveEmbeddedTerminalAutoStartTask(call: MethodCall, result: MethodChannel.Result) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val snapshot = embeddedTerminalAutoStartManager.saveTask(
                    id = call.argument<String>("id"),
                    name = call.argument<String>("name").orEmpty(),
                    command = call.argument<String>("command").orEmpty(),
                    workingDirectory = call.argument<String>("workingDirectory"),
                    enabled = call.argument<Boolean>("enabled") != false
                )
                withContext(Dispatchers.Main) {
                    result.success(snapshot.toMap())
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "Error saving embedded terminal auto-start task", e)
                withContext(Dispatchers.Main) {
                    result.error(
                        "SAVE_AUTO_START_TASK_FAILED",
                        "Failed to save embedded terminal auto-start task.",
                        e.message
                    )
                }
            }
        }
    }

    fun deleteEmbeddedTerminalAutoStartTask(call: MethodCall, result: MethodChannel.Result) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                embeddedTerminalAutoStartManager.deleteTask(
                    taskId = call.argument<String>("id").orEmpty()
                )
                withContext(Dispatchers.Main) {
                    result.success(null)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "Error deleting embedded terminal auto-start task", e)
                withContext(Dispatchers.Main) {
                    result.error(
                        "DELETE_AUTO_START_TASK_FAILED",
                        "Failed to delete embedded terminal auto-start task.",
                        e.message
                    )
                }
            }
        }
    }

    fun runEmbeddedTerminalAutoStartTask(call: MethodCall, result: MethodChannel.Result) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val runResult = embeddedTerminalAutoStartManager.runTaskNow(
                    taskId = call.argument<String>("id").orEmpty()
                )
                withContext(Dispatchers.Main) {
                    result.success(runResult.toMap())
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "Error running embedded terminal auto-start task", e)
                withContext(Dispatchers.Main) {
                    result.error(
                        "RUN_AUTO_START_TASK_FAILED",
                        "Failed to run embedded terminal auto-start task.",
                        e.message
                    )
                }
            }
        }
    }

    fun syncTerminalEnvironmentVariables(call: MethodCall, result: MethodChannel.Result) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val rawVariables = call.argument<List<Any?>>("variables").orEmpty()
                val variables = linkedMapOf<String, String>()
                rawVariables.forEach { item ->
                    val map = item as? Map<*, *> ?: return@forEach
                    val key = map["key"]?.toString().orEmpty()
                    val value = map["value"]?.toString().orEmpty()
                    variables[key] = value
                }
                val normalized = OmnibotTerminalEnvironment.saveUserVariables(context, variables)
                runCatching {
                    CodexAppServerManager.getInstance(context).invalidateLocalTerminalEnvironment()
                }.onFailure { error ->
                    OmniLog.w(TAG, "Failed to invalidate Codex environment: ${error.message}")
                }
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "variables" to normalized.map { (key, value) ->
                                mapOf("key" to key, "value" to value)
                            }
                        )
                    )
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "Error syncing terminal environment variables", e)
                withContext(Dispatchers.Main) {
                    result.error(
                        "SYNC_TERMINAL_ENV_FAILED",
                        "Failed to sync terminal environment variables.",
                        e.message
                    )
                }
            }
        }
    }

    fun openNativeTerminal(call: MethodCall, result: MethodChannel.Result) {
        try {
            val openSetup = call.argument<Boolean>("openSetup") == true
            val setupPackageIds = call.argument<List<String>>("setupPackageIds")
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            EmbeddedTerminalLaunchHelper.launch(
                context = context,
                openSetup = openSetup,
                setupPackageIds = setupPackageIds
            )
            result.success(true)
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error opening native terminal", e)
            result.error(
                "OPEN_NATIVE_TERMINAL_FAILED",
                "Failed to open native terminal.",
                e.message
            )
        }
    }

    fun prepareTermuxLiveWrapper(result: MethodChannel.Result) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val status = EmbeddedTerminalInitCoordinator.prepare(context)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "success" to status.success,
                            "wrapperReady" to status.wrapperReady,
                            "sharedStorageReady" to status.sharedStorageReady,
                            "message" to status.message
                        )
                    )
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "Error preparing embedded terminal runtime", e)
                withContext(Dispatchers.Main) {
                    result.error(
                        "PREPARE_FAILED",
                        "Failed to prepare embedded terminal runtime.",
                        e.message
                    )
                }
            }
        }
    }

    fun getEmbeddedTerminalInitSnapshot(result: MethodChannel.Result) {
        try {
            result.success(EmbeddedTerminalInitCoordinator.buildSnapshot())
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error reading embedded terminal init snapshot", e)
            result.error(
                "READ_FAILED",
                "Failed to read embedded terminal init snapshot.",
                e.message
            )
        }
    }

    fun isUnknownAppInstallAllowed(result: MethodChannel.Result) {
        try {
            result.success(ExternalApkInstaller.canInstallPackages(context))
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error checking unknown app install permission", e)
            result.error(
                "CHECK_FAILED",
                "Failed to check unknown app install permission.",
                e.message
            )
        }
    }

    fun openUnknownAppInstallSettings(result: MethodChannel.Result) {
        try {
            ExternalApkInstaller.openInstallPermissionSettings(context)
            result.success(true)
        } catch (e: Exception) {
            OmniLog.e(TAG, "请求打开未知应用安装设置页时发生异常。", e)
            result.error(
                "INTENT_FAILED",
                "无法打开未知应用安装设置页，可能没有 Activity 能处理此 Intent。",
                e.message
            )
        }
    }

    fun downloadAndInstallTermuxApk(call: MethodCall, result: MethodChannel.Result) {
        result.success(
            mapOf(
                "success" to true,
                "status" to "not_needed",
                "message" to "终端能力已内置到应用中，无需下载安装独立 Termux。",
                "filePath" to null
            )
        )
    }

    fun requestPermissions(call: MethodCall, result: MethodChannel.Result) {
        try {
            val permissions = call.argument<List<String>>("permissions")
            if (permissions == null||permissions.isEmpty()) {
                result.error("INVALID_ARGUMENT", "Invalid argument: permissions is null.", null)
                return
            }
            val mPermissions = ArrayList<String>();
            mPermissions.addAll(permissions)
            //要权限优先关闭 关闭聊天对话框
            CoroutineScope(Dispatchers.Default).launch {
                AssistsUtil.UI.closeChatBotDialog()
            }
            PermissionRequest.requestPermissions(context, mPermissions.toTypedArray()) {
                val isGranted = it.all { it.value }
                result.success(if (isGranted) "Success" else "Failed")
            }
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error requesting permissions", e)
            result.error("request_Permissions_FAILED", "Failed to request permissions.", e.message)
        }
    }
}

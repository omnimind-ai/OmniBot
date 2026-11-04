package cn.com.omnimind.bot.ui.channel

import cn.com.omnimind.bot.manager.SpecialPermissionManager
import cn.com.omnimind.bot.agent.personal.PersonalContextPermissionActions
import cn.com.omnimind.bot.terminal.EmbeddedTerminalInitCoordinator
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class SpecialPermissionChannel {
    @SuppressLint("StaticFieldLeak")
    var specialPermissionManager: SpecialPermissionManager? = null
    private  val CHANNEL = "cn.com.omnimind.bot/SpecialPermissionEvent"
    private  val EVENT_CHANNEL = "cn.com.omnimind.bot/SpecialPermissionEvents"
    private var methodChannel: MethodChannel? = null
    private var eventChannel: EventChannel? = null
    private var eventSink: EventChannel.EventSink? = null
    private var embeddedTerminalInitListener: ((Map<String, Any?>) -> Unit)? = null
    private var personalContextActions: PersonalContextPermissionActions? = null

    fun onCreate(context: Context) {
        specialPermissionManager?.closeRootSettings()
        specialPermissionManager = SpecialPermissionManager(context)
        personalContextActions?.close()
        personalContextActions = PersonalContextPermissionActions(context)
    }

    fun setChannel(flutterEngine: FlutterEngine) {

        methodChannel= MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        eventChannel = EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
        eventChannel?.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                eventSink = events
            }

            override fun onCancel(arguments: Any?) {
                eventSink = null
            }
        })
        embeddedTerminalInitListener?.let {
            EmbeddedTerminalInitCoordinator.removeListener(it)
        }
        embeddedTerminalInitListener = { payload ->
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    eventSink?.success(payload)
                }
            }
        }
        embeddedTerminalInitListener?.let {
            EmbeddedTerminalInitCoordinator.addListener(it)
        }
        methodChannel?.setMethodCallHandler { call, result ->
                if (personalContextActions?.handle(call, result) == true) return@setMethodCallHandler
                when (call.method) {
                    "isIgnoringBatteryOptimizations" -> specialPermissionManager!!.isIgnoringBatteryOptimizations(
                        result
                    )

                    "isBackgroundRunAllowed" -> specialPermissionManager!!.isBackgroundRunAllowed(
                        result
                    )

                    "openBatteryOptimizationSettings" -> specialPermissionManager!!.openBatteryOptimizationSettings(
                        result
                    )
                    "isOverlayPermission" -> specialPermissionManager!!.isOverlayPermission(
                        result
                    )

                    "openOverlaySettings" -> specialPermissionManager!!.openOverlaySettings(
                        result
                    )

                    "isAndroidGuiAccessibilityEnabled" -> specialPermissionManager!!
                        .isAndroidGuiAccessibilityEnabled(result)

                    "isAndroidGuiAccessibilityReady" -> specialPermissionManager!!
                        .isAndroidGuiAccessibilityReady(result)

                    "openAndroidGuiAccessibilitySettings" -> specialPermissionManager!!
                        .openAndroidGuiAccessibilitySettings(result)

                    "isInstalledAppsPermissionGranted" -> specialPermissionManager!!.isInstalledAppsPermissionGranted(
                        result
                    )

                    "openInstalledAppsSettings" -> specialPermissionManager!!.openInstalledAppsSettings(
                        result
                    )
                    "openAutoStartSettings" -> specialPermissionManager!!.openAutoStartSettings(
                        result
                    )
                    "isShizukuInstalled" -> specialPermissionManager!!.isShizukuInstalled(
                        result
                    )
                    "isShizukuRunning" -> specialPermissionManager!!.isShizukuRunning(
                        result
                    )
                    "openShizukuDownloadOrApp" -> specialPermissionManager!!.openShizukuDownloadOrApp(
                        result
                    )
                    "requestShizukuPermission" -> specialPermissionManager!!.requestShizukuPermission(
                        result
                    )
                    "getRootStatus" -> specialPermissionManager!!.getRootStatus(result)
                    "setRootEnabled" -> specialPermissionManager!!.setRootEnabled(call, result)
                    "requestRootAccess" -> specialPermissionManager!!.requestRootAccess(result)
                    "getShizukuStatus" -> specialPermissionManager!!.getShizukuStatus(
                        result
                    )
                    "runShizukuHealthCheck" -> specialPermissionManager!!.runShizukuHealthCheck(
                        result
                    )
                    "isTermuxInstalled" -> specialPermissionManager!!.isTermuxInstalled(
                        result
                    )
                    "isTermuxRunCommandPermissionGranted" -> specialPermissionManager!!
                        .isTermuxRunCommandPermissionGranted(result)
                    "requestTermuxRunCommandPermission" -> specialPermissionManager!!
                        .requestTermuxRunCommandPermission(result)
                    "openTermuxApp" -> specialPermissionManager!!.openTermuxApp(
                        result
                    )
                    "openAppDetailsSettings" -> specialPermissionManager!!
                        .openAppDetailsSettings(result)
                    "openNotificationSettings" -> specialPermissionManager!!
                        .openNotificationSettings(result)
                    "isNotificationPermissionGranted" -> specialPermissionManager!!
                        .isNotificationPermissionGranted(result)
                    "requestNotificationPermission" -> specialPermissionManager!!
                        .requestNotificationPermission(result)
                    "isLocalNetworkPermissionGranted" -> specialPermissionManager!!
                        .isLocalNetworkPermissionGranted(result)
                    "getLocalNetworkPermissionStatus" -> specialPermissionManager!!
                        .getLocalNetworkPermissionStatus(result)
                    "requestLocalNetworkPermission" -> specialPermissionManager!!
                        .requestLocalNetworkPermission(result)
                    "isWorkspaceStorageAccessGranted" -> specialPermissionManager!!
                        .isWorkspaceStorageAccessGranted(result)
                    "openWorkspaceStorageSettings" -> specialPermissionManager!!
                        .openWorkspaceStorageSettings(result)
                    "isPublicStorageAccessGranted" -> specialPermissionManager!!
                        .isPublicStorageAccessGranted(result)
                    "openPublicStorageSettings" -> specialPermissionManager!!
                        .openPublicStorageSettings(result)
                    "getWorkspacePathSnapshot" -> specialPermissionManager!!
                        .getWorkspacePathSnapshot(result)
                    "getEmbeddedTerminalRuntimeStatus" -> specialPermissionManager!!
                        .getEmbeddedTerminalRuntimeStatus(result)
                    "getEmbeddedTerminalSetupStatus" -> specialPermissionManager!!
                        .getEmbeddedTerminalSetupStatus(result)
                    "getEmbeddedTerminalSetupInventory" -> specialPermissionManager!!
                        .getEmbeddedTerminalSetupInventory(result)
                    "getEmbeddedTerminalDistribution" -> specialPermissionManager!!
                        .getEmbeddedTerminalDistribution(result)
                    "setEmbeddedTerminalDistribution" -> specialPermissionManager!!
                        .setEmbeddedTerminalDistribution(call, result)
                    "switchEmbeddedTerminalDistribution" -> specialPermissionManager!!
                        .switchEmbeddedTerminalDistribution(call, result)
                    "getEmbeddedTerminalSetupSessionSnapshot" -> specialPermissionManager!!
                        .getEmbeddedTerminalSetupSessionSnapshot(result)
                    "installEmbeddedTerminalPackages" -> specialPermissionManager!!
                        .installEmbeddedTerminalPackages(call, result)
                    "startEmbeddedTerminalSetupSession" -> specialPermissionManager!!
                        .startEmbeddedTerminalSetupSession(call, result)
                    "dismissEmbeddedTerminalSetupSession" -> specialPermissionManager!!
                        .dismissEmbeddedTerminalSetupSession(result)
                    "getEmbeddedTerminalAutoStartTasks" -> specialPermissionManager!!
                        .getEmbeddedTerminalAutoStartTasks(result)
                    "saveEmbeddedTerminalAutoStartTask" -> specialPermissionManager!!
                        .saveEmbeddedTerminalAutoStartTask(call, result)
                    "deleteEmbeddedTerminalAutoStartTask" -> specialPermissionManager!!
                        .deleteEmbeddedTerminalAutoStartTask(call, result)
                    "runEmbeddedTerminalAutoStartTask" -> specialPermissionManager!!
                        .runEmbeddedTerminalAutoStartTask(call, result)
                    "syncTerminalEnvironmentVariables" -> specialPermissionManager!!
                        .syncTerminalEnvironmentVariables(call, result)
                    "openNativeTerminal" -> specialPermissionManager!!
                        .openNativeTerminal(call, result)
                    "prepareTermuxLiveWrapper" -> specialPermissionManager!!
                        .prepareTermuxLiveWrapper(call, result)
                    "getEmbeddedTerminalInitSnapshot" -> specialPermissionManager!!
                        .getEmbeddedTerminalInitSnapshot(result)
                    "cancelEmbeddedTerminalInit" -> specialPermissionManager!!
                        .cancelEmbeddedTerminalInit(result)
                    "isUnknownAppInstallAllowed" -> specialPermissionManager!!
                        .isUnknownAppInstallAllowed(result)
                    "openUnknownAppInstallSettings" -> specialPermissionManager!!
                        .openUnknownAppInstallSettings(result)
                    "downloadAndInstallTermuxApk" -> specialPermissionManager!!
                        .downloadAndInstallTermuxApk(call, result)
                    "requestPermissions"-> specialPermissionManager!!.requestPermissions(
                        call, result
                    )

                    else -> {
                        result.notImplemented()
                    }
                }
            }
    }

    fun clear() {
        specialPermissionManager?.closeRootSettings()
        personalContextActions?.close()
        personalContextActions = null
        embeddedTerminalInitListener?.let {
            EmbeddedTerminalInitCoordinator.removeListener(it)
        }
        embeddedTerminalInitListener = null
        eventSink = null
        eventChannel?.setStreamHandler(null)
        eventChannel = null
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
    }
}

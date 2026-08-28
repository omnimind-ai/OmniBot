package cn.com.omnimind.androidgui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.baselib.runlog.Action
import cn.com.omnimind.baselib.runlog.ActionCoordinateCodec
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.baselib.runlog.State
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

data class AndroidGuiActionResult(
    val success: Boolean,
    val message: String,
    val diagnostics: Map<String, String> = emptyMap(),
)

data class AndroidGuiScreenSnapshot(
    val packageName: String,
    val activityName: String,
    val displayWidth: Int,
    val displayHeight: Int,
    val screenshotJpeg: ByteArray?,
)

data class AndroidGuiObservation(
    val state: State,
    val stabilization: Map<String, Any?>,
)

/** Raised when a physical-display GUI task can no longer safely continue. */
class AndroidGuiDisplayOffException : IllegalStateException("android_gui_display_off")

enum class AndroidGuiAccessibilityStatus {
    DISABLED,
    CONNECTING,
    READY,
}

class AndroidGuiEnvironment internal constructor(
    private val appContext: Context?,
    private val platform: AndroidGuiPlatform,
) {
    constructor(context: Context) : this(
        appContext = context.applicationContext ?: context,
        platform = AccessibilityAndroidGuiPlatform(context.applicationContext ?: context),
    )

    fun accessibilityStatus(): AndroidGuiAccessibilityStatus = when {
        !platform.isAccessibilityEnabled() -> AndroidGuiAccessibilityStatus.DISABLED
        platform.isReady() -> AndroidGuiAccessibilityStatus.READY
        else -> AndroidGuiAccessibilityStatus.CONNECTING
    }

    fun isAccessibilityEnabled(): Boolean =
        accessibilityStatus() != AndroidGuiAccessibilityStatus.DISABLED

    fun isReady(): Boolean = accessibilityStatus() == AndroidGuiAccessibilityStatus.READY

    suspend fun awaitReady(timeoutMs: Long = ACCESSIBILITY_READY_TIMEOUT_MS): Boolean {
        ensureDisplayInteractive()
        if (!isAccessibilityEnabled()) return false
        return withTimeoutOrNull(timeoutMs) {
            while (!platform.isReady()) delay(50L)
            true
        } ?: false
    }

    fun openAccessibilitySettings() {
        val context = checkNotNull(appContext) { "android_gui_context_required" }
        val component = ComponentName(context, AssistsService::class.java)
        val detailsIntent = Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
            .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallbackIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val detailsActivity = detailsIntent.resolveActivityInfo(context.packageManager, 0)
        val detailsPermissionGranted = detailsActivity?.permission
            ?.let { permission ->
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }
            ?: true
        val openedDetails = detailsActivity != null && detailsPermissionGranted &&
            runCatching { context.startActivity(detailsIntent) }.isSuccess
        if (!openedDetails) {
            context.startActivity(fallbackIntent)
        }
    }

    fun displaySize(): Pair<Int, Int> = platform.displaySize()

    fun screenshotExcludesOverlays(): Boolean = platform.screenshotExcludesOverlays()

    suspend fun observe(
        captureScreenshot: Boolean = true,
        waitToStabilize: Boolean = false,
    ): State = observeWithDiagnostics(
        captureScreenshot = captureScreenshot,
        waitToStabilize = waitToStabilize,
    ).state

    suspend fun observeWithDiagnostics(
        captureScreenshot: Boolean = true,
        waitToStabilize: Boolean = false,
    ): AndroidGuiObservation {
        check(awaitReady()) { "android_gui_accessibility_not_ready" }
        val context = checkNotNull(appContext) { "android_gui_context_required" }
        val observedAndStability = if (waitToStabilize) {
            observeStable(captureScreenshot)
        } else {
            platform.observe(captureScreenshot) to false
        }
        val observed = observedAndStability.first
        val stabilized = observedAndStability.second
        val state = State.create(
            packageName = observed.packageName,
            activityName = observed.activityName,
            displayWidth = observed.displayWidth,
            displayHeight = observed.displayHeight,
            xml = observed.xml,
        )
        val persisted = InternalRunLogStore.persistState(context, state, observed.screenshotJpeg)
        return AndroidGuiObservation(
            state = persisted,
            stabilization = linkedMapOf<String, Any?>(
                "wait_requested" to waitToStabilize,
                "stabilized" to if (waitToStabilize) stabilized else null,
                "result" to when {
                    !waitToStabilize -> "not_requested"
                    stabilized -> "stable"
                    else -> "timeout"
                },
            ).filterValues { it != null },
        )
    }

    private suspend fun observeStable(
        captureScreenshot: Boolean,
    ): Pair<AndroidGuiPlatformState, Boolean> {
        var previous = platform.observe(captureScreenshot)
        var current = previous
        var stableChecks = 1
        val deadline = SystemClock.elapsedRealtime() + OBSERVE_STABILIZATION_TIMEOUT_MS
        while (stableChecks < OBSERVE_STABILIZATION_THRESHOLD) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) break
            delay(min(OBSERVE_STABILIZATION_POLL_MS, remaining))
            if (SystemClock.elapsedRealtime() >= deadline) break
            current = platform.observe(captureScreenshot)
            stableChecks = if (sameUiState(previous, current)) {
                stableChecks + 1
            } else {
                1
            }
            previous = current
        }
        return current to (stableChecks >= OBSERVE_STABILIZATION_THRESHOLD)
    }

    private fun sameUiState(
        previous: AndroidGuiPlatformState,
        current: AndroidGuiPlatformState,
    ): Boolean = previous.packageName == current.packageName &&
        previous.activityName == current.activityName &&
        previous.displayWidth == current.displayWidth &&
        previous.displayHeight == current.displayHeight &&
        previous.xml == current.xml

    /** Capture a transient preview without writing a RunLog state or image to disk. */
    suspend fun captureScreenSnapshot(): AndroidGuiScreenSnapshot {
        check(awaitReady()) { "android_gui_accessibility_not_ready" }
        val observed = platform.observe(captureScreenshot = true)
        return AndroidGuiScreenSnapshot(
            packageName = observed.packageName,
            activityName = observed.activityName,
            displayWidth = observed.displayWidth,
            displayHeight = observed.displayHeight,
            screenshotJpeg = observed.screenshotJpeg,
        )
    }

    suspend fun act(
        action: Action,
        awaitStabilization: Boolean = true,
    ): AndroidGuiActionResult {
        if (!awaitReady()) {
            return AndroidGuiActionResult(
                success = false,
                message = "android_gui_accessibility_not_ready",
            )
        }
        return try {
            val stateBeforeAction = if (
                awaitStabilization && action.tool != OobActionSchema.TOOL_WAIT
            ) {
                platform.observe(captureScreenshot = false).uiFingerprint()
            } else {
                null
            }
            val result = platform.dispatch(canonicalForDisplay(action))
            if (!result.success) return result
            if (!awaitStabilization) {
                return result.withStabilization("runtime_delegated")
            }
            if (action.tool == OobActionSchema.TOOL_WAIT) {
                return result.withStabilization("completed_by_action")
            }
            val stable = withTimeoutOrNull(stateStabilizationTimeoutMs(action.tool)) {
                var previous = stateBeforeAction
                var stateChanged = false
                while (true) {
                    delay(STATE_STABILIZATION_POLL_MS)
                    val fingerprint = platform.observe(
                        captureScreenshot = false,
                    ).uiFingerprint()
                    if (!stateChanged) {
                        if (fingerprint != stateBeforeAction) {
                            stateChanged = true
                        }
                    } else if (fingerprint == previous) {
                        return@withTimeoutOrNull true
                    }
                    previous = fingerprint
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } == true
            result.copy(
                diagnostics = result.diagnostics + mapOf(
                    "state_stabilization" to if (stable) {
                        "host_completed"
                    } else {
                        "runtime_delegated"
                    },
                    "state_stabilization_result" to if (stable) "stable" else "timeout",
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AndroidGuiActionResult(
                success = false,
                message = error.message ?: "android_gui_action_failed",
            )
        }
    }

    private fun AndroidGuiPlatformState.uiFingerprint(): String = buildString {
        append(packageName)
        append('\u0000')
        append(activityName)
        append('\u0000')
        append(xml)
    }

    private fun AndroidGuiActionResult.withStabilization(result: String) = copy(
        diagnostics = diagnostics + mapOf(
            "state_stabilization" to "host_completed",
            "state_stabilization_result" to result,
        ),
    )

    suspend fun inputTarget(x: Float? = null, y: Float? = null): AndroidGuiInputTarget? =
        if (awaitReady()) platform.inputTarget(x, y) else null

    suspend fun installedApplications(): Map<String, String> = platform.installedApplications()

    fun inputMethodTop(): Int? = runCatching(platform::inputMethodTop).getOrNull()

    private suspend fun canonicalForDisplay(action: Action): Action {
        if (action.tool !in OobActionSchema.coordinateToolNames) return action
        val display = platform.displaySize()
        val args = ActionCoordinateCodec.toScreenPixels(
            args = action.args,
            displaySize = ActionCoordinateCodec.DisplaySize(
                width = display.first.toDouble(),
                height = display.second.toDouble(),
            ),
        )
        return action.copy(args = args)
    }

    private fun ensureDisplayInteractive() {
        val context = appContext ?: return
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null && !powerManager.isInteractive) {
            throw AndroidGuiDisplayOffException()
        }
    }
}

private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
    "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
internal const val ACCESSIBILITY_READY_TIMEOUT_MS = 15_000L
private const val STATE_STABILIZATION_POLL_MS = 100L
private const val STATE_STABILIZATION_TIMEOUT_MS = 1_500L
private const val OBSERVE_STABILIZATION_THRESHOLD = 3
private const val OBSERVE_STABILIZATION_POLL_MS = 500L
private const val OBSERVE_STABILIZATION_TIMEOUT_MS = 6_000L
private const val OPEN_APP_STABILIZATION_TIMEOUT_MS = 5_000L

internal fun stateStabilizationTimeoutMs(tool: String): Long =
    if (tool == OobActionSchema.TOOL_OPEN_APP) {
        OPEN_APP_STABILIZATION_TIMEOUT_MS
    } else {
        STATE_STABILIZATION_TIMEOUT_MS
    }

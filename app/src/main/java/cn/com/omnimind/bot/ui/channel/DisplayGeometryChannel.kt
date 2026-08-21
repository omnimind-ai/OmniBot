package cn.com.omnimind.bot.ui.channel

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.RoundedCorner
import android.view.WindowInsets
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.lang.ref.WeakReference

internal data class ScreenCornerRadiiPx(
    val topLeft: Int,
    val topRight: Int,
    val bottomLeft: Int,
    val bottomRight: Int,
) {
    fun toLogicalPixels(density: Float): Map<String, Double> {
        val safeDensity = density.takeIf { it > 0f } ?: 1f
        return mapOf(
            "topLeft" to topLeft.coerceAtLeast(0) / safeDensity.toDouble(),
            "topRight" to topRight.coerceAtLeast(0) / safeDensity.toDouble(),
            "bottomLeft" to bottomLeft.coerceAtLeast(0) / safeDensity.toDouble(),
            "bottomRight" to bottomRight.coerceAtLeast(0) / safeDensity.toDouble(),
        )
    }

    companion object {
        val Zero = ScreenCornerRadiiPx(0, 0, 0, 0)
    }
}

/** Supplies window geometry that Flutter cannot query directly. */
class DisplayGeometryChannel {
    private var activityRef: WeakReference<Activity>? = null
    private var channel: MethodChannel? = null

    fun onCreate(context: Context) {
        activityRef = WeakReference(context as? Activity)
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        channel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL_NAME,
        ).also { methodChannel ->
            methodChannel.setMethodCallHandler(::handleMethodCall)
        }
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            METHOD_SCREEN_CORNER_RADII -> result.success(readScreenCornerRadii())
            else -> result.notImplemented()
        }
    }

    private fun readScreenCornerRadii(): Map<String, Double> {
        val activity = activityRef?.get() ?: return ScreenCornerRadiiPx.Zero.toLogicalPixels(1f)
        val density = activity.resources.displayMetrics.density
        if (activity.isInMultiWindowMode) {
            return ScreenCornerRadiiPx.Zero.toLogicalPixels(density)
        }

        val insets = activity.window.decorView.rootWindowInsets
        val fallbackTop = frameworkCornerRadius(activity, "rounded_corner_radius_top")
            .takeIf { it > 0 }
            ?: frameworkCornerRadius(activity, "rounded_corner_radius")
        val fallbackBottom = frameworkCornerRadius(activity, "rounded_corner_radius_bottom")
            .takeIf { it > 0 }
            ?: frameworkCornerRadius(activity, "rounded_corner_radius")

        val radii = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ScreenCornerRadiiPx(
                topLeft = insets.radiusAt(RoundedCorner.POSITION_TOP_LEFT) ?: fallbackTop,
                topRight = insets.radiusAt(RoundedCorner.POSITION_TOP_RIGHT) ?: fallbackTop,
                bottomLeft = insets.radiusAt(RoundedCorner.POSITION_BOTTOM_LEFT) ?: fallbackBottom,
                bottomRight = insets.radiusAt(RoundedCorner.POSITION_BOTTOM_RIGHT) ?: fallbackBottom,
            )
        } else {
            ScreenCornerRadiiPx(
                topLeft = fallbackTop,
                topRight = fallbackTop,
                bottomLeft = fallbackBottom,
                bottomRight = fallbackBottom,
            )
        }
        return radii.toLogicalPixels(density)
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
        activityRef?.clear()
        activityRef = null
    }

    companion object {
        private const val CHANNEL_NAME = "cn.com.omnimind.bot/DisplayGeometry"
        private const val METHOD_SCREEN_CORNER_RADII = "getScreenCornerRadii"
    }
}

@SuppressLint("DiscouragedApi")
private fun frameworkCornerRadius(context: Context, name: String): Int {
    val id = context.resources.getIdentifier(name, "dimen", "android")
    return if (id == 0) 0 else context.resources.getDimensionPixelSize(id)
}

private fun WindowInsets?.radiusAt(position: Int): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    return this?.getRoundedCorner(position)?.radius?.takeIf { it > 0 }
}

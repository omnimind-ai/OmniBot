package cn.com.omnimind.bot.util

import android.content.Context
import android.provider.Settings
import cn.com.omnimind.androidgui.AndroidGuiEnvironment
import cn.com.omnimind.baselib.util.exception.PermissionException

internal data class AndroidAutomationPermissionCheck(
    val missingIds: List<String>,
    val context: Context,
) {
    val granted: Boolean
        get() = missingIds.isEmpty()

    fun getDisplayNames(): List<String> = missingIds.map { id ->
        when (id) {
            AndroidAutomationPermissionGate.ACCESSIBILITY -> context.getString(cn.com.omnimind.bot.R.string.permission_accessibility)
            AndroidAutomationPermissionGate.OVERLAY -> context.getString(cn.com.omnimind.bot.R.string.permission_overlay)
            else -> id
        }
    }

    val errorCode: String
        get() = if (AndroidAutomationPermissionGate.ACCESSIBILITY in missingIds) {
            "OOB_ACCESSIBILITY_REQUIRED"
        } else {
            "OOB_PERMISSION_REQUIRED"
        }

    fun getMessage(): String = when {
        AndroidAutomationPermissionGate.ACCESSIBILITY in missingIds &&
            AndroidAutomationPermissionGate.OVERLAY in missingIds ->
            context.getString(cn.com.omnimind.bot.R.string.permission_automation_both_required)
        AndroidAutomationPermissionGate.ACCESSIBILITY in missingIds ->
            context.getString(cn.com.omnimind.bot.R.string.permission_automation_accessibility_required)
        AndroidAutomationPermissionGate.OVERLAY in missingIds ->
            context.getString(cn.com.omnimind.bot.R.string.permission_automation_overlay_required)
        else -> ""
    }

    fun requireGranted() {
        if (!granted) throw PermissionException(getMessage())
    }
}

internal object AndroidAutomationPermissionGate {
    const val ACCESSIBILITY = "accessibility"
    const val OVERLAY = "overlay"

    fun check(context: Context): AndroidAutomationPermissionCheck = evaluate(
        context = context,
        accessibilityEnabled = AndroidGuiEnvironment(context).isAccessibilityEnabled(),
        overlayEnabled = Settings.canDrawOverlays(context),
    )

    internal fun evaluate(
        context: Context,
        accessibilityEnabled: Boolean,
        overlayEnabled: Boolean,
    ): AndroidAutomationPermissionCheck = AndroidAutomationPermissionCheck(
        missingIds = buildList {
            if (!accessibilityEnabled) add(ACCESSIBILITY)
            if (!overlayEnabled) add(OVERLAY)
        },
        context = context,
    )
}

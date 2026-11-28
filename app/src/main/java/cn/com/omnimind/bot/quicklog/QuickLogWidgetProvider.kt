package cn.com.omnimind.bot.quicklog

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.activity.AppIconManager
import cn.com.omnimind.bot.activity.QuickLogEntryActivity
import cn.com.omnimind.bot.activity.QuickLogWidgetSettingsActivity

class QuickLogWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateWidgets(context, appWidgetManager, appWidgetIds)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        QuickLogWidgetUpdater.updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE -> {
                OmniLog.d(TAG, "Received APPWIDGET_UPDATE")
                QuickLogWidgetUpdater.updateAll(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                OmniLog.d(TAG, "Received MY_PACKAGE_REPLACED")
                QuickLogWidgetUpdater.updateAll(context)
            }
            ACTION_ADD_LOG -> {
                OmniLog.d(TAG, "Received widget add action")
                context.startActivity(
                    Intent(context, QuickLogEntryActivity::class.java).apply {
                        action = ACTION_ADD_LOG
                        putExtra(
                            EXTRA_LIST_ID,
                            QuickLogService.LIST_TASKS
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
            }
            ACTION_EDIT_LOG -> {
                val logId = intent.getStringExtra(EXTRA_LOG_ID)?.trim().orEmpty()
                OmniLog.d(TAG, "Received widget edit action for logId=$logId")
                context.startActivity(
                    Intent(context, QuickLogEntryActivity::class.java).apply {
                        action = "$ACTION_EDIT_LOG.$logId"
                        putExtra(QuickLogEntryActivity.EXTRA_LOG_ID, logId)
                        putExtra(
                            QuickLogEntryActivity.EXTRA_LOG_CONTENT,
                            intent.getStringExtra(EXTRA_LOG_CONTENT)
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
            }
            ACTION_OPEN_SHORT_MEMORIES -> {
                OmniLog.d(TAG, "Received widget open memories action")
                context.startActivity(
                    AppIconManager.createLauncherIntent(context).apply {
                        action = ACTION_OPEN_SHORT_MEMORIES
                        putExtra("route", "/memory/memory_center_page")
                        putExtra("needClear", false)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
            }
            ACTION_DELETE_LOG -> {
                val logId = intent.getStringExtra(EXTRA_LOG_ID)?.trim().orEmpty()
                if (logId.isNotEmpty()) {
                    OmniLog.d(TAG, "Received widget delete action for logId=$logId")
                    runCatching {
                        QuickLogService(context).deleteLog(logId)
                    }
                }
            }
            ACTION_OPEN_SETTINGS -> {
                OmniLog.d(TAG, "Received widget settings action")
                context.startActivity(
                    Intent(context, QuickLogWidgetSettingsActivity::class.java).apply {
                        action = ACTION_OPEN_SETTINGS
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
            }
        }
    }

    companion object {
        private const val TAG = "QuickLogWidget"
        const val ACTION_OPEN_SHORT_MEMORIES =
            "cn.com.omnimind.bot.quicklog.action.OPEN_SHORT_MEMORIES"
        const val ACTION_ADD_LOG = "cn.com.omnimind.bot.quicklog.action.ADD_LOG"
        const val ACTION_EDIT_LOG = "cn.com.omnimind.bot.quicklog.action.EDIT_LOG"
        const val ACTION_DELETE_LOG = "cn.com.omnimind.bot.quicklog.action.DELETE_LOG"
        const val ACTION_OPEN_SETTINGS = "cn.com.omnimind.bot.quicklog.action.OPEN_SETTINGS"
        const val EXTRA_LOG_ID = "extra_quick_log_id"
        const val EXTRA_LOG_CONTENT = "extra_quick_log_content"
        const val EXTRA_APP_WIDGET_ID = "extra_app_widget_id"
        const val EXTRA_LIST_ID = "extra_quick_log_list_id"

        fun updateWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager = AppWidgetManager.getInstance(context),
            appWidgetIds: IntArray = appWidgetManager.getAppWidgetIds(
                ComponentName(context, QuickLogWidgetProvider::class.java)
            )
        ) {
            val service = QuickLogService(context)
            appWidgetIds.forEach { appWidgetId ->
                appWidgetManager.updateAppWidget(
                    appWidgetId,
                    buildRemoteViews(context, service, appWidgetId)
                )
            }
            if (appWidgetIds.isNotEmpty()) {
                appWidgetManager.notifyAppWidgetViewDataChanged(
                    appWidgetIds,
                    R.id.quick_log_widget_list
                )
            }
        }

        private fun buildRemoteViews(
            context: Context,
            service: QuickLogService,
            appWidgetId: Int
        ): RemoteViews {
            val localizedContext = AppLocaleManager.localizedContext(context)
            val views = RemoteViews(context.packageName, R.layout.widget_quick_log)
            val settings = service.getWidgetSettings()
            val addPendingIntent = buildQuickAddPendingIntent(context, QuickLogService.LIST_TASKS)
            val openPendingIntent = buildOpenShortMemoriesPendingIntent(context)
            val settingsPendingIntent = buildSettingsPendingIntent(context)

            bindStaticTexts(views, localizedContext)
            bindTheme(views, settings)

            views.setOnClickPendingIntent(R.id.quick_log_widget_title_action, openPendingIntent)
            views.setOnClickPendingIntent(R.id.quick_log_widget_add_action, addPendingIntent)
            views.setOnClickPendingIntent(R.id.quick_log_widget_settings_action, settingsPendingIntent)
            views.setOnClickPendingIntent(R.id.quick_log_widget_open_action, openPendingIntent)
            views.setOnClickPendingIntent(R.id.quick_log_widget_empty_state, addPendingIntent)
            views.setOnClickPendingIntent(R.id.quick_log_widget_empty_text, addPendingIntent)

            views.setTextViewText(R.id.quick_log_widget_summary, "")
            views.setViewVisibility(R.id.quick_log_widget_summary, View.GONE)

            val adapterIntent = Intent(context, QuickLogWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(EXTRA_APP_WIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.quick_log_widget_list, adapterIntent)
            views.setEmptyView(R.id.quick_log_widget_list, R.id.quick_log_widget_empty_state)
            views.setPendingIntentTemplate(
                R.id.quick_log_widget_list,
                buildCollectionItemTemplatePendingIntent(context)
            )

            return views
        }

        private fun bindStaticTexts(
            views: RemoteViews,
            context: Context
        ) {
            views.setTextViewText(
                R.id.quick_log_widget_title,
                context.getString(R.string.quick_log_widget_title)
            )
            views.setTextViewText(
                R.id.quick_log_widget_empty_text,
                context.getString(R.string.quick_log_widget_empty_hint)
            )
            views.setTextViewText(
                R.id.quick_log_widget_open_action,
                context.getString(R.string.quick_log_widget_open)
            )
        }

        private fun bindTheme(views: RemoteViews, settings: QuickLogWidgetSettings) {
            val background = when (settings.colorTheme) {
                QuickLogService.COLOR_LIGHT -> R.drawable.quick_log_widget_card_light
                QuickLogService.COLOR_BLUE -> R.drawable.quick_log_widget_card_blue
                QuickLogService.COLOR_PINK -> R.drawable.quick_log_widget_card_pink
                else -> when {
                    settings.opacityPercent >= 90 -> R.drawable.quick_log_widget_card_dark_92
                    settings.opacityPercent >= 75 -> R.drawable.quick_log_widget_card_dark_78
                    else -> R.drawable.quick_log_widget_card_dark_62
                }
            }
            val isLightSurface = settings.colorTheme == QuickLogService.COLOR_LIGHT ||
                settings.colorTheme == QuickLogService.COLOR_BLUE ||
                settings.colorTheme == QuickLogService.COLOR_PINK
            val primaryText = if (isLightSurface) {
                0xFF1F2937.toInt()
            } else {
                0xFFF8FAFC.toInt()
            }
            val secondaryText = if (isLightSurface) {
                0xFF64748B.toInt()
            } else {
                0xFFB9C2D0.toInt()
            }
            val accentText = if (isLightSurface) {
                0xFF2563EB.toInt()
            } else {
                0xFF6AE7C8.toInt()
            }
            views.setInt(R.id.quick_log_widget_root, "setBackgroundResource", background)
            views.setInt(R.id.quick_log_widget_logo, "setColorFilter", accentText)
            views.setTextColor(R.id.quick_log_widget_title, primaryText)
            views.setInt(R.id.quick_log_widget_add_action, "setColorFilter", accentText)
            views.setInt(R.id.quick_log_widget_settings_action, "setColorFilter", accentText)
            views.setTextColor(R.id.quick_log_widget_summary, secondaryText)
            views.setTextColor(R.id.quick_log_widget_open_action, secondaryText)
        }

        private fun buildCollectionItemTemplatePendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, QuickLogWidgetProvider::class.java)
            return PendingIntent.getBroadcast(
                context,
                3101,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        }

        private fun buildOpenShortMemoriesPendingIntent(context: Context): PendingIntent {
            val intent = AppIconManager.createLauncherIntent(context).apply {
                action = ACTION_OPEN_SHORT_MEMORIES
                putExtra("route", "/memory/memory_center_page")
                putExtra("needClear", false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                3001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun buildSettingsPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, QuickLogWidgetSettingsActivity::class.java).apply {
                action = ACTION_OPEN_SETTINGS
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                3002,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun buildQuickAddPendingIntent(context: Context, listId: String): PendingIntent {
            val intent = Intent(context, QuickLogEntryActivity::class.java).apply {
                action = ACTION_ADD_LOG
                putExtra(EXTRA_LIST_ID, listId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                3102,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}

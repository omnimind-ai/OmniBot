package cn.com.omnimind.bot.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentAlarmToolService

/**
 * Native adapter over the existing alarm-sound owner. `AgentAlarmToolService`
 * keeps the MMKV settings and validation; `AgentAlarmRingingService` plays the
 * selection through `MediaPlayer.setDataSource(context, uri)`, which accepts
 * content URIs, so a picked document is stored as its content URI with a
 * persisted read grant instead of copying bytes into app storage.
 */
internal class NativeAlarmSettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val service = AgentAlarmToolService(appContext)

    fun read(): AlarmSoundSnapshot {
        val settings = service.getAlarmSoundSettings()
        return AlarmSoundSnapshot(
            source = settings.source,
            localValue = settings.localPath,
            localLabel = resolveLocalLabel(settings.localPath),
            remoteUrl = settings.remoteUrl,
        )
    }

    /** Validation stays in the service; invalid input throws IllegalArgumentException. */
    fun save(source: String, localPath: String?, remoteUrl: String?) {
        service.saveAlarmSettings(source = source, localPath = localPath, remoteUrl = remoteUrl)
    }

    /**
     * Keeps the picked document readable for the later alarm trigger. A provider
     * that rejects the persistable grant still leaves the pick usable until the
     * process dies, and playback falls back to the default ringtone afterwards.
     */
    fun persistLocalSelection(uri: Uri): String {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure {
            OmniLog.w("NativeAlarmSettings", "Persistable audio grant unavailable; grant lasts for this process")
        }
        return resolveDisplayName(uri)
    }

    private fun resolveLocalLabel(value: String): String {
        if (!value.startsWith("content://")) return value
        return runCatching { resolveDisplayName(Uri.parse(value)) }.getOrDefault(value)
    }

    private fun resolveDisplayName(uri: Uri): String = runCatching {
        appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf(String::isNotBlank) else null
            }
    }.getOrNull() ?: uri.lastPathSegment ?: uri.toString()
}

internal data class AlarmSoundSnapshot(
    val source: String,
    val localValue: String,
    val localLabel: String,
    val remoteUrl: String,
)

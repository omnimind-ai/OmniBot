package cn.com.omnimind.bot.jackery

import android.content.Context
import cn.com.omnimind.assists.HumanTrajectoryLearningSession
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.util.RuntimeLogStore
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.agent.AgentAlarmToolService
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.WorkspaceMemoryRollupScheduler
import cn.com.omnimind.bot.agent.WorkspaceMemoryService
import cn.com.omnimind.bot.agent.WorkspaceScheduledTaskScheduler
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import cn.com.omnimind.bot.share.SharedOpenDraftStore
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

internal class JackeryDemoResetFailure(val contentChanged: Boolean, cause: Exception) :
    IllegalStateException(
        if (contentChanged) "Some demo data has been cleared. Please try again or ask exhibition staff for help."
        else "Unable to reset the demo. Please try again or ask exhibition staff for help.",
        cause,
    )

/** Only demo content is erased. Staff auth, hardware connection and assets survive. */
internal object JackeryDemoReset {
    suspend fun reset(context: Context, flushFlutterPersistence: suspend () -> Unit) {
        check(BuildConfig.APP_EDITION == "jackeryCes") { "Demo reset is only available in the CES edition." }
        var contentChanged = false
        try {
            AgentRuntimeManager.getInstance(context).withExclusiveDemoReset {
                // Drain Dart persistence while shared ACP admission is blocked.
                flushFlutterPersistence()
                AgentRuntimeManager.getInstance(context).drainDemoHistoryMutations()
                withContext(Dispatchers.IO) {
                    // Validate staff configuration before erasing demo content;
                    // reinstall after deletion to restore the identity files.
                    JackeryCesProvisioning.install(context)
                    WorkspaceMemoryRollupScheduler(context).setEnabled(false)
                    contentChanged = true
                    WorkspaceScheduledTaskScheduler(context).clearAllForDemoReset()
                    val alarms = AgentAlarmToolService(context)
                    alarms.listExactReminders().forEach { alarm ->
                        val id = requireNotNull(alarm["alarmId"]?.toString())
                        check(alarms.closeExactReminder(id)) { "Unable to clear a demo alarm" }
                    }
                    HumanTrajectoryLearningSession.activeRunId()?.let { id ->
                        check(HumanTrajectoryLearningSession.cancelActive(id, "Demo reset")) { "A trajectory is still finishing. Try reset again." }
                    }
                    WorkspaceMemoryService(context).clearAllForDemoReset()
                    // One transaction removes orphan items and bindings too.
                    DatabaseHelper.clearDemoConversationContent()
                    resetWorkspace(context)
                    deleteOwnedTree(File(context.filesDir, "run_logs"))
                    RuntimeLogStore.clear()
                    SharedOpenDraftStore.clearPending(context)
                    MMKV.defaultMMKV()?.removeValuesForKeys(arrayOf(
                        "quick_logs_records_v1", "quick_logs_widget_settings_v1",
                        "agent_alarm_sound_settings_v1", "agent_exact_alarm_records_v2",
                        "workspace_memory_rollup_last_run_at_v1", "workspace_memory_rollup_last_summary_v1",
                    ))
                    resetConversationPreferences(context)
                    // Restore fixed model, English, light appearance and mode.
                    JackeryCesProvisioning.install(context)
                }
            }
        } catch (error: Exception) {
            throw JackeryDemoResetFailure(contentChanged, error)
        }
    }

    private fun resetWorkspace(context: Context) {
        val workspace = AgentWorkspaceManager(context)
        workspace.ensureRuntimeDirectories()
        val root = AgentWorkspaceManager.rootDirectory(context)
        root.listFiles().orEmpty().filter { it.name != ".omnibot" }.forEach(::deleteOwnedTree)
        val internal = AgentWorkspaceManager.internalRootDirectory(context)
        listOf("attachments", "shared", "offloads", "browser", "audio").forEach { name ->
            deleteOwnedTree(File(internal, name))
        }
        // Installed tools, skills and shipped pet resources are application
        // infrastructure; product artwork lives outside the workspace.
        workspace.ensureRuntimeDirectories()
    }

    internal fun deleteOwnedTree(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            Files.delete(file.toPath())
            return
        }
        if (!file.exists()) return
        if (file.isDirectory) file.listFiles()?.forEach(::deleteOwnedTree)
            ?: error("Unable to read demo directory")
        check(file.delete()) { "Unable to remove demo file: ${file.name}" }
    }

    private fun resetConversationPreferences(context: Context) {
        val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        prefs.all.keys.filter { key ->
            key.startsWith("flutter.current_conversation_") ||
                key.startsWith("flutter.hidden_") ||
                key in setOf("flutter.last_visible_conversation_target", "flutter.scheduled_tasks", "flutter.manual_model_context_thresholds")
        }.forEach(editor::remove)
        check(editor.commit()) { "Unable to restore demo preferences" }
        val profiles = context.getSharedPreferences("acp_agent_profiles", Context.MODE_PRIVATE)
        check(profiles.edit().remove("session_bindings").remove("conversation_bindings").commit()) {
            "Unable to clear demo session selection"
        }
    }
}

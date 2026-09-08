package cn.com.omnimind.bot.activity

import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.quicklog.QuickLogRecord
import cn.com.omnimind.bot.quicklog.QuickLogService
import cn.com.omnimind.bot.quicklog.QuickLogWidgetSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object QuickLogEditorScreen {
    fun bind(activity: ComponentActivity, sourceIntent: Intent) {
        val quickLogService = QuickLogService(activity)
        val logId = sourceIntent.getStringExtra(QuickLogEntryActivity.EXTRA_LOG_ID)?.trim().orEmpty()
        val isEditMode = logId.isNotEmpty()
        val existingLog = if (isEditMode) quickLogService.getLog(logId) else null

        if (isEditMode && existingLog == null) {
            Toast.makeText(
                activity,
                activity.getString(R.string.quick_log_not_found),
                Toast.LENGTH_SHORT
            ).show()
            activity.finish()
            return
        }

        activity.setContent {
            MaterialTheme {
                QuickLogEditorContent(
                    activity = activity,
                    quickLogService = quickLogService,
                    logId = logId,
                    isEditMode = isEditMode,
                    existingLog = existingLog,
                    initialContent = sourceIntent
                        .getStringExtra(QuickLogEntryActivity.EXTRA_LOG_CONTENT)
                        .orEmpty()
                )
            }
        }
    }

    @Composable
    private fun QuickLogEditorContent(
        activity: ComponentActivity,
        quickLogService: QuickLogService,
        logId: String,
        isEditMode: Boolean,
        existingLog: QuickLogRecord?,
        initialContent: String
    ) {
        var content by remember {
            val initialText = initialContent.ifBlank { existingLog?.content.orEmpty() }
            mutableStateOf(
                TextFieldValue(
                    text = initialText,
                    selection = TextRange(initialText.length)
                )
            )
        }
        val context = LocalContext.current
        val resources = LocalResources.current
        val widgetSettings = remember { quickLogService.getWidgetSettings() }
        val editorColors = remember(widgetSettings) { QuickLogEditorColors.from(widgetSettings) }
        val focusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current
        val coroutineScope = rememberCoroutineScope()

        fun keepKeyboardOpen() {
            focusRequester.requestFocus()
            keyboardController?.show()
        }

        LaunchedEffect(Unit) {
            delay(250)
            keepKeyboardOpen()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { activity.finish() },
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {}
                    .imePadding()
                    .navigationBarsPadding(),
                color = editorColors.surface,
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = resources.getString(
                                if (isEditMode) {
                                    R.string.quick_log_edit_task
                                } else {
                                    R.string.quick_log_add_task
                                }
                            ),
                            color = editorColors.primaryText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { activity.finish() }) {
                            Text(
                                resources.getString(R.string.quick_log_cancel),
                                color = editorColors.secondaryText,
                                fontSize = 14.sp
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.height(48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = content,
                            onValueChange = { content = it },
                            textStyle = TextStyle(
                                color = editorColors.primaryText,
                                fontSize = 16.sp,
                                lineHeight = 20.sp
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .focusRequester(focusRequester)
                                .background(editorColors.inputBackground, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            decorationBox = { innerTextField ->
                                if (content.text.isBlank()) {
                                    Text(
                                        text = resources.getString(R.string.quick_log_entry_hint),
                                        color = editorColors.placeholderText,
                                        fontSize = 16.sp,
                                        lineHeight = 20.sp
                                    )
                                }
                                innerTextField()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            modifier = Modifier.size(40.dp),
                            onClick = {
                                coroutineScope.launch {
                                    val normalized = content.text.trim()
                                    if (normalized.isBlank()) {
                                        Toast.makeText(
                                            context,
                                            resources.getString(R.string.quick_log_content_required),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@launch
                                    }
                                    val savedRecord = runCatching {
                                        if (isEditMode) {
                                            quickLogService.updateLog(
                                                id = logId,
                                                content = normalized,
                                                listId = QuickLogService.LIST_TASKS,
                                                isImportant = false,
                                                dueAtMillis = null,
                                                reminderAtMillis = null,
                                                repeatRule = null,
                                                updateTaskMetadata = true
                                            )
                                        } else {
                                            quickLogService.addLog(
                                                content = normalized,
                                                source = QuickLogService.SOURCE_WIDGET,
                                                listId = QuickLogService.LIST_TASKS,
                                                isImportant = false
                                            )
                                        }
                                    }.onFailure {
                                        Toast.makeText(
                                            context,
                                            resources.getString(
                                                if (isEditMode) {
                                                    R.string.quick_log_update_failed
                                                } else {
                                                    R.string.quick_log_save_failed
                                                }
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }.getOrNull()
                                    if (savedRecord == null) {
                                        return@launch
                                    }
                                    Toast.makeText(
                                        context,
                                        resources.getString(
                                            if (isEditMode) {
                                                R.string.quick_log_update_success
                                            } else {
                                                R.string.quick_log_save_success
                                            }
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    activity.finish()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = editorColors.accent),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("↑", color = editorColors.accentContent, fontSize = 20.sp)
                        }
                    }

                    if (isEditMode) {
                        TextButton(
                            onClick = {
                                runCatching { quickLogService.deleteLog(logId) }
                                    .onSuccess { deleted ->
                                        Toast.makeText(
                                            context,
                                            resources.getString(
                                                if (deleted) {
                                                    R.string.quick_log_delete_success
                                                } else {
                                                    R.string.quick_log_delete_failed
                                                }
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        if (deleted) activity.finish()
                                    }
                                    .onFailure {
                                        Toast.makeText(
                                            context,
                                            resources.getString(R.string.quick_log_delete_failed),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                            }
                        ) {
                            Text(
                                resources.getString(R.string.quick_log_delete_task),
                                color = editorColors.danger
                            )
                        }
                    }
                }
            }
        }
    }

    private data class QuickLogEditorColors(
        val surface: Color,
        val primaryText: Color,
        val secondaryText: Color,
        val placeholderText: Color,
        val inputBackground: Color,
        val accent: Color,
        val accentContent: Color,
        val danger: Color
    ) {
        companion object {
            fun from(settings: QuickLogWidgetSettings): QuickLogEditorColors {
                val isLightSurface = settings.colorTheme == QuickLogService.COLOR_LIGHT ||
                    settings.colorTheme == QuickLogService.COLOR_BLUE ||
                    settings.colorTheme == QuickLogService.COLOR_PINK
                val surface = when (settings.colorTheme) {
                    QuickLogService.COLOR_LIGHT -> Color(0xF2FFFFFF)
                    QuickLogService.COLOR_BLUE -> Color(0xF2DCEBFF)
                    QuickLogService.COLOR_PINK -> Color(0xF2FFE1ED)
                    else -> when {
                        settings.opacityPercent >= 90 -> Color(0xEA1F2327)
                        settings.opacityPercent >= 75 -> Color(0xC71F2327)
                        else -> Color(0x9E1F2327)
                    }
                }
                return if (isLightSurface) {
                    QuickLogEditorColors(
                        surface = surface,
                        primaryText = Color(0xFF1F2937),
                        secondaryText = Color(0xFF64748B),
                        placeholderText = Color(0xFF64748B),
                        inputBackground = Color(0x99FFFFFF),
                        accent = Color(0xFF2563EB),
                        accentContent = Color.White,
                        danger = Color(0xFFB83B68)
                    )
                } else {
                    QuickLogEditorColors(
                        surface = surface,
                        primaryText = Color.White,
                        secondaryText = Color(0xFFB9C2D0),
                        placeholderText = Color(0xFF7E8793),
                        inputBackground = Color(0x22111111),
                        accent = Color(0xFF6AE7C8),
                        accentContent = Color(0xFF10201D),
                        danger = Color(0xFFE05A8A)
                    )
                }
            }
        }
    }

}

package cn.com.omnimind.bot.activity

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.quicklog.QuickLogService
import cn.com.omnimind.bot.quicklog.QuickLogWidgetSettings

class QuickLogWidgetSettingsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(
            if (newBase == null) null else AppLocaleManager.localizedContext(newBase)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val service = QuickLogService(this)
        setContent {
            MaterialTheme {
                WidgetSettingsContent(
                    initialSettings = service.getWidgetSettings(),
                    onChanged = { settings ->
                        service.updateWidgetSettings { settings }
                    },
                    onClose = { finish() }
                )
            }
        }
    }

    @Composable
    private fun WidgetSettingsContent(
        initialSettings: QuickLogWidgetSettings,
        onChanged: (QuickLogWidgetSettings) -> Unit,
        onClose: () -> Unit
    ) {
        var settings by remember { mutableStateOf(initialSettings) }

        fun update(next: QuickLogWidgetSettings) {
            settings = next
            onChanged(next)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onClose() },
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {}
                    .navigationBarsPadding(),
                color = Color(0xFFF8FAFC),
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.quick_log_settings_title),
                            color = Color(0xFF111827),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onClose) {
                            Text(stringResource(R.string.quick_log_settings_done))
                        }
                    }

                    SettingLabel(
                        stringResource(R.string.quick_log_opacity),
                        "${settings.opacityPercent}%"
                    )
                    Slider(
                        value = settings.opacityPercent.toFloat(),
                        valueRange = 35f..100f,
                        onValueChange = {
                            update(settings.copy(opacityPercent = it.toInt()))
                        }
                    )

                    SettingLabel(stringResource(R.string.quick_log_widget_color), null)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ColorChoice(
                            color = Color(0xFF1F2327),
                            selected = settings.colorTheme == QuickLogService.COLOR_DARK
                        ) {
                            update(settings.copy(colorTheme = QuickLogService.COLOR_DARK))
                        }
                        ColorChoice(
                            color = Color.White,
                            selected = settings.colorTheme == QuickLogService.COLOR_LIGHT
                        ) {
                            update(settings.copy(colorTheme = QuickLogService.COLOR_LIGHT))
                        }
                        ColorChoice(
                            color = Color(0xFFDCEBFF),
                            selected = settings.colorTheme == QuickLogService.COLOR_BLUE
                        ) {
                            update(settings.copy(colorTheme = QuickLogService.COLOR_BLUE))
                        }
                        ColorChoice(
                            color = Color(0xFFFFE1ED),
                            selected = settings.colorTheme == QuickLogService.COLOR_PINK
                        ) {
                            update(settings.copy(colorTheme = QuickLogService.COLOR_PINK))
                        }
                    }

                    SettingLabel(stringResource(R.string.quick_log_font_size), null)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FontChoice(
                            stringResource(R.string.quick_log_font_small),
                            settings.fontSize == QuickLogService.FONT_SMALL
                        ) {
                            update(settings.copy(fontSize = QuickLogService.FONT_SMALL))
                        }
                        FontChoice(
                            stringResource(R.string.quick_log_font_regular),
                            settings.fontSize == QuickLogService.FONT_REGULAR
                        ) {
                            update(settings.copy(fontSize = QuickLogService.FONT_REGULAR))
                        }
                        FontChoice(
                            stringResource(R.string.quick_log_font_large),
                            settings.fontSize == QuickLogService.FONT_LARGE
                        ) {
                            update(settings.copy(fontSize = QuickLogService.FONT_LARGE))
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }

    @Composable
    private fun SettingLabel(title: String, value: String?) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = Color(0xFF111827),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (value != null) {
                Text(text = value, color = Color(0xFF6B7280), fontSize = 16.sp)
            }
        }
    }

    @Composable
    private fun ColorChoice(color: Color, selected: Boolean, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (selected) Color(0xFF0B7DE3) else Color(0xFFE5E7EB))
                .padding(4.dp)
                .clip(CircleShape)
                .background(color)
                .clickable(onClick = onClick)
        )
    }

    @Composable
    private fun FontChoice(text: String, selected: Boolean, onClick: () -> Unit) {
        Surface(
            color = if (selected) Color(0xFF0B7DE3) else Color(0xFFEFF2F7),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.clickable(onClick = onClick)
        ) {
            Text(
                text = text,
                color = if (selected) Color.White else Color(0xFF374151),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

}

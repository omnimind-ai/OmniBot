package com.rk.terminal.ui.screens.customization

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.compose.ui.res.stringResource
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.components.compose.preferences.base.PreferenceLayout
import com.rk.components.compose.preferences.base.PreferenceTemplate
import com.rk.components.compose.preferences.switch.PreferenceSwitch
import com.rk.resources.drawables
import com.rk.resources.strings
import com.rk.libcommons.child
import com.rk.libcommons.createFileIfNot
import com.rk.libcommons.dpToPx
import com.rk.settings.Settings
import com.rk.terminal.ui.components.SettingsToggle
import com.rk.terminal.ui.navHosts.horizontal_statusBar
import com.rk.terminal.ui.navHosts.showStatusBar
import com.rk.terminal.ui.screens.terminal.bitmap
import com.rk.terminal.ui.screens.terminal.darkText
import com.rk.terminal.ui.screens.terminal.setFont
import com.rk.terminal.ui.screens.terminal.showHorizontalToolbar
import com.rk.terminal.ui.screens.terminal.showToolbar
import com.rk.terminal.ui.screens.terminal.showVirtualKeys
import com.rk.terminal.ui.screens.terminal.terminalView
import com.rk.terminal.ui.screens.terminal.wallAlpha
import com.rk.terminal.ui.screens.terminal.ShortcutAction
import com.rk.terminal.ui.screens.terminal.ShortcutCaptureDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.math.RoundingMode
import java.text.DecimalFormat
import kotlin.math.roundToInt


private const val min_text_size = 10f
private const val max_text_size = 20f

@Composable
fun Customization(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    PreferenceLayout(label = stringResource(strings.customizations)) {
        var sliderPosition by remember { mutableFloatStateOf(Settings.terminal_font_size.toFloat()) }
        PreferenceGroup {
            PreferenceTemplate(title = { Text(stringResource(strings.text_size)) }) {
                Text(sliderPosition.toInt().toString())
            }
            PreferenceTemplate(title = {}) {
                Slider(
                    modifier = modifier,
                    value = sliderPosition,
                    onValueChange = {
                        sliderPosition = it
                        Settings.terminal_font_size = it.toInt()
                        terminalView.get()?.setTextSize(dpToPx(it.toFloat(), context))
                    },
                    steps = (max_text_size - min_text_size).toInt() - 1,
                    valueRange = min_text_size..max_text_size,
                )
            }
        }

        fun getFileNameFromUri(context: Context, uri: Uri): String? {
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex != -1) {
                        return cursor.getString(nameIndex)
                    }
                }
            } else if (uri.scheme == ContentResolver.SCHEME_FILE) {
                return File(uri.path!!).name
            }
            return null
        }

        PreferenceGroup {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(15.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = ImageVector.vectorResource(drawables.ic_lucide_info), contentDescription = null)
                }
                Text(
                    text = stringResource(strings.font_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            val scope = rememberCoroutineScope()
            val font by remember { mutableStateOf<File>(context.filesDir.child("font.ttf")) }
            var fontExists by remember { mutableStateOf(font.exists()) }

            val noFontSelected = stringResource(strings.no_font_selected)
            var fontName by remember { mutableStateOf(if (!fontExists || !font.canRead()){
                noFontSelected
            }else{
                Settings.custom_font_name
            }) }

            val fontLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                uri?.let {
                    scope.launch(Dispatchers.IO){
                        font.createFileIfNot()
                        context.contentResolver.openInputStream(it)?.use { inputStream ->
                            font.outputStream().use { outputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }

                        val name = getFileNameFromUri(context,uri).toString()
                        Settings.custom_font_name = name
                        fontName = name
                        fontExists = font.exists()
                        setFont(Typeface.createFromFile(font))
                    }
                }

            }

            PreferenceTemplate(
                modifier = Modifier.clickable(onClick = {
                    scope.launch{
                        fontLauncher.launch("font/ttf")
                    }
                }),
                title = {
                    Text(stringResource(strings.custom_font))
                },
                description = {
                    Text(fontName)
                },
                endWidget = {
                    if (fontExists){
                        IconButton(onClick = {
                            scope.launch{
                                font.delete()
                                fontName = noFontSelected
                                Settings.custom_font_name = noFontSelected
                                setFont(Typeface.MONOSPACE)
                                fontExists = font.exists()
                            }
                        }) {
                            Icon(imageVector = ImageVector.vectorResource(drawables.ic_lucide_trash_2),contentDescription = "delete")
                        }
                    }
                }
            )
        }

        PreferenceGroup {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val image by remember { mutableStateOf<File>(context.filesDir.child("background")) }


            var imageExists by remember { mutableStateOf(image.exists()) }



            val noImageSelected = stringResource(strings.no_image_selected)
            var backgroundName by remember { mutableStateOf(if (!imageExists || !image.canRead()){
                noImageSelected
            }else{
                Settings.custom_background_name
            }) }



            val launcher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                uri?.let {
                    scope.launch(Dispatchers.IO){
                        image.createFileIfNot()
                        context.contentResolver.openInputStream(it)?.use { inputStream ->
                            image.outputStream().use { outputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }

                        val name = getFileNameFromUri(context,uri).toString()
                        Settings.custom_background_name = name
                        backgroundName = name


                        withContext(Dispatchers.IO) {
                            val file = context.filesDir.child("background")
                            if (!file.exists()) return@withContext
                            bitmap.value = BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                            bitmap.value?.apply {
                                val androidBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                val buffer = IntArray(width * height)
                                readPixels(buffer, 0, 0, width, height)
                                androidBitmap.setPixels(buffer, 0, width, 0, 0, width, height)
                                Palette.from(androidBitmap).generate { palette ->
                                    val dominantColor = palette?.getDominantColor(android.graphics.Color.WHITE)
                                    val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(dominantColor ?: android.graphics.Color.WHITE)
                                    val blackText = luminance > 0.5f
                                    Settings.blackTextColor = blackText
                                    darkText.value = blackText
                                }
                            }

                        }
                        imageExists = image.exists()
                    }

                }


            }

            PreferenceTemplate(
                modifier = Modifier.clickable(onClick = {
                    scope.launch{
                        launcher.launch("image/*")
                    }
                }),
                title = {
                    Text(stringResource(strings.custom_background))
                },
                description = {
                    Text(backgroundName)
                },
                endWidget = {
                    val darkMode = isSystemInDarkTheme()
                    if (imageExists){
                        IconButton(onClick = {
                            scope.launch{
                                image.delete()
                                Settings.custom_background_name = noImageSelected
                                backgroundName = noImageSelected
                                darkText.value = !darkMode
                                imageExists = image.exists()
                                bitmap.value = null
                            }
                        }) {
                            Icon(imageVector = ImageVector.vectorResource(drawables.ic_lucide_trash_2),contentDescription = "delete")
                        }
                    }

                }
            )

        }

        PreferenceGroup {
            PreferenceTemplate(title = {
                Text(stringResource(strings.wallpaper_alpha))
            }) { Text(
                DecimalFormat("0.##")
                .apply { roundingMode = RoundingMode.HALF_UP }
                .format(wallAlpha)) }
            PreferenceTemplate(title = {}){
                Slider(
                    value = wallAlpha,
                    onValueChange = {
                        wallAlpha = it
                    },
                    onValueChangeFinished = {
                        Settings.wallTransparency = wallAlpha
                    }
                )
            }
        }


        PreferenceGroup {
            SettingsToggle(label = stringResource(strings.bell), description = stringResource(strings.bell_desc), showSwitch = true, default = Settings.bell, sideEffect = {
                Settings.bell = it
            })

            SettingsToggle(label = stringResource(strings.vibrate), description = stringResource(strings.vibrate_desc), showSwitch = true, default = Settings.vibrate, sideEffect = {
                Settings.vibrate = it
            })
        }

        PreferenceGroup {
            SettingsToggle(
                label = stringResource(strings.statusbar),
                description = stringResource(strings.statusbar_desc),
                showSwitch = true,
                default = Settings.statusBar, sideEffect = {
                    Settings.statusBar = it
                    showStatusBar.value = it
                })

            SettingsToggle(
                label = stringResource(strings.horizontal_statusbar),
                description = stringResource(strings.horizontal_statusbar_desc),
                showSwitch = true,
                default = Settings.horizontal_statusBar, sideEffect = {
                    Settings.horizontal_statusBar = it
                    horizontal_statusBar.value = it
                })


            val attentionTitle = stringResource(strings.attention)
            val toolbarWarning = stringResource(strings.toolbar_warning)
            val cancelStr = stringResource(strings.cancel)
            val sideEffect:(Boolean)-> Unit = {
                if (!it && showToolbar.value){
                    MaterialAlertDialogBuilder(context).apply {
                        setTitle(attentionTitle)
                        setMessage(toolbarWarning)
                        setPositiveButton("OK"){_,_ ->
                            Settings.toolbar = it
                            showToolbar.value = it
                        }
                        setNegativeButton(cancelStr,null)
                        show()
                    }
                }else{
                    Settings.toolbar = it
                    showToolbar.value = it
                }

            }


            PreferenceSwitch(checked = showToolbar.value,
                onCheckedChange = {
                    sideEffect.invoke(it)
                },
                label = stringResource(strings.titlebar),
                modifier = modifier,
                description = stringResource(strings.titlebar_desc),
                onClick = {
                    sideEffect.invoke(!showToolbar.value)
                })

            SettingsToggle(
                isEnabled = showToolbar.value,
                label = stringResource(strings.horizontal_titlebar),
                description = stringResource(strings.horizontal_titlebar_desc),
                showSwitch = true,
                default = Settings.toolbar_in_horizontal, sideEffect = {
                    Settings.toolbar_in_horizontal = it
                    showHorizontalToolbar.value = it
                })
            SettingsToggle(
                label = stringResource(strings.virtual_keys),
                description = stringResource(strings.virtual_keys_desc),
                showSwitch = true,
                default = Settings.virtualKeys, sideEffect = {
                    Settings.virtualKeys = it
                    showVirtualKeys.value = it
                })

            SettingsToggle(
                label = stringResource(strings.hide_soft_keyboard),
                description = stringResource(strings.hide_soft_keyboard_desc),
                showSwitch = true,
                default = Settings.hide_soft_keyboard_if_hwd, sideEffect = {
                    Settings.hide_soft_keyboard_if_hwd = it
                })

        }

        // Keyboard Shortcuts
        PreferenceGroup(heading = stringResource(strings.keyboard_shortcuts)) {
            var shortcutsEnabled by remember { mutableStateOf(Settings.shortcuts_enabled) }
            var showCaptureFor by remember { mutableStateOf<ShortcutAction?>(null) }

            SettingsToggle(
                label = stringResource(strings.keyboard_shortcuts),
                description = stringResource(strings.keyboard_shortcuts_desc),
                showSwitch = true,
                default = Settings.shortcuts_enabled,
                sideEffect = {
                    Settings.shortcuts_enabled = it
                    shortcutsEnabled = it
                })

            for (action in ShortcutAction.entries) {
                val binding = Settings.getShortcutBinding(action)
                val labelRes = when (action) {
                    ShortcutAction.PASTE -> strings.shortcut_paste
                    ShortcutAction.NEW_SESSION -> strings.shortcut_new_session
                    ShortcutAction.CLOSE_SESSION -> strings.shortcut_close_session
                    ShortcutAction.SWITCH_SESSION_PREV -> strings.shortcut_switch_prev
                    ShortcutAction.SWITCH_SESSION_NEXT -> strings.shortcut_switch_next
                }
                val descRes = when (action) {
                    ShortcutAction.PASTE -> strings.shortcut_paste_desc
                    ShortcutAction.NEW_SESSION -> strings.shortcut_new_session_desc
                    ShortcutAction.CLOSE_SESSION -> strings.shortcut_close_session_desc
                    ShortcutAction.SWITCH_SESSION_PREV -> strings.shortcut_switch_prev_desc
                    ShortcutAction.SWITCH_SESSION_NEXT -> strings.shortcut_switch_next_desc
                }
                SettingsToggle(
                    isEnabled = shortcutsEnabled,
                    label = stringResource(labelRes),
                    description = "${stringResource(descRes)} (${binding.toDisplayString()})",
                    showSwitch = false,
                    default = false,
                    sideEffect = { showCaptureFor = action },
                )
            }

            if (showCaptureFor != null) {
                ShortcutCaptureDialog(
                    action = showCaptureFor!!,
                    onDismiss = { showCaptureFor = null },
                    onConfirm = { binding ->
                        Settings.setShortcutBinding(showCaptureFor!!, binding)
                        showCaptureFor = null
                    },
                )
            }
        }


    }


}

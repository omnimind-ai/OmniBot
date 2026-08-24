package com.rk.terminal.ui.screens.settings

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.rk.components.compose.preferences.base.LocalOmniPaletteExtras
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.components.compose.preferences.base.PreferenceLayout
import com.rk.components.compose.preferences.base.PreferenceTemplate
import com.rk.resources.drawables
import com.rk.resources.strings
import com.rk.settings.AlpinePackageMirror
import com.rk.settings.Settings
import com.rk.settings.UbuntuPackageMirror
import com.rk.terminal.ui.activities.terminal.MainActivity
import com.rk.terminal.ui.components.SettingsToggle
import com.rk.terminal.ui.routes.MainActivityRoutes
import androidx.core.net.toUri
import com.rk.terminal.runtime.AlpineRepositoryManager
import com.rk.terminal.runtime.TerminalDistribution
import com.rk.terminal.runtime.UbuntuRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    title: @Composable () -> Unit,
    description: @Composable () -> Unit = {},
    startWidget: (@Composable () -> Unit)? = null,
    endWidget: (@Composable () -> Unit)? = null,
    isEnabled: Boolean = true,
    onClick: () -> Unit
) {
    PreferenceTemplate(
        modifier = modifier
            .combinedClickable(
                enabled = isEnabled,
                indication = ripple(),
                interactionSource = interactionSource,
                onClick = onClick
            ),
        title = title,
        description = description,
        startWidget = startWidget,
        endWidget = endWidget,
    )

}


object WorkingMode{
    const val ALPINE = 0
    const val ANDROID = 1
    const val UBUNTU = 2
}

object InputMode {
    const val DEFAULT = 0
    const val TYPE_NULL = 1
    const val VISIBLE_PASSWORD = 2
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Settings(modifier: Modifier = Modifier,navController: NavController,mainActivity: MainActivity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedOption by remember { mutableIntStateOf(Settings.working_Mode) }
    var selectedDistribution by remember { mutableIntStateOf(Settings.terminal_distribution) }
    var selectedInputMode by remember { mutableIntStateOf(Settings.input_mode) }
    var selectedAlpineMirror by remember { mutableIntStateOf(Settings.alpine_package_mirror) }
    var selectedUbuntuMirror by remember { mutableIntStateOf(Settings.ubuntu_package_mirror) }

    fun selectAlpineMirror(source: Int) {
        selectedAlpineMirror = source
        Settings.alpine_package_mirror = source
        scope.launch {
            val toastMessage = withContext(Dispatchers.IO) {
                runCatching {
                    val result = AlpineRepositoryManager.applySelectedRepositoryToInstalledRootfs()
                    if (result.applied) {
                        context.getString(strings.alpine_package_mirror_applied)
                    } else {
                        context.getString(strings.alpine_package_mirror_pending)
                    }
                }.getOrElse { error ->
                    context.getString(
                        strings.alpine_package_mirror_failed,
                        error.message ?: error.javaClass.simpleName
                    )
                }
            }
            Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
        }
    }

    fun selectUbuntuMirror(source: Int) {
        selectedUbuntuMirror = source
        Settings.ubuntu_package_mirror = source
        scope.launch {
            val toastMessage = withContext(Dispatchers.IO) {
                runCatching {
                    val result = UbuntuRepositoryManager.applySelectedRepositoryToInstalledRootfs()
                    if (result.applied) {
                        context.getString(strings.ubuntu_package_mirror_applied)
                    } else {
                        context.getString(strings.ubuntu_package_mirror_pending)
                    }
                }.getOrElse { error ->
                    context.getString(
                        strings.ubuntu_package_mirror_failed,
                        error.message ?: error.javaClass.simpleName
                    )
                }
            }
            Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
        }
    }

    fun selectDistribution(workingMode: Int) {
        val distribution = TerminalDistribution.fromWorkingMode(workingMode)
        selectedDistribution = distribution.workingMode
        selectedOption = distribution.workingMode
        Settings.terminal_distribution = distribution.workingMode
        Settings.working_Mode = distribution.workingMode
    }

    PreferenceLayout(label = stringResource(strings.settings)) {
        PreferenceGroup(heading = stringResource(strings.default_working_mode)) {

            SettingsCard(
                title = { Text("Alpine") },
                description = {Text(stringResource(strings.alpine_desc))},
                startWidget = {
                    RadioButton(
                        modifier = Modifier.padding(start = 8.dp),
                        selected = selectedOption == WorkingMode.ALPINE,
                        onClick = {
                            selectDistribution(WorkingMode.ALPINE)
                        })
                },
                onClick = {
                    selectDistribution(WorkingMode.ALPINE)
                })

            SettingsCard(
                title = { Text("Ubuntu") },
                description = { Text(stringResource(strings.ubuntu_desc)) },
                startWidget = {
                    RadioButton(
                        modifier = Modifier.padding(start = 8.dp),
                        selected = selectedOption == WorkingMode.UBUNTU,
                        onClick = {
                            selectDistribution(WorkingMode.UBUNTU)
                        })
                },
                onClick = {
                    selectDistribution(WorkingMode.UBUNTU)
                })


            SettingsCard(
                title = { Text("Android") },
                description = {Text(stringResource(strings.android_desc))},
                startWidget = {
                    RadioButton(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            ,
                        selected = selectedOption == WorkingMode.ANDROID,
                        onClick = {
                            selectedOption = WorkingMode.ANDROID
                            Settings.working_Mode = selectedOption
                        })
                },
                onClick = {
                    selectedOption = WorkingMode.ANDROID
                    Settings.working_Mode = selectedOption
                })
        }

        PreferenceGroup(heading = stringResource(strings.input_mode)) {

            SettingsCard(
                title = { Text(stringResource(strings.input_mode_default)) },
                description = { Text(stringResource(strings.input_mode_default_desc)) },
                startWidget = {
                    RadioButton(
                        modifier = Modifier.padding(start = 8.dp),
                        selected = selectedInputMode == InputMode.DEFAULT,
                        onClick = {
                            selectedInputMode = InputMode.DEFAULT
                            Settings.input_mode = selectedInputMode
                        })
                },
                onClick = {
                    selectedInputMode = InputMode.DEFAULT
                    Settings.input_mode = selectedInputMode
                })

            SettingsCard(
                title = { Text(stringResource(strings.input_mode_type_null)) },
                description = { Text(stringResource(strings.input_mode_type_null_desc)) },
                startWidget = {
                    RadioButton(
                        modifier = Modifier.padding(start = 8.dp),
                        selected = selectedInputMode == InputMode.TYPE_NULL,
                        onClick = {
                            selectedInputMode = InputMode.TYPE_NULL
                            Settings.input_mode = selectedInputMode
                        })
                },
                onClick = {
                    selectedInputMode = InputMode.TYPE_NULL
                    Settings.input_mode = selectedInputMode
                })

            SettingsCard(
                title = { Text(stringResource(strings.input_mode_visible_password)) },
                description = { Text(stringResource(strings.input_mode_visible_password_desc)) },
                startWidget = {
                    RadioButton(
                        modifier = Modifier.padding(start = 8.dp),
                        selected = selectedInputMode == InputMode.VISIBLE_PASSWORD,
                        onClick = {
                            selectedInputMode = InputMode.VISIBLE_PASSWORD
                            Settings.input_mode = selectedInputMode
                        })
                },
                onClick = {
                    selectedInputMode = InputMode.VISIBLE_PASSWORD
                    Settings.input_mode = selectedInputMode
                })
        }

        if (selectedDistribution == WorkingMode.ALPINE) {
            PreferenceGroup(heading = stringResource(strings.alpine_package_mirror)) {

            SettingsCard(
                title = { Text(stringResource(strings.alpine_package_mirror_official)) },
                description = { Text(stringResource(strings.alpine_package_mirror_official_desc)) },
                startWidget = {
                    RadioButton(
                        modifier = Modifier.padding(start = 8.dp),
                        selected = selectedAlpineMirror == AlpinePackageMirror.OFFICIAL,
                        onClick = {
                            selectAlpineMirror(AlpinePackageMirror.OFFICIAL)
                        })
                },
                onClick = {
                    selectAlpineMirror(AlpinePackageMirror.OFFICIAL)
                })


            SettingsCard(
                title = { Text(stringResource(strings.alpine_package_mirror_tsinghua)) },
                description = { Text(stringResource(strings.alpine_package_mirror_tsinghua_desc)) },
                startWidget = {
                    RadioButton(
                        modifier = Modifier.padding(start = 8.dp),
                        selected = selectedAlpineMirror == AlpinePackageMirror.TSINGHUA,
                        onClick = {
                            selectAlpineMirror(AlpinePackageMirror.TSINGHUA)
                        })
                },
                onClick = {
                    selectAlpineMirror(AlpinePackageMirror.TSINGHUA)
                })
            }
        } else if (selectedDistribution == WorkingMode.UBUNTU) {
            PreferenceGroup(heading = stringResource(strings.ubuntu_package_mirror)) {

                SettingsCard(
                    title = { Text(stringResource(strings.ubuntu_package_mirror_official)) },
                    description = { Text(stringResource(strings.ubuntu_package_mirror_official_desc)) },
                    startWidget = {
                        RadioButton(
                            modifier = Modifier.padding(start = 8.dp),
                            selected = selectedUbuntuMirror == UbuntuPackageMirror.OFFICIAL,
                            onClick = {
                                selectUbuntuMirror(UbuntuPackageMirror.OFFICIAL)
                            })
                    },
                    onClick = {
                        selectUbuntuMirror(UbuntuPackageMirror.OFFICIAL)
                    })

                SettingsCard(
                    title = { Text(stringResource(strings.ubuntu_package_mirror_tsinghua)) },
                    description = { Text(stringResource(strings.ubuntu_package_mirror_tsinghua_desc)) },
                    startWidget = {
                        RadioButton(
                            modifier = Modifier.padding(start = 8.dp),
                            selected = selectedUbuntuMirror == UbuntuPackageMirror.TSINGHUA,
                            onClick = {
                                selectUbuntuMirror(UbuntuPackageMirror.TSINGHUA)
                            })
                    },
                    onClick = {
                        selectUbuntuMirror(UbuntuPackageMirror.TSINGHUA)
                    })
            }
        }


        PreferenceGroup {
            SettingsToggle(
                label = stringResource(strings.customizations),
                showSwitch = false,
                default = false,
                sideEffect = {
                   navController.navigate(MainActivityRoutes.Customization.route)
            }, endWidget = {
                Icon(
                    imageVector = ImageVector.vectorResource(drawables.ic_lucide_chevron_right),
                    contentDescription = null,
                    tint = LocalOmniPaletteExtras.current.textTertiary,
                    modifier = Modifier.size(18.dp)
                )
            })
        }

        PreferenceGroup {
            SettingsToggle(
                label = stringResource(strings.seccomp),
                description = stringResource(strings.seccomp_desc),
                showSwitch = true,
                default = Settings.seccomp,
                sideEffect = {
                    Settings.seccomp = it
                })

            SettingsToggle(
                label = stringResource(strings.all_file_access),
                description = stringResource(strings.all_file_access_desc),
                showSwitch = false,
                default = false,
                sideEffect = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching {
                            val intent = Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                "package:${context.packageName}".toUri()
                            )
                            context.startActivity(intent)
                        }.onFailure {
                            val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            context.startActivity(intent)
                        }
                    }else{
                        val intent = Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:${context.packageName}".toUri()
                        )
                        context.startActivity(intent)
                    }

                })

        }
    }
}

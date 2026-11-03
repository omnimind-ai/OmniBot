package com.rk.terminal.ui.screens.downloader

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.rk.libcommons.*
import com.rk.resources.strings
import com.rk.terminal.runtime.EmbeddedRuntimeInstaller
import com.rk.terminal.ui.activities.terminal.MainActivity
import com.rk.terminal.ui.screens.terminal.Rootfs
import com.rk.terminal.ui.screens.terminal.TerminalScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Downloader(
    modifier: Modifier = Modifier,
    mainActivity: MainActivity,
    navController: NavHostController
) {
    val context = LocalContext.current
    var progress by remember { mutableFloatStateOf(0f) }
    val installingStr = stringResource(strings.installing)
    val networkErrorStr = stringResource(strings.network_error)
    val setupFailedStr = stringResource(strings.setup_failed)
    var progressText by remember { mutableStateOf(installingStr) }
    var isSetupComplete by remember { mutableStateOf(false) }
    var needsDownload by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            needsDownload = !Rootfs.isFilesDownloaded()
            val status = EmbeddedRuntimeInstaller.ensureRuntimeInstalled(context) { step ->
                progressText = step.message
                progress = step.progress?.toFloat()?.coerceIn(0f, 1f) ?: progress
            }
            if (!status.success) {
                toast(setupFailedStr.format(status.message))
                return@LaunchedEffect
            }
            Rootfs.isDownloaded.value = Rootfs.isFilesDownloaded()
            progress = 1f
            progressText = installingStr
            isSetupComplete = true
        } catch (e: Exception) {
            toast(setupFailedStr.format(e.message))
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!isSetupComplete) {
            if (needsDownload) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(progressText, style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(0.8f))
                }
            }
        } else {
            TerminalScreen(mainActivityActivity = mainActivity, navController = navController)
        }
    }
}

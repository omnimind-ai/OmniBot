package cn.com.omnimind.nativeui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import androidx.compose.runtime.getValue

/**
 * Standard secondary page: Miuix [Scaffold] on the page color, [OmniTopBar] and a snackbar
 * for one-shot [notice] text. [onNoticeShown] runs after the snackbar is dismissed so the
 * ViewModel can clear its notice; a new notice value shows again.
 */
@Composable
internal fun OmniPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    notice: String? = null,
    onNoticeShown: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val currentOnNoticeShown by rememberUpdatedState(onNoticeShown)
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice)
            currentOnNoticeShown()
        }
    }
    Scaffold(
        modifier = modifier,
        containerColor = LocalOmniPalette.current.page,
        topBar = { OmniTopBar(title, onBack, actions) },
        snackbarHost = { SnackbarHost(snackbar) },
        content = content,
    )
}

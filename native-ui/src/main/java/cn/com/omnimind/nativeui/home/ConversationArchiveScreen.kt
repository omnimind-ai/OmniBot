package cn.com.omnimind.nativeui.home

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.com.omnimind.nativeui.NativeHomeActions
import cn.com.omnimind.nativeui.NativeHomeState
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniTopBar
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Scaffold

/** Archive and restore share the same list projection and durable-history owner as the drawer. */
@Composable
internal fun ConversationArchiveScreen(state: NativeHomeState, actions: NativeHomeActions, onBack: () -> Unit) {
    Scaffold(
        containerColor = LocalOmniPalette.current.page,
        topBar = { OmniTopBar(stringResource(R.string.omni_archived_conversations), onBack) },
    ) { padding ->
        DrawerConversationList(state, "", actions,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(vertical = 10.dp),
            archivedOnly = true)
    }
}

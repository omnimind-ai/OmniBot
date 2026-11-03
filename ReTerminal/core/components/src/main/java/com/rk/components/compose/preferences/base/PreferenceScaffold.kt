package com.rk.components.compose.preferences.base

/*
 * Copyright 2021, Lawnchair.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.rk.components.compose.appbars.TopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreferenceScaffold(
    label: String,
    isExpandedScreen: Boolean,
    modifier: Modifier = Modifier,
    backArrowVisible: Boolean = true,
    fab: (@Composable () -> Unit) = {},
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = { BottomSpacer() },
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // Match the Flutter settings column while keeping the page background
    // edge-to-edge. This boundary is used only by preferences; terminal output
    // retains its full viewport. One scaffold keeps its scroll and focus owners
    // when the available window width changes.
    Box(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Scaffold(
            modifier = Modifier.widthIn(max = 840.dp)
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopBar(
                    backArrowVisible = backArrowVisible,
                    label = label,
                    isExpandedScreen = isExpandedScreen,
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = fab,
            bottomBar = bottomBar,
        ) {
            content(it)
        }
    }
}

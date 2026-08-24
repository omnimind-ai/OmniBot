package com.rk.components.compose.appbars

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

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import com.rk.components.compose.icons.ClickableIcon
import com.rk.resources.drawables

/**
 * App bar styled after the main OmnibotApp: small centered title
 * (17sp semi-bold) on the page background, Lucide back chevron.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(
    backArrowVisible: Boolean,
    label: String,
    isExpandedScreen: Boolean,
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    CenterAlignedTopAppBar(
        modifier = modifier,
        title = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = actions,
        navigationIcon = {
            if (backArrowVisible) {
                ClickableIcon(
                    imageVector = ImageVector.vectorResource(drawables.ic_lucide_arrow_left),
                    onClick = { backDispatcher?.onBackPressed() },
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        colors =
            TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                scrolledContainerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        scrollBehavior = scrollBehavior,
    )
}

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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A section of preference rows, styled after the main OmnibotApp settings
 * pages: no card container — rows sit directly on the page background,
 * separated by hairlines, with a small uppercase-style section header.
 */
@Composable
fun PreferenceGroup(
    modifier: Modifier = Modifier,
    heading: String? = null,
    description: String? = null,
    showDescription: Boolean = true,
    showDividers: Boolean = true,
    dividerStartIndent: Dp = 0.dp,
    dividerEndIndent: Dp = 0.dp,
    dividersToSkip: Int = 0,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        PreferenceGroupHeading(heading)
        if (showDividers) {
            DividerColumn(
                startIndent = dividerStartIndent,
                endIndent = dividerEndIndent,
                content = content,
                dividersToSkip = dividersToSkip,
            )
        } else {
            Column { content() }
        }
        PreferenceGroupDescription(description = description, showDescription = showDescription)
    }
}

@Composable
fun PreferenceGroupHeading(heading: String?, modifier: Modifier = Modifier) {
    if (heading != null) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        ) {
            Text(
                text = heading,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
                color = LocalOmniPaletteExtras.current.textTertiary,
                modifier = Modifier.semantics { this.heading() },
            )
        }
    }
}

@Composable
fun PreferenceGroupDescription(
    modifier: Modifier = Modifier,
    description: String? = null,
    showDescription: Boolean = true,
) {
    description?.let {
        ExpandAndShrink(modifier = modifier, visible = showDescription) {
            Row(modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp)) {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

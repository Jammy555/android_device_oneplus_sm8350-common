/*
 * Copyright (C) 2018 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package org.lineageos.device.DeviceSettings.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.lineageos.device.DeviceSettings.ui.symbols.AnimatedSymbol
import org.lineageos.device.DeviceSettings.ui.symbols.Symbol

data class Choice(val value: String, val label: String)

/** Bottom sheet list picker; the check icon fills in on the selected row. */
@Composable
fun ChoiceSheet(
    title: String,
    choices: List<Choice>,
    selected: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                title,
                style = MaterialTheme.typography.titleLargeEmphasized,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(choices, key = { it.value }) { choice ->
                    val isSelected = choice.value == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.RadioButton) { onSelect(choice.value) }
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        AnimatedSymbol(
                            Symbol.CHECK_CIRCLE, active = isSelected,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            choice.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Two-thumb picker over a sorted list of frequencies. Thumbs snap to real frequency steps, tick
 * with haptics, and are clamped to [lowestMin]..[highestMax] (profile restrictions). The change is
 * committed once, when the finger lifts - never per step - so sysfs is written once.
 */
@Composable
fun FrequencyRange(
    freqs: List<Long>,
    minIndex: Int,
    maxIndex: Int,
    lowestMin: Int,
    highestMax: Int,
    enabled: Boolean,
    format: (Long) -> String,
    onCommit: (newMin: Int, newMax: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val last = freqs.lastIndex
    if (last < 1) return
    val haptic = LocalHapticFeedback.current
    var range by remember(minIndex, maxIndex) {
        mutableStateOf(minIndex.toFloat()..maxIndex.toFloat())
    }
    val lo = range.start.roundToInt().coerceIn(0, last)
    val hi = range.endInclusive.roundToInt().coerceIn(0, last)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(format(freqs[lo]), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(format(freqs[hi]), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        RangeSlider(
            value = range,
            onValueChange = { r ->
                val start = r.start.coerceAtLeast(lowestMin.toFloat())
                val end = r.endInclusive.coerceAtMost(highestMax.toFloat())
                val next = start..maxOf(start, end)
                if (next.start.roundToInt() != lo || next.endInclusive.roundToInt() != hi) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                range = next
            },
            onValueChangeFinished = {
                onCommit(
                    range.start.roundToInt().coerceIn(0, last),
                    range.endInclusive.roundToInt().coerceIn(0, last),
                )
            },
            valueRange = 0f..last.toFloat(),
            steps = (last - 1).coerceAtLeast(0),
            enabled = enabled,
        )
    }
}

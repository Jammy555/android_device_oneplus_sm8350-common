/*
 * Copyright (C) 2026 LunarisOS
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

package org.lineageos.device.DeviceSettings.ui.dock

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import org.lineageos.device.DeviceSettings.ui.components.Choice
import org.lineageos.device.DeviceSettings.ui.components.ChoiceSheet
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.lineageos.device.DeviceSettings.R
import org.lineageos.device.DeviceSettings.ui.components.NavRow
import org.lineageos.device.DeviceSettings.ui.components.NoteRow
import org.lineageos.device.DeviceSettings.ui.components.SettingsGroup
import org.lineageos.device.DeviceSettings.ui.components.SettingsScreen
import org.lineageos.device.DeviceSettings.ui.components.StaggerIn
import org.lineageos.device.DeviceSettings.ui.components.SwitchRow
import org.lineageos.device.DeviceSettings.ui.components.ValueSliderRow
import org.lineageos.device.DeviceSettings.ui.symbols.Symbol
import org.lineageos.device.DeviceSettings.ui.theme.Motion

@Composable
fun DockSettingsScreen(
    vm: DockSettingsViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenHistory: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsState()
    var historyLimit by remember(s.historyLimit) { mutableIntStateOf(s.historyLimit) }
    var confirmClear by remember { mutableStateOf(false) }
    var showTimeout by remember { mutableStateOf(false) }
    var showFade by remember { mutableStateOf(false) }
    val fadeChoices = listOf(25, 50, 75).map {
        Choice(it.toString(), stringResource(R.string.dock_transparency_percent, it))
    }
    val timeoutChoices = listOf(Choice("0", stringResource(R.string.dock_timeout_off)),
        Choice("1", stringResource(R.string.dock_timeout_one)), Choice("10", stringResource(R.string.dock_timeout_ten)))
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
        it?.let(vm::exportHistory)
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(vm::importHistory)
    }

    SettingsScreen(
        title = stringResource(R.string.dock_settings_title),
        subtitle = if (s.helperEnabled) stringResource(R.string.dock_subtitle_helper_on) else null,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    ) {
        item(key = "preview") {
            StaggerIn(0) { DockPreview(s.width, s.height) }
        }

        item(key = "helper") {
            StaggerIn(1) {
                SettingsGroup {
                    item("dock_input_helper") { p ->
                        NavRow(
                            p, Symbol.CONTENT_PASTE, stringResource(R.string.dock_input_title),
                            stringResource(if (s.helperEnabled) R.string.dock_input_enabled else R.string.dock_input_disabled),
                            onClick = vm::openAccessibilitySettings,
                        )
                    }
                    item("dock_input_description") { p -> NoteRow(p, stringResource(R.string.dock_input_description)) }
                }
            }
        }

        item(key = "behaviour") {
            StaggerIn(2) {
                SettingsGroup {
                    item("dock_auto_show") { p ->
                        SwitchRow(
                            p, Symbol.AUTO_AWESOME, stringResource(R.string.dock_auto_show),
                            stringResource(R.string.dock_auto_show_summary), s.autoShow, vm::setAutoShow,
                        )
                    }
                    item("dock_capture_clipboard") { p ->
                        SwitchRow(
                            p, Symbol.CONTENT_COPY, stringResource(R.string.dock_capture),
                            stringResource(R.string.dock_capture_summary), s.capture, vm::setCapture,
                        )
                    }
                    item("dock_hold_controls") { p ->
                        SwitchRow(
                            p, Symbol.TUNE, stringResource(R.string.dock_hold_controls),
                            stringResource(R.string.dock_hold_controls_summary), s.holdControls, vm::setHoldControls,
                        )
                    }
                    item("dock_double_tap_screenshot") { p ->
                        SwitchRow(
                            p, Symbol.IMAGE, stringResource(R.string.dock_double_tap_screenshot),
                            stringResource(R.string.dock_double_tap_screenshot_summary),
                            s.doubleTapScreenshot, vm::setDoubleTapScreenshot,
                        )
                    }
                    item("dock_blur") { p ->
                        SwitchRow(p, Symbol.CONTRAST, stringResource(R.string.dock_blur),
                            stringResource(R.string.dock_blur_summary), s.blur, vm::setBlur)
                    }
                    item("dock_battery_rail") { p ->
                        SwitchRow(p, Symbol.AUTO_AWESOME, stringResource(R.string.dock_battery_rail),
                            stringResource(R.string.dock_battery_rail_summary), s.batteryRail, vm::setBatteryRail)
                    }
                    item("dock_timeout") { p ->
                        NavRow(p, Symbol.HISTORY, stringResource(R.string.dock_clipboard_timeout),
                            stringResource(R.string.dock_clipboard_timeout_summary),
                            trailingText = timeoutChoices.firstOrNull { it.value == s.clipboardTimeout.toString() }?.label,
                            onClick = { showTimeout = true })
                    }
                    item("dock_auto_fade") { p ->
                        SwitchRow(
                            p, Symbol.CONTRAST, stringResource(R.string.dock_auto_fade),
                            stringResource(R.string.dock_auto_fade_summary), s.autoFade, vm::setAutoFade,
                        )
                    }
                    if (s.autoFade) {
                        item("dock_fade_transparency") { p ->
                            NavRow(p, Symbol.CONTRAST, stringResource(R.string.dock_fade_transparency),
                                stringResource(R.string.dock_fade_transparency_summary),
                                trailingText = stringResource(R.string.dock_transparency_percent, s.fadeTransparency),
                                onClick = { showFade = true })
                        }
                    }
                    item("dock_hide_landscape") { p ->
                        SwitchRow(p, Symbol.SWAP_HORIZ, stringResource(R.string.dock_hide_landscape),
                            stringResource(R.string.dock_hide_landscape_summary), s.hideLandscape, vm::setHideLandscape)
                    }
                }
            }
        }

        item(key = "size") {
            StaggerIn(3) {
                SettingsGroup(stringResource(R.string.dock_handle_section)) {
                    item("dock_width") { p ->
                        ValueSliderRow(
                            p, Symbol.SWAP_HORIZ, stringResource(R.string.dock_width), "${s.width} dp",
                            s.width, 72, enabled = true, onValueChange = vm::setWidth,
                            hapticEvery = 1, min = 20, step = 4,
                        )
                    }
                    item("dock_height") { p ->
                        ValueSliderRow(
                            p, Symbol.SWAP_VERT, stringResource(R.string.dock_height), "${s.height} dp",
                            s.height, 160, enabled = true, onValueChange = vm::setHeight,
                            hapticEvery = 1, min = 40, step = 8,
                        )
                    }
                    item("dock_size_info") { p -> NoteRow(p, stringResource(R.string.dock_size_summary)) }
                }
            }
        }

        item(key = "history") {
            StaggerIn(4) {
                SettingsGroup {
                    item("dock_history_page") { p ->
                        NavRow(
                            p, Symbol.CONTENT_PASTE, stringResource(R.string.dock_history_page_title),
                            stringResource(R.string.dock_history_page_summary), onClick = onOpenHistory,
                            enabled = !s.storageError,
                            trailingText = s.itemCount?.toString(),
                        )
                    }
                    item("dock_history_limit") { p ->
                        ValueSliderRow(
                            p, Symbol.HISTORY, stringResource(R.string.dock_history_limit),
                            if (historyLimit == 0) stringResource(R.string.dock_history_infinite) else historyLimit.toString(),
                            if (historyLimit == 0) 150 else historyLimit, 150,
                            enabled = !s.storageError && !s.archiveBusy,
                            onValueChange = { historyLimit = if (it == 150) 0 else it },
                            onValueChangeFinished = { vm.setHistoryLimit(historyLimit) },
                            hapticEvery = 1, min = 50, step = 50,
                        )
                    }
                    item("dock_history_limit_info") { p ->
                        NoteRow(p, stringResource(if (historyLimit == 0) R.string.dock_history_unlimited
                            else R.string.dock_history_limit_summary))
                    }
                    item("dock_export") { p ->
                        NavRow(p, Symbol.SHARE, stringResource(R.string.dock_export),
                            stringResource(R.string.dock_export_summary), enabled = !s.archiveBusy && !s.storageError,
                            onClick = { export.launch("File-Dock-History.zip") })
                    }
                    item("dock_import") { p ->
                        NavRow(p, Symbol.FOLDER_OPEN, stringResource(R.string.dock_import),
                            stringResource(R.string.dock_import_summary), enabled = !s.archiveBusy,
                            onClick = { import.launch(arrayOf("application/zip", "application/octet-stream")) })
                    }
                }
            }
        }

        item(key = "storage") {
            StaggerIn(4) {
                SettingsGroup {
                    item("dock_clear_storage") { p ->
                        NavRow(
                            p, Symbol.DELETE, stringResource(R.string.dock_clear_all),
                            when {
                                s.storageError -> stringResource(R.string.dock_storage_error)
                                s.itemCount != null -> pluralStringResource(R.plurals.dock_item_count, s.itemCount!!, s.itemCount!!)
                                else -> null
                            },
                            onClick = { confirmClear = true },
                            enabled = !s.storageError,
                        )
                    }
                }
            }
        }
    }

    if (showTimeout) {
        ChoiceSheet(stringResource(R.string.dock_clipboard_timeout), timeoutChoices,
            s.clipboardTimeout.toString(), onSelect = { vm.setClipboardTimeout(it.toInt()); showTimeout = false },
            onDismiss = { showTimeout = false })
    }

    if (showFade) {
        ChoiceSheet(stringResource(R.string.dock_fade_transparency), fadeChoices,
            s.fadeTransparency.toString(), onSelect = { vm.setFadeTransparency(it.toInt()); showFade = false },
            onDismiss = { showFade = false })
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.dock_clear_all)) },
            text = { Text(stringResource(R.string.dock_clear_confirmation)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.clearAll() }) {
                    Text(stringResource(R.string.dock_clear_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

/** A tiny phone with the handle drawn at the edge; it resizes live as the sliders move. */
@Composable
private fun DockPreview(widthDp: Int, heightDp: Int) {
    val scheme = MaterialTheme.colorScheme
    val w by animateDpAsState((widthDp / 72f * 34f).dp, Motion.spatial(), label = "previewW")
    val h by animateDpAsState((heightDp / 160f * 100f).dp, Motion.spatial(), label = "previewH")
    Box(
        Modifier
            .fillMaxWidth()
            .height(172.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(scheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 112.dp, height = 140.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(scheme.surfaceContainerHighest)
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(w.coerceAtLeast(8.dp), h.coerceAtLeast(16.dp))
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .background(scheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(2) {
                        Box(
                            Modifier
                                .size(width = 3.dp, height = 14.dp)
                                .clip(RoundedCornerShape(50))
                                .background(scheme.onPrimary.copy(alpha = 0.8f))
                        )
                    }
                }
            }
        }
    }
}

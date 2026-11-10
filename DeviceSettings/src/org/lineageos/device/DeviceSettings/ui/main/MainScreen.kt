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

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.lineageos.device.DeviceSettings.ui.main

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.delay
import org.lineageos.device.DeviceSettings.DeviceKeys
import org.lineageos.device.DeviceSettings.R
import org.lineageos.device.DeviceSettings.ui.components.NavRow
import org.lineageos.device.DeviceSettings.ui.components.NoteRow
import org.lineageos.device.DeviceSettings.ui.components.ProfileDial
import org.lineageos.device.DeviceSettings.ui.components.Profiles
import org.lineageos.device.DeviceSettings.ui.components.SettingsGroup
import org.lineageos.device.DeviceSettings.ui.components.SettingsScreen
import org.lineageos.device.DeviceSettings.ui.components.StaggerIn
import org.lineageos.device.DeviceSettings.ui.components.VibrationStrengthRow
import org.lineageos.device.DeviceSettings.ui.components.SwitchRow
import org.lineageos.device.DeviceSettings.ui.symbols.Symbol

const val POWERTOOLS_CONTAINER = "powertools-container"

/** Row key -> index of the lazy item that holds it, so a Settings search hit can scroll there. */
private val SectionOfKey = mapOf(
    DeviceKeys.DOCK_ENABLE to 0, "dock_settings" to 0,
    DeviceKeys.EDGE_TOUCH to 1, "edge_touch_info" to 1, DeviceKeys.GAME_MODE to 1, "game_mode_info" to 1,
    DeviceKeys.USB2_FAST_CHARGE to 2,
    DeviceKeys.VIB_STRENGTH to 3,
    "powertools_entry" to 4,
    "network_bands_entry" to 5,
    "audio_share_entry" to 6,
)

@Composable
fun MainScreen(
    vm: MainViewModel,
    snackbarHostState: SnackbarHostState,
    highlightKey: String?,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    onOpenPowertools: () -> Unit,
    onOpenNetworkBands: () -> Unit,
    onOpenAudioShare: () -> Unit,
    onOpenDockSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsState()
    val listState = rememberLazyListState()
    var highlighted by remember { mutableStateOf(highlightKey) }

    // Deep link from system Settings search: scroll to the row, pulse it, then let go.
    LaunchedEffect(highlightKey) {
        val target = SectionOfKey[highlightKey] ?: return@LaunchedEffect
        delay(350)
        listState.animateScrollToItem(target)
        delay(1800)
        highlighted = null
    }

    SettingsScreen(
        title = stringResource(R.string.device_title),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        listState = listState,
        modifier = modifier,
    ) {
        item(key = "dock") {
            StaggerIn(0) {
                SettingsGroup(stringResource(R.string.dock_title)) {
                    item(DeviceKeys.DOCK_ENABLE) { p ->
                        SwitchRow(
                            p, Symbol.DOCK_TO_RIGHT, stringResource(R.string.dock_enable),
                            stringResource(R.string.dock_enable_summary),
                            s.dockEnabled, vm::setDockEnabled,
                            highlighted = highlighted == DeviceKeys.DOCK_ENABLE,
                        )
                    }
                    item("dock_settings") { p ->
                        NavRow(
                            p, Symbol.TUNE, stringResource(R.string.dock_settings_title),
                            stringResource(R.string.dock_summary), onOpenDockSettings,
                            enabled = s.dockEnabled, highlighted = highlighted == "dock_settings",
                        )
                    }
                }
            }
        }

        item(key = "touch") {
            StaggerIn(1) {
                SettingsGroup(stringResource(R.string.touchpanel_title)) {
                    item(DeviceKeys.EDGE_TOUCH) { p ->
                        SwitchRow(
                            p, Symbol.TOUCH_APP, stringResource(R.string.edge_touch_title),
                            stringResource(R.string.edge_touch_summary),
                            s.edge.checked, vm::setEdgeTouch, s.edge.enabled,
                            highlighted == DeviceKeys.EDGE_TOUCH,
                        )
                    }
                    if (s.edge.checked) {
                        item("edge_touch_info") { p -> NoteRow(p, stringResource(R.string.edge_touch_warning)) }
                    }
                    item(DeviceKeys.GAME_MODE) { p ->
                        SwitchRow(
                            p, Symbol.SPORTS_ESPORTS, stringResource(R.string.game_mode_title),
                            stringResource(R.string.game_mode_summary),
                            s.game.checked, vm::setGameMode, s.game.enabled,
                            highlighted == DeviceKeys.GAME_MODE,
                        )
                    }
                    if (s.game.checked) {
                        item("game_mode_info") { p -> NoteRow(p, stringResource(R.string.game_mode_warning)) }
                    }
                }
            }
        }

        item(key = "usb") {
            StaggerIn(2) {
                SettingsGroup(stringResource(R.string.usb_title)) {
                    item(DeviceKeys.USB2_FAST_CHARGE) { p ->
                        SwitchRow(
                            p, Symbol.BOLT, stringResource(R.string.usb2_fc_title),
                            stringResource(R.string.usb2_fc_summary),
                            s.usb2.checked, vm::setUsb2FastCharge, s.usb2.enabled,
                            highlighted == DeviceKeys.USB2_FAST_CHARGE,
                        )
                    }
                }
            }
        }

        item(key = "vibrator") {
            StaggerIn(3) {
                SettingsGroup(stringResource(R.string.category_vibrator)) {
                    item(DeviceKeys.VIB_STRENGTH) { p ->
                        VibrationStrengthRow(
                            p, s.vibValue, s.vibMax, s.vibEnabled, vm::setVibration,
                            highlighted == DeviceKeys.VIB_STRENGTH,
                        )
                    }
                }
            }
        }

        item(key = "powertools") {
            StaggerIn(4) {
                SettingsGroup(stringResource(R.string.Powertools_title)) {
                    item("powertools_entry") { p ->
                        NavRow(
                            p, Symbol.SPEED, stringResource(R.string.Powertools_title),
                            stringResource(R.string.Powertools_summary), onOpenPowertools,
                            highlighted = highlighted == "powertools_entry",
                            icon = { tint ->
                                val profileDescription = stringResource(
                                    Profiles.firstOrNull { it.mode == s.mode }?.labelRes
                                        ?: R.string.powerprofile_mode_unknown,
                                )
                                ProfileDial(s.mode, tint, Modifier.semantics { stateDescription = profileDescription })
                            },
                        )
                    }
                }
            }
        }

        item(key = "network") {
            StaggerIn(5) {
                SettingsGroup(stringResource(R.string.network_category_title)) {
                    item("network_bands_entry") { p ->
                        NavRow(
                            p, Symbol.CELL_TOWER, stringResource(R.string.network_bands_title),
                            stringResource(R.string.network_bands_summary), onOpenNetworkBands,
                            highlighted = highlighted == "network_bands_entry",
                        )
                    }
                }
            }
        }

        item(key = "bluetooth") {
            StaggerIn(6) {
                SettingsGroup(stringResource(R.string.audio_share_category_title)) {
                    item("audio_share_entry") { p ->
                        NavRow(
                            p, Symbol.HEADPHONES, stringResource(R.string.audio_share_title),
                            stringResource(R.string.audio_share_summary), onOpenAudioShare,
                            highlighted = highlighted == "audio_share_entry",
                        )
                    }
                }
            }
        }

        item(key = "appearance") {
            StaggerIn(7) {
                SettingsGroup(stringResource(R.string.appearance_title)) {
                    item("appearance_dynamic") { p ->
                        SwitchRow(
                            p, Symbol.PALETTE, stringResource(R.string.appearance_dynamic_title),
                            stringResource(R.string.appearance_dynamic_summary),
                            dynamicColor, onDynamicColorChange,
                        )
                    }
                }
            }
        }
    }
}

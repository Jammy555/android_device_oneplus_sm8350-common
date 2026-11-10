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
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.lineageos.device.DeviceSettings.ui.audioshare

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.lineageos.device.DeviceSettings.DeviceKeys
import org.lineageos.device.DeviceSettings.R
import org.lineageos.device.DeviceSettings.audioshare.AudioShareUiState
import org.lineageos.device.DeviceSettings.audioshare.AudioShareViewModel
import org.lineageos.device.DeviceSettings.audioshare.EngineInfo
import org.lineageos.device.DeviceSettings.audioshare.HeadsetUi
import org.lineageos.device.DeviceSettings.ui.components.Choice
import org.lineageos.device.DeviceSettings.ui.components.ChoiceSheet
import org.lineageos.device.DeviceSettings.ui.components.CodecPill
import org.lineageos.device.DeviceSettings.ui.components.GroupCard
import org.lineageos.device.DeviceSettings.ui.components.GroupPosition
import org.lineageos.device.DeviceSettings.ui.components.MorphBadge
import org.lineageos.device.DeviceSettings.ui.components.NavRow
import org.lineageos.device.DeviceSettings.ui.components.NoteRow
import org.lineageos.device.DeviceSettings.ui.components.SettingsGroup
import org.lineageos.device.DeviceSettings.ui.components.SettingsScreen
import org.lineageos.device.DeviceSettings.ui.components.StaggerIn
import org.lineageos.device.DeviceSettings.ui.components.SwapRow
import org.lineageos.device.DeviceSettings.ui.components.SwitchRow
import org.lineageos.device.DeviceSettings.ui.components.ValueSliderRow
import org.lineageos.device.DeviceSettings.ui.symbols.AnimatedSymbol
import org.lineageos.device.DeviceSettings.ui.symbols.Symbol
import org.lineageos.device.DeviceSettings.ui.symbols.SymbolIcon

private enum class Pick { Primary, Secondary, Codec, LipsyncMode }

@Composable
fun AudioShareScreen(
    vm: AudioShareViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenSeparateAppSound: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsState()
    var pick by remember { mutableStateOf<Pick?>(null) }

    val subtitle = stringResource(
        when {
            !s.btOn -> R.string.audio_share_subtitle_bt_off
            s.sharing -> R.string.audio_share_subtitle_active
            s.starting -> R.string.audio_share_status_starting_title
            s.masterOn -> R.string.audio_share_status_idle_title
            else -> R.string.audio_share_subtitle_off
        }
    )
    val master = s.masterOn
    val ready = s.sharingReady && !s.starting && !s.stopping
    val lipsyncReady = ready && s.connected.isNotEmpty()
    val bothHeadsets = s.primary != null && s.secondary != null

    SettingsScreen(
        title = stringResource(R.string.audio_share_title),
        subtitle = subtitle,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    ) {
        item(key = "hero") {
            StaggerIn(0) { SharingHero(s, ready && bothHeadsets, vm::toggleSharing) }
        }

        if (!s.btOn) {
            item(key = "bt-off") {
                SettingsGroup { item("bt-off-note") { p -> NoteRow(p, stringResource(R.string.audio_share_bt_off_hint)) } }
            }
        }

        item(key = "master") {
            StaggerIn(1) {
                SettingsGroup {
                    item("audio_share_master_switch") { p ->
                        SwitchRow(
                            p, Symbol.GRAPHIC_EQ, stringResource(R.string.audio_share_enable),
                            stringResource(R.string.audio_share_enable_summary),
                            master, vm::setMaster, enabled = s.btOn && !s.offloadSwitching,
                        )
                    }
                }
            }
        }

        item(key = "devices") {
            StaggerIn(2) {
                SettingsGroup(stringResource(R.string.audio_share_devices_category), Modifier.animateContentSize()) {
                    item("primary_device_info") { p ->
                        HeadsetControls(
                            p, stringResource(R.string.audio_share_primary_device), s.primary,
                            stringResource(R.string.audio_share_no_primary_device),
                            s.primaryPercent, ready, ready && s.primary != null, s.resetTick,
                            vm::setPrimaryVolume,
                            onSelect = { if (vm.onPrimaryClicked()) pick = Pick.Primary },
                        )
                    }
                    item("secondary_device_select") { p ->
                        HeadsetControls(
                            p, stringResource(R.string.audio_share_secondary_device), s.secondary,
                            stringResource(R.string.audio_share_secondary_device_summary),
                            s.secondaryPercent, ready && !s.sharing && s.candidates.isNotEmpty(),
                            ready && s.sharing, s.resetTick, vm::setSecondaryVolume,
                            onSelect = { pick = Pick.Secondary },
                        )
                    }
                    item("audio_share_swap_roles") { p ->
                        SwapRow(
                            p, stringResource(R.string.audio_share_swap_roles),
                            stringResource(R.string.audio_share_swap_roles_summary),
                            enabled = ready && bothHeadsets, onClick = vm::swapRoles,
                        )
                    }
                }
            }
        }

        item(key = "volume") {
            StaggerIn(3) {
                SettingsGroup(stringResource(R.string.audio_share_volume_category)) {
                    item("audio_share_sync_volumes") { p ->
                        SwitchRow(
                            p, Symbol.LINK, stringResource(R.string.audio_share_sync_volumes),
                            stringResource(R.string.audio_share_sync_volumes_summary),
                            s.syncVolumes, vm::setSyncVolumes, enabled = ready && s.sharing,
                        )
                    }
                }
            }
        }

        item(key = "lipsync") {
            StaggerIn(4) {
                SettingsGroup(stringResource(R.string.audio_share_lipsync_category), Modifier.animateContentSize()) {
                    item("audio_share_lipsync") { p ->
                        SwitchRow(
                            p, Symbol.SYNC, stringResource(R.string.audio_share_lipsync_title),
                            stringResource(R.string.audio_share_lipsync_summary),
                            s.lipsyncOn, vm::setLipsync, enabled = lipsyncReady,
                        )
                    }
                    item("audio_share_lipsync_mode") { p ->
                        NavRow(
                            p, Symbol.TUNE, stringResource(R.string.audio_share_lipsync_mode_title),
                            s.lipsyncModeLabel + "\n" + s.lipsyncDetail,
                            onClick = { pick = Pick.LipsyncMode }, enabled = lipsyncReady && s.lipsyncOn,
                        )
                    }
                    if (s.lipsyncOn && s.primary != null) {
                        item("audio_share_adj_main") { p ->
                            ValueSliderRow(
                                p, Symbol.TIMER,
                                stringResource(R.string.audio_share_adj_title, s.primary!!.name),
                                "${s.adjMain} ms", s.adjMain, 300,
                                enabled = lipsyncReady, onValueChange = { vm.setAdjust(true, it) },
                                resetKey = s.resetTick, hapticEvery = 50,
                            )
                        }
                    }
                    if (s.lipsyncOn && s.secondary != null) {
                        item("audio_share_adj_second") { p ->
                            ValueSliderRow(
                                p, Symbol.TIMER,
                                stringResource(R.string.audio_share_adj_title, s.secondary!!.name),
                                "${s.adjSecond} ms", s.adjSecond, 300,
                                enabled = lipsyncReady, onValueChange = { vm.setAdjust(false, it) },
                                resetKey = s.resetTick, hapticEvery = 50,
                            )
                        }
                    }
                }
            }
        }

        item(key = "sound") {
            StaggerIn(5) {
                SettingsGroup(stringResource(R.string.audio_share_sound_category)) {
                    item("audio_share_codec") { p ->
                        NavRow(
                            p, Symbol.MEMORY, stringResource(R.string.audio_share_codec_title),
                            listOfNotNull(s.codecChoiceLabel, s.codecResult).joinToString("\n"),
                            onClick = { pick = Pick.Codec }, enabled = master,
                        )
                    }
                    item("separate_app_sound_pref") { p ->
                        NavRow(
                            p, Symbol.APPS, stringResource(R.string.separate_app_sound_title),
                            stringResource(R.string.separate_app_sound_summary),
                            onClick = onOpenSeparateAppSound, enabled = ready,
                        )
                    }
                    item(DeviceKeys.APTX_LOW_LATENCY) { p ->
                        SwitchRow(
                            p, Symbol.GRAPHIC_EQ, stringResource(R.string.aptx_adaptive_low_latency_title),
                            stringResource(R.string.aptx_adaptive_low_latency_summary),
                            s.aptxLowLatency, vm::setAptxLowLatency, enabled = s.aptxAvailable,
                        )
                    }
                }
            }
        }

        item(key = "advanced") {
            StaggerIn(6) {
                SettingsGroup(stringResource(R.string.audio_share_hardware_category)) {
                    item("bluetooth_a2dp_hw_offload") { p ->
                        SwitchRow(
                            p, Symbol.DEVELOPER_BOARD, stringResource(R.string.bluetooth_a2dp_hw_offload_title),
                            stringResource(
                                if (s.offloadOn) R.string.bluetooth_a2dp_hw_offload_summary_on
                                else R.string.bluetooth_a2dp_hw_offload_summary_off
                            ),
                            s.offloadOn, vm::setHardwareOffload, enabled = s.btOn && !s.offloadSwitching,
                        )
                    }
                    item("hardware_engine_info") { p -> EngineCard(p, s.engine, s.offloadSwitching) }
                    item("audio_share_info") { p -> NoteRow(p, stringResource(R.string.audio_share_desc_info)) }
                }
            }
        }
    }

    when (pick) {
        Pick.Primary -> ChoiceSheet(
            title = stringResource(R.string.audio_share_primary_device),
            choices = s.connected.map { Choice(it.address, "${it.name} (${it.address})") },
            selected = s.primary?.address,
            onSelect = { pick = null; vm.selectPrimary(it) },
            onDismiss = { pick = null },
        )
        Pick.Secondary -> {
            val suffix = stringResource(R.string.audio_share_connected_suffix)
            ChoiceSheet(
                title = stringResource(R.string.audio_share_pick_secondary),
                choices = s.candidates.map { Choice(it.address, deviceLine(it) + " ($suffix)") },
                selected = s.secondary?.address,
                onSelect = { pick = null; vm.selectSecondary(it) },
                onDismiss = { pick = null },
            )
        }
        Pick.Codec -> {
            val entries = stringArrayResource(R.array.audio_share_codec_entries)
            val values = stringArrayResource(R.array.audio_share_codec_values)
            ChoiceSheet(
                title = stringResource(R.string.audio_share_pick_codec),
                choices = values.mapIndexed { i, v -> Choice(v, entries.getOrElse(i) { v }) },
                selected = s.codecChoice,
                onSelect = { pick = null; vm.setSharingCodec(it) },
                onDismiss = { pick = null },
            )
        }
        Pick.LipsyncMode -> {
            val entries = stringArrayResource(R.array.audio_share_lipsync_mode_entries)
            val values = stringArrayResource(R.array.audio_share_lipsync_mode_values)
            ChoiceSheet(
                title = stringResource(R.string.audio_share_pick_lipsync_mode),
                choices = values.mapIndexed { i, v -> Choice(v, entries.getOrElse(i) { v }) },
                selected = s.lipsyncMode,
                onSelect = { pick = null; vm.setLipsyncMode(it) },
                onDismiss = { pick = null },
            )
        }
        null -> Unit
    }
}

/** Controls stay with their headset, and state comes from accepted volume feedback. */
@Composable
private fun HeadsetControls(
    position: GroupPosition,
    title: String,
    headset: HeadsetUi?,
    fallback: String,
    percent: Int,
    canSelect: Boolean,
    canAdjust: Boolean,
    resetKey: Int,
    onVolume: (Int) -> Unit,
    onSelect: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var restoreVolume by rememberSaveable(headset?.address) { mutableIntStateOf(50) }
    Column(Modifier.animateContentSize()) {
        NavRow(
            position, Symbol.HEADPHONES, title, deviceSummary(headset, fallback),
            onClick = { expanded = !expanded }, expanded = expanded,
        )
        AnimatedVisibility(expanded) {
            Column {
                GroupCard(GroupPosition.Middle) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = onSelect, enabled = canSelect, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.audio_share_change_device))
                        }
                        FilledTonalIconToggleButton(
                            checked = percent == 0, enabled = canAdjust,
                            onCheckedChange = { mute ->
                                if (mute) { restoreVolume = percent; onVolume(0) }
                                else onVolume(restoreVolume.coerceIn(1, 100))
                            },
                        ) {
                            AnimatedSymbol(
                                if (percent == 0) Symbol.VOLUME_OFF else Symbol.VOLUME_UP,
                                active = percent == 0,
                                contentDescription = stringResource(
                                    if (percent == 0) R.string.audio_share_unmute else R.string.audio_share_mute),
                            )
                        }
                    }
                }
                ValueSliderRow(
                    GroupPosition.Last, if (percent == 0) Symbol.VOLUME_OFF else Symbol.VOLUME_UP,
                    stringResource(R.string.audio_share_volume_category), "$percent%", percent, 100,
                    enabled = canAdjust, onValueChange = onVolume, resetKey = resetKey,
                )
            }
        }
    }
}

private fun deviceLine(h: HeadsetUi): String =
    if (h.battery != null) "${h.name} • ${h.battery}%" else h.name

/** Name • battery, then the live codec line; [fallback] when there is no headset. */
private fun deviceSummary(h: HeadsetUi?, fallback: String): String {
    if (h == null) return fallback
    val codec = h.codec?.let { if (it.detail.isEmpty()) it.name else it.name + " • " + it.detail }
    return listOfNotNull(deviceLine(h), codec).joinToString("\n")
}

/** Status hero: morphing badge, both headsets, the one big button. */
@Composable
private fun SharingHero(s: AudioShareUiState, canStart: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val primName = s.primary?.name
    val secName = s.secondary?.name
    val title = stringResource(
        when {
            s.stopping -> R.string.audio_share_stopping
            s.sharing -> R.string.audio_share_status_active_title
            s.starting -> R.string.audio_share_status_starting_title
            else -> R.string.audio_share_status_idle_title
        }
    )
    val summary = when {
        s.sharing -> stringResource(R.string.audio_share_status_active, primName ?: "?", secName ?: "?")
        s.starting -> stringResource(R.string.audio_share_status_starting)
        primName == null -> stringResource(R.string.audio_share_no_primary_device)
        secName == null -> stringResource(R.string.audio_share_pick_second)
        else -> stringResource(R.string.audio_share_ready, primName, secName)
    }
    val polygon = when {
        s.sharing -> MaterialShapes.SoftBurst
        s.starting -> MaterialShapes.Cookie9Sided
        else -> MaterialShapes.Circle
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(scheme.surfaceContainerHigh)
            .padding(16.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MorphBadge(polygon, Symbol.GRAPHIC_EQ, spinning = s.sharing || s.starting)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmallEmphasized, color = scheme.onSurface)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
        if (s.primary != null && s.secondary != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeadsetChip(s.primary, Modifier.weight(1f))
                HeadsetChip(s.secondary, Modifier.weight(1f))
            }
        }
        Button(
            onClick = onToggle,
            enabled = !s.stopping && if (s.sharing || s.starting) s.sharingReady else canStart,
            shapes = ButtonDefaults.shapes(),
            colors = if (s.sharing) {
                ButtonDefaults.buttonColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer)
            } else ButtonDefaults.buttonColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (s.stopping) {
                LoadingIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.audio_share_stopping), Modifier.padding(start = 8.dp))
            } else {
                SymbolIcon(if (s.sharing || s.starting) Symbol.CLOSE else Symbol.PLAY_ARROW, size = 20.dp)
                Text(
                    stringResource(if (s.sharing || s.starting) R.string.audio_share_stop_action else R.string.audio_share_start_action),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun HeadsetChip(h: HeadsetUi, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(h.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            h.codec?.let { CodecPill(it.name) }
            h.battery?.let {
                SymbolIcon(Symbol.BATTERY_SAVER, size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$it%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The old plain-text "hardware engine" blob as a small table. */
@Composable
private fun EngineCard(position: GroupPosition, e: EngineInfo, busy: Boolean) {
    val none = stringResource(R.string.common_none)
    val on = stringResource(R.string.common_on)
    val off = stringResource(R.string.common_off)
    GroupCard(position) {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) LoadingIndicator(Modifier.size(28.dp))
                else SymbolIcon(Symbol.INFO, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.audio_share_engine_title), style = MaterialTheme.typography.titleMedium)
            }
            EngineLine(
                stringResource(R.string.audio_share_engine_label),
                stringResource(if (e.offloadDisabled) R.string.audio_share_engine_sw else R.string.audio_share_engine_hw),
            )
            EngineLine(stringResource(R.string.audio_share_engine_prop), e.offloadDisabled.toString())
            EngineLine(stringResource(R.string.audio_share_engine_sharing), if (e.sharingEnabled) on else off)
            EngineLine(stringResource(R.string.audio_share_engine_codec), e.sharingCodec)
            EngineLine(
                stringResource(R.string.audio_share_engine_state),
                stringResource(if (e.sharingActive) R.string.audio_share_engine_state_active else R.string.audio_share_status_standby),
            )
            EngineLine(stringResource(R.string.audio_share_engine_primary), e.primaryCodec ?: none)
            EngineLine(stringResource(R.string.audio_share_engine_secondary), e.secondaryCodec ?: none)
        }
    }
}

@Composable
private fun EngineLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.45f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(0.55f))
    }
}

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

package org.lineageos.device.DeviceSettings.ui.powertools

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.lineageos.device.DeviceSettings.R
import org.lineageos.device.DeviceSettings.powertools.PowerProfileUtil
import org.lineageos.device.DeviceSettings.ui.components.Choice
import org.lineageos.device.DeviceSettings.ui.components.ChoiceSheet
import org.lineageos.device.DeviceSettings.ui.components.FrequencyRange
import org.lineageos.device.DeviceSettings.ui.components.GroupCard
import org.lineageos.device.DeviceSettings.ui.components.GroupPosition
import org.lineageos.device.DeviceSettings.ui.components.LiveMeter
import org.lineageos.device.DeviceSettings.ui.components.NavRow
import org.lineageos.device.DeviceSettings.ui.components.NoteRow
import org.lineageos.device.DeviceSettings.ui.components.ProfilePicker
import org.lineageos.device.DeviceSettings.ui.components.Profiles
import org.lineageos.device.DeviceSettings.ui.components.SettingsGroup
import org.lineageos.device.DeviceSettings.ui.components.SettingsScreen
import org.lineageos.device.DeviceSettings.ui.components.StaggerIn
import org.lineageos.device.DeviceSettings.ui.components.StatChip
import org.lineageos.device.DeviceSettings.ui.components.SwitchRow
import org.lineageos.device.DeviceSettings.ui.components.ValuePill
import org.lineageos.device.DeviceSettings.ui.components.containerTransform
import org.lineageos.device.DeviceSettings.ui.components.freqLabel
import org.lineageos.device.DeviceSettings.ui.components.mhzFromHz
import org.lineageos.device.DeviceSettings.ui.components.mhzFromKhz
import org.lineageos.device.DeviceSettings.ui.main.POWERTOOLS_CONTAINER
import org.lineageos.device.DeviceSettings.ui.symbols.Symbol
import org.lineageos.device.DeviceSettings.ui.symbols.SymbolIcon

private class SheetSpec(val title: String, val state: ChoiceState)

@Composable
fun PowertoolsScreen(
    vm: PowertoolsViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsState()
    var sheet by remember { mutableStateOf<SheetSpec?>(null) }
    var showLiveSheet by remember { mutableStateOf(false) }
    val locked = s.applying
    val manualOk = s.manualAllowed && !locked

    val modeLabel = Profiles.firstOrNull { it.mode == s.mode }?.let { stringResource(it.labelRes) }
        ?: stringResource(R.string.powerprofile_mode_unknown)

    val governorTitle = stringResource(R.string.powertools_pick_governor)
    val schedulerTitle = stringResource(R.string.powertools_pick_scheduler)
    val tcpTitle = stringResource(R.string.powertools_pick_tcp)

    SettingsScreen(
        title = stringResource(R.string.Powertools_title),
        subtitle = modeLabel,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier.containerTransform(POWERTOOLS_CONTAINER),
    ) {
        // ---- profile ---------------------------------------------------------------------
        item(key = "profile") {
            StaggerIn(0) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(32.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(16.dp)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            stringResource(R.string.powerprofile_select_mode_title),
                            style = MaterialTheme.typography.titleMediumEmphasized,
                        )
                        Text(
                            stringResource(s.statusRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ProfilePicker(
                        selectedMode = s.mode,
                        pendingMode = s.pendingMode,
                        enabled = !locked,
                        onSelect = vm::setMode,
                    )
                }
            }
        }

        // ---- live hardware ----------------------------------------------------------------
        item(key = "live") {
            StaggerIn(1) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(
                            role = Role.Button,
                            onClick = {
                                vm.startTelemetry()
                                showLiveSheet = true
                            },
                        )
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SymbolIcon(Symbol.SPEED, tint = MaterialTheme.colorScheme.primary, size = 24.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.live_section_title),
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            stringResource(R.string.live_section_summary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SymbolIcon(Symbol.CHEVRON_RIGHT, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (!s.manualAllowed && s.loaded) {
            item(key = "manual-hint") {
                SettingsGroup {
                    item("manual-hint-row") { p -> NoteRow(p, stringResource(R.string.powertools_manual_hint)) }
                }
            }
        }

        // ---- CPU --------------------------------------------------------------------------
        item(key = "cpu") {
            StaggerIn(2) {
                SettingsGroup(stringResource(R.string.powertools_cpu_enable_title)) {
                    item("cpu_enable") { p ->
                        SwitchRow(
                            p, Symbol.MEMORY, stringResource(R.string.powertools_cpu_enable_title),
                            stringResource(R.string.powertools_cpu_enable_summary),
                            s.cpuEnabled && s.manualAllowed, { vm.setManualEnabled("cpu_enable", it) }, manualOk,
                        )
                    }
                    s.clusters.forEach { c ->
                        item("cluster_${c.id}") { p ->
                            FreqCard(
                                position = p,
                                title = stringResource(c.titleRes),
                                nowText = c.currentKhz?.let { mhzFromKhz(it) },
                                freq = c.freq,
                                governor = c.governor,
                                governorTitle = stringResource(R.string.powertools_governor),
                                onCommit = vm::commitRange,
                                onGovernor = { gov -> sheet = SheetSpec(governorTitle, gov) },
                            )
                        }
                    }
                }
            }
        }

        // ---- GPU --------------------------------------------------------------------------
        item(key = "gpu") {
            StaggerIn(3) {
                SettingsGroup(stringResource(R.string.powertools_gpu_category_title)) {
                    item("gpu_enable") { p ->
                        SwitchRow(
                            p, Symbol.VIDEOGAME_ASSET, stringResource(R.string.powertools_gpu_enable_title),
                            stringResource(R.string.powertools_gpu_enable_summary),
                            s.gpuEnabled && s.manualAllowed, { vm.setManualEnabled("gpu_enable", it) }, manualOk,
                        )
                    }
                    item("gpu_controls") { p ->
                        FreqCard(
                            position = p,
                            title = stringResource(R.string.live_gpu),
                            nowText = s.telemetry?.gpuHz?.let { mhzFromHz(it) },
                            freq = s.gpuFreq,
                            governor = s.gpuGovernor,
                            governorTitle = stringResource(R.string.gpu_governor),
                            onCommit = vm::commitRange,
                            onGovernor = { gov -> sheet = SheetSpec(governorTitle, gov) },
                        )
                    }
                }
            }
        }

        // ---- storage ----------------------------------------------------------------------
        item(key = "storage") {
            StaggerIn(4) {
                SettingsGroup(stringResource(R.string.storage_category_title)) {
                    item("storage_enable") { p ->
                        SwitchRow(
                            p, Symbol.HARD_DRIVE, stringResource(R.string.storage_enable_title),
                            null,
                            s.storageEnabled && s.manualAllowed, { vm.setManualEnabled("storage_enable", it) }, manualOk,
                        )
                    }
                    s.io?.let { io ->
                        item("io_scheduler") { p ->
                            NavRow(
                                p, Symbol.STORAGE, stringResource(R.string.io_scheduler_title), null,
                                onClick = { sheet = SheetSpec(schedulerTitle, io) },
                                enabled = io.enabled && !locked, trailingText = io.selectedLabel,
                            )
                        }
                    }
                }
            }
        }

        // ---- network ----------------------------------------------------------------------
        s.tcp?.let { tcp ->
            item(key = "network") {
                StaggerIn(5) {
                    SettingsGroup(stringResource(R.string.network_kernel_category_title)) {
                        item("tcp_congestion_control") { p ->
                            NavRow(
                                p, Symbol.LAN, stringResource(R.string.tcp_congestion_title), null,
                                onClick = { sheet = SheetSpec(tcpTitle, tcp) },
                                enabled = !locked, trailingText = tcp.selectedLabel,
                            )
                        }
                    }
                }
            }
        }
    }

    sheet?.let { spec ->
        ChoiceSheet(
            title = spec.title,
            choices = spec.state.choices,
            selected = spec.state.selected,
            onSelect = { value ->
                sheet = null
                vm.setValue(spec.state.key, value)
            },
            onDismiss = { sheet = null },
        )
    }

    if (showLiveSheet) {
        LiveHardwareSheet(
            s = s,
            onDismiss = {
                showLiveSheet = false
                vm.stopTelemetry()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveHardwareSheet(
    s: PowertoolsUiState,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.live_section_title),
                style = MaterialTheme.typography.titleLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val t = s.telemetry
            s.clusters.forEach { c ->
                val label = stringResource(
                    when (c.id) {
                        "little" -> R.string.live_cpu_little
                        "big" -> R.string.live_cpu_big
                        else -> R.string.live_cpu_prime
                    }
                )
                val cur = when (c.id) {
                    "little" -> t?.littleKhz
                    "big" -> t?.bigKhz
                    else -> t?.primeKhz
                }
                val max = c.freq?.freqs?.lastOrNull()
                LiveMeter(
                    label,
                    cur?.let { mhzFromKhz(it) } ?: stringResource(R.string.live_unavailable),
                    if (cur != null && max != null && max > 0) cur.toFloat() / max else null,
                )
            }
            val gpuMax = s.gpuFreq?.freqs?.lastOrNull()
            LiveMeter(
                stringResource(R.string.live_gpu),
                t?.gpuHz?.let { mhzFromHz(it) } ?: stringResource(R.string.live_unavailable),
                t?.gpuHz?.let { hz -> if (gpuMax != null && gpuMax > 0) hz.toFloat() / gpuMax else null },
            )
            if (t != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    t.batteryTempC?.let {
                        StatChip(Symbol.THERMOSTAT, stringResource(R.string.live_battery_temp, "%.1f".format(it)))
                    }
                    t.batteryPercent?.let {
                        StatChip(
                            if (t.charging) Symbol.BATTERY_CHARGING_FULL else Symbol.BATTERY_SAVER,
                            stringResource(R.string.live_battery_level, it),
                        )
                    }
                }
            }
        }
    }
}

/** Range slider + governor row for one CPU cluster or the GPU. */
@Composable
private fun FreqCard(
    position: GroupPosition,
    title: String,
    nowText: String?,
    freq: FreqState?,
    governor: ChoiceState?,
    governorTitle: String,
    onCommit: (FreqState, Int, Int) -> Unit,
    onGovernor: (ChoiceState) -> Unit,
) {
    val enabled = freq?.enabled ?: governor?.enabled ?: false
    GroupCard(position, enabled = enabled) {
        Column(
            Modifier.padding(16.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (nowText != null) ValuePill(stringResource(R.string.powertools_cluster_state, nowText))
            }
            if (freq != null) {
                FrequencyRange(
                    freqs = freq.freqs,
                    minIndex = freq.minIndex,
                    maxIndex = freq.maxIndex,
                    lowestMin = freq.lowestMin,
                    highestMax = freq.highestMax,
                    enabled = freq.enabled,
                    format = ::freqLabel,
                    onCommit = { lo, hi -> onCommit(freq, lo, hi) },
                )
                freq.limitNote?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SymbolIcon(Symbol.INFO, size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (governor != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .let { m -> if (governor.enabled) m.clickableRow { onGovernor(governor) } else m }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SymbolIcon(Symbol.TUNE, size = 20.dp, tint = MaterialTheme.colorScheme.primary)
                    Text(governorTitle, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(governor.selectedLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    SymbolIcon(Symbol.EXPAND_MORE, size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

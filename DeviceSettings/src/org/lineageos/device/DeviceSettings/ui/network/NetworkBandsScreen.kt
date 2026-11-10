/*
 * Copyright (C) 2025 Lunaris Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package org.lineageos.device.DeviceSettings.ui.network

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.lineageos.device.DeviceSettings.R
import org.lineageos.device.DeviceSettings.network.BandUi
import org.lineageos.device.DeviceSettings.network.DiagMetric
import org.lineageos.device.DeviceSettings.network.NetworkBandsUiState
import org.lineageos.device.DeviceSettings.network.NetworkBandsViewModel
import org.lineageos.device.DeviceSettings.ui.components.Choice
import org.lineageos.device.DeviceSettings.ui.components.ChoiceSheet
import org.lineageos.device.DeviceSettings.ui.components.GroupCard
import org.lineageos.device.DeviceSettings.ui.components.GroupPosition
import org.lineageos.device.DeviceSettings.ui.components.LiveMeter
import org.lineageos.device.DeviceSettings.ui.components.MorphBadge
import org.lineageos.device.DeviceSettings.ui.components.NavRow
import org.lineageos.device.DeviceSettings.ui.components.NoteRow
import org.lineageos.device.DeviceSettings.ui.components.SettingsGroup
import org.lineageos.device.DeviceSettings.ui.components.SettingsScreen
import org.lineageos.device.DeviceSettings.ui.components.StaggerIn
import org.lineageos.device.DeviceSettings.ui.components.SwitchRow
import org.lineageos.device.DeviceSettings.ui.symbols.AnimatedSymbol
import org.lineageos.device.DeviceSettings.ui.symbols.Symbol
import org.lineageos.device.DeviceSettings.ui.symbols.SymbolIcon
import org.lineageos.device.DeviceSettings.ui.theme.Motion

private enum class Dlg { Apply, Reset, Save, Advanced, CarrierPicker, RatPicker }

private val PcellGreen = Color(0xFF4ADE80)
private val ScellBlue = Color(0xFF38BDF8)
private val TrendRed = Color(0xFFF87171)

@Composable
fun NetworkBandsScreen(
    vm: NetworkBandsViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsState()
    var dialog by remember { mutableStateOf<Dlg?>(null) }
    var diagOpen by remember { mutableStateOf(false) }
    val generations = listOf("5G Bands", "4G Bands", "3G Bands", "2G Bands")

    SettingsScreen(
        title = stringResource(R.string.network_bands_title),
        subtitle = if (s.diag?.hasService == true) s.tech + " • " + s.carrierName else null,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        itemSpacing = 2.dp,
        modifier = modifier,
        actions = {
            IconButton(onClick = { dialog = Dlg.Advanced }) {
                SymbolIcon(Symbol.SETTINGS, contentDescription = "Settings")
            }
        },
        bottomBar = {
            ApplyBar(
                selected = s.totalChecked,
                canApply = s.modified,
                canReset = s.available,
                onApply = { if (s.totalChecked == 0) vm.applyBands() else dialog = Dlg.Apply },
                onReset = { dialog = Dlg.Reset },
            )
        },
    ) {
        item(key = "status") {
            StaggerIn(0, Modifier.padding(bottom = 18.dp)) { StatusCard(s) }
        }

        if (s.sims.size > 1) {
            item(key = "sims") {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    s.sims.forEach { sim ->
                        FilterChip(
                            selected = sim.subId == s.currentSubId,
                            onClick = { vm.selectSim(sim.subId) },
                            label = { Text(sim.label) },
                        )
                    }
                }
            }
        }

        item(key = "profile") {
            StaggerIn(1, Modifier.padding(bottom = 18.dp)) {
                SettingsGroup("Profile Selection") {
                    item("carrier_preset") { p ->
                        NavRow(
                            p, Symbol.TUNE, "Carrier profile",
                            null,
                            onClick = { dialog = Dlg.CarrierPicker },
                            enabled = s.available && !s.is2gOnly,
                            trailingText = s.carrierPresets.getOrNull(s.carrierPreset)?.substringBefore(" (") ?: "",
                        )
                    }
                    item("summary") { p -> SummaryCard(p, s) }
                }
            }
        }

        item(key = "diag") {
            Column(Modifier.padding(bottom = 18.dp)) {
                SettingsGroup {
                    item("diag_toggle") { p ->
                        GroupCard(p, onClick = { diagOpen = !diagOpen }, role = Role.Button) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                AnimatedSymbol(Symbol.MONITORING, active = diagOpen, tint = MaterialTheme.colorScheme.primary)
                                Text(
                                    "Advanced Telephony Diagnostics",
                                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                                )
                                SymbolIcon(if (diagOpen) Symbol.EXPAND_LESS else Symbol.EXPAND_MORE)
                            }
                        }
                    }
                }
                AnimatedVisibility(diagOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    DiagnosticsCard(s)
                }
            }
        }

        if (s.rats.g5) {
            item(key = "nr") {
                StaggerIn(2, Modifier.padding(bottom = 18.dp)) { NrModeCard(s, vm::setNrMode) }
            }
        }

        item(key = "rat") {
            StaggerIn(3, Modifier.padding(bottom = 18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("Manual Band Lock")
                    RatCard(s, vm::toggleRat) { dialog = Dlg.RatPicker }
                }
            }
        }

        item(key = "tabs") {
            GenerationTabs(s, generations, vm::selectGeneration, Modifier.padding(bottom = 8.dp))
        }

        item(key = "band-header") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Selected " + generations[s.generation].replace(" Bands", "") + " Bands  " + s.genSelected + "/" + s.genTotal,
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { dialog = Dlg.Save }, enabled = s.available) { Text("Save Profile") }
                TextButton(onClick = vm::clearGeneration, enabled = s.available) { Text("Clear") }
            }
        }

        items(s.bands, key = { it.key }) { band ->
            val i = s.bands.indexOf(band)
            BandRow(
                position = when {
                    s.bands.size == 1 -> GroupPosition.Single
                    i == 0 -> GroupPosition.First
                    i == s.bands.lastIndex -> GroupPosition.Last
                    else -> GroupPosition.Middle
                },
                band = band, enabled = s.available, onToggle = { vm.toggleBand(band.key) },
            )
        }

        item(key = "band-note") {
            Column(Modifier.padding(top = 12.dp, bottom = 12.dp)) {
                SettingsGroup { item("band_note") { p -> NoteRow(p, Glossary.BAND_NOTE) } }
                Text(
                    s.status, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }

    when (dialog) {
        Dlg.Apply -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.network_bands_dialog_title)) },
            text = { Text(stringResource(R.string.network_bands_dialog_message, s.totalChecked)) },
            confirmButton = {
                TextButton(onClick = { dialog = null; vm.applyBands() }) { Text(stringResource(R.string.network_bands_apply)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
        Dlg.Reset -> {
            // The destructive button stays locked for 3 seconds, as before
            var left by remember { mutableIntStateOf(3) }
            LaunchedEffect(Unit) {
                while (left > 0) {
                    delay(1000)
                    left--
                }
            }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Reset Band Locking") },
                text = { Text(Glossary.RESET_MESSAGE) },
                confirmButton = {
                    TextButton(
                        enabled = left == 0,
                        onClick = { dialog = null; vm.resetBands() },
                    ) {
                        Text(if (left > 0) "Reset (${left}s)" else "Reset", color = TrendRed.copy(alpha = if (left == 0) 1f else 0.5f))
                    }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(android.R.string.cancel)) } },
            )
        }
        Dlg.Save -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Save Custom Band Profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Save your currently selected bands as a quick preset profile for this SIM:")
                    FilledTonalButton(
                        onClick = { dialog = null; vm.saveCustomProfile(true) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save as Recommended Profile") }
                    Text("Overrides carrier recommended preset with your custom bands", style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(
                        onClick = { dialog = null; vm.saveCustomProfile(false) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save as Battery Saver Profile") }
                    Text("Overrides battery saver preset with your low-band FDD anchors", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
        Dlg.CarrierPicker -> ChoiceSheet(
            title = "Carrier profile",
            choices = s.carrierPresets.mapIndexed { i, name -> Choice(i.toString(), name) },
            selected = s.carrierPreset.toString(),
            onSelect = { dialog = null; vm.selectCarrierPreset(it.toInt()) },
            onDismiss = { dialog = null },
        )
        Dlg.RatPicker -> ChoiceSheet(
            title = "RAT preference",
            choices = Glossary.RAT_PRESETS.mapIndexed { i, name -> Choice(i.toString(), name) },
            selected = s.ratPreset.toString(),
            onSelect = { dialog = null; vm.applyRatPreset(it.toInt()) },
            onDismiss = { dialog = null },
        )
        Dlg.Advanced -> AdvancedSheet(s, vm::setVoNr) { dialog = null }
        null -> Unit
    }

    if (s.showNonIndiaPopup) {
        var left by remember { mutableIntStateOf(5) }
        LaunchedEffect(Unit) {
            while (left > 0) {
                delay(1000)
                left--
            }
        }
        AlertDialog(
            onDismissRequest = {},
            title = { Text(Glossary.NON_INDIA_TITLE) },
            text = { Text(Glossary.NON_INDIA_MESSAGE) },
            confirmButton = {
                TextButton(enabled = left == 0, onClick = vm::dismissNonIndiaPopup) {
                    Text(if (left > 0) "Got it (${left}s)" else "Got it")
                }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp),
    )
}

@Composable
private fun ApplyBar(selected: Int, canApply: Boolean, canReset: Boolean, onApply: () -> Unit, onReset: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Selected Bands: $selected", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onReset, enabled = canReset, shapes = ButtonDefaults.shapes()) { Text("Reset") }
                Button(
                    onClick = onApply, enabled = canApply, shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.network_bands_apply)) }
            }
        }
    }
}

@Composable
private fun StatusCard(s: NetworkBandsUiState) {
    val scheme = MaterialTheme.colorScheme
    val d = s.diag
    val tech = s.tech
    val polygon = when {
        d?.hasService != true -> MaterialShapes.Circle
        tech.startsWith("5G") -> MaterialShapes.Cookie9Sided
        tech.startsWith("4G") -> MaterialShapes.Clover4Leaf
        else -> MaterialShapes.Pentagon
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(scheme.surfaceContainerHigh)
            .padding(16.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MorphBadge(polygon, Symbol.CELL_TOWER, spinning = d?.hasService == true, size = 64.dp)
            Column(Modifier.weight(1f)) {
                Text(tech, style = MaterialTheme.typography.headlineSmallEmphasized, color = scheme.onSurface)
                Text(
                    listOfNotNull(s.carrierName.takeIf { it.isNotEmpty() }, d?.signalQuality?.takeIf { it != "--" }).joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                )
            }
            if (d?.hasService == true) Pill("Connected", scheme.primaryContainer, scheme.onPrimaryContainer)
        }
        val rsrp = d?.rsrp?.text?.substringBefore(' ')?.toIntOrNull()
        LiveMeter("Signal strength", d?.rsrp?.text ?: "--", if (rsrp != null) ((rsrp + 140) / 70f) else null)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("RSRP", d?.rsrp, Modifier.weight(1f))
            Metric("SINR", d?.sinr, Modifier.weight(1f))
            Metric("CQI", d?.cqi, Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Carrier Aggregation", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            Text(d?.caDisplay ?: "--", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.activeBands.split("\n").forEach { line ->
                val tint = if (line.contains("PCell")) PcellGreen else if (line.contains("SCell")) ScellBlue else scheme.onSurfaceVariant
                Pill(line, tint.copy(alpha = 0.16f), tint)
            }
        }
    }
}

@Composable
private fun Pill(text: String, container: Color, content: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(container).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

@Composable
private fun Metric(label: String, metric: DiagMetric?, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(scheme.surfaceContainerHighest).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(metric?.text ?: "--", style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            when {
                (metric?.trend ?: 0) > 0 -> Text(" ▲", color = PcellGreen)
                (metric?.trend ?: 0) < 0 -> Text(" ▼", color = TrendRed)
            }
        }
    }
}

@Composable
private fun SummaryCard(position: GroupPosition, s: NetworkBandsUiState) {
    GroupCard(position) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Band lock", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            KeyValue("Profile", s.profileLabel)
            KeyValue("Band Lock", if (s.bandLockEnabled) "Enabled" else "Default")
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DiagnosticsCard(s: NetworkBandsUiState) {
    val sections = s.diag?.sections.orEmpty()
    Column(
        Modifier.fillMaxWidth().padding(top = 2.dp).clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (sections.isEmpty()) Text("Collecting…", style = MaterialTheme.typography.bodyMedium)
        sections.forEach { section ->
            Text(section.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            section.rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.45f))
                    Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(0.55f))
                }
            }
        }
    }
}

@Composable
private fun NrModeCard(s: NetworkBandsUiState, onMode: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val labels = listOf("NSA Only", "Auto (SA+NSA)", "SA Only")
    val dot by animateColorAsState(if (s.nrActive) PcellGreen else scheme.outline, Motion.effects(), label = "nrDot")
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (s.available) 1f else 0.4f)
            .clip(RoundedCornerShape(28.dp))
            .background(scheme.surfaceContainerHigh)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("5G Network Mode", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = s.nrMode == i,
                    onClick = { onMode(i) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = labels.size),
                    enabled = s.available,
                    label = { Text(label, maxLines = 1) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Text(s.nrActiveText, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RatCard(s: NetworkBandsUiState, onToggle: (Int) -> Unit, onPreset: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (s.available) 1f else 0.4f)
            .clip(RoundedCornerShape(28.dp))
            .background(scheme.surfaceContainerHigh)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("RAT Preference", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("5G" to s.rats.g5, "4G" to s.rats.g4, "3G" to s.rats.g3, "2G" to s.rats.g2).forEachIndexed { i, (label, on) ->
                FilterChip(selected = on, onClick = { onToggle(i) }, enabled = s.available, label = { Text(label) })
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(scheme.surfaceContainerHighest)
                .clickable(enabled = s.available, onClick = onPreset)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SymbolIcon(Symbol.AUTO_MODE, size = 20.dp, tint = scheme.primary)
            Text(Glossary.RAT_PRESETS.getOrElse(s.ratPreset) { Glossary.RAT_PRESETS[0] }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            SymbolIcon(Symbol.EXPAND_MORE, size = 20.dp, tint = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GenerationTabs(s: NetworkBandsUiState, labels: List<String>, onSelect: (Int) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, label ->
            val selected = s.generation == i
            val enabled = s.generationEnabled(i)
            val bg by animateColorAsState(if (selected) scheme.primary else scheme.surfaceContainerHigh, Motion.effects(), label = "tabBg")
            val fg by animateColorAsState(if (selected) scheme.onPrimary else scheme.onSurfaceVariant, Motion.effects(), label = "tabFg")
            Box(
                Modifier
                    .weight(1f)
                    .alpha(if (enabled) 1f else 0.4f)
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label.substringBefore(' '), style = MaterialTheme.typography.labelLarge, color = fg) }
        }
    }
}

@Composable
private fun BandRow(position: GroupPosition, band: BandUi, enabled: Boolean, onToggle: () -> Unit) {
    GroupCard(position, onClick = onToggle, enabled = enabled, highlighted = band.checked && enabled, role = Role.Checkbox) {
        Row(
            Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(checked = band.checked, onCheckedChange = null, enabled = enabled)
            Column(Modifier.weight(1f)) {
                Text(band.label, style = MaterialTheme.typography.titleMedium)
                Text(band.freqHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                band.isPCell -> Pill("PCell", PcellGreen.copy(alpha = 0.16f), PcellGreen)
                band.isSCell -> Pill("SCell", ScellBlue.copy(alpha = 0.16f), ScellBlue)
                band.isActive -> Pill("ACTIVE", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun AdvancedSheet(
    s: NetworkBandsUiState,
    onVoNr: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var open by remember { mutableIntStateOf(-1) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Radio Settings & Network Glossary", style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.padding(8.dp))
            Text(
                "Advanced 5G Voice controls, hardware channel guide, and complete diagnostic metrics explanation",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
            )
            SettingsGroup("Radio & Voice Controls") {
                item("vonr") { p ->
                    SwitchRow(
                        p, Symbol.SIGNAL_CELLULAR_ALT, "Force Vo5G (Voice over 5G / VoNR)",
                        "Enables ultra-HD voice calls directly over 5G Standalone (SA) towers. Eliminates 4G VoLTE fallback during phone calls for faster call setup and lower latency.",
                        s.vonr, onVoNr, enabled = s.available,
                    )
                }
            }
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Glossary.sections.forEachIndexed { i, (title, body) ->
                    val position = when {
                        i == 0 -> GroupPosition.First
                        i == Glossary.sections.lastIndex -> GroupPosition.Last
                        else -> GroupPosition.Middle
                    }
                    GroupCard(position, onClick = { open = if (open == i) -1 else i }, role = Role.Button) {
                        Column(Modifier.padding(16.dp).animateContentSize()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                SymbolIcon(if (open == i) Symbol.EXPAND_LESS else Symbol.EXPAND_MORE)
                            }
                            if (open == i) {
                                Text(
                                    body, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
            Text(
                "Made with ❤️ by SuperJammy\nSpecial Credits to Gemini",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

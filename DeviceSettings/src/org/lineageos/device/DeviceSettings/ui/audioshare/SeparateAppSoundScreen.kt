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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.lineageos.device.DeviceSettings.R
import org.lineageos.device.DeviceSettings.audioshare.AppItem
import org.lineageos.device.DeviceSettings.audioshare.SeparateAppSoundViewModel
import org.lineageos.device.DeviceSettings.ui.components.GroupCard
import org.lineageos.device.DeviceSettings.ui.components.GroupPosition
import org.lineageos.device.DeviceSettings.ui.components.SettingsGroup
import org.lineageos.device.DeviceSettings.ui.components.SettingsScreen
import org.lineageos.device.DeviceSettings.ui.components.StaggerIn
import org.lineageos.device.DeviceSettings.ui.components.SwitchRow
import org.lineageos.device.DeviceSettings.ui.symbols.AnimatedSymbol
import org.lineageos.device.DeviceSettings.ui.symbols.Symbol
import org.lineageos.device.DeviceSettings.ui.symbols.SymbolIcon
import org.lineageos.device.DeviceSettings.ui.theme.Motion

@Composable
fun SeparateAppSoundScreen(
    vm: SeparateAppSoundViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }

    val filtered = remember(s.apps, query) {
        if (query.isBlank()) s.apps
        else s.apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
    }
    val selectedApps = filtered.filter { it.packageName in s.selected }
    val otherApps = filtered.filter { it.packageName !in s.selected }

    SettingsScreen(
        title = stringResource(R.string.separate_app_sound_title),
        subtitle = if (s.enabled) {
            stringResource(R.string.separate_app_sound_subtitle_on, s.selected.size)
        } else stringResource(R.string.separate_app_sound_subtitle_off),
        onBack = onBack,
        itemSpacing = 2.dp,
        modifier = modifier,
    ) {
        item(key = "enable") {
            StaggerIn(0, Modifier.padding(bottom = 18.dp)) {
                SettingsGroup {
                    item("separate_app_sound_enable") { p ->
                        SwitchRow(
                            p, Symbol.APPS, stringResource(R.string.separate_app_sound_enable),
                            stringResource(R.string.separate_app_sound_enable_summary),
                            s.enabled, vm::setEnabled,
                        )
                    }
                }
            }
        }

        item(key = "route") {
            StaggerIn(1, Modifier.padding(bottom = 18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.separate_app_sound_routes_to),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RouteCard(
                            Symbol.SPEAKER, stringResource(R.string.separate_app_sound_device_speaker),
                            selected = s.device == "speaker", enabled = s.enabled,
                            onClick = { vm.setDevice("speaker") }, modifier = Modifier.weight(1f),
                        )
                        RouteCard(
                            Symbol.HEADSET, stringResource(R.string.separate_app_sound_device_bt),
                            selected = s.device == "bluetooth", enabled = s.enabled,
                            onClick = { vm.setDevice("bluetooth") }, modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        item(key = "search") {
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.separate_app_sound_search)) },
                leadingIcon = { SymbolIcon(Symbol.SEARCH) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        Box(Modifier.clip(CircleShape).clickable { query = "" }.padding(8.dp)) {
                            SymbolIcon(Symbol.CLOSE, size = 20.dp)
                        }
                    }
                },
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
            )
        }

        if (s.loading) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            }
        } else if (filtered.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.separate_app_sound_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }

        if (selectedApps.isNotEmpty()) {
            item(key = "selected-header") {
                SectionHeader(stringResource(R.string.separate_app_sound_selected, selectedApps.size))
            }
            items(selectedApps, key = { "sel-" + it.packageName }) { app ->
                val i = selectedApps.indexOf(app)
                AppRow(positionOf(i, selectedApps.size), app, true, s.enabled) { vm.setAppSelected(app.packageName, it) }
            }
        }
        if (otherApps.isNotEmpty()) {
            item(key = "all-header") {
                SectionHeader(stringResource(R.string.separate_app_sound_all_apps), Modifier.padding(top = 18.dp))
            }
            items(otherApps, key = { "app-" + it.packageName }) { app ->
                val i = otherApps.indexOf(app)
                AppRow(positionOf(i, otherApps.size), app, false, s.enabled) { vm.setAppSelected(app.packageName, it) }
            }
        }
    }
}

private fun positionOf(index: Int, count: Int): GroupPosition = when {
    count == 1 -> GroupPosition.Single
    index == 0 -> GroupPosition.First
    index == count - 1 -> GroupPosition.Last
    else -> GroupPosition.Middle
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun AppRow(
    position: GroupPosition,
    app: AppItem,
    selected: Boolean,
    featureOn: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val icon = remember(app.icon) { app.icon?.asImageBitmap() }
    GroupCard(
        position,
        onClick = { onChange(!selected) },
        enabled = featureOn,
        highlighted = selected && featureOn,
        role = Role.Checkbox,
    ) {
        Row(
            Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (icon != null) {
                Image(icon, contentDescription = null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
            } else {
                Box(Modifier.size(44.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    app.packageName, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            AnimatedSymbol(
                Symbol.CHECK_CIRCLE, active = selected,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** One of the two output choices; rounds up and fills with the primary colour when selected. */
@Composable
private fun RouteCard(
    symbol: String,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val corner by animateDpAsState(if (selected) 36.dp else 24.dp, Motion.spatialFast(), label = "routeCorner")
    val container by animateColorAsState(
        if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh, Motion.effects(), label = "routeBg",
    )
    val content by animateColorAsState(
        if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant, Motion.effects(), label = "routeFg",
    )
    Column(
        modifier
            .alpha(if (enabled) 1f else 0.42f)
            .clip(RoundedCornerShape(corner.coerceAtLeast(0.dp)))
            .background(container)
            .clickable(enabled = enabled && !selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 20.dp, horizontal = 12.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedSymbol(symbol, active = selected, size = 32.dp, tint = content)
        Text(
            label, style = MaterialTheme.typography.labelLarge, color = content,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

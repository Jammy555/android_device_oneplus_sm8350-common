/*
 * Copyright (C) 2026 The LineageOS Project
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
package org.lineageos.device.DeviceSettings.ui

import android.content.ComponentName
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.lineageos.device.DeviceSettings.R
import org.lineageos.device.DeviceSettings.defaultPrefs
import org.lineageos.device.DeviceSettings.dock.DockInputService
import org.lineageos.device.DeviceSettings.ui.symbols.AnimatedSymbol
import org.lineageos.device.DeviceSettings.ui.symbols.Symbol
import org.lineageos.device.DeviceSettings.ui.symbols.SymbolIcon

@Composable
internal fun FirstUseDialog(resumed: Boolean, onOpenPasteSettings: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.defaultPrefs() }
    val key = "devicesettings_welcome_dismissed"
    var dismissed by rememberSaveable { mutableStateOf(prefs.getBoolean(key, false)) }
    val helperEnabled = remember(context, resumed) {
        val component = ComponentName(context, DockInputService::class.java)
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 &&
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
    }
    LaunchedEffect(resumed) {
        // Consume first use even when the helper is already on; never prompt after a later disable.
        if (resumed && !prefs.getBoolean(key, false)) prefs.edit().putBoolean(key, true).apply()
    }
    if (!resumed || dismissed || helperEnabled) return

    fun dismiss() { dismissed = true }

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text(stringResource(R.string.devicesettings_welcome_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val scheme = MaterialTheme.colorScheme
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                        .background(scheme.surfaceContainerHigh).padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedSymbol(Symbol.DOCK_TO_RIGHT, active = true, size = 48.dp,
                        tint = scheme.primary, contentDescription = stringResource(R.string.dock_title))
                    SymbolIcon(Symbol.CHEVRON_RIGHT, tint = scheme.onSurfaceVariant)
                    AnimatedSymbol(Symbol.CONTENT_PASTE, active = true, size = 48.dp,
                        tint = scheme.primary, contentDescription = stringResource(R.string.dock_input_title))
                }
                Text(stringResource(R.string.devicesettings_welcome_dock))
            }
        },
        confirmButton = {
            TextButton(onClick = { onOpenPasteSettings(); dismiss() }) {
                Text(stringResource(R.string.devicesettings_open_accessibility))
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss) {
                Text(stringResource(R.string.devicesettings_not_now))
            }
        },
    )
}

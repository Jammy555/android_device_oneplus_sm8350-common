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
package org.lineageos.device.DeviceSettings.ui.dock

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.device.DeviceSettings.R
import org.lineageos.device.DeviceSettings.defaultPrefs
import org.lineageos.device.DeviceSettings.dock.DockHistoryArchive
import org.lineageos.device.DeviceSettings.dock.DockClipboardTimeout
import org.lineageos.device.DeviceSettings.dock.DockDatabase
import org.lineageos.device.DeviceSettings.dock.DockInputService
import org.lineageos.device.DeviceSettings.dock.SmartDockService

data class DockSettingsState(
    val autoShow: Boolean = true,
    val capture: Boolean = true,
    val autoFade: Boolean = false,
    val fadeTransparency: Int = 50,
    val hideLandscape: Boolean = false,
    val holdControls: Boolean = true,
    val doubleTapScreenshot: Boolean = true,
    val blur: Boolean = false,
    val batteryRail: Boolean = true,
    val clipboardTimeout: Int = 0,
    val historyLimit: Int = 0,
    val archiveBusy: Boolean = false,
    val width: Int = 48,
    val height: Int = 80,
    val helperEnabled: Boolean = false,
    /** null while loading or when the history could not be opened. */
    val itemCount: Int? = null,
    val storageError: Boolean = false,
)

/**
 * File Dock settings. The overlay service listens to the same SharedPreferences, so every change
 * here applies live, exactly like the old preference screen.
 */
class DockSettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app
    private val prefs = ctx.defaultPrefs()

    private val _state = MutableStateFlow(read())
    val state: StateFlow<DockSettingsState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private fun read() = DockSettingsState(
        autoShow = prefs.getBoolean(SmartDockService.KEY_AUTO_SHOW, true),
        capture = prefs.getBoolean(SmartDockService.KEY_CAPTURE, true),
        autoFade = prefs.getBoolean(SmartDockService.KEY_AUTO_FADE, false),
        fadeTransparency = prefs.getInt(SmartDockService.KEY_FADE_TRANSPARENCY, 50).coerceIn(25, 75),
        hideLandscape = prefs.getBoolean(SmartDockService.KEY_HIDE_LANDSCAPE, false),
        holdControls = prefs.getBoolean(SmartDockService.KEY_HOLD_CONTROLS, true),
        doubleTapScreenshot = prefs.getBoolean(SmartDockService.KEY_DOUBLE_TAP_SCREENSHOT, true),
        blur = prefs.getBoolean(SmartDockService.KEY_BLUR, false),
        batteryRail = prefs.getBoolean(SmartDockService.KEY_BATTERY_RAIL, true),
        clipboardTimeout = prefs.getInt(SmartDockService.KEY_CLIPBOARD_TIMEOUT, 0),
        historyLimit = prefs.getInt(SmartDockService.KEY_HISTORY_LIMIT, 0).takeIf { it == 50 || it == 100 } ?: 0,
        width = prefs.getInt(SmartDockService.KEY_WIDTH, 48),
        height = prefs.getInt(SmartDockService.KEY_HEIGHT, 80),
        helperEnabled = DockInputService.connected(),
    )

    fun refresh() {
        _state.update { read().copy(itemCount = it.itemCount, storageError = it.storageError, archiveBusy = it.archiveBusy) }
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                try {
                    DockDatabase.getInstance(ctx).getItemCount()
                } catch (e: RuntimeException) {
                    null
                }
            }
            _state.update { it.copy(itemCount = count, storageError = count == null) }
        }
    }

    fun setAutoShow(v: Boolean) {
        prefs.edit().putBoolean(SmartDockService.KEY_AUTO_SHOW, v).apply()
        _state.update { it.copy(autoShow = v) }
    }

    fun setCapture(v: Boolean) {
        prefs.edit().putBoolean(SmartDockService.KEY_CAPTURE, v).apply()
        _state.update { it.copy(capture = v) }
    }

    fun setAutoFade(v: Boolean) {
        prefs.edit().putBoolean(SmartDockService.KEY_AUTO_FADE, v).apply()
        _state.update { it.copy(autoFade = v) }
    }

    fun setFadeTransparency(v: Int) {
        if (v !in listOf(25, 50, 75)) return
        prefs.edit().putInt(SmartDockService.KEY_FADE_TRANSPARENCY, v).apply()
        _state.update { it.copy(fadeTransparency = v) }
    }

    fun setHideLandscape(v: Boolean) {
        prefs.edit().putBoolean(SmartDockService.KEY_HIDE_LANDSCAPE, v).apply()
        _state.update { it.copy(hideLandscape = v) }
    }

    fun setHoldControls(v: Boolean) {
        prefs.edit().putBoolean(SmartDockService.KEY_HOLD_CONTROLS, v).apply()
        _state.update { it.copy(holdControls = v) }
    }

    fun setDoubleTapScreenshot(v: Boolean) {
        prefs.edit().putBoolean(SmartDockService.KEY_DOUBLE_TAP_SCREENSHOT, v).apply()
        _state.update { it.copy(doubleTapScreenshot = v) }
    }

    fun setBlur(v: Boolean) {
        prefs.edit().putBoolean(SmartDockService.KEY_BLUR, v).apply()
        _state.update { it.copy(blur = v) }
    }

    fun setBatteryRail(v: Boolean) {
        prefs.edit().putBoolean(SmartDockService.KEY_BATTERY_RAIL, v).apply()
        _state.update { it.copy(batteryRail = v) }
    }

    fun setClipboardTimeout(v: Int) {
        if (v !in listOf(0, 1, 10)) return
        prefs.edit().putInt(SmartDockService.KEY_CLIPBOARD_TIMEOUT, v).apply()
        _state.update { it.copy(clipboardTimeout = v) }
        DockClipboardTimeout.schedule(ctx)
    }

    fun setHistoryLimit(v: Int) {
        if (v !in listOf(0, 50, 100)) return
        prefs.edit().putInt(SmartDockService.KEY_HISTORY_LIMIT, v).apply()
        _state.update { it.copy(historyLimit = v) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    DockDatabase.getInstance(ctx).trimToLimit()
                    true
                } catch (e: RuntimeException) {
                    false
                }
            }
            if (!ok) _messages.trySend(ctx.getString(R.string.dock_storage_error))
            refresh()
        }
    }

    fun exportHistory(uri: Uri) = archive(uri, importing = false)
    fun importHistory(uri: Uri) = archive(uri, importing = true)

    private fun archive(uri: Uri, importing: Boolean) {
        if (_state.value.archiveBusy) return
        _state.update { it.copy(archiveBusy = true) }
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                try {
                    if (importing) {
                        ctx.contentResolver.openInputStream(uri)?.use { DockHistoryArchive.import(ctx, it) }
                    } else {
                        ctx.contentResolver.openOutputStream(uri, "wt")?.use { DockHistoryArchive.export(ctx, it) }
                    }
                } catch (e: Exception) {
                    null
                }
            }
            _state.update { it.copy(archiveBusy = false) }
            _messages.trySend(if (count == null) ctx.getString(R.string.dock_archive_error)
                else ctx.getString(if (importing) R.string.dock_imported_count else R.string.dock_exported_count, count))
            refresh()
        }
    }

    fun setWidth(v: Int) {
        prefs.edit().putInt(SmartDockService.KEY_WIDTH, v).apply()
        _state.update { it.copy(width = v) }
    }

    fun setHeight(v: Int) {
        prefs.edit().putInt(SmartDockService.KEY_HEIGHT, v).apply()
        _state.update { it.copy(height = v) }
    }

    fun openAccessibilitySettings() {
        var intent = Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
            .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(ctx, DockInputService::class.java))
        if (intent.resolveActivity(ctx.packageManager) == null) {
            intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun clearAll() {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    DockDatabase.getInstance(ctx).clearAll()
                    true
                } catch (e: RuntimeException) {
                    false
                }
            }
            if (!ok) _messages.trySend(ctx.getString(R.string.dock_storage_error))
            refresh()
        }
    }
}

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

package org.lineageos.device.DeviceSettings.ui.main

import android.app.Application
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
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
import org.lineageos.device.DeviceSettings.DeviceKeys
import org.lineageos.device.DeviceSettings.DeviceNodes
import org.lineageos.device.DeviceSettings.DeviceRestore
import org.lineageos.device.DeviceSettings.R
import org.lineageos.device.DeviceSettings.Utils
import org.lineageos.device.DeviceSettings.defaultPrefs
import org.lineageos.device.DeviceSettings.dock.SmartDockService
import org.lineageos.device.DeviceSettings.powertools.PowerProfileUtil

data class SwitchUi(val checked: Boolean = false, val enabled: Boolean = false)

data class MainUiState(
    val mode: Int = PowerProfileUtil.MODE_BALANCE,
    /** Profile being applied right now (shows a loader on that button). */
    val pendingMode: Int? = null,
    val dockEnabled: Boolean = false,
    val edge: SwitchUi = SwitchUi(),
    val game: SwitchUi = SwitchUi(),
    val usb2: SwitchUi = SwitchUi(),
    val vibValue: Int = 3,
    val vibMax: Int = DeviceRestore.VIB_MAX,
    val vibEnabled: Boolean = false,
)

/**
 * State + actions behind the main screen. Same rules as the old preference fragment:
 * game mode is forced by the profile, vibration is capped in Power Save.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app
    private val prefs = ctx.defaultPrefs()
    private val profiles = PowerProfileUtil(ctx)
    private val vibrator: Vibrator? = ctx.getSystemService(Vibrator::class.java)

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        refresh()
    }

    // ---------------------------------------------------------------- lifecycle

    fun onResume() {
        refresh()
    }

    fun onPause() {
    }

    /** Re-reads every control from the hardware / prefs and enforces the profile policies. */
    fun refresh() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { readControls() }
            _state.update {
                it.copy(
                    mode = snapshot.mode,
                    dockEnabled = snapshot.dock,
                    edge = snapshot.edge,
                    game = snapshot.game,
                    usb2 = snapshot.usb2,
                    vibValue = snapshot.vibValue,
                    vibMax = snapshot.vibMax,
                    vibEnabled = snapshot.vibEnabled,
                )
            }
        }
    }

    private class Controls(
        val mode: Int, val dock: Boolean, val edge: SwitchUi, val game: SwitchUi, val usb2: SwitchUi,
        val vibValue: Int, val vibMax: Int, val vibEnabled: Boolean,
    )

    private fun readControls(): Controls {
        val mode = profiles.currentMode
        val saver = mode == PowerProfileUtil.MODE_BATTERY_SAVER

        // touch panel policy
        val edge = when (mode) {
            PowerProfileUtil.MODE_PERFORMANCE -> SwitchUi(true, false)
            PowerProfileUtil.MODE_BATTERY_SAVER -> SwitchUi(false, false)
            else -> SwitchUi(
                prefs.getBoolean(DeviceKeys.EDGE_TOUCH, false),
                mode == PowerProfileUtil.MODE_BALANCE && Utils.fileWritable(DeviceNodes.EDGE),
            )
        }
        val game = when (mode) {
            PowerProfileUtil.MODE_PERFORMANCE -> SwitchUi(true, false)
            PowerProfileUtil.MODE_BATTERY_SAVER -> SwitchUi(false, false)
            else -> SwitchUi(
                prefs.getBoolean(DeviceKeys.GAME_MODE, false),
                mode == PowerProfileUtil.MODE_BALANCE && Utils.fileWritable(DeviceNodes.GAME),
            )
        }

        val usb2 = if (Utils.fileWritable(DeviceNodes.FAST_CHARGE)) {
            SwitchUi(
                prefs.getBoolean(DeviceKeys.USB2_FAST_CHARGE, Utils.getFileValueAsBoolean(DeviceNodes.FAST_CHARGE, false)),
                true,
            )
        } else SwitchUi(false, false)

        // vibrator: capped in Power Save
        val vibWritable = Utils.fileWritable(DeviceNodes.VIB_LEVEL)
        val vibMax = if (saver) DeviceRestore.VIB_MAX_POWERSAVE else DeviceRestore.VIB_MAX
        var vib = prefs.getInt(
            DeviceKeys.VIB_STRENGTH,
            Utils.getFileValue(DeviceNodes.VIB_LEVEL, DeviceRestore.DEFAULT_VIB_LEVEL).toIntOrNull() ?: 3,
        )
        if (vibWritable && saver && vib > DeviceRestore.VIB_MAX_POWERSAVE) {
            vib = DeviceRestore.VIB_MAX_POWERSAVE
            prefs.edit().putInt(DeviceKeys.VIB_STRENGTH, vib).apply()
            Utils.writeValue(DeviceNodes.VIB_LEVEL, vib.toString())
        }

        return Controls(
            mode = mode,
            dock = prefs.getBoolean(DeviceKeys.DOCK_ENABLE, false),
            edge = edge, game = game, usb2 = usb2,
            vibValue = vib.coerceIn(0, vibMax), vibMax = vibMax, vibEnabled = vibWritable,
        )
    }

    // ---------------------------------------------------------------- actions

    fun setProfile(mode: Int) {
        if (_state.value.pendingMode != null) return
        if (PowerProfileUtil.profileCooldownRemaining() > 0) {
            say(ctx.getString(R.string.profile_cooldown))
            return
        }
        _state.update { it.copy(pendingMode = mode) }
        viewModelScope.launch {
            val ok = profiles.setMode(mode)
            _state.update { it.copy(pendingMode = null) }
            refresh()
            if (!ok) say(ctx.getString(R.string.powertools_apply_failed))
        }
    }

    fun setEdgeTouch(enabled: Boolean) = setTouch(DeviceKeys.EDGE_TOUCH, enabled)

    fun setGameMode(enabled: Boolean) = setTouch(DeviceKeys.GAME_MODE, enabled)

    private fun setTouch(key: String, enabled: Boolean) {
        _state.update {
            if (key == DeviceKeys.EDGE_TOUCH) it.copy(edge = it.edge.copy(checked = enabled))
            else it.copy(game = it.game.copy(checked = enabled))
        }
        viewModelScope.launch {
            val ok = profiles.setTouchValue(key, enabled)
            refresh()
            if (!ok) say(ctx.getString(R.string.powertools_apply_failed))
        }
    }

    fun setUsb2FastCharge(enabled: Boolean) {
        prefs.edit().putBoolean(DeviceKeys.USB2_FAST_CHARGE, enabled).apply()
        _state.update { it.copy(usb2 = it.usb2.copy(checked = enabled)) }
        viewModelScope.launch(Dispatchers.IO) {
            Utils.writeValue(DeviceNodes.FAST_CHARGE, if (enabled) "1" else "0")
        }
    }

    fun setVibration(value: Int) {
        val saver = _state.value.mode == PowerProfileUtil.MODE_BATTERY_SAVER
        if (saver && value > DeviceRestore.VIB_MAX_POWERSAVE) {
            say(ctx.getString(R.string.vib_capped_powersave))
            refresh()
            return
        }
        prefs.edit().putInt(DeviceKeys.VIB_STRENGTH, value).apply()
        _state.update { it.copy(vibValue = value) }
        viewModelScope.launch(Dispatchers.IO) {
            Utils.writeValue(DeviceNodes.VIB_LEVEL, value.toString())
            previewVibration(value)
        }
    }

    private fun previewVibration(value: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator() || value <= 0) return
        val duration = 8L + value * 3
        val amplitude = minOf(160, 40 + value * 35)
        try {
            v.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        } catch (e: Exception) {
            @Suppress("DEPRECATION") v.vibrate(duration)
        }
    }

    fun setDockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(DeviceKeys.DOCK_ENABLE, enabled).apply()
        _state.update { it.copy(dockEnabled = enabled) }
        val intent = Intent(ctx, SmartDockService::class.java).putExtra("enable", enabled)
        if (enabled) ctx.startService(intent) else ctx.stopService(intent)
    }

    private fun say(text: String) {
        _messages.trySend(text)
    }
}

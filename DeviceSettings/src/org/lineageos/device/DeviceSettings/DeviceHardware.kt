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

package org.lineageos.device.DeviceSettings

import android.content.Context
import android.util.Log
import org.lineageos.device.DeviceSettings.powertools.PowerProfileUtil

/** Sysfs / procfs nodes driven by the main screen. */
object DeviceNodes {
    const val GAME = "/proc/touchpanel/game_switch_enable"
    const val EDGE = "/proc/touchpanel/oplus_tp_direction"
    const val FAST_CHARGE = "/sys/module/oplus_chg/parameters/force_fast_charge"
    const val VIB_LEVEL = "/sys/devices/platform/soc/88c000.i2c/i2c-6/6-005a/leds/vibrator/level"
}

/** SharedPreferences keys. Must stay identical to res/xml/main.xml (settings search) and old installs. */
object DeviceKeys {
    const val GAME_MODE = "game_mode"
    const val EDGE_TOUCH = "edge_touch"
    const val USB2_FAST_CHARGE = "usb2_fast_charge"
    const val VIB_STRENGTH = "vib_strength"
    const val APTX_LOW_LATENCY = "aptx_adaptive_low_latency"
    const val DOCK_ENABLE = "dock_sidebar_enable"
}

/** Re-applies persisted values after boot. Called from [Startup]. */
object DeviceRestore {
    private const val TAG = "DeviceSettings"
    const val DEFAULT_VIB_LEVEL = "3"
    const val VIB_MAX = 3
    const val VIB_MAX_POWERSAVE = 2

    @JvmStatic
    fun restoreFastCharge(context: Context) {
        if (!Utils.fileWritable(DeviceNodes.FAST_CHARGE)) return
        val value = context.defaultPrefs().getBoolean(
            DeviceKeys.USB2_FAST_CHARGE, Utils.getFileValueAsBoolean(DeviceNodes.FAST_CHARGE, false))
        Utils.writeValue(DeviceNodes.FAST_CHARGE, if (value) "1" else "0")
    }

    @JvmStatic
    fun restoreVibStrength(context: Context) {
        if (!Utils.fileWritable(DeviceNodes.VIB_LEVEL)) return
        val prefs = context.defaultPrefs()
        val fallback = Utils.getFileValue(DeviceNodes.VIB_LEVEL, DEFAULT_VIB_LEVEL).toIntOrNull() ?: 3
        var value = prefs.getInt(DeviceKeys.VIB_STRENGTH, fallback)
        val powersave = PowerProfileUtil(context).currentMode == PowerProfileUtil.MODE_BATTERY_SAVER
        if (powersave && value > VIB_MAX_POWERSAVE) value = VIB_MAX_POWERSAVE
        Utils.writeValue(DeviceNodes.VIB_LEVEL, value.toString())
        Log.i(TAG, "Restored vibrator level to sysfs: $value")
    }
}

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

package org.lineageos.device.DeviceSettings.powertools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** One read-only sample of live hardware state. Every field is null when its node is unreadable. */
data class Telemetry(
    val littleKhz: Long?,
    val bigKhz: Long?,
    val primeKhz: Long?,
    val gpuHz: Long?,
    val batteryTempC: Float?,
    val batteryPercent: Int?,
    val charging: Boolean,
) {
    companion object {
        private const val CPUFREQ = "/sys/devices/system/cpu/cpufreq/policy"
        private val GPU_CUR_FREQ = listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
        )

        fun sample(context: Context): Telemetry {
            val battery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            return Telemetry(
                littleKhz = SysfsUtils.readLong(CPUFREQ + "0/scaling_cur_freq"),
                bigKhz = SysfsUtils.readLong(CPUFREQ + "4/scaling_cur_freq"),
                primeKhz = SysfsUtils.readLong(CPUFREQ + "7/scaling_cur_freq"),
                gpuHz = GPU_CUR_FREQ.firstNotNullOfOrNull { SysfsUtils.readLong(it) },
                batteryTempC = if (tenths != null && tenths != Int.MIN_VALUE) tenths / 10f else null,
                batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            )
        }
    }
}

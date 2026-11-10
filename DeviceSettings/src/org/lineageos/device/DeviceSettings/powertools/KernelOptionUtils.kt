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

object KernelOptionUtils {

    private const val CPUFREQ = "/sys/devices/system/cpu/cpufreq/policy"
    private const val KGSL = "/sys/class/kgsl/kgsl-3d0/"

    const val CPU_LITTLE_AVAILABLE_FREQUENCIES = CPUFREQ + "0/scaling_available_frequencies"
    const val CPU_BIG_AVAILABLE_FREQUENCIES = CPUFREQ + "4/scaling_available_frequencies"
    const val CPU_PRIME_AVAILABLE_FREQUENCIES = CPUFREQ + "7/scaling_available_frequencies"
    const val CPU_LITTLE_AVAILABLE_GOVERNORS = CPUFREQ + "0/scaling_available_governors"
    const val CPU_BIG_AVAILABLE_GOVERNORS = CPUFREQ + "4/scaling_available_governors"
    const val CPU_PRIME_AVAILABLE_GOVERNORS = CPUFREQ + "7/scaling_available_governors"

    const val GPU_AVAILABLE_FREQUENCIES = KGSL + "gpu_available_frequencies"
    const val GPU_DEVFREQ_AVAILABLE_FREQUENCIES = KGSL + "devfreq/available_frequencies"
    val GPU_AVAILABLE_FREQUENCY_PATHS = listOf(GPU_AVAILABLE_FREQUENCIES, GPU_DEVFREQ_AVAILABLE_FREQUENCIES)
    const val GPU_AVAILABLE_GOVERNORS = KGSL + "devfreq/available_governors"

    const val IO_SCHEDULER = "/sys/block/sda/queue/scheduler"
    const val TCP_CONGESTION_CONTROL = "/proc/sys/net/ipv4/tcp_congestion_control"
    const val TCP_AVAILABLE_CONGESTION_CONTROL = "/proc/sys/net/ipv4/tcp_available_congestion_control"

    const val CPU_LITTLE_MIN_FREQ = CPUFREQ + "0/scaling_min_freq"
    const val CPU_LITTLE_MAX_FREQ = CPUFREQ + "0/scaling_max_freq"
    const val CPU_BIG_MIN_FREQ = CPUFREQ + "4/scaling_min_freq"
    const val CPU_BIG_MAX_FREQ = CPUFREQ + "4/scaling_max_freq"
    const val CPU_PRIME_MIN_FREQ = CPUFREQ + "7/scaling_min_freq"
    const val CPU_PRIME_MAX_FREQ = CPUFREQ + "7/scaling_max_freq"
    const val CPU_LITTLE_GOVERNOR = CPUFREQ + "0/scaling_governor"
    const val CPU_BIG_GOVERNOR = CPUFREQ + "4/scaling_governor"
    const val CPU_PRIME_GOVERNOR = CPUFREQ + "7/scaling_governor"

    const val GPU_MIN_FREQ = KGSL + "devfreq/min_freq"
    const val GPU_MAX_FREQ = KGSL + "devfreq/max_freq"
    const val GPU_GOVERNOR = KGSL + "devfreq/governor"

    const val KP_MAX_FREQ_RATIO = "/sys/kernel/kprofiles/kp_max_freq_ratio"

    /** Parses a space separated sysfs list. Brackets (active marker) are stripped. */
    fun readAvailableValues(path: String, dropNone: Boolean = false): List<String> {
        val raw = SysfsUtils.readLine(path)
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.replace("[", "").replace("]", "").trim().split(Regex("\\s+"))
            .filter { it.isNotEmpty() && !(dropNone && it == "none") }
    }

    /** First path that yields values wins. */
    fun readAvailableValues(paths: List<String>, dropNone: Boolean = false): List<String> {
        for (path in paths) {
            val values = readAvailableValues(path, dropNone)
            if (values.isNotEmpty()) return values
        }
        return emptyList()
    }

    /** `[bfq] kyber none` -> `bfq`. */
    fun readBracketedValue(path: String): String? {
        val raw = SysfsUtils.readLine(path)
        if (raw.isNullOrEmpty()) return null
        val start = raw.indexOf('[')
        val end = raw.indexOf(']')
        return if (start != -1 && end != -1 && start < end) raw.substring(start + 1, end) else null
    }

    fun formatFrequency(raw: String): String {
        val frequency = raw.toLongOrNull() ?: return raw
        val mhz = if (frequency >= 10_000_000L) frequency / 1_000_000L else frequency / 1_000L
        return "$mhz MHz"
    }

    fun prettifyName(raw: String?): String {
        when (raw) {
            "msm-adreno-tz" -> return "MSM Adreno TZ"
            "simple_ondemand" -> return "Simple Ondemand"
            "mq-deadline" -> return "MQ-Deadline"
            "schedutil" -> return "Schedutil"
        }
        if (raw.isNullOrEmpty()) return ""
        return if (raw.length <= 4) raw.uppercase() else raw.substring(0, 1).uppercase() + raw.substring(1)
    }

    fun displayValue(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return if (value.toLongOrNull() != null) formatFrequency(value) else prettifyName(value)
    }
}

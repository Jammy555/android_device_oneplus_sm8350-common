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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.SystemProperties
import android.util.Log
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.lineageos.device.DeviceSettings.R
import org.lineageos.device.DeviceSettings.defaultPrefs

/**
 * Power profile state machine (Power Save / Normal / Performance) plus the manual CPU/GPU/storage
 * overrides on top of it.
 *
 * Every hardware write is funnelled through ONE single-thread dispatcher, exactly like the old
 * `EXECUTOR`, so two writers (screen, QS tile, boot receiver) can never interleave sysfs writes.
 * All `suspend` entry points return `true` on success; any RuntimeException turns into `false`.
 */
class PowerProfileUtil(context: Context) {

    private val context: Context = context.applicationContext
    private val prefs = this.context.defaultPrefs()

    // ---------------------------------------------------------------- reading

    /** Current kernel/profile mode, [MODE_UNKNOWN] when nothing sensible is reported. */
    val currentMode: Int
        get() {
            val kernelMode = SysfsUtils.readInt(KPROFILES_NODE, -1)
            if (kernelMode == 0) return MODE_BALANCE
            if (kernelMode in 1..3) return kernelMode - 1
            val mode = SystemProperties.getInt(SYS_PROP, MODE_UNKNOWN)
            return if (mode in 0..2) mode else MODE_UNKNOWN
        }

    val managedMode: Int get() = currentMode

    val modeLabel: String
        get() = context.getString(
            when (currentMode) {
                MODE_BATTERY_SAVER -> R.string.powerprofile_mode_battery_saver
                MODE_BALANCE -> R.string.powerprofile_mode_balance
                MODE_PERFORMANCE -> R.string.powerprofile_mode_performance
                else -> R.string.powerprofile_mode_unknown
            }
        )

    // ---------------------------------------------------------------- public (serialised) API

    suspend fun setMode(mode: Int): Boolean = serial { applyMode(mode) }.also { markProfileChange() }

    suspend fun toggleMode(): Boolean = serial { applyMode((currentMode + 1) % 3) }.also { markProfileChange() }

    suspend fun setManualEnabled(key: String, enabled: Boolean): Boolean = serial {
        val mode = currentMode
        if ((mode != MODE_BALANCE && mode != MODE_PERFORMANCE) || key !in MANUAL_KEYS) return@serial false
        if (!enabled && !applyCategory(key, mode)) {
            restoreManualValues(mode)
            return@serial false
        }
        prefs.edit().putBoolean(key, enabled).apply()
        if (!enabled) {
            val prefix = when (key) {
                "cpu_enable" -> "cpu_"
                "gpu_enable" -> "gpu_"
                else -> "io_"
            }
            val editor = prefs.edit()
            for (valueKey in PERSIST_KEYS) {
                if (valueKey.startsWith(prefix)) editor.putString(valueKey, getValidatedStockValueForMode(mode, valueKey))
            }
            editor.apply()
        }
        true
    }

    suspend fun setHardwareValue(key: String, value: String): Boolean = serial {
        if (key == KEY_TCP) {
            if (value !in KernelOptionUtils.readAvailableValues(KernelOptionUtils.TCP_AVAILABLE_CONGESTION_CONTROL)) {
                return@serial false
            }
            if (!SysfsUtils.setPropertyAndWait("sys.powertools.tcp", value, KernelOptionUtils.TCP_CONGESTION_CONTROL)) {
                return@serial false
            }
        } else {
            val mode = currentMode
            val category = when {
                key.startsWith("gpu_") -> "gpu_enable"
                key.startsWith("cpu_") -> "cpu_enable"
                else -> "storage_enable"
            }
            if (key !in PERSIST_KEYS ||
                (mode != MODE_BALANCE && mode != MODE_PERFORMANCE) ||
                !prefs.getBoolean(category, false) ||
                value != validateKernelValue(key, value)
            ) return@serial false

            val property: String
            val path: String
            if (key == KEY_IO_SCHEDULER) {
                property = "persist.sys.parts.storage.scheduler"
                path = KernelOptionUtils.IO_SCHEDULER
            } else if (key.startsWith("gpu_")) {
                val field = key.substring(4)
                property = "persist.sys.parts.gpu.$field"
                path = "/sys/class/kgsl/kgsl-3d0/devfreq/" +
                    if (field == "governor") field else "profile_" + field.replace("frequency", "freq")
            } else {
                val parts = key.split("_", limit = 3)
                val policy = policyFor(parts[1])
                property = "persist.sys.parts.cpu.${parts[1]}.${parts[2]}"
                path = "/sys/devices/system/cpu/cpufreq/policy$policy/scaling_" +
                    if (parts[2] == "governor") "governor" else "profile_" + parts[2].replace("frequency", "freq")
            }
            if (!SysfsUtils.setPropertyAndWait(property, value, path)) return@serial false
        }
        prefs.edit().putString(key, value).apply()
        true
    }

    suspend fun setTouchValue(key: String, enabled: Boolean): Boolean = serial {
        if ((key != "game_mode" && key != "edge_touch") || currentMode != MODE_BALANCE) return@serial false
        if (!writeTouch(if (key == "game_mode") FILE_GAME else FILE_EDGE, enabled)) return@serial false
        prefs.edit().putBoolean(key, enabled).apply()
        true
    }

    // ---------------------------------------------------------------- mode application

    private fun applyMode(mode: Int): Boolean {
        if (mode < MODE_BATTERY_SAVER || mode > MODE_PERFORMANCE) return false
        val previous = currentMode
        if (!applyModeHardware(mode) || !applyTouchPanel(mode) ||
            !BlurUtils.setBlurDisabled(context, mode == MODE_BATTERY_SAVER)
        ) {
            Log.e(TAG, "Profile did not apply; restoring previous preset")
            if (previous in MODE_BATTERY_SAVER..MODE_PERFORMANCE) {
                // Non short-circuit on purpose: every step must run even if an earlier one failed.
                var restored = applyModeHardware(previous)
                restored = restored and restoreManualValues(previous)
                restored = restored and applyTouchPanel(previous)
                restored = restored and BlurUtils.setBlurDisabled(context, previous == MODE_BATTERY_SAVER)
                if (!restored) Log.e(TAG, "Previous preset could not be fully restored")
            }
            updateNotification(currentMode)
            return false
        }
        val editor = prefs.edit()
            .putString("power_profile_mode", mode.toString())
            .putBoolean("cpu_enable", false)
            .putBoolean("gpu_enable", false)
            .putBoolean("storage_enable", false)
        for (key in PERSIST_KEYS) editor.putString(key, getValidatedStockValueForMode(mode, key))
        editor.apply()
        updateNotification(mode)
        return true
    }

    private fun applyModeHardware(mode: Int): Boolean = try {
        applyModeHardwareUnchecked(mode)
    } catch (e: RuntimeException) {
        Log.e(TAG, "Could not apply profile $mode", e)
        false
    }

    private fun applyModeHardwareUnchecked(mode: Int): Boolean {
        if (!SysfsUtils.writeValue(KPROFILES_NODE, (mode + 1).toString()) ||
            SysfsUtils.readInt(KPROFILES_NODE, -1) != mode + 1 ||
            !applyCategory("cpu_enable", mode) ||
            !applyCategory("gpu_enable", mode) ||
            !applyCategory("storage_enable", mode)
        ) return false

        SystemProperties.set("sys.perf_mode_applied", "-1")
        SystemProperties.set(SYS_PROP, mode.toString())
        val halProfile = when (mode) {
            MODE_BATTERY_SAVER -> "POWERTOOLS_SAVER"
            MODE_PERFORMANCE -> "POWERTOOLS_PERFORMANCE"
            else -> "POWERTOOLS_NORMAL"
        }
        if (!SysfsUtils.awaitValue({ SystemProperties.get("sys.perf_mode_applied") }, mode.toString()) ||
            !SysfsUtils.awaitValue({ SystemProperties.get("vendor.powerhal.profile_applied") }, halProfile)
        ) return false

        val sleep = SysfsUtils.readLine("/sys/module/lpm_levels/parameters/sleep_disabled")
        val boost = SysfsUtils.readLine("/sys/devices/system/cpu/cpu_boost/input_boost_freq")
        val expectedBoost = when (mode) {
            MODE_BATTERY_SAVER -> "0:0"
            MODE_PERFORMANCE -> "0:1516800"
            else -> "0:1305600"
        }
        return (sleep == "N" || sleep == "0") &&
            boost != null && boost.split(Regex("\\s+")).contains(expectedBoost) &&
            (if (mode == MODE_PERFORMANCE) "0" else "1") == SysfsUtils.readLine(StorageUtils.UFS_CLOCK_SCALING) &&
            (if (mode == MODE_BATTERY_SAVER) "0" else "1") == SystemProperties.get("vendor.powerhal.boosts") &&
            (mode != MODE_BATTERY_SAVER || verifySaverCeilings())
    }

    private fun verifySaverCeilings(): Boolean {
        val ratio = readKpMaxFreqRatio()
        for (policy in intArrayOf(0, 4, 7)) {
            val path = "/sys/devices/system/cpu/cpufreq/policy$policy/"
            val minimum = SysfsUtils.readInt(path + "cpuinfo_min_freq", -1)
            val maximum = SysfsUtils.readInt(path + "cpuinfo_max_freq", -1)
            if (minimum <= 0 || maximum < minimum) return false
            val ceiling = maxOf(minimum.toLong(), maximum.toLong() * ratio / 100)
            val settled = SysfsUtils.awaitValue({
                val actual = SysfsUtils.readInt(path + "scaling_max_freq", -1)
                if (actual > 0 && actual <= ceiling) "1" else "0"
            }, "1")
            if (!settled) return false
        }
        return true
    }

    private fun restoreManualValues(mode: Int): Boolean {
        if (mode == MODE_BATTERY_SAVER) return true
        var success = true
        if (prefs.getBoolean("cpu_enable", false)) {
            for (cluster in CLUSTERS) {
                val prefix = "cpu_${cluster}_"
                success = success and CPUUtils.setCluster(
                    cluster, policyFor(cluster),
                    prefs.getString(prefix + "min_frequency", "0") ?: "0",
                    prefs.getString(prefix + "max_frequency", "0") ?: "0",
                    prefs.getString(prefix + "governor", "schedutil") ?: "schedutil"
                )
            }
        }
        if (prefs.getBoolean("gpu_enable", false)) {
            success = success and GPUUtils.setFrequencies(
                prefs.getString(KEY_GPU_MIN_FREQ, "0") ?: "0",
                prefs.getString(KEY_GPU_MAX_FREQ, "0") ?: "0",
                prefs.getString(KEY_GPU_GOVERNOR, "msm-adreno-tz") ?: "msm-adreno-tz"
            )
        }
        if (prefs.getBoolean("storage_enable", false)) {
            success = success and StorageUtils.setIoScheduler(prefs.getString(KEY_IO_SCHEDULER, "bfq") ?: "bfq")
        }
        return success
    }

    private fun applyCategory(key: String, mode: Int): Boolean = when (key) {
        "cpu_enable" ->
            CPUUtils.setCluster("little", 0, "0", "0", "schedutil") &&
                CPUUtils.setCluster("big", 4, "0", "0", "schedutil") &&
                CPUUtils.setCluster("prime", 7, "0", "0", "schedutil")
        "gpu_enable" -> GPUUtils.setFrequencies(
            "0",
            if (mode == MODE_BATTERY_SAVER) getValidatedStockValueForMode(mode, KEY_GPU_MAX_FREQ) else "0",
            "msm-adreno-tz"
        )
        "storage_enable" -> StorageUtils.setIoScheduler(getValidatedStockValueForMode(mode, KEY_IO_SCHEDULER))
        else -> false
    }

    private fun applyTouchPanel(mode: Int): Boolean {
        val game = mode == MODE_PERFORMANCE || (mode == MODE_BALANCE && prefs.getBoolean("game_mode", false))
        val edge = mode == MODE_PERFORMANCE || (mode == MODE_BALANCE && prefs.getBoolean("edge_touch", false))
        return writeTouch(FILE_GAME, game) && writeTouch(FILE_EDGE, edge)
    }

    private fun writeTouch(path: String, enabled: Boolean): Boolean {
        if (!SysfsUtils.isReadable(path)) return true
        val value = if (enabled) "1" else "0"
        if (!SysfsUtils.writeValue(path, value)) return false
        val actual = SysfsUtils.readLine(path)
        return actual != null && value == actual.split(",", limit = 2)[0].trim()
    }

    private fun updateNotification(mode: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (mode != MODE_PERFORMANCE) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.powerprofile_tile_label), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, PowertoolsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_thermal_performance)
                .setContentTitle(context.getString(R.string.powertools_performance_active))
                .setContentText(context.getString(R.string.powertools_performance_notification))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
        )
    }

    // ---------------------------------------------------------------- stock values / validation

    fun getStockValueForMode(mode: Int, key: String): String {
        val targetIndex = if (mode == MODE_BATTERY_SAVER || mode == MODE_PERFORMANCE) mode else MODE_BALANCE
        // Battery saver max-freq keys: ceiling is derived from kp_max_freq_ratio.
        if (mode == MODE_BATTERY_SAVER && key.startsWith("cpu_") && key.endsWith("_max_frequency")) {
            computeKpCeilingForKey(key)?.let { return it }
        }
        return PROFILE_DEFAULTS[key]?.get(targetIndex) ?: ""
    }

    fun getValidatedStockValueForMode(mode: Int, key: String): String =
        validateKernelValue(key, getStockValueForMode(mode, key))

    /** Highest available frequency at or below the kprofiles ceiling, or null if no clamp is active. */
    private fun computeKpCeilingForKey(key: String): String? {
        val ratio = readKpMaxFreqRatio()
        if (ratio >= 100) return null
        val paths = sysfsPathsForKey(key) ?: return null
        val available = KernelOptionUtils.readAvailableValues(paths, false)
        if (available.isEmpty()) return null

        val maxFreq = available.mapNotNull { it.toLongOrNull() }.maxOrNull() ?: 0L
        val ceiling = maxFreq * ratio / 100
        var best: String? = null
        var bestVal = 0L
        for (f in available) {
            val v = f.toLongOrNull() ?: continue
            if (v <= ceiling && v > bestVal) {
                best = f
                bestVal = v
            }
        }
        return best
    }

    private fun validateKernelValue(key: String, requested: String): String {
        val paths = sysfsPathsForKey(key)
        if (paths == null || requested.isEmpty()) return requested
        val available = KernelOptionUtils.readAvailableValues(paths, key == KEY_IO_SCHEDULER)
        if (available.isEmpty()) return requested
        if (requested in available) return requested
        return if (key.endsWith("_frequency")) nearestFrequency(key, requested, available) else available[0]
    }

    private fun nearestFrequency(key: String, requested: String, available: List<String>): String {
        try {
            val requestedValue = requested.toLong()
            var fallback: String? = null
            var fallbackValue = 0L
            for (value in available) {
                val candidate = value.toLong()
                if (key.contains("_min_frequency")) {
                    if (candidate >= requestedValue && (fallback == null || candidate < fallbackValue)) {
                        fallback = value
                        fallbackValue = candidate
                    }
                } else if (key.contains("_max_frequency")) {
                    if (candidate <= requestedValue && (fallback == null || candidate > fallbackValue)) {
                        fallback = value
                        fallbackValue = candidate
                    }
                }
            }
            if (fallback != null) return fallback

            fallback = available[0]
            fallbackValue = fallback.toLong()
            for (value in available) {
                val candidate = value.toLong()
                if (key.contains("_min_frequency")) {
                    if (candidate > fallbackValue) {
                        fallback = value
                        fallbackValue = candidate
                    }
                } else if (candidate < fallbackValue) {
                    fallback = value
                    fallbackValue = candidate
                }
            }
            return fallback!!
        } catch (e: NumberFormatException) {
            return available[0]
        }
    }

    private fun sysfsPathsForKey(key: String): List<String>? = when (key) {
        KEY_CPU_LITTLE_MIN_FREQ, KEY_CPU_LITTLE_MAX_FREQ -> listOf(KernelOptionUtils.CPU_LITTLE_AVAILABLE_FREQUENCIES)
        KEY_CPU_BIG_MIN_FREQ, KEY_CPU_BIG_MAX_FREQ -> listOf(KernelOptionUtils.CPU_BIG_AVAILABLE_FREQUENCIES)
        KEY_CPU_PRIME_MIN_FREQ, KEY_CPU_PRIME_MAX_FREQ -> listOf(KernelOptionUtils.CPU_PRIME_AVAILABLE_FREQUENCIES)
        KEY_CPU_LITTLE_GOVERNOR -> listOf(KernelOptionUtils.CPU_LITTLE_AVAILABLE_GOVERNORS)
        KEY_CPU_BIG_GOVERNOR -> listOf(KernelOptionUtils.CPU_BIG_AVAILABLE_GOVERNORS)
        KEY_CPU_PRIME_GOVERNOR -> listOf(KernelOptionUtils.CPU_PRIME_AVAILABLE_GOVERNORS)
        KEY_GPU_MIN_FREQ, KEY_GPU_MAX_FREQ -> KernelOptionUtils.GPU_AVAILABLE_FREQUENCY_PATHS
        KEY_GPU_GOVERNOR -> listOf(KernelOptionUtils.GPU_AVAILABLE_GOVERNORS)
        KEY_IO_SCHEDULER -> listOf(KernelOptionUtils.IO_SCHEDULER)
        else -> null
    }

    /** Public so the UI can show exactly the value the hardware layer would accept. */
    fun validate(key: String, requested: String): String = validateKernelValue(key, requested)

    // ---------------------------------------------------------------- helpers

    private suspend fun serial(block: () -> Boolean): Boolean = withContext(dispatcher) {
        try {
            block()
        } catch (e: RuntimeException) {
            Log.e(TAG, "Profile control failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "PowerProfileUtil"
        private const val SYS_PROP = "sys.perf_mode_active"
        private const val KPROFILES_NODE = "/sys/kernel/kprofiles/kp_mode"
        private const val FILE_GAME = "/proc/touchpanel/game_switch_enable"
        private const val FILE_EDGE = "/proc/touchpanel/oplus_tp_direction"
        private const val CHANNEL = "power_profile"
        private const val NOTIFICATION_ID = 2003

        /** Minimum gap between two profile switches, whichever surface asks (screen, hero, QS tile). */
        const val PROFILE_COOLDOWN_MS = 2500L

        @Volatile
        private var lastProfileChange = 0L

        private fun markProfileChange() {
            lastProfileChange = SystemClock.elapsedRealtime()
        }

        /** Milliseconds still to wait before the next profile switch is accepted, 0 when ready. */
        @JvmStatic
        fun profileCooldownRemaining(): Long =
            maxOf(0L, PROFILE_COOLDOWN_MS - (SystemClock.elapsedRealtime() - lastProfileChange))

        /** One writer at a time, process wide. */
        private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

        const val MODE_BATTERY_SAVER = 0
        const val MODE_BALANCE = 1
        const val MODE_PERFORMANCE = 2
        const val MODE_UNKNOWN = 4

        const val KEY_TCP = "tcp_congestion_control"
        const val KEY_GPU_MIN_FREQ = "gpu_min_frequency"
        const val KEY_GPU_MAX_FREQ = "gpu_max_frequency"
        const val KEY_GPU_GOVERNOR = "gpu_governor"
        const val KEY_CPU_LITTLE_MIN_FREQ = "cpu_little_min_frequency"
        const val KEY_CPU_LITTLE_MAX_FREQ = "cpu_little_max_frequency"
        const val KEY_CPU_LITTLE_GOVERNOR = "cpu_little_governor"
        const val KEY_CPU_BIG_MIN_FREQ = "cpu_big_min_frequency"
        const val KEY_CPU_BIG_MAX_FREQ = "cpu_big_max_frequency"
        const val KEY_CPU_BIG_GOVERNOR = "cpu_big_governor"
        const val KEY_CPU_PRIME_MIN_FREQ = "cpu_prime_min_frequency"
        const val KEY_CPU_PRIME_MAX_FREQ = "cpu_prime_max_frequency"
        const val KEY_CPU_PRIME_GOVERNOR = "cpu_prime_governor"
        const val KEY_IO_SCHEDULER = "io_scheduler"

        val CLUSTERS = listOf("little", "big", "prime")
        private val MANUAL_KEYS = listOf("cpu_enable", "gpu_enable", "storage_enable")

        fun policyFor(cluster: String): Int = when (cluster) {
            "little" -> 0
            "big" -> 4
            else -> 7
        }

        val PERSIST_KEYS = listOf(
            KEY_CPU_LITTLE_MIN_FREQ, KEY_CPU_LITTLE_MAX_FREQ, KEY_CPU_LITTLE_GOVERNOR,
            KEY_CPU_BIG_MIN_FREQ, KEY_CPU_BIG_MAX_FREQ, KEY_CPU_BIG_GOVERNOR,
            KEY_CPU_PRIME_MIN_FREQ, KEY_CPU_PRIME_MAX_FREQ, KEY_CPU_PRIME_GOVERNOR,
            KEY_GPU_MIN_FREQ, KEY_GPU_MAX_FREQ, KEY_GPU_GOVERNOR,
            KEY_IO_SCHEDULER
        )

        /** [power save, normal, performance] */
        private val PROFILE_DEFAULTS: Map<String, List<String>> = mapOf(
            KEY_CPU_LITTLE_GOVERNOR to listOf("schedutil", "schedutil", "schedutil"),
            KEY_CPU_BIG_GOVERNOR to listOf("schedutil", "schedutil", "schedutil"),
            KEY_CPU_PRIME_GOVERNOR to listOf("schedutil", "schedutil", "schedutil"),
            KEY_GPU_GOVERNOR to listOf("msm-adreno-tz", "msm-adreno-tz", "msm-adreno-tz"),
            KEY_IO_SCHEDULER to listOf("bfq", "bfq", "kyber"),

            KEY_CPU_LITTLE_MIN_FREQ to listOf("300000", "300000", "300000"),
            KEY_CPU_BIG_MIN_FREQ to listOf("710400", "710400", "710400"),
            KEY_CPU_PRIME_MIN_FREQ to listOf("844800", "844800", "844800"),

            KEY_CPU_LITTLE_MAX_FREQ to listOf("1401600", "1804800", "1804800"),
            KEY_CPU_BIG_MAX_FREQ to listOf("1881600", "2419200", "2419200"),
            KEY_CPU_PRIME_MAX_FREQ to listOf("2150400", "2841600", "2841600"),

            KEY_GPU_MIN_FREQ to listOf("315000000", "315000000", "315000000"),
            KEY_GPU_MAX_FREQ to listOf("540000000", "840000000", "840000000")
        )

        /** kp_max_freq_ratio (10..100); 100 = no clamping / node missing. */
        fun readKpMaxFreqRatio(): Int {
            val ratio = SysfsUtils.readLine(KernelOptionUtils.KP_MAX_FREQ_RATIO)?.trim()?.toIntOrNull()
            return if (ratio != null && ratio in 10..100) ratio else 100
        }

        /** kprofiles ceiling for an available-frequencies node, 0 when nothing is clamped. */
        fun getKpCeilingForCluster(availableFreqsPath: String): Long {
            val ratio = readKpMaxFreqRatio()
            if (ratio >= 100) return 0
            val available = KernelOptionUtils.readAvailableValues(availableFreqsPath, false)
            if (available.isEmpty()) return 0
            val maxFreq = available.mapNotNull { it.toLongOrNull() }.maxOrNull() ?: 0L
            return maxFreq * ratio / 100
        }
    }
}

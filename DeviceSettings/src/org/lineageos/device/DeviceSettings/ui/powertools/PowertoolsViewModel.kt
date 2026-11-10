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

package org.lineageos.device.DeviceSettings.ui.powertools

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.device.DeviceSettings.R
import org.lineageos.device.DeviceSettings.defaultPrefs
import org.lineageos.device.DeviceSettings.powertools.KernelOptionUtils
import org.lineageos.device.DeviceSettings.powertools.PowerProfileUtil
import org.lineageos.device.DeviceSettings.powertools.SysfsUtils
import org.lineageos.device.DeviceSettings.powertools.Telemetry
import org.lineageos.device.DeviceSettings.ui.components.Choice

data class ChoiceState(val key: String, val choices: List<Choice>, val selected: String?, val enabled: Boolean) {
    val selectedLabel: String get() = choices.firstOrNull { it.value == selected }?.label ?: selected.orEmpty()
}

data class FreqState(
    val minKey: String,
    val maxKey: String,
    val freqs: List<Long>,
    val minIndex: Int,
    val maxIndex: Int,
    /** Lowest index the min thumb may take (Performance profile floor). */
    val lowestMin: Int,
    /** Highest index the max thumb may take (Power Save ceiling). */
    val highestMax: Int,
    val limitNote: String?,
    val enabled: Boolean,
)

data class ClusterState(
    val id: String,
    @param:StringRes val titleRes: Int,
    val freq: FreqState?,
    val governor: ChoiceState?,
    val currentKhz: Long?,
)

data class PowertoolsUiState(
    val mode: Int = PowerProfileUtil.MODE_BALANCE,
    val applying: Boolean = false,
    val pendingMode: Int? = null,
    val cpuEnabled: Boolean = false,
    val gpuEnabled: Boolean = false,
    val storageEnabled: Boolean = false,
    val manualAllowed: Boolean = false,
    val clusters: List<ClusterState> = emptyList(),
    val gpuFreq: FreqState? = null,
    val gpuGovernor: ChoiceState? = null,
    val io: ChoiceState? = null,
    val tcp: ChoiceState? = null,
    val telemetry: Telemetry? = null,
    val loaded: Boolean = false,
) {
    @get:StringRes
    val statusRes: Int
        get() = when (mode) {
            PowerProfileUtil.MODE_PERFORMANCE -> R.string.mode_status_performance
            PowerProfileUtil.MODE_BATTERY_SAVER -> R.string.mode_status_battery_saver
            else -> R.string.mode_status_balanced
        }
}

/**
 * Port of PowertoolsSettingsFragment's logic. Rules kept 1:1:
 * - values shown come from SharedPreferences, healed against what the kernel really offers;
 * - Power Save caps max frequency and bans some governors, Performance floors min frequency and
 *   bans others (same messages as before);
 * - manual CPU/GPU/storage control only exists in Normal and Performance, and only while its
 *   toggle is on;
 * - while anything is being applied every control is locked.
 */
class PowertoolsViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app
    private val prefs = ctx.defaultPrefs()
    private val profiles = PowerProfileUtil(ctx)

    private val _state = MutableStateFlow(PowertoolsUiState())
    val state: StateFlow<PowertoolsUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var telemetryJob: Job? = null

    init {
        refresh()
    }

    fun onResume() {
        refresh()
    }

    fun onPause() {
        stopTelemetry()
    }

    fun startTelemetry() {
        if (telemetryJob?.isActive == true) return
        telemetryJob = viewModelScope.launch {
            while (isActive) {
                val sample = withContext(Dispatchers.IO) { Telemetry.sample(ctx) }
                _state.update { it.copy(telemetry = sample) }
                delay(1500)
            }
        }
    }

    fun stopTelemetry() {
        telemetryJob?.cancel()
        telemetryJob = null
        _state.update { it.copy(telemetry = null) }
    }

    override fun onCleared() {
        stopTelemetry()
        super.onCleared()
    }

    // ---------------------------------------------------------------- actions

    fun setMode(mode: Int) {
        val s = _state.value
        if (s.applying || mode == s.mode) return
        if (PowerProfileUtil.profileCooldownRemaining() > 0) {
            say(ctx.getString(R.string.profile_cooldown))
            return
        }
        _state.update { it.copy(applying = true, pendingMode = mode) }
        viewModelScope.launch {
            val ok = profiles.setMode(mode)
            finishApply(ok)
        }
    }

    fun setManualEnabled(key: String, enabled: Boolean) {
        if (_state.value.applying) return
        _state.update { it.copy(applying = true) }
        viewModelScope.launch {
            val ok = profiles.setManualEnabled(key, enabled)
            finishApply(ok)
        }
    }

    /** Applies one value after the profile restriction check. */
    fun setValue(key: String, value: String) {
        applyValues(listOf(key to value))
    }

    /**
     * Commits a range pick. Order matters so the kernel never sees min > max: when the ceiling goes
     * up (or stays) raise max first, otherwise lower min first.
     */
    fun commitRange(freq: FreqState, newMin: Int, newMax: Int) {
        val minChanged = newMin != freq.minIndex
        val maxChanged = newMax != freq.maxIndex
        if (!minChanged && !maxChanged) return
        val minPair = freq.minKey to freq.freqs[newMin].toString()
        val maxPair = freq.maxKey to freq.freqs[newMax].toString()
        val ordered = when {
            minChanged && maxChanged -> if (newMax >= freq.maxIndex) listOf(maxPair, minPair) else listOf(minPair, maxPair)
            minChanged -> listOf(minPair)
            else -> listOf(maxPair)
        }
        applyValues(ordered)
    }

    private fun applyValues(pairs: List<Pair<String, String>>) {
        if (_state.value.applying) return
        for ((key, value) in pairs) {
            val restriction = checkRestriction(key, value)
            if (restriction != null) {
                say(restriction)
                refresh()
                return
            }
        }
        _state.update { it.copy(applying = true) }
        viewModelScope.launch {
            var ok = true
            for ((key, value) in pairs) {
                if (!profiles.setHardwareValue(key, value)) {
                    ok = false
                    break
                }
            }
            finishApply(ok)
        }
    }

    private suspend fun finishApply(success: Boolean) {
        val snapshot = withContext(Dispatchers.IO) { compute(profiles.currentMode) }
        _state.update { snapshot.copy(telemetry = it.telemetry, applying = false, pendingMode = null) }
        if (!success) say(ctx.getString(R.string.powertools_apply_failed))
    }

    fun refresh() {
        viewModelScope.launch {
            if (_state.value.applying) return@launch
            val snapshot = withContext(Dispatchers.IO) { compute(profiles.currentMode) }
            _state.update {
                if (it.applying) it else snapshot.copy(telemetry = it.telemetry)
            }
        }
    }

    private fun say(text: String) {
        _messages.trySend(text)
    }

    // ---------------------------------------------------------------- restrictions (verbatim rules)

    private fun checkRestriction(key: String, value: String): String? {
        val mode = _state.value.mode
        if (key.endsWith("_frequency")) {
            val freq = value.toLongOrNull()
            if (freq != null) {
                if (mode == PowerProfileUtil.MODE_BATTERY_SAVER && key.contains("_max_frequency")) {
                    val default = profiles.getValidatedStockValueForMode(mode, key)
                    val defaultMax = default.toLongOrNull()
                    if (defaultMax != null && freq > defaultMax) {
                        return ctx.getString(R.string.powertools_restricted_powersave_max, KernelOptionUtils.formatFrequency(default))
                    }
                } else if (mode == PowerProfileUtil.MODE_PERFORMANCE && key.contains("_min_frequency")) {
                    val default = profiles.getValidatedStockValueForMode(mode, key)
                    val defaultMin = default.toLongOrNull()
                    if (defaultMin != null && freq < defaultMin) {
                        return ctx.getString(R.string.powertools_restricted_performance_min, KernelOptionUtils.formatFrequency(default))
                    }
                }
            }
        }
        val isCpuGov = key == PowerProfileUtil.KEY_CPU_LITTLE_GOVERNOR ||
            key == PowerProfileUtil.KEY_CPU_BIG_GOVERNOR || key == PowerProfileUtil.KEY_CPU_PRIME_GOVERNOR
        val isGpuGov = key == PowerProfileUtil.KEY_GPU_GOVERNOR
        val isIo = key == PowerProfileUtil.KEY_IO_SCHEDULER
        val label = KernelOptionUtils.displayValue(value)
        if (mode == PowerProfileUtil.MODE_BATTERY_SAVER) {
            if ((isCpuGov && value == "performance") || (isGpuGov && value == "performance") || (isIo && value == "kyber")) {
                return ctx.getString(R.string.governor_restricted_powersave, label)
            }
        } else if (mode == PowerProfileUtil.MODE_PERFORMANCE) {
            if ((isCpuGov && (value == "conservative" || value == "powersave")) ||
                (isGpuGov && (value == "userspace" || value == "powersave")) ||
                (isIo && value == "bfq")
            ) {
                return ctx.getString(R.string.governor_restricted_performance, label)
            }
        }
        return null
    }

    // ---------------------------------------------------------------- state building (IO thread)

    private fun compute(mode: Int): PowertoolsUiState {
        val cpuOn = prefs.getBoolean("cpu_enable", false)
        val gpuOn = prefs.getBoolean("gpu_enable", false)
        val storageOn = prefs.getBoolean("storage_enable", false)
        val manualAllowed = mode == PowerProfileUtil.MODE_BALANCE || mode == PowerProfileUtil.MODE_PERFORMANCE
        val cpuActive = cpuOn && manualAllowed
        val gpuActive = gpuOn && manualAllowed
        val storageActive = storageOn && manualAllowed
        val saver = mode == PowerProfileUtil.MODE_BATTERY_SAVER

        val clusters = listOf(
            clusterOf(
                "little", R.string.powertools_cpu_little_category, mode, cpuActive,
                KernelOptionUtils.CPU_LITTLE_AVAILABLE_FREQUENCIES, KernelOptionUtils.CPU_LITTLE_AVAILABLE_GOVERNORS,
                PowerProfileUtil.KEY_CPU_LITTLE_MIN_FREQ, PowerProfileUtil.KEY_CPU_LITTLE_MAX_FREQ,
                PowerProfileUtil.KEY_CPU_LITTLE_GOVERNOR,
                R.array.cpu_little_freq_values, KernelOptionUtils.CPU_LITTLE_MIN_FREQ,
            ),
            clusterOf(
                "big", R.string.powertools_cpu_big_category, mode, cpuActive,
                KernelOptionUtils.CPU_BIG_AVAILABLE_FREQUENCIES, KernelOptionUtils.CPU_BIG_AVAILABLE_GOVERNORS,
                PowerProfileUtil.KEY_CPU_BIG_MIN_FREQ, PowerProfileUtil.KEY_CPU_BIG_MAX_FREQ,
                PowerProfileUtil.KEY_CPU_BIG_GOVERNOR,
                R.array.cpu_big_freq_values, KernelOptionUtils.CPU_BIG_MIN_FREQ,
            ),
            clusterOf(
                "prime", R.string.powertools_cpu_prime_category, mode, cpuActive,
                KernelOptionUtils.CPU_PRIME_AVAILABLE_FREQUENCIES, KernelOptionUtils.CPU_PRIME_AVAILABLE_GOVERNORS,
                PowerProfileUtil.KEY_CPU_PRIME_MIN_FREQ, PowerProfileUtil.KEY_CPU_PRIME_MAX_FREQ,
                PowerProfileUtil.KEY_CPU_PRIME_GOVERNOR,
                R.array.cpu_prime_freq_values, KernelOptionUtils.CPU_PRIME_MIN_FREQ,
            ),
        )

        val gpuFreqs = freqList(KernelOptionUtils.GPU_AVAILABLE_FREQUENCY_PATHS, R.array.gpu_frequency_values)
        val gpuFreq = freqState(
            mode, gpuFreqs, PowerProfileUtil.KEY_GPU_MIN_FREQ, PowerProfileUtil.KEY_GPU_MAX_FREQ, 0L, gpuActive,
        )
        val gpuGovernor = choiceState(
            PowerProfileUtil.KEY_GPU_GOVERNOR, mode, gpuActive, listOf(KernelOptionUtils.GPU_AVAILABLE_GOVERNORS),
            false, R.array.gpu_governor_entries, R.array.gpu_governor_values,
        )
        val io = choiceState(
            PowerProfileUtil.KEY_IO_SCHEDULER, mode, storageActive, listOf(KernelOptionUtils.IO_SCHEDULER),
            true, R.array.io_scheduler_entries, R.array.io_scheduler_values,
        )
        val tcp = choiceState(
            PowerProfileUtil.KEY_TCP, mode, true, listOf(KernelOptionUtils.TCP_AVAILABLE_CONGESTION_CONTROL),
            false, R.array.tcp_congestion_entries, R.array.tcp_congestion_values,
        )
        return PowertoolsUiState(
            mode = mode, cpuEnabled = cpuOn, gpuEnabled = gpuOn, storageEnabled = storageOn,
            manualAllowed = manualAllowed, clusters = clusters, gpuFreq = gpuFreq, gpuGovernor = gpuGovernor,
            io = io, tcp = tcp, loaded = true,
        )
    }

    private fun clusterOf(
        id: String, @StringRes title: Int, mode: Int, enabled: Boolean,
        freqPath: String, govPath: String, minKey: String, maxKey: String, govKey: String,
        fallbackFreqArray: Int, @Suppress("UNUSED_PARAMETER") curMinPath: String,
    ): ClusterState {
        val saver = mode == PowerProfileUtil.MODE_BATTERY_SAVER
        val ceiling = if (saver) PowerProfileUtil.getKpCeilingForCluster(freqPath) else 0L
        val freqs = freqList(listOf(freqPath), fallbackFreqArray)
        val policy = PowerProfileUtil.policyFor(id)
        return ClusterState(
            id = id,
            titleRes = title,
            freq = freqState(mode, freqs, minKey, maxKey, ceiling, enabled),
            governor = choiceState(
                govKey, mode, enabled, listOf(govPath), false,
                R.array.cpu_governor_entries, R.array.cpu_governor_values,
            ),
            currentKhz = SysfsUtils.readLong("/sys/devices/system/cpu/cpufreq/policy$policy/scaling_cur_freq"),
        )
    }

    private fun freqList(paths: List<String>, fallbackArray: Int): List<Long> {
        val raw = KernelOptionUtils.readAvailableValues(paths, false).ifEmpty {
            ctx.resources.getStringArray(fallbackArray).toList()
        }
        return raw.mapNotNull { it.toLongOrNull() }.distinct().sorted()
    }

    private fun freqState(
        mode: Int, freqs: List<Long>, minKey: String, maxKey: String, kpCeiling: Long, enabled: Boolean,
    ): FreqState? {
        if (freqs.size < 2) return null
        val minSaved = healed(minKey, freqs.map { it.toString() }, mode)
        val maxSaved = healed(maxKey, freqs.map { it.toString() }, mode)

        var highest = freqs.lastIndex
        var lowest = 0
        var note: String? = null
        if (mode == PowerProfileUtil.MODE_BATTERY_SAVER) {
            val stock = profiles.getValidatedStockValueForMode(mode, maxKey)
            val cap = stock.toLongOrNull()
            val limit = listOfNotNull(cap, kpCeiling.takeIf { it > 0 }).minOrNull()
            if (limit != null) {
                val idx = freqs.indexOfLast { it <= limit }
                if (idx >= 0) highest = idx
                note = ctx.getString(R.string.powertools_restricted_powersave_max, KernelOptionUtils.formatFrequency(freqs[highest].toString()))
            }
        } else if (mode == PowerProfileUtil.MODE_PERFORMANCE) {
            val stock = profiles.getValidatedStockValueForMode(mode, minKey).toLongOrNull()
            if (stock != null) {
                val idx = freqs.indexOfFirst { it >= stock }
                if (idx >= 0) lowest = idx
                note = ctx.getString(R.string.powertools_restricted_performance_min, KernelOptionUtils.formatFrequency(freqs[lowest].toString()))
            }
        }
        val minIdx = nearestIndex(freqs, minSaved).coerceIn(0, freqs.lastIndex)
        val maxIdx = nearestIndex(freqs, maxSaved).coerceIn(minIdx, freqs.lastIndex)
        return FreqState(minKey, maxKey, freqs, minIdx, maxIdx, lowest, highest, note, enabled)
    }

    private fun nearestIndex(freqs: List<Long>, value: String?): Int {
        val v = value?.toLongOrNull() ?: return 0
        var best = 0
        for (i in freqs.indices) if (kotlin.math.abs(freqs[i] - v) < kotlin.math.abs(freqs[best] - v)) best = i
        return best
    }

    private fun choiceState(
        key: String, mode: Int, enabled: Boolean, paths: List<String>, dropNone: Boolean,
        fallbackEntries: Int, fallbackValues: Int,
    ): ChoiceState {
        val fromSysfs = KernelOptionUtils.readAvailableValues(paths, dropNone)
        val choices = if (fromSysfs.isNotEmpty()) {
            fromSysfs.map { Choice(it, KernelOptionUtils.prettifyName(it)) }
        } else {
            val entries = ctx.resources.getStringArray(fallbackEntries)
            val values = ctx.resources.getStringArray(fallbackValues)
            values.indices.map { Choice(values[it], entries.getOrElse(it) { values[it] }) }
        }
        val selected = healed(key, choices.map { it.value }, mode)
        return ChoiceState(key, choices, selected, enabled)
    }

    /**
     * Saved value for [key] if the kernel still offers it, else the value the kernel is really
     * using, else the profile's stock value. The healed value is written back to prefs.
     */
    private fun healed(key: String, options: List<String>, mode: Int): String? {
        var saved = prefs.getString(key, "").orEmpty()
        if (saved !in options) {
            val active = activeValue(key)
            saved = if (active != null && active in options) active
            else profiles.getValidatedStockValueForMode(mode, key)
            if (saved.isNotEmpty()) prefs.edit().putString(key, saved).apply()
        }
        return saved.ifEmpty { null }
    }

    private fun activeValue(key: String): String? {
        val path = when (key) {
            PowerProfileUtil.KEY_CPU_LITTLE_MIN_FREQ -> KernelOptionUtils.CPU_LITTLE_MIN_FREQ
            PowerProfileUtil.KEY_CPU_LITTLE_MAX_FREQ -> KernelOptionUtils.CPU_LITTLE_MAX_FREQ
            PowerProfileUtil.KEY_CPU_LITTLE_GOVERNOR -> KernelOptionUtils.CPU_LITTLE_GOVERNOR
            PowerProfileUtil.KEY_CPU_BIG_MIN_FREQ -> KernelOptionUtils.CPU_BIG_MIN_FREQ
            PowerProfileUtil.KEY_CPU_BIG_MAX_FREQ -> KernelOptionUtils.CPU_BIG_MAX_FREQ
            PowerProfileUtil.KEY_CPU_BIG_GOVERNOR -> KernelOptionUtils.CPU_BIG_GOVERNOR
            PowerProfileUtil.KEY_CPU_PRIME_MIN_FREQ -> KernelOptionUtils.CPU_PRIME_MIN_FREQ
            PowerProfileUtil.KEY_CPU_PRIME_MAX_FREQ -> KernelOptionUtils.CPU_PRIME_MAX_FREQ
            PowerProfileUtil.KEY_CPU_PRIME_GOVERNOR -> KernelOptionUtils.CPU_PRIME_GOVERNOR
            PowerProfileUtil.KEY_GPU_MIN_FREQ -> KernelOptionUtils.GPU_MIN_FREQ
            PowerProfileUtil.KEY_GPU_MAX_FREQ -> KernelOptionUtils.GPU_MAX_FREQ
            PowerProfileUtil.KEY_GPU_GOVERNOR -> KernelOptionUtils.GPU_GOVERNOR
            PowerProfileUtil.KEY_TCP -> return SysfsUtils.readLine(KernelOptionUtils.TCP_CONGESTION_CONTROL)
            PowerProfileUtil.KEY_IO_SCHEDULER -> return KernelOptionUtils.readBracketedValue(KernelOptionUtils.IO_SCHEDULER)
            else -> return null
        }
        return SysfsUtils.readLine(path)?.takeIf { it.isNotEmpty() }
    }
}

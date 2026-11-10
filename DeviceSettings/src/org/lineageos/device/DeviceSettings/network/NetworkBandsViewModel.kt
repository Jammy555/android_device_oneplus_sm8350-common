/*
 * Copyright (C) 2025 Lunaris Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package org.lineageos.device.DeviceSettings.network

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.SystemProperties
import android.provider.Settings
import android.telephony.AccessNetworkConstants
import android.telephony.CellInfo
import android.telephony.PhysicalChannelConfig
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.lineageos.device.DeviceSettings.R

data class SimUi(val subId: Int, val label: String)

data class BandUi(
    val key: String,
    val label: String,
    val freqHint: String,
    val checked: Boolean,
    val isActive: Boolean,
    val isPCell: Boolean,
    val isSCell: Boolean,
)

data class NetworkBandsUiState(
    val sims: List<SimUi> = emptyList(),
    val currentSubId: Int = SubscriptionManager.DEFAULT_SUBSCRIPTION_ID,
    val hasSim: Boolean = false,
    val airplane: Boolean = false,
    val rats: NetworkModem.Rats = NetworkModem.Rats(true, true, true, true),
    val carrierName: String = "",
    val carrierPresets: List<String> = emptyList(),
    val carrierPreset: Int = 0,
    /** 0 = NSA only, 1 = Auto (SA+NSA), 2 = SA only */
    val nrMode: Int = 1,
    val nrActiveText: String = "Active: Checking...",
    val nrActive: Boolean = false,
    /** 0 = 5G, 1 = 4G, 2 = 3G, 3 = 2G */
    val generation: Int = 0,
    val bands: List<BandUi> = emptyList(),
    val genSelected: Int = 0,
    val genTotal: Int = 0,
    val totalChecked: Int = 0,
    val modified: Boolean = false,
    val status: String = "",
    val profileLabel: String = "Manual",
    val bandLockEnabled: Boolean = false,
    val activeBands: String = "No Active Band",
    val diag: DiagSnapshot? = null,
    val vonr: Boolean = true,
    val showNonIndiaPopup: Boolean = false,
) {
    val available: Boolean get() = hasSim && !airplane
    val ratPreset: Int get() = rats.presetIndex()
    val tech: String get() = diag?.tech ?: "--"
    val is2gOnly: Boolean get() = rats.g2 && !rats.g3 && !rats.g4 && !rats.g5
    fun generationEnabled(tab: Int): Boolean = available && when (tab) {
        0 -> rats.g5
        1 -> rats.g4
        2 -> rats.g3
        else -> rats.g2
    }
}

/**
 * Port of NetworkBandsFragment's logic. Rules kept: per-SIM saved bands / NR mode / RAT mask in
 * `band_lock_prefs`, the 2 s debounce before touching the modem, 4G-anchor guard for 5G NSA,
 * data-SIM / ICCID change auto reset, and the radio power cycle after apply and reset.
 * Telephony work runs off the main thread; the radio power-on after a cycle always runs to the end.
 */
@SuppressLint("MissingPermission")
class NetworkBandsViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app
    private val prefs = NetworkPrefs.of(ctx)
    private val handler = Handler(Looper.getMainLooper())
    private val baseTm: TelephonyManager = ctx.getSystemService(TelephonyManager::class.java)
    private val subscriptionManager: SubscriptionManager = ctx.getSystemService(SubscriptionManager::class.java)
    private val mainExecutor = ctx.mainExecutor

    private val entries: MutableList<BandEntry> = BandCatalog.buildAll()
    private var activeSubs: List<SubscriptionInfo> = emptyList()
    private var currentSubId = SubscriptionManager.DEFAULT_SUBSCRIPTION_ID
    private var lastConfigs: List<PhysicalChannelConfig> = emptyList()
    private val trend = DiagTrend()
    private val knownIccid = HashMap<Int, String>()
    private var lastDefaultDataSubId = -1
    private var lastNrInteraction = 0L

    private var generation = 0
    private var rats = NetworkModem.Rats(true, true, true, true)
    private var carrierName = ""
    private var carrierPresets: List<String> = emptyList()
    private var carrierPreset = 0
    private var nrMode = 1
    private var nrActiveText = "Active: Checking..."
    private var nrActive = false
    private var status = ""
    private var profileLabel = "Manual"
    private var bandLockEnabled = false
    private var activeBands = "No Active Band"
    private var diag: DiagSnapshot? = null
    private var showNonIndiaPopup = false

    private var monitor: BandMonitorCallback? = null
    private var subListener: SubscriptionManager.OnSubscriptionsChangedListener? = null
    private var receiverRegistered = false
    private var ratJob: Job? = null
    private var nrJob: Job? = null
    private var liveJob: Job? = null
    private var activeJob: Job? = null

    private val _state = MutableStateFlow(NetworkBandsUiState())
    val state: StateFlow<NetworkBandsUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val airplaneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Intent.ACTION_AIRPLANE_MODE_CHANGED == intent.action) {
                handler.post {
                    refreshActive()
                    refreshLive(null)
                    updateNrForRats()
                    publish()
                }
            }
        }
    }

    init {
        setupSims()
        loadCurrentBands()
        setupCarrierPresets()
        syncRatsFromSystem()
        checkFirstTimePopup()
        publish()
    }

    // ------------------------------------------------------------------ lifecycle

    fun onResume() {
        try {
            ctx.registerReceiver(airplaneReceiver, IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED), Context.RECEIVER_EXPORTED)
            receiverRegistered = true
        } catch (_: Exception) {
        }
        registerMonitor()
        refreshActive()
        refreshLive(null)
        syncRatsFromSystem()
        updateNrForRats()
        publish()
    }

    fun onPause() {
        if (receiverRegistered) {
            try {
                ctx.unregisterReceiver(airplaneReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
        unregisterMonitor()
    }

    override fun onCleared() {
        onPause()
        handler.removeCallbacksAndMessages(null)
        super.onCleared()
    }

    // ------------------------------------------------------------------ helpers

    private fun tm(): TelephonyManager =
        if (currentSubId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) baseTm else baseTm.createForSubscriptionId(currentSubId)

    private fun say(text: String) {
        _messages.trySend(text)
    }

    private fun savedKeys(): Set<String> = prefs.getStringSet(NetworkPrefs.KEY_BANDS + currentSubId, emptySet()) ?: emptySet()

    private fun isAirplaneModeOn(): Boolean = try {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    } catch (e: Exception) {
        false
    }

    private fun hasActiveSim() = activeSubs.isNotEmpty()

    private fun isAvailable() = hasActiveSim() && !isAirplaneModeOn()

    private fun countChecked() = entries.count { it.checked && !it.isHeader }

    private fun isSelectionModifiedFromSaved(): Boolean {
        val current = entries.filter { it.checked && !it.isHeader }.map { it.key }.toSet()
        return savedKeys() != current
    }

    private fun ratOfGeneration(tab: Int) = when (tab) {
        1 -> AccessNetworkConstants.AccessNetworkType.EUTRAN
        2 -> AccessNetworkConstants.AccessNetworkType.UTRAN
        3 -> AccessNetworkConstants.AccessNetworkType.GERAN
        else -> AccessNetworkConstants.AccessNetworkType.NGRAN
    }

    // ------------------------------------------------------------------ state

    private fun publish() {
        val rat = ratOfGeneration(generation)
        val saved = savedKeys()
        val shown = entries.filter { !it.isHeader && it.rat == rat }.sortedWith { a, b ->
            val aTop = a.checked || saved.contains(a.key) || a.isActive || a.isPCell || a.isSCell
            val bTop = b.checked || saved.contains(b.key) || b.isActive || b.isPCell || b.isSCell
            if (aTop != bTop) (if (aTop) -1 else 1) else a.bandNum.compareTo(b.bandNum)
        }
        val genEntries = entries.filter { !it.isHeader && it.rat == rat }
        val available = isAvailable()
        val airplane = isAirplaneModeOn()
        val displayStatus = when {
            !available && airplane -> "Airplane Mode active — band locking disabled"
            !available && !hasActiveSim() -> "No SIM card detected — band locking disabled"
            else -> status
        }
        _state.value = NetworkBandsUiState(
            sims = if (activeSubs.size > 1) activeSubs.map { SimUi(it.subscriptionId, simLabel(it)) } else emptyList(),
            currentSubId = currentSubId,
            hasSim = hasActiveSim(),
            airplane = airplane,
            rats = rats,
            carrierName = carrierName,
            carrierPresets = carrierPresets,
            carrierPreset = carrierPreset,
            nrMode = nrMode,
            nrActiveText = nrActiveText,
            nrActive = nrActive,
            generation = generation,
            bands = shown.map { BandUi(it.key, it.label, it.freqHint, it.checked, it.isActive, it.isPCell, it.isSCell) },
            genSelected = genEntries.count { it.checked },
            genTotal = genEntries.size,
            totalChecked = countChecked(),
            modified = available && isSelectionModifiedFromSaved(),
            status = displayStatus,
            profileLabel = profileLabel,
            bandLockEnabled = bandLockEnabled,
            activeBands = activeBands,
            diag = diag,
            vonr = NetworkModem.isVoNrEnabled(),
            showNonIndiaPopup = showNonIndiaPopup,
        )
    }

    private fun simLabel(info: SubscriptionInfo): String {
        var label = "SIM " + (info.simSlotIndex + 1)
        val name = info.displayName
        if (name != null && name.isNotEmpty()) label = name.toString()
        return label
    }

    // ------------------------------------------------------------------ SIMs

    private fun setupSims() {
        activeSubs = try {
            subscriptionManager.activeSubscriptionInfoList ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get active subscriptions: ${e.message}")
            emptyList()
        }
        if (activeSubs.size <= 1) {
            if (activeSubs.isNotEmpty()) currentSubId = activeSubs[0].subscriptionId
            return
        }
        val defaultData = SubscriptionManager.getDefaultDataSubscriptionId()
        var initial = 0
        for (i in activeSubs.indices) {
            if (activeSubs[i].subscriptionId == defaultData) {
                initial = i
                break
            }
        }
        currentSubId = activeSubs[initial].subscriptionId
    }

    fun selectSim(subId: Int) {
        if (subId == currentSubId) return
        val info = activeSubs.firstOrNull { it.subscriptionId == subId } ?: return
        currentSubId = subId
        info.iccId?.let { knownIccid[currentSubId] = it }
        unregisterMonitor()
        loadCurrentBands()
        registerMonitor()
        refreshActive()
        setupCarrierPresets()
        syncRatsFromSystem()
        publish()
    }

    // ------------------------------------------------------------------ band list

    private fun loadCurrentBands() {
        for (e in entries) {
            e.checked = false
            e.isActive = false
        }
        val keys = savedKeys()
        if (keys.isEmpty()) {
            status = ctx.getString(R.string.network_bands_status_no_signal)
        } else {
            var loaded = 0
            for (e in entries) {
                if (!e.isHeader && keys.contains(e.key)) {
                    e.checked = true
                    loaded++
                }
            }
            status = ctx.getString(R.string.network_bands_status_active, loaded)
        }
        updateNrForRats()
    }

    fun toggleBand(key: String) {
        if (!isAvailable()) return
        val entry = entries.firstOrNull { it.key == key && !it.isHeader } ?: return
        entry.checked = !entry.checked
        // Picking a band by hand leaves any preset
        if (carrierPreset != 0) {
            carrierPreset = 0
            prefs.edit().putInt(NetworkPrefs.KEY_CARRIER_PRESET + currentSubId, 0).apply()
            profileLabel = "Manual"
            bandLockEnabled = isSelectionModifiedFromSaved()
        }
        publish()
    }

    fun selectGeneration(tab: Int) {
        if (!_state.value.generationEnabled(tab)) {
            say("This network generation is currently disabled in RAT preferences.")
            return
        }
        generation = tab
        publish()
    }

    fun clearGeneration() {
        val rat = ratOfGeneration(generation)
        for (e in entries) if (e.rat == rat && !e.isHeader) e.checked = false
        publish()
    }

    // ------------------------------------------------------------------ carrier presets

    private fun detectCarrierName(): String {
        if (activeSubs.isEmpty()) return "No SIM"
        for (info in activeSubs) {
            if (info.subscriptionId == currentSubId) {
                var name: CharSequence? = info.displayName
                if (name == null || name.isEmpty()) name = info.carrierName
                if (name != null && name.isNotEmpty()) return name.toString().trim()
            }
        }
        try {
            val t = tm()
            val networkOp = t.networkOperatorName
            if (!networkOp.isNullOrBlank()) return networkOp.trim()
            val simOp = t.simOperatorName
            if (!simOp.isNullOrBlank()) return simOp.trim()
        } catch (e: Exception) {
            Log.w(TAG, "detectActiveCarrierName: TelephonyManager fallback failed: ${e.message}")
        }
        return "Unknown"
    }

    private fun setupCarrierPresets() {
        carrierName = detectCarrierName()
        val hasCarrier = carrierName != "No SIM"
        carrierPresets = listOf(
            "Manual / Custom Band Selection",
            if (hasCarrier) "Recommended ($carrierName)" else "Recommended",
            "Battery Saver (FDD Anchor)",
        )
        val saved = prefs.getInt(NetworkPrefs.KEY_CARRIER_PRESET + currentSubId, 0)
        carrierPreset = if (saved in carrierPresets.indices) saved else 0
        profileLabel = if (carrierPreset > 0) carrierPresets[carrierPreset] else "Manual"
        bandLockEnabled = isSelectionModifiedFromSaved()
        updateNrForRats()
    }

    fun selectCarrierPreset(position: Int) {
        if (position !in carrierPresets.indices) return
        carrierPreset = position
        prefs.edit().putInt(NetworkPrefs.KEY_CARRIER_PRESET + currentSubId, position).apply()
        if (position > 0) {
            applyCarrierPreset(carrierName, position)
            profileLabel = carrierPresets[position]
            bandLockEnabled = true
        } else {
            profileLabel = "Manual"
            bandLockEnabled = isSelectionModifiedFromSaved()
        }
        publish()
    }

    private fun applyCarrierPreset(carrier: String, option: Int) {
        for (e in entries) e.checked = false
        // A profile the user saved for this SIM wins over the built-in one
        val customKey = if (option == 1) NetworkPrefs.KEY_CUSTOM_RECOMMENDED + currentSubId
        else NetworkPrefs.KEY_CUSTOM_BATTERY_SAVER + currentSubId
        val custom = prefs.getStringSet(customKey, null)
        if (!custom.isNullOrEmpty()) {
            for (e in entries) if (!e.isHeader && custom.contains(e.key)) e.checked = true
            return
        }
        for ((rat, band) in CarrierPresets.bandsFor(option, carrier)) checkBand(rat, band)
    }

    private fun checkBand(rat: Int, band: Int) {
        // Bands of a generation that is switched off are skipped
        when (rat) {
            AccessNetworkConstants.AccessNetworkType.NGRAN -> if (!rats.g5) return
            AccessNetworkConstants.AccessNetworkType.EUTRAN -> if (!rats.g4) return
            AccessNetworkConstants.AccessNetworkType.UTRAN -> if (!rats.g3) return
            AccessNetworkConstants.AccessNetworkType.GERAN -> if (!rats.g2) return
        }
        for (e in entries) if (e.rat == rat && e.bandNum == band) e.checked = true
    }

    /** @return false (with a message) when nothing is selected. */
    fun saveCustomProfile(recommended: Boolean): Boolean {
        if (countChecked() == 0) {
            say("No bands selected to save profile.")
            return false
        }
        val keys = entries.filter { it.checked && !it.isHeader }.map { it.key }.toSet()
        val key = if (recommended) NetworkPrefs.KEY_CUSTOM_RECOMMENDED + currentSubId
        else NetworkPrefs.KEY_CUSTOM_BATTERY_SAVER + currentSubId
        prefs.edit().putStringSet(key, keys).apply()
        say((if (recommended) "Recommended" else "Battery Saver") + " custom profile saved!")
        return true
    }

    // ------------------------------------------------------------------ RAT slots

    fun toggleRat(tab: Int) {
        if (!isAvailable()) return
        rats = when (tab) {
            0 -> rats.copy(g5 = !rats.g5)
            1 -> rats.copy(g4 = !rats.g4)
            2 -> rats.copy(g3 = !rats.g3)
            else -> rats.copy(g2 = !rats.g2)
        }
        triggerDebouncedRatUpdate()
        updateNrForRats()
        publish()
    }

    fun applyRatPreset(index: Int) {
        val preset = NetworkModem.ratsForPreset(index) ?: return
        rats = preset
        updateNrForRats()
        applyRatFromSlots()
        publish()
    }

    private fun triggerDebouncedRatUpdate() {
        ratJob?.cancel()
        val r = rats
        say(
            when {
                r.g5 && r.g4 && !r.g3 && !r.g2 -> "Applying 5G + 4G..."
                r.g4 && !r.g5 && !r.g3 && !r.g2 -> "Applying 4G LTE Only..."
                r.g4 && r.g2 && !r.g5 && !r.g3 -> "Applying 4G + 2G..."
                r.g5 && !r.g4 && !r.g3 && !r.g2 -> "Applying 5G Only..."
                else -> "Applying RAT Preference..."
            }
        )
        ratJob = viewModelScope.launch {
            delay(2000)
            applyRatFromSlots()
        }
    }

    private fun applyRatFromSlots() {
        var r = rats
        // Safety guard: 5G NSA needs a 4G anchor
        if (r.g5) {
            val savedNr = prefs.getInt(NetworkPrefs.KEY_NR_MODE + currentSubId, 1)
            if (!r.g4) {
                if (savedNr == 0) {
                    r = r.copy(g4 = true)
                    rats = r
                    say("5G NSA Only requires 4G LTE anchor — auto-enabled 4G.")
                    publish()
                } else if (savedNr == 1) {
                    say("Note: 5G NSA connection requires 4G LTE anchor cell.")
                }
            }
        }
        val bitmask = r.toMask()
        if (bitmask == 0L) {
            say("Select at least 1 RAT slot (2G/3G/4G/5G)")
            return
        }
        Log.i(TAG, "applyRatFromSlots: bitmask=$bitmask (2G=${r.g2}, 3G=${r.g3}, 4G=${r.g4}, 5G=${r.g5})")
        val subId = currentSubId
        val t = tm()
        viewModelScope.launch(Dispatchers.Default) {
            try {
                t.setAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER, bitmask)
                prefs.edit().putLong(NetworkPrefs.KEY_RAT_MODE + subId, bitmask).apply()
                refreshLive(null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply RAT bitmask: ${e.message}")
            }
        }
    }

    private fun syncRatsFromSystem() {
        try {
            val saved = prefs.getLong(NetworkPrefs.KEY_RAT_MODE + currentSubId, 0)
            var bitmask = 0L
            try {
                bitmask = tm().getAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get allowed network types from system: ${e.message}")
            }
            if (bitmask != 0L && bitmask != saved) {
                // The system changed it (native Settings): follow it instead of overwriting
                prefs.edit().putLong(NetworkPrefs.KEY_RAT_MODE + currentSubId, bitmask).apply()
            }
            rats = NetworkModem.ratsFromMask(bitmask)
            updateNrForRats()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync RAT slots from system: ${e.message}")
        }
    }

    /** NR card visibility, enabled generation tabs and automatic tab switch (was updateNrModeSeekbarForCarrier). */
    private fun updateNrForRats() {
        val available = isAvailable()
        if (rats.g5 && available) nrMode = prefs.getInt(NetworkPrefs.KEY_NR_MODE + currentSubId, 1)
        val allowed = (generation == 0 && rats.g5) || (generation == 1 && rats.g4) ||
            (generation == 2 && rats.g3) || (generation == 3 && rats.g2)
        if (!allowed) {
            generation = if (rats.g5) 0 else if (rats.g4) 1 else if (rats.g3) 2 else if (rats.g2) 3 else generation
        }
    }

    // ------------------------------------------------------------------ 5G NR mode

    fun setNrMode(progress: Int) {
        if (!isAvailable()) return
        lastNrInteraction = SystemClock.elapsedRealtime()
        nrMode = progress
        prefs.edit().putInt(NetworkPrefs.KEY_NR_MODE + currentSubId, progress).apply()
        publish()
        nrJob?.cancel()
        say("Applying 5G NR Mode...")
        val subId = currentSubId
        nrJob = viewModelScope.launch {
            delay(2000)
            if (rats.g5) {
                val slot = SubscriptionManager.getSlotIndex(subId)
                if (SubscriptionManager.isValidSlotIndex(slot)) {
                    launch(Dispatchers.Default) { NetworkModem.setOplusNrMode(slot, NetworkModem.nrModeToOplus(progress)) }
                }
            }
            updateActiveNrDisplay()
            publish()
        }
    }

    private fun updateActiveNrDisplay() {
        if (SystemClock.elapsedRealtime() - lastNrInteraction < 2500) return
        var hasNr = false
        var hasLte = false
        var nrBand = 0
        for (e in entries) {
            if (!e.isActive) continue
            if (e.rat == AccessNetworkConstants.AccessNetworkType.NGRAN) {
                hasNr = true
                nrBand = e.bandNum
            }
            if (e.rat == AccessNetworkConstants.AccessNetworkType.EUTRAN) hasLte = true
        }
        if (hasNr) {
            val nrStr = if (nrBand > 0) "NR n$nrBand" else "NR"
            nrActiveText = if (hasLte) "EN-DC Active: 5G NSA (LTE Anchor + $nrStr)" else "Active: 5G SA ($nrStr Standalone)"
            nrActive = true
        } else {
            nrActiveText = "Active: LTE / No 5G"
            nrActive = false
        }
    }

    fun setVoNr(enabled: Boolean) {
        val slot = SubscriptionManager.getSlotIndex(currentSubId)
        val active = if (SubscriptionManager.isValidSlotIndex(slot)) slot else 0
        viewModelScope.launch(Dispatchers.Default) { NetworkModem.setOplusVoNrEnabled(active, enabled) }
        say(if (enabled) "Vo5G (Voice over 5G) Enabled" else "Vo5G Disabled — VoLTE Fallback Active")
        refreshLive(null)
        publish()
    }

    // ------------------------------------------------------------------ apply / reset

    fun applyBands() {
        val checked = countChecked()
        if (checked == 0) {
            say(ctx.getString(R.string.network_bands_nothing_selected))
            return
        }
        val newKeys = entries.filter { it.checked && !it.isHeader }.map { it.key }.toSet()
        prefs.edit().putStringSet(NetworkPrefs.KEY_BANDS + currentSubId, newKeys).apply()
        Log.i(TAG, "applyBands: subId=$currentSubId, selected=$checked, keys=$newKeys")
        val snapshot = entries.map { it.copyFlags() }
        val t = tm()
        publish()
        appScope.launch {
            val specifiers = NetworkModem.buildSpecifiers(snapshot, t)
            if (specifiers.isEmpty()) {
                say(ctx.getString(R.string.network_bands_nothing_selected))
                return@launch
            }
            // Multi-band lock needs LTE Carrier Aggregation (4G+) on
            if (checked > 1) enableLteCarrierAggregation(checked)
            try {
                t.setSystemSelectionChannels(specifiers)
                status = ctx.getString(R.string.network_bands_status_active, checked)
                say("Band Lock applied — cycling radio power...")
                t.requestRadioPowerOffForReason(TelephonyManager.RADIO_POWER_REASON_USER)
                handler.post { publish() }
                delay(1200)
                try {
                    t.clearRadioPowerOffForReason(TelephonyManager.RADIO_POWER_REASON_USER)
                    say("Modem online — re-scanning physical channels...")
                    delay(4000)
                    handler.post {
                        refreshActive()
                        refreshLive(null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to power on radio: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setSystemSelectionChannels: ${e.message}")
                say("Modem update error: ${e.message}")
            }
        }
    }

    private fun enableLteCarrierAggregation(count: Int) {
        try {
            val resolver = ctx.contentResolver
            val already = Settings.Global.getInt(resolver, "lte_ca_enabled", 0) == 1 ||
                SystemProperties.get("persist.vendor.radio.lte_ca_enabled", "0") == "1" ||
                SystemProperties.get("persist.sys.lte_ca_enable", "false").equals("true", ignoreCase = true)
            if (!already) {
                Settings.Global.putInt(resolver, "lte_ca_enabled", 1)
                Settings.Global.putInt(resolver, "show_carrier_aggregation_option", 1)
                Settings.System.putInt(resolver, "lte_ca_enabled", 1)
                SystemProperties.set("persist.vendor.radio.lte_ca_enabled", "1")
                SystemProperties.set("persist.sys.lte_ca_enable", "true")
                SystemProperties.set("vendor.radio.lte_ca.enable", "1")
                Log.i(TAG, "Multi-band selection ($count bands): enabled LTE Carrier Aggregation (4G+)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check/enforce LTE CA settings: ${e.message}")
        }
    }

    fun resetBands() {
        Log.i(TAG, "resetBands: back to modem defaults for subId=$currentSubId")
        for (e in entries) {
            e.checked = false
            e.isActive = false
            e.isPCell = false
            e.isSCell = false
        }
        carrierPreset = 0
        prefs.edit().putInt(NetworkPrefs.KEY_CARRIER_PRESET + currentSubId, 0).apply()
        profileLabel = "Manual"
        bandLockEnabled = isSelectionModifiedFromSaved()
        rats = NetworkModem.Rats(true, true, true, true)
        updateNrForRats()
        nrMode = 1
        status = ctx.getString(R.string.network_bands_status_no_signal)
        publish()

        say("Resetting modem — clearing all band forcing...")
        val t = tm()
        val subId = currentSubId
        appScope.launch {
            try {
                // Overwrite the modem band table with every valid catalog band
                val all = NetworkModem.buildAllBandsSpecifiers(t)
                try {
                    t.setSystemSelectionChannels(all)
                } catch (e: Exception) {
                    Log.w(TAG, "reset: failed to set all-bands specifiers: ${e.message}")
                }
                try {
                    t.setAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER, NetworkModem.MASK_ALL)
                } catch (_: Exception) {
                }
                try {
                    t.setNetworkSelectionModeAutomatic()
                } catch (_: Exception) {
                }
                val slot = SubscriptionManager.getSlotIndex(subId)
                if (SubscriptionManager.isValidSlotIndex(slot)) {
                    NetworkModem.setOplusNrMode(slot, NetworkModem.OPLUS_NR_MODE_SA_PRE)
                }
                // Power cycle so the modem re-attaches with all bands
                try {
                    t.requestRadioPowerOffForReason(TelephonyManager.RADIO_POWER_REASON_USER)
                } catch (_: Exception) {
                }
                delay(1500)
                try {
                    t.clearRadioPowerOffForReason(TelephonyManager.RADIO_POWER_REASON_USER)
                    say("Modem restored to default — scanning all bands...")
                    delay(4000)
                    // Clear the selection channels once the modem has re-attached
                    try {
                        t.setSystemSelectionChannels(ArrayList())
                    } catch (_: Exception) {
                    }
                    handler.post {
                        refreshActive()
                        refreshLive(null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to power on radio after reset: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset bands: ${e.message}")
                say("Reset failed: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------ live monitoring

    private inner class BandMonitorCallback : TelephonyCallback(),
        TelephonyCallback.PhysicalChannelConfigListener,
        TelephonyCallback.CellInfoListener,
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.ServiceStateListener,
        TelephonyCallback.DisplayInfoListener {

        override fun onPhysicalChannelConfigChanged(configs: MutableList<PhysicalChannelConfig>) {
            lastConfigs = configs
            refreshActive()
            refreshLive(null)
        }

        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
            refreshActive()
            refreshLive(null)
        }

        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            refreshLive(signalStrength)
        }

        override fun onServiceStateChanged(serviceState: ServiceState) {
            refreshActive()
            refreshLive(null)
        }

        override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
            refreshActive()
            refreshLive(null)
        }
    }

    private fun registerMonitor() {
        if (monitor == null) {
            val cb = BandMonitorCallback()
            try {
                tm().registerTelephonyCallback(mainExecutor, cb)
                monitor = cb
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register BandMonitorCallback: ${e.message}")
            }
        }
        if (subListener == null) {
            try {
                val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
                    override fun onSubscriptionsChanged() {
                        val currentDefault = SubscriptionManager.getDefaultDataSubscriptionId()
                        if (lastDefaultDataSubId != -1 && currentDefault != lastDefaultDataSubId) {
                            Log.i(TAG, "Default data SIM switched ($lastDefaultDataSubId -> $currentDefault): resetting band lock")
                            say("Mobile Data SIM switched in System Settings — reset band lock to default.")
                            resetBands()
                        }
                        lastDefaultDataSubId = currentDefault

                        val info = subscriptionManager.getActiveSubscriptionInfo(currentSubId)
                        if (info != null) {
                            val iccid = info.iccId
                            val previous = knownIccid[currentSubId]
                            if (iccid != null && previous != null && iccid != previous) {
                                Log.i(TAG, "SIM card swap detected for subId=$currentSubId: resetting bands")
                                say("SIM Card changed — automatically reset band filters to modem default.")
                                resetBands()
                            }
                            if (iccid != null) knownIccid[currentSubId] = iccid
                        }
                    }
                }
                subscriptionManager.addOnSubscriptionsChangedListener(mainExecutor, listener)
                subListener = listener
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register OnSubscriptionsChangedListener: ${e.message}")
            }
        }
    }

    private fun unregisterMonitor() {
        monitor?.let {
            try {
                tm().unregisterTelephonyCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister BandMonitorCallback: ${e.message}")
            }
            monitor = null
        }
        subListener?.let {
            try {
                subscriptionManager.removeOnSubscriptionsChangedListener(it)
            } catch (_: Exception) {
            }
            subListener = null
        }
    }

    private fun refreshLive(signal: SignalStrength?) {
        liveJob?.cancel()
        val t = tm()
        val configs = lastConfigs
        val subId = currentSubId
        val carrier = carrierName
        liveJob = viewModelScope.launch(Dispatchers.Default) {
            val snap = try {
                LiveDiagnostics.collect(ctx, t, subId, configs, carrier, signal, trend, prefs.getBoolean("force_vo5g", false))
            } catch (e: Exception) {
                Log.w(TAG, "diagnostics failed: ${e.message}")
                return@launch
            }
            handler.post {
                diag = snap
                publish()
            }
        }
    }

    private fun refreshActive() {
        activeJob?.cancel()
        val t = tm()
        val configs = lastConfigs
        val subId = currentSubId
        val snapshot = entries.map { it.copyFlags() }
        activeJob = viewModelScope.launch(Dispatchers.Default) {
            val result = ActiveBandScanner.scan(t, subId, configs, snapshot)
            handler.post {
                // copy only the live flags back: the user may have changed checkmarks meanwhile
                for (i in entries.indices) {
                    if (i < result.entries.size) {
                        entries[i].isActive = result.entries[i].isActive
                        entries[i].isPCell = result.entries[i].isPCell
                        entries[i].isSCell = result.entries[i].isSCell
                    }
                }
                activeBands = result.chips
                updateActiveNrDisplay()
                publish()
            }
        }
    }

    // ------------------------------------------------------------------ first run note

    private fun checkFirstTimePopup() {
        if (!NetworkModem.isNonIndiaRegion(tm())) return
        if (prefs.getBoolean(NetworkPrefs.KEY_NON_INDIA_POPUP, false)) return
        prefs.edit().putBoolean(NetworkPrefs.KEY_NON_INDIA_POPUP, true).apply()
        showNonIndiaPopup = true
    }

    fun dismissNonIndiaPopup() {
        showNonIndiaPopup = false
        publish()
    }

    private companion object {
        const val TAG = "NetworkBandsFragment"

        /** Radio power cycles must finish even if the screen is closed mid-way. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

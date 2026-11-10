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

package org.lineageos.device.DeviceSettings.audioshare

import android.app.Application
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemProperties
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import com.oplus.bluetooth.OplusA2dpSharingManager
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.device.DeviceSettings.DeviceKeys
import org.lineageos.device.DeviceSettings.R
import org.lineageos.device.DeviceSettings.defaultPrefs

data class CodecInfo(val name: String, val detail: String)

data class HeadsetUi(
    val address: String,
    val name: String,
    val battery: Int?,
    val codec: CodecInfo?,
)

data class EngineInfo(
    val offloadDisabled: Boolean,
    val sharingEnabled: Boolean,
    val sharingCodec: String,
    val sharingActive: Boolean,
    val primaryCodec: String?,
    val secondaryCodec: String?,
)

data class AudioShareUiState(
    val btOn: Boolean = false,
    val masterOn: Boolean = false,
    val sharing: Boolean = false,
    val starting: Boolean = false,
    val stopping: Boolean = false,
    val primary: HeadsetUi? = null,
    val secondary: HeadsetUi? = null,
    /** Connected headsets that can be the second one (everything but the main headset). */
    val candidates: List<HeadsetUi> = emptyList(),
    /** Every connected A2DP headset (for the main-headset picker). */
    val connected: List<HeadsetUi> = emptyList(),
    val primaryPercent: Int = 80,
    val secondaryPercent: Int = 80,
    val syncVolumes: Boolean = false,
    val lipsyncOn: Boolean = true,
    val lipsyncMode: String = "auto",
    val lipsyncModeLabel: String = "",
    val lipsyncDetail: String = "",
    val adjMain: Int = 0,
    val adjSecond: Int = 0,
    val codecChoice: String = "auto",
    val codecChoiceLabel: String = "",
    val codecResult: String? = null,
    val offloadOn: Boolean = true,
    val offloadSwitching: Boolean = false,
    val aptxLowLatency: Boolean = false,
    val aptxAvailable: Boolean = false,
    val engine: EngineInfo = EngineInfo(false, false, "auto", false, null, null),
    /** Bumped when a requested volume was refused, so sliders snap back. */
    val resetTick: Int = 0,
) {
    val sharingReady: Boolean get() = masterOn && btOn
}

/**
 * Port of AudioShareSettingsFragment's logic, 1:1: same device resolution rules, same OPlus
 * sharing manager calls, same system properties, same volume linking maths. Only the plumbing
 * changed - everything is published as one immutable [AudioShareUiState].
 */
class AudioShareViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app
    private val prefs = ctx.defaultPrefs()
    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val audioManager: AudioManager? = ctx.getSystemService(AudioManager::class.java)
    private var a2dp: BluetoothA2dp? = null
    private val sharingManager: OplusA2dpSharingManager?

    private var primary: BluetoothDevice? = null
    private var secondary: BluetoothDevice? = null
    private var sharingActive = false
    private var starting = false
    private var stopping = false
    private var lastPrimaryPercent = 80
    private var primaryPercent = 80
    private var secondaryPercent = 80
    private var masterOn = prefs.getBoolean(KEY_MASTER_SWITCH, false)
    private var syncVolumes = prefs.getBoolean(KEY_SYNC_VOLUMES, false)
    private var lipsyncOn = true
    private var lipsyncMode = "auto"
    private var codecChoice = "auto"
    private var offloadOn = true
    private var offloadSwitching = false
    private var aptxLowLatency = prefs.getBoolean(DeviceKeys.APTX_LOW_LATENCY, false)
    private var aptxAvailable = false
    private var primaryCodec: CodecInfo? = null
    private var secondaryCodec: CodecInfo? = null
    private var candidates: List<BluetoothDevice> = emptyList()
    private var connectedList: List<BluetoothDevice> = emptyList()
    private var resetTick = 0

    private val _state = MutableStateFlow(AudioShareUiState())
    val state: StateFlow<AudioShareUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    // ------------------------------------------------------------------------ callbacks

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                a2dp = proxy as? BluetoothA2dp
                refreshDevices()
                handler.postDelayed({ refreshCodec() }, 400)
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                a2dp = null
                refreshDevices()
                handler.post { refreshCodec() }
            }
        }
    }

    private val stateCallback = SharingStateBridge.create(object : SharingStateBridge.Listener {
        override fun onStarted(primary: BluetoothDevice?, secondary: BluetoothDevice?) {
            handler.post {
                Log.i(TAG, "Audio sharing started callback: primary=$primary, sec=$secondary")
                if (stopping) return@post
                sharingActive = true
                starting = false
                handler.postDelayed({ publish() }, 1500)
                masterOn = true
                prefs.edit().putBoolean(KEY_MASTER_SWITCH, true).apply()
                if (primary != null) this@AudioShareViewModel.primary = primary
                if (secondary != null) this@AudioShareViewModel.secondary = secondary
                refreshDevices()
            }
        }

        override fun onStopped() {
            handler.post {
                Log.i(TAG, "Audio sharing stopped callback")
                sharingActive = false
                starting = false
                stopping = false
                // Keep the second headset selected across pause/resume while it stays connected
                refreshDevices()
            }
        }
    })

    private val volumeCallback = OplusA2dpSharingManager.VolumeCallback { device, volume ->
        handler.post {
            if (device == null) return@post
            val percent = (volume * 100.0f / 127.0f).roundToInt().coerceIn(0, 100)
            if (device == primary) {
                primaryPercent = percent
                lastPrimaryPercent = percent
                publish()
            } else if (device == secondary) {
                secondaryPercent = percent
                publish()
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val action = intent.action) {
                "android.media.VOLUME_CHANGED_ACTION" -> onMusicVolumeChanged(intent)
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    Log.i(TAG, "BluetoothAdapter state changed: $state")
                    if (state == BluetoothAdapter.STATE_ON) {
                        offloadSwitching = false
                        refreshOffload()
                        refreshDevices()
                        refreshCodec()
                    } else if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                        sharingActive = false
                        starting = false
                        stopping = false
                        publish()
                    }
                }
                BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED -> {
                    Log.i(TAG, "ACTION_CODEC_CONFIG_CHANGED received")
                    refreshCodec()
                }
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED -> {
                    if (action == BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED) {
                        val active = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        Log.i(TAG, "ACTIVE_DEVICE_CHANGED: $active")
                        if (active != null) {
                            primary = active
                            if (secondary?.address.equals(active.address, ignoreCase = true)) secondary = null
                        }
                        handler.postDelayed({ refreshCodec() }, 500)
                    }
                    refreshDevices()
                }
            }
        }
    }

    init {
        OplusA2dpSharingManager.init(ctx)
        sharingManager = OplusA2dpSharingManager.getInstance()

        val lipsync = SystemProperties.get(PROP_LIPSYNC, "auto")
        lipsyncOn = lipsync != "off"
        lipsyncMode = if (lipsync == "manual") "manual" else prefs.getString(KEY_LIPSYNC_MODE, "auto") ?: "auto"
        codecChoice = SystemProperties.get(PROP_SHARING_CODEC, "auto")
        readMusicVolume()
        refreshOffload()

        adapter?.getProfileProxy(ctx, profileListener, BluetoothProfile.A2DP)

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED)
            addAction("android.media.VOLUME_CHANGED_ACTION")
        }
        ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)

        sharingManager?.let {
            it.registerStateCallback(null, stateCallback)
            it.registerVolumeCallback(null, volumeCallback)
            sharingActive = it.inSharingMode()
        }
        refreshDevices()
    }

    override fun onCleared() {
        handler.removeCallbacksAndMessages(null)
        try {
            ctx.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        val proxy = a2dp
        if (adapter != null && proxy != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
        sharingManager?.unregisterStateCallback(stateCallback)
        sharingManager?.unregisterVolumeCallback(volumeCallback)
        super.onCleared()
    }

    fun onResume() {
        offloadSwitching = false
        sharingManager?.let { sharingActive = it.inSharingMode() }
        if (!sharingActive) readMusicVolume()
        val shouldBeChecked = sharingActive || (sharingManager?.isA2dpSharingEnabled == true)
        if (masterOn != shouldBeChecked) {
            masterOn = shouldBeChecked
            prefs.edit().putBoolean(KEY_MASTER_SWITCH, shouldBeChecked).apply()
        }
        refreshOffload()
        refreshDevices()
        refreshCodec()
    }

    // ------------------------------------------------------------------------ reads

    private fun readMusicVolume() {
        val am = audioManager ?: return
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val percent = if (max > 0) cur * 100 / max else 80
        lastPrimaryPercent = percent
        primaryPercent = percent
    }

    private fun refreshOffload() {
        offloadOn = !SystemProperties.getBoolean(PROP_OFFLOAD_DISABLED, false)
    }

    private fun isA2dpConnected(device: BluetoothDevice?): Boolean {
        val proxy = a2dp
        if (device == null || proxy == null) return false
        return proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
    }

    private fun activeA2dpDevice(): BluetoothDevice? {
        try {
            adapter?.getActiveDevices(BluetoothProfile.A2DP)?.firstOrNull()?.let { return it }
        } catch (_: Throwable) {
        }
        val proxy = a2dp
        if (proxy != null) {
            try {
                val m = proxy.javaClass.getMethod("getActiveDevice")
                (m.invoke(proxy) as? BluetoothDevice)?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun sameDevice(a: BluetoothDevice?, b: BluetoothDevice?): Boolean =
        a != null && b != null && a.address.equals(b.address, ignoreCase = true)

    /** Resolves main / second headset and the selectable list from what is connected right now. */
    private fun refreshDevices() {
        val ad = adapter
        if (ad == null || !ad.isEnabled) {
            candidates = emptyList()
            connectedList = emptyList()
            publish()
            return
        }

        val connected = ArrayList<BluetoothDevice>()
        a2dp?.let { proxy ->
            proxy.connectedDevices?.let { connected.addAll(it) }
            val active = activeA2dpDevice()
            val current = primary
            primary = when {
                active != null -> active
                // keep the current main headset while it is connected, even with playback paused
                current != null ->
                    if (connected.any { sameDevice(it, current) }) current else connected.firstOrNull()
                else -> connected.firstOrNull()
            }
        }
        connectedList = connected

        if (sameDevice(secondary, primary)) secondary = null

        val seen = HashSet<String>()
        primary?.let { seen.add(it.address.uppercase(Locale.ROOT)) }
        val list = ArrayList<BluetoothDevice>()
        for (dev in connected) {
            if (seen.add(dev.address.uppercase(Locale.ROOT))) list.add(dev)
        }
        candidates = list

        // the second headset disconnected since
        secondary?.let { sec ->
            if (connected.none { it.address.equals(sec.address, ignoreCase = true) }) {
                Log.w(TAG, "Secondary device disconnected, clearing: ${sec.address}")
                secondary = null
            }
        }

        if (secondary == null) {
            val last = prefs.getString(KEY_LAST_SECONDARY, null)
            val prim = primary
            if (last != null && seen.contains(last.uppercase(Locale.ROOT)) &&
                (prim == null || !last.equals(prim.address, ignoreCase = true))
            ) {
                secondary = ad.getRemoteDevice(last)
            } else if (list.isNotEmpty()) {
                val auto = list[0].address
                secondary = ad.getRemoteDevice(auto)
                prefs.edit().putString(KEY_LAST_SECONDARY, auto).apply()
            }
        }
        publish()
    }

    private fun refreshCodec() {
        primaryCodec = codecInfo(primary)
        secondaryCodec = codecInfo(secondary)
        refreshAptx()
        publish()
    }

    // The vendor aptX mode requires its native codec index.
    @Suppress("DEPRECATION")
    private fun aptxConfig(): BluetoothCodecConfig? {
        val proxy = a2dp ?: return null
        val device = activeA2dpDevice() ?: return null
        val config = proxy.getCodecStatus(device)?.codecConfig
        return if (config != null && config.codecType == CODEC_TYPE_APTX_ADAPTIVE) config else null
    }

    private fun applyAptxMode(current: BluetoothCodecConfig, lowLatency: Boolean) {
        val proxy = a2dp ?: return
        val device = activeA2dpDevice() ?: return
        val config = BluetoothCodecConfig.Builder()
            .setExtendedCodecType(current.extendedCodecType)
            .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
            .setSampleRate(current.sampleRate)
            .setBitsPerSample(current.bitsPerSample)
            .setChannelMode(current.channelMode)
            .setCodecSpecific1(current.codecSpecific1)
            .setCodecSpecific2(current.codecSpecific2)
            .setCodecSpecific3(current.codecSpecific3)
            .setCodecSpecific4(if (lowLatency) APTX_MODE_LL else APTX_MODE_HQ)
            .build()
        proxy.setCodecConfigPreference(device, config)
    }

    private fun refreshAptx() {
        val config = aptxConfig()
        aptxAvailable = config != null
        aptxLowLatency = if (config != null) {
            val liveLl = (config.codecSpecific4 and APTX_MODE_LL) != 0L
            if (liveLl != prefs.getBoolean(DeviceKeys.APTX_LOW_LATENCY, false)) {
                prefs.edit().putBoolean(DeviceKeys.APTX_LOW_LATENCY, liveLl).apply()
            }
            liveLl
        } else {
            prefs.getBoolean(DeviceKeys.APTX_LOW_LATENCY, false)
        }
    }

    // ------------------------------------------------------------------------ actions

    fun setMaster(enabled: Boolean) {
        val offloadDisabled = SystemProperties.getBoolean(PROP_OFFLOAD_DISABLED, false)
        val mgr = sharingManager
        if (!enabled && (sharingActive || starting) && !requestStop()) return
        mgr?.setA2dpSharingEnabled(enabled)
        if (!enabled && prefs.getBoolean(PREF_OFFLOAD_OFF_FOR_SHARING, false)) {
            prefs.edit().remove(PREF_OFFLOAD_OFF_FOR_SHARING).apply()
            if (offloadDisabled) switchHardwareOffload(true)
        }
        masterOn = enabled
        prefs.edit().putBoolean(KEY_MASTER_SWITCH, enabled).apply()
        refreshDevices()
        handler.postDelayed({ refreshCodec() }, 400)
    }

    fun toggleSharing() {
        Log.d(TAG, "Audio share action clicked. sharingActive=$sharingActive")
        val mgr = sharingManager
        if (stopping) return
        if (sharingActive || starting) {
            requestStop()
            return
        }
        if (!masterOn) {
            say(R.string.audio_share_must_enable_force_sbc)
            return
        }
        if (primary == null) primary = activeA2dpDevice()
        val prim = primary
        val sec = secondary
        if (prim == null) {
            say(R.string.audio_share_no_active_primary)
            return
        }
        if (sec == null) {
            say(R.string.audio_share_select_second_first)
            return
        }
        if (!isA2dpConnected(sec)) {
            say(R.string.audio_share_secondary_not_connected)
            secondary = null
            refreshDevices()
            return
        }
        if (sec.address.equals(prim.address, ignoreCase = true)) {
            say(R.string.audio_share_same_device)
            return
        }
        Log.d(TAG, "Starting audio share: primary=${prim.address} -> secondary=${sec.address}")
        val started = mgr?.startSharing(sec, OplusA2dpSharingManager.SHARING_TYPE_BONDED) ?: false
        starting = started
        publish()
        if (!started) say(R.string.audio_share_start_failed)
    }

    private fun requestStop(): Boolean {
        if (stopping) return true
        stopping = true
        val accepted = sharingManager?.stopSharing() == true
        if (!accepted) {
            stopping = false
            say(R.string.audio_share_stop_failed)
        }
        publish()
        return accepted
    }

    fun selectSecondary(address: String) {
        val ad = adapter ?: return
        if (address.isEmpty()) return
        val candidate = ad.getRemoteDevice(address)
        if (!isA2dpConnected(candidate)) {
            say(R.string.audio_share_secondary_not_connected)
            publish()
            return
        }
        secondary = candidate
        prefs.edit().putString(KEY_LAST_SECONDARY, address).apply()
        publish()
    }

    /** Returns true when the main-headset picker should open (after the same checks as before). */
    fun onPrimaryClicked(): Boolean {
        if (a2dp == null) return false
        if (connectedList.isEmpty()) {
            say(R.string.audio_share_no_primary_device)
            return false
        }
        if (connectedList.size == 1) {
            say(R.string.audio_share_only_one_connected)
            return false
        }
        return true
    }

    fun selectPrimary(address: String) {
        val target = connectedList.firstOrNull { it.address.equals(address, ignoreCase = true) } ?: return
        val ad = adapter ?: return
        try {
            ad.setActiveDevice(target, BluetoothAdapter.ACTIVE_DEVICE_AUDIO)
            primary = target
            refreshDevices()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to switch active A2DP device", t)
        }
    }

    fun swapRoles() {
        if (!masterOn) {
            say(R.string.audio_share_must_enable_force_sbc)
            return
        }
        val oldPrim = primary
        val oldSec = secondary
        val proxy = a2dp
        if (oldPrim == null || oldSec == null || proxy == null) {
            say(R.string.audio_share_need_both)
            return
        }
        Log.i(TAG, "swapRoles requested: oldPrim=$oldPrim, oldSec=$oldSec")
        // While sharing, the sharing service reads the active device switch as a role swap, with
        // no stop and restart.
        try {
            proxy.javaClass.getMethod("setActiveDevice", BluetoothDevice::class.java).invoke(proxy, oldSec)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to invoke setActiveDevice reflectively", e)
        }
        primary = oldSec
        secondary = oldPrim
        refreshDevices()
        refreshCodec()
    }

    fun setPrimaryVolume(percent: Int) {
        val am = audioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val newVol = percent * max / 100
        val mgr = sharingManager
        if (sharingActive) {
            // Keep the shared PCM gain intact, and unmute even when STREAM_MUSIC is unchanged.
            val prim = primary
            val avrcp = (percent * 127.0f / 100.0f).roundToInt().coerceIn(0, 127)
            if (mgr == null || prim == null || !mgr.setSharingDeviceAbsoluteVolume(prim, avrcp)) {
                say(R.string.audio_share_independent_volume_unavailable)
                resetTick++
                publish()
                return
            }
        } else {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
        }
        applyLinkedSecondary(percent)
        lastPrimaryPercent = percent
        primaryPercent = percent
        publish()
    }

    /** With "Link volumes" on, the second headset follows the main one by the same delta. */
    private fun applyLinkedSecondary(percent: Int) {
        val sec = secondary
        val mgr = sharingManager
        if (!(syncVolumes && sharingActive && sec != null)) return
        val delta = percent - lastPrimaryPercent
        val newSec = when {
            percent == 0 -> 0
            lastPrimaryPercent == 0 && percent > 0 -> percent
            else -> (secondaryPercent + delta).coerceIn(0, 100)
        }
        if (mgr != null) {
            val avrcp = (newSec * 127.0f / 100.0f).roundToInt().coerceIn(0, 127)
            if (mgr.setSharingDeviceAbsoluteVolume(sec, avrcp)) secondaryPercent = newSec
        }
    }

    private fun onMusicVolumeChanged(intent: Intent) {
        if (intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
        val am = audioManager ?: return
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val percent = if (max > 0) cur * 100 / max else 80
        primaryPercent = percent
        applyLinkedSecondary(percent)
        lastPrimaryPercent = percent
        publish()
    }

    fun setSecondaryVolume(percent: Int) {
        val sec = secondary
        val mgr = sharingManager
        if (sec != null && mgr != null) {
            // 0..100 % -> AVRCP absolute volume 0..127
            val avrcp = (percent * 127.0f / 100.0f).roundToInt().coerceIn(0, 127)
            if (mgr.setSharingDeviceAbsoluteVolume(sec, avrcp)) {
                secondaryPercent = percent
                publish()
                return
            }
            say(R.string.audio_share_independent_volume_unavailable)
            resetTick++
            publish()
        }
    }

    fun setSyncVolumes(enabled: Boolean) {
        syncVolumes = enabled
        prefs.edit().putBoolean(KEY_SYNC_VOLUMES, enabled).apply()
        publish()
    }

    fun setLipsync(on: Boolean) = applyLipsync(on, lipsyncMode)

    fun setLipsyncMode(mode: String) = applyLipsync(lipsyncOn, mode)

    private fun applyLipsync(on: Boolean, mode: String) {
        if (!masterOn || adapter?.isEnabled != true || starting || stopping ||
            connectedList.none(::isA2dpConnected)) return
        try {
            SystemProperties.set(PROP_LIPSYNC, if (on) mode else "off")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to set $PROP_LIPSYNC", t)
            return
        }
        prefs.edit().putString(KEY_LIPSYNC_MODE, mode).apply()
        lipsyncOn = on
        lipsyncMode = mode
        publish()
        // The stack picks the new hold up on its next encoder tick
        handler.postDelayed({ publish() }, 500)
    }

    fun setAdjust(main: Boolean, value: Int) {
        val device = (if (main) primary else secondary) ?: return
        try {
            SystemProperties.set(adjustProp(device), value.toString())
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to set ${adjustProp(device)}", t)
            resetTick++
            publish()
            return
        }
        handler.postDelayed({ publish() }, 500)
    }

    fun setSharingCodec(codec: String) {
        try {
            SystemProperties.set(PROP_SHARING_CODEC, codec)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to set $PROP_SHARING_CODEC", t)
            return
        }
        if (sharingActive) say(R.string.audio_share_codec_next_time)
        codecChoice = codec
        publish()
    }

    fun setHardwareOffload(enable: Boolean) {
        val current = !SystemProperties.getBoolean(PROP_OFFLOAD_DISABLED, false)
        if (enable == current) {
            say(if (enable) R.string.bluetooth_a2dp_hw_offload_already_hw else R.string.bluetooth_a2dp_hw_offload_already_sw)
            return
        }
        // The switch restarts Bluetooth, which ends a running share.
        if (enable) prefs.edit().remove(PREF_OFFLOAD_OFF_FOR_SHARING).apply()
        if (sharingManager != null && sharingActive) sharingManager.stopSharing()
        switchHardwareOffload(enable)
    }

    fun setAptxLowLatency(enabled: Boolean) {
        val config = aptxConfig()
        if (config == null) {
            refreshAptx()
            publish()
            return
        }
        applyAptxMode(config, enabled)
        prefs.edit().putBoolean(DeviceKeys.APTX_LOW_LATENCY, enabled).apply()
        aptxLowLatency = enabled
        publish()
    }

    /** Sets the property, restarts audioserver and cycles Bluetooth. Survives leaving the screen. */
    // Privileged system apps are exempt from the Bluetooth enable/disable restriction.
    @Suppress("DEPRECATION")
    private fun switchHardwareOffload(enable: Boolean) {
        offloadSwitching = true
        offloadOn = enable
        say(R.string.bluetooth_a2dp_hw_offload_toast)
        publish()

        val wereConnected = a2dp?.connectedDevices.orEmpty().toList()
        appScope.launch {
            try {
                SystemProperties.set(PROP_OFFLOAD_DISABLED, (!enable).toString())
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to set $PROP_OFFLOAD_DISABLED", t)
            }
            try {
                SystemProperties.set("ctl.restart", "audioserver")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed ctl.restart audioserver", t)
            }
            try {
                val bt = ctx.getSystemService(BluetoothManager::class.java)?.adapter
                if (bt != null && bt.isEnabled) {
                    bt.disable()
                    // enable() while the adapter is still turning off can be dropped: wait for OFF
                    waitForAdapterState(bt, BluetoothAdapter.STATE_OFF)
                    bt.enable()
                    if (!waitForAdapterState(bt, BluetoothAdapter.STATE_ON)) {
                        Log.w(TAG, "Bluetooth not back on after the offload switch, enabling again")
                        bt.enable()
                        waitForAdapterState(bt, BluetoothAdapter.STATE_ON)
                    }
                    // After Android's own reconnect of the last headset, connect the rest
                    delay(5000)
                    for (device in wereConnected) {
                        if (!device.isConnected) {
                            Log.i(TAG, "Reconnecting $device after the offload switch")
                            device.connect()
                        }
                    }
                }
            } catch (t: Exception) {
                Log.e(TAG, "Failed to cycle BluetoothAdapter", t)
            }
            withContext(Dispatchers.Main) {
                offloadSwitching = false
                refreshOffload()
                refreshDevices()
                refreshCodec()
            }
        }
    }

    /** Polls the adapter state for up to 8 s. */
    private suspend fun waitForAdapterState(bt: BluetoothAdapter, state: Int): Boolean {
        repeat(80) {
            if (bt.state == state) return true
            delay(100)
        }
        return bt.state == state
    }

    private fun say(@StringRes res: Int) {
        _messages.trySend(ctx.getString(res))
    }

    // ------------------------------------------------------------------------ view state

    private fun displayName(device: BluetoothDevice?): String {
        if (device == null) return "?"
        val alias = device.alias
        if (!alias.isNullOrEmpty()) return alias
        val name = device.name
        if (!name.isNullOrEmpty()) return name
        return device.address
    }

    private fun ui(device: BluetoothDevice?, codec: CodecInfo?): HeadsetUi? {
        if (device == null) return null
        val battery = device.batteryLevel
        return HeadsetUi(device.address, displayName(device), if (battery >= 0) battery else null, codec)
    }

    private fun adjustValue(device: BluetoothDevice?): Int =
        if (device == null) 0 else SystemProperties.getInt(adjustProp(device), 0)

    private fun labelOf(entriesRes: Int, valuesRes: Int, value: String): String {
        val values = ctx.resources.getStringArray(valuesRes)
        val entries = ctx.resources.getStringArray(entriesRes)
        val i = values.indexOf(value)
        return if (i in entries.indices) entries[i] else value
    }

    private fun publish() {
        val btOn = adapter?.isEnabled == true
        val holdDetail = if (!sharingActive) {
            ctx.getString(
                if (lipsyncMode == "auto") R.string.audio_share_lipsync_auto_idle else R.string.audio_share_lipsync_manual_idle
            )
        } else {
            val hold = SystemProperties.getInt(PROP_HOLD_MS, 0)
            when {
                hold > 0 -> ctx.getString(R.string.audio_share_lipsync_hold_second, hold)
                hold < 0 -> ctx.getString(R.string.audio_share_lipsync_hold_main, -hold)
                else -> ctx.getString(R.string.audio_share_lipsync_in_step)
            }
        }
        val offloadDisabled = SystemProperties.getBoolean(PROP_OFFLOAD_DISABLED, false)
        _state.value = AudioShareUiState(
            btOn = btOn,
            masterOn = masterOn,
            sharing = sharingActive,
            starting = starting,
            stopping = stopping,
            primary = if (btOn) ui(primary, primaryCodec) else null,
            secondary = if (btOn) ui(secondary, if (secondary != null) secondaryCodec else null) else null,
            candidates = if (btOn) candidates.mapNotNull { ui(it, null) } else emptyList(),
            connected = if (btOn) connectedList.mapNotNull { ui(it, null) } else emptyList(),
            primaryPercent = primaryPercent,
            secondaryPercent = secondaryPercent,
            syncVolumes = syncVolumes,
            lipsyncOn = lipsyncOn,
            lipsyncMode = lipsyncMode,
            lipsyncModeLabel = labelOf(R.array.audio_share_lipsync_mode_entries, R.array.audio_share_lipsync_mode_values, lipsyncMode),
            lipsyncDetail = holdDetail,
            adjMain = adjustValue(primary),
            adjSecond = adjustValue(secondary),
            codecChoice = codecChoice,
            codecChoiceLabel = labelOf(R.array.audio_share_codec_entries, R.array.audio_share_codec_values, codecChoice),
            codecResult = sharingCodecResult(),
            offloadOn = offloadOn,
            offloadSwitching = offloadSwitching,
            aptxLowLatency = aptxLowLatency,
            aptxAvailable = aptxAvailable,
            engine = EngineInfo(
                offloadDisabled = offloadDisabled,
                sharingEnabled = masterOn,
                sharingCodec = SystemProperties.get(PROP_SHARING_CODEC, "auto"),
                sharingActive = sharingActive,
                primaryCodec = primaryCodec?.let { it.name + " • " + it.detail }?.trimEnd(' ', '•'),
                secondaryCodec = secondaryCodec?.let { it.name + " • " + it.detail }?.trimEnd(' ', '•'),
            ),
            resetTick = resetTick,
        )
    }

    // ------------------------------------------------------------------------ codecs

    // Vendor sharing compares native codec indices; the replacement accessor is absent in platform headers.
    @Suppress("DEPRECATION")
    private fun codecInfo(device: BluetoothDevice?): CodecInfo? {
        val proxy = a2dp
        if (device == null || proxy == null) return null
        return try {
            val cfg = proxy.getCodecStatus(device)?.codecConfig ?: return null
            val parts = listOf(
                sampleRateString(cfg.sampleRate), bitsPerSampleString(cfg.bitsPerSample), channelModeString(cfg.channelMode)
            ).filter { it.isNotEmpty() }
            CodecInfo(codecTypeName(cfg.codecType), parts.joinToString(" • "))
        } catch (t: Throwable) {
            Log.w(TAG, "codecLine: ${t.message}")
            null
        }
    }

    // Vendor sharing compares native codec indices; the replacement accessor is absent in platform headers.
    @Suppress("DEPRECATION")
    private fun supportsCodec(status: BluetoothCodecStatus?, type: Int): Boolean {
        if (status == null) return false
        return status.codecsSelectableCapabilities.any {
            it.codecType == type && (it.channelMode and BluetoothCodecConfig.CHANNEL_MODE_STEREO) != 0
        }
    }

    // ponytail: compares codec types only, the service also matches sample rate and bit depth
    private fun predictSharingCodec(choice: String): Int {
        val proxy = a2dp
        val prim = primary
        val sec = secondary
        if (proxy == null || prim == null || sec == null) return -1
        val primStatus = proxy.getCodecStatus(prim)
        val secStatus = proxy.getCodecStatus(sec)
        val values = ctx.resources.getStringArray(R.array.audio_share_codec_values)
        for (i in 1 until values.size) {
            if (values[i] == choice) {
                val type = SHARING_CODEC_ORDER[i - 1]
                if (supportsCodec(primStatus, type) && supportsCodec(secStatus, type)) return type
            }
        }
        for (type in SHARING_CODEC_ORDER) {
            if (supportsCodec(primStatus, type) && supportsCodec(secStatus, type)) return type
        }
        return -1
    }

    // Vendor sharing compares native codec indices; the replacement accessor is absent in platform headers.
    @Suppress("DEPRECATION")
    private fun sharingCodecResult(): String? = try {
        val proxy = a2dp
        val prim = primary
        if (sharingActive && proxy != null && prim != null) {
            proxy.getCodecStatus(prim)?.codecConfig?.let {
                ctx.getString(R.string.audio_share_codec_in_use, codecTypeName(it.codecType))
            }
        } else {
            val type = predictSharingCodec(codecChoice)
            if (type != -1) ctx.getString(R.string.audio_share_codec_will_use, codecTypeName(type))
            else ctx.getString(R.string.audio_share_codec_pick_devices)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "sharingCodecResult: ${t.message}")
        null
    }

    private fun sampleRateString(rate: Int): String = when {
        rate and BluetoothCodecConfig.SAMPLE_RATE_44100 != 0 -> "44.1 kHz"
        rate and BluetoothCodecConfig.SAMPLE_RATE_48000 != 0 -> "48 kHz"
        rate and BluetoothCodecConfig.SAMPLE_RATE_88200 != 0 -> "88.2 kHz"
        rate and BluetoothCodecConfig.SAMPLE_RATE_96000 != 0 -> "96 kHz"
        rate and BluetoothCodecConfig.SAMPLE_RATE_176400 != 0 -> "176.4 kHz"
        rate and BluetoothCodecConfig.SAMPLE_RATE_192000 != 0 -> "192 kHz"
        else -> ""
    }

    private fun bitsPerSampleString(bits: Int): String = when {
        bits and BluetoothCodecConfig.BITS_PER_SAMPLE_16 != 0 -> "16-bit"
        bits and BluetoothCodecConfig.BITS_PER_SAMPLE_24 != 0 -> "24-bit"
        bits and BluetoothCodecConfig.BITS_PER_SAMPLE_32 != 0 -> "32-bit"
        else -> ""
    }

    private fun channelModeString(mode: Int): String = when {
        mode and BluetoothCodecConfig.CHANNEL_MODE_STEREO != 0 -> "Stereo"
        mode and BluetoothCodecConfig.CHANNEL_MODE_MONO != 0 -> "Mono"
        else -> ""
    }

    // Native codec indices match the vendor sharing preference and ordering.
    @Suppress("DEPRECATION")
    private fun codecTypeName(type: Int): String = when (type) {
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC -> "SBC"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC -> "AAC"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX -> "aptX"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD -> "aptX HD"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC -> "LDAC"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_LC3 -> "LC3"
        BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS -> "Opus"
        else -> "Unknown"
    }

    private fun adjustProp(device: BluetoothDevice): String =
        PROP_ADJ_PREFIX + device.address.replace(":", "").uppercase(Locale.ROOT)

    private companion object {
        const val TAG = "AudioShareSettings"

        const val KEY_MASTER_SWITCH = "audio_share_master_switch"
        const val KEY_SYNC_VOLUMES = "audio_share_sync_volumes"
        const val KEY_LIPSYNC_MODE = "audio_share_lipsync_mode"
        const val KEY_LAST_SECONDARY = "last_secondary_device"

        const val PROP_OFFLOAD_DISABLED = "persist.bluetooth.a2dp_offload.disabled"
        // off, auto or manual; read by the Bluetooth stack (btif_a2dp_sharing_update_hold)
        const val PROP_LIPSYNC = "persist.bluetooth.a2dp_sharing.lipsync"
        // The hold the stack applies now, in ms (auto mode's result)
        const val PROP_HOLD_MS = "bluetooth.a2dp_sharing.hold_ms"
        // + headset address without colons: how much later than its delay report a headset plays
        const val PROP_ADJ_PREFIX = "persist.bluetooth.a2dp_sharing.adj."
        // Read by OplusA2dpSharingService when sharing starts
        const val PROP_SHARING_CODEC = "persist.bluetooth.a2dp_sharing.codec"
        // Older versions switched offload off for sharing and recorded it here; read once to restore
        const val PREF_OFFLOAD_OFF_FOR_SHARING = "offload_off_for_sharing"

        // Native BTAV_A2DP_CODEC_INDEX_SOURCE_APTX_ADAPTIVE and the aptX Adaptive mode bits of
        // codec_specific_4 the Bluetooth stack takes (Qualcomm APTX_HQ / APTX_LL).
        const val CODEC_TYPE_APTX_ADAPTIVE = 7
        const val APTX_MODE_HQ = 0x1000L
        const val APTX_MODE_LL = 0x2000L

        /** Same order as OplusA2dpSharingService: the codec shared is the first both headsets support. */
        @Suppress("DEPRECATION") // Vendor sharing uses these fixed native codec indices.
        val SHARING_CODEC_ORDER = intArrayOf(
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD,
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX,
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
        )

        /** The Bluetooth cycle must finish even if the screen is closed mid-way. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

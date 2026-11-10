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

import android.app.PendingIntent
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.oplus.bluetooth.OplusA2dpSharingManager
import org.lineageos.device.DeviceSettings.R
import org.lineageos.device.DeviceSettings.defaultPrefs

/** Quick Settings tile: one tap starts/stops sharing, or opens the screen when it cannot decide. */
class AudioShareTileService : TileService() {

    private var sharingManager: OplusA2dpSharingManager? = null
    private var adapter: BluetoothAdapter? = null
    private var a2dp: BluetoothProfile? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastClickTime = 0L

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateTile()
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                a2dp = proxy
                updateTile()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                a2dp = null
                updateTile()
            }
        }
    }

    private val stateCallback = SharingStateBridge.create(object : SharingStateBridge.Listener {
        override fun onStarted(primary: BluetoothDevice?, secondary: BluetoothDevice?) {
            handler.post { updateTile() }
        }

        override fun onStopped() {
            handler.post { updateTile() }
        }
    })

    override fun onCreate() {
        super.onCreate()
        OplusA2dpSharingManager.init(this)
        sharingManager = OplusA2dpSharingManager.getInstance()
        adapter = getSystemService(BluetoothManager::class.java)?.adapter
        adapter?.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)
        sharingManager?.registerStateCallback(null, stateCallback)
    }

    override fun onDestroy() {
        sharingManager?.unregisterStateCallback(stateCallback)
        val proxy = a2dp
        if (adapter != null && proxy != null) adapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy)
        super.onDestroy()
    }

    override fun onStartListening() {
        super.onStartListening()
        if (a2dp == null) adapter?.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED)
            }
            registerReceiver(btReceiver, filter, Context.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register btReceiver", e)
        }
        updateTile()
    }

    override fun onStopListening() {
        try {
            unregisterReceiver(btReceiver)
        } catch (_: Exception) {
        }
        super.onStopListening()
    }

    override fun onClick() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickTime < DEBOUNCE_DELAY_MS) {
            Log.d(TAG, "onClick: debounced, ignoring tap within ${now - lastClickTime}ms")
            return
        }
        lastClickTime = now

        val mgr = sharingManager
        val ad = adapter
        if (mgr == null || ad == null || !ad.isEnabled) {
            launchActivity()
            return
        }

        if (mgr.inSharingMode()) {
            Log.i(TAG, "onClick: stopping sharing")
            mgr.stopSharing()
            updateTile()
            return
        }

        val secondary = candidateSecondaryDevice()
        if (secondary == null) {
            Log.i(TAG, "onClick: no candidate secondary device, toggling master switch and opening UI")
            mgr.setA2dpSharingEnabled(!mgr.isA2dpSharingEnabled)
            launchActivity()
            return
        }
        Log.i(TAG, "onClick: enabling master switch and starting sharing to ${secondary.address}")
        mgr.setA2dpSharingEnabled(true)
        mgr.startSharing(secondary, OplusA2dpSharingManager.SHARING_TYPE_BONDED)
        updateTile()
    }

    private fun launchActivity() {
        val intent = Intent(this, AudioShareActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startActivityAndCollapse(pi)
    }

    private fun activeA2dpDevice(): BluetoothDevice? {
        val proxy = a2dp ?: return null
        return try {
            proxy.javaClass.getMethod("getActiveDevice").invoke(proxy) as? BluetoothDevice
        } catch (_: Throwable) {
            null
        }
    }

    private fun candidateSecondaryDevice(): BluetoothDevice? {
        val proxy = a2dp ?: return null
        if (adapter == null) return null
        val connected = proxy.connectedDevices
        if (connected.isNullOrEmpty()) return null
        val active = activeA2dpDevice()
        val last = defaultPrefs().getString("last_secondary_device", null)
        if (last != null) {
            connected.firstOrNull {
                it.address.equals(last, ignoreCase = true) &&
                    (active == null || !it.address.equals(active.address, ignoreCase = true))
            }?.let { return it }
        }
        return connected.firstOrNull { active == null || !it.address.equals(active.address, ignoreCase = true) }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val mgr = sharingManager
        val sharing = mgr?.inSharingMode() == true
        tile.icon = Icon.createWithResource(this, R.drawable.ic_audio_share)
        tile.label = getString(R.string.audio_share_tile_label)
        if (sharing) {
            tile.state = Tile.STATE_ACTIVE
            val devices = mgr?.sharingDevices
            tile.subtitle = if (devices != null && devices.size >= 2) {
                getString(R.string.audio_share_tile_streaming_to, deviceName(devices[0]), deviceName(devices[1]))
            } else {
                getString(R.string.audio_share_active_status)
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            val secondary = candidateSecondaryDevice()
            tile.subtitle = if (secondary != null) deviceName(secondary) else getString(R.string.audio_share_idle_status)
        }
        tile.updateTile()
    }

    private fun deviceName(dev: BluetoothDevice?): String {
        if (dev == null) return "Device"
        dev.alias?.takeIf { it.isNotEmpty() }?.let { return it }
        dev.name?.takeIf { it.isNotEmpty() }?.let { return it }
        return dev.address
    }

    private companion object {
        const val TAG = "AudioShareTileService"
        const val DEBOUNCE_DELAY_MS = 1200L
    }
}

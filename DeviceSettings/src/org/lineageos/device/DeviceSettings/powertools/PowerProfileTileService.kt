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

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.lineageos.device.DeviceSettings.R

/** Quick Settings tile cycling Power Save -> Normal -> Performance. */
class PowerProfileTileService : TileService() {

    private lateinit var manager: PowerProfileUtil
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var applying = false

    override fun onCreate() {
        super.onCreate()
        manager = PowerProfileUtil(this)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartListening() {
        super.onStartListening()
        if (!applying) updateTile()
    }

    override fun onClick() {
        if (applying) return
        if (PowerProfileUtil.profileCooldownRemaining() > 0) {
            Toast.makeText(this, R.string.profile_cooldown, Toast.LENGTH_SHORT).show()
            return
        }
        applying = true

        // Grey the tile out immediately so rapid taps cannot queue up.
        qsTile?.let {
            it.state = Tile.STATE_UNAVAILABLE
            it.subtitle = getString(R.string.powertools_toast_applying)
            it.updateTile()
        }

        scope.launch {
            val success = manager.toggleMode()
            applying = false
            updateTile()
            if (!success) {
                Toast.makeText(this@PowerProfileTileService, R.string.powertools_apply_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        var mode = manager.managedMode
        if (mode < 0 || mode >= TILE_STATES.size) mode = PowerProfileUtil.MODE_UNKNOWN

        tile.state = TILE_STATES[mode]
        tile.icon = Icon.createWithResource(this, TILE_ICONS[mode])
        tile.label = getString(R.string.powerprofile_tile_label)
        tile.subtitle = manager.modeLabel
        tile.updateTile()
    }

    private companion object {
        // 0: Saver, 1: Balance, 2: Performance, 3: unused, 4: unknown
        val TILE_STATES = intArrayOf(
            Tile.STATE_INACTIVE, Tile.STATE_INACTIVE, Tile.STATE_ACTIVE, Tile.STATE_INACTIVE, Tile.STATE_INACTIVE
        )
        val TILE_ICONS = intArrayOf(
            R.drawable.ic_thermal_battery_saver, R.drawable.ic_thermal_balance, R.drawable.ic_thermal_performance,
            R.drawable.ic_thermal_balance, R.drawable.ic_thermal_balance
        )
    }
}

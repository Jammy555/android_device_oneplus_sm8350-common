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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import java.util.concurrent.Executors
import org.lineageos.device.DeviceSettings.audioshare.SeparateAppSoundManager
import org.lineageos.device.DeviceSettings.dock.SmartDockService
import org.lineageos.device.DeviceSettings.network.NetworkBandsRestore

class Startup : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != ACTION_INITIALIZE
        ) return

        Log.i(TAG, "Startup broadcast received: $action")
        val pending = goAsync()
        executor.execute {
            try {
                DeviceRestore.restoreFastCharge(context)
                DeviceRestore.restoreVibStrength(context)
                NetworkBandsRestore.restoreNrModeSettings(context)

                val userManager = context.getSystemService(UserManager::class.java)
                if (userManager == null || userManager.isUserUnlocked) {
                    SmartDockService.restoreState(context)
                    SeparateAppSoundManager.getInstance(context).updateRouting()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore hardware settings during startup", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DeviceSettingsStartup"
        const val ACTION_INITIALIZE = "lineageos.intent.action.INITIALIZE_LINEAGE_HARDWARE"
        val executor = Executors.newSingleThreadExecutor()
    }
}

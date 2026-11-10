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
import android.provider.Settings
import android.util.Log
import org.lineageos.device.DeviceSettings.defaultPrefs

object BlurUtils {

    /**
     * The disable_window_blurs value that was active BEFORE power save forced blur off.
     * Only present while power save is (or was) active; removed as soon as it is restored.
     */
    private const val PREF_POWERSAVE_BLUR_BACKUP = "powersave_blur_backup"
    private const val SETTING_BLUR = "disable_window_blurs"
    private const val TAG = "BlurUtils"

    /**
     * disable = true  -> power save is active: back up the current blur state, force blur OFF.
     * disable = false -> leaving power save: restore the backed-up value if one exists, otherwise
     *                    leave the setting alone (Settings.Global already persists by itself).
     */
    @JvmStatic
    fun setBlurDisabled(context: Context, disable: Boolean): Boolean {
        val prefs = context.defaultPrefs()
        return try {
            if (disable) {
                if (!prefs.contains(PREF_POWERSAVE_BLUR_BACKUP)) {
                    val current = Settings.Global.getInt(context.contentResolver, SETTING_BLUR, 0)
                    if (!prefs.edit().putInt(PREF_POWERSAVE_BLUR_BACKUP, current).commit()) return false
                }
                Settings.Global.putInt(context.contentResolver, SETTING_BLUR, 1)
            } else {
                if (prefs.contains(PREF_POWERSAVE_BLUR_BACKUP)) {
                    val backup = prefs.getInt(PREF_POWERSAVE_BLUR_BACKUP, 0)
                    if (Settings.Global.putInt(context.contentResolver, SETTING_BLUR, backup)) {
                        prefs.edit().remove(PREF_POWERSAVE_BLUR_BACKUP).apply()
                    } else {
                        return false
                    }
                }
                true
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not apply blur preference", e)
            false
        }
    }
}

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

import android.content.ComponentName
import android.content.Intent
import org.lineageos.device.DeviceSettings.audioshare.AudioShareActivity
import org.lineageos.device.DeviceSettings.ui.ComposeHostActivity
import org.lineageos.device.DeviceSettings.ui.Screen

/** QS tile long-press / performance notification target. Opens straight on Power Tools. */
class PowertoolsActivity : ComposeHostActivity() {
    override val startScreen: Screen = Screen.Powertools

    override fun redirect(): Intent? {
        val component = intent?.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
        if (component != null && component.className.contains("AudioShare")) {
            return Intent(this, AudioShareActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)
        }
        return null
    }
}

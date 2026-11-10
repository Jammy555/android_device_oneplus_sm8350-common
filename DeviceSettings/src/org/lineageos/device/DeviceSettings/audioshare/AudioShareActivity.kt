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

import org.lineageos.device.DeviceSettings.ui.ComposeHostActivity
import org.lineageos.device.DeviceSettings.ui.Screen

/** Audio Sharing. Component name and AUDIO_SHARE action unchanged (QS tile, Power Tools redirect). */
class AudioShareActivity : ComposeHostActivity() {
    override val startScreen: Screen = Screen.AudioShare
}

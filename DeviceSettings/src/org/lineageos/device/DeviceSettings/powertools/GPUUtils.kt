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

object GPUUtils {
    fun setFrequencies(min: String, max: String, governor: String): Boolean {
        val property = "persist.sys.parts.gpu."
        val path = "/sys/class/kgsl/kgsl-3d0/devfreq/"
        return SysfsUtils.setPropertyAndWait(property + "min_frequency", "0", path + "profile_min_freq") &&
            SysfsUtils.setPropertyAndWait(property + "max_frequency", max, path + "profile_max_freq") &&
            SysfsUtils.setPropertyAndWait(property + "min_frequency", min, path + "profile_min_freq") &&
            SysfsUtils.setPropertyAndWait(property + "governor", governor, path + "governor")
    }
}

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

import org.lineageos.device.DeviceSettings.ui.ComposeHostActivity
import org.lineageos.device.DeviceSettings.ui.Screen

/** Network band locking. Component name unchanged. */
class NetworkBandsActivity : ComposeHostActivity() {
    override val startScreen: Screen = Screen.NetworkBands
}

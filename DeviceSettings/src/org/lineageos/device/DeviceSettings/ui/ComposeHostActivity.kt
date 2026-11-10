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

package org.lineageos.device.DeviceSettings.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.lineageos.device.DeviceSettings.Utils
import org.lineageos.device.DeviceSettings.audioshare.AudioShareViewModel
import org.lineageos.device.DeviceSettings.audioshare.SeparateAppSoundViewModel
import org.lineageos.device.DeviceSettings.network.NetworkBandsViewModel
import org.lineageos.device.DeviceSettings.ui.dock.DockHistoryViewModel
import org.lineageos.device.DeviceSettings.ui.dock.DockSettingsViewModel
import org.lineageos.device.DeviceSettings.ui.main.MainViewModel
import org.lineageos.device.DeviceSettings.ui.powertools.PowertoolsViewModel
import org.lineageos.device.DeviceSettings.ui.theme.DeviceSettingsTheme

/**
 * Shared host for the Compose UI. [DeviceSettingsActivity][org.lineageos.device.DeviceSettings.DeviceSettingsActivity]
 * starts on the main screen, `PowertoolsActivity` (QS tile long-press, notification) starts on
 * Power Tools. Both can reach the other screen without leaving the activity.
 */
abstract class ComposeHostActivity : ComponentActivity(), AppNavigator {

    protected abstract val startScreen: Screen

    /** Return an intent to hand off to another activity instead of showing UI. */
    protected open fun redirect(): Intent? = null

    private val mainVm: MainViewModel by viewModels()
    private val powerVm: PowertoolsViewModel by viewModels()

    // Lazy on purpose: these register Bluetooth listeners, so only create them once their screen opens.
    private val audioVm: Lazy<AudioShareViewModel> = viewModels()
    private val appSoundVm: Lazy<SeparateAppSoundViewModel> = viewModels()
    private val dockVm: Lazy<DockSettingsViewModel> = viewModels()
    private val historyVm: Lazy<DockHistoryViewModel> = viewModels()
    private val networkVm: Lazy<NetworkBandsViewModel> = viewModels()
    private var resumed by mutableStateOf(false)
    private var dynamicColor by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        redirect()?.let {
            startActivity(it)
            finish()
            return
        }

        dynamicColor = Utils.useDynamicColors(this)
        val highlightKey = intent?.getStringExtra(EXTRA_FRAGMENT_ARG_KEY)
        val openScreen = intent?.getStringExtra(EXTRA_OPEN_SCREEN)

        setContent {
            DeviceSettingsTheme(dynamicColor = dynamicColor) {
                AppRoot(
                    start = startScreen,
                    mainVm = mainVm,
                    powerVm = powerVm,
                    audioVm = audioVm,
                    appSoundVm = appSoundVm,
                    dockVm = dockVm,
                    historyVm = historyVm,
                    openScreen = openScreen,
                    networkVm = networkVm,
                    resumed = resumed,
                    dynamicColor = dynamicColor,
                    onDynamicColorChange = {
                        dynamicColor = it
                        Utils.setUseDynamicColors(this@ComposeHostActivity, it)
                    },
                    highlightKey = highlightKey,
                    navigator = this@ComposeHostActivity,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun close() = finish()

    companion object {
        /** Extra system Settings search adds so the target screen can highlight the matching row. */
        const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"

        /** Set by the File Dock shelf to jump straight to a screen ("dock_history"). */
        const val EXTRA_OPEN_SCREEN = "open_screen"
    }
}

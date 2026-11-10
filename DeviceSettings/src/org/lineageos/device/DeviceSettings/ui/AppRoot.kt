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

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import org.lineageos.device.DeviceSettings.audioshare.AudioShareViewModel
import org.lineageos.device.DeviceSettings.audioshare.SeparateAppSoundViewModel
import org.lineageos.device.DeviceSettings.ui.audioshare.AudioShareScreen
import org.lineageos.device.DeviceSettings.ui.audioshare.SeparateAppSoundScreen
import org.lineageos.device.DeviceSettings.network.NetworkBandsViewModel
import org.lineageos.device.DeviceSettings.ui.network.NetworkBandsScreen
import org.lineageos.device.DeviceSettings.ui.dock.DockHistoryScreen
import org.lineageos.device.DeviceSettings.ui.dock.DockHistoryViewModel
import org.lineageos.device.DeviceSettings.ui.dock.DockSettingsScreen
import org.lineageos.device.DeviceSettings.ui.dock.DockSettingsViewModel
import org.lineageos.device.DeviceSettings.ui.components.LocalNavVisibilityScope
import org.lineageos.device.DeviceSettings.ui.components.LocalSharedTransitionScope
import org.lineageos.device.DeviceSettings.ui.main.MainScreen
import org.lineageos.device.DeviceSettings.ui.main.MainViewModel
import org.lineageos.device.DeviceSettings.ui.powertools.PowertoolsScreen
import org.lineageos.device.DeviceSettings.ui.powertools.PowertoolsViewModel

enum class Screen { Main, Powertools, AudioShare, SeparateAppSound, DockSettings, DockHistory, NetworkBands }

/** What the Compose UI needs from its host activity. */
interface AppNavigator {
    fun close()
}

/**
 * Root of the app. Main and Power Tools live in one transition layout, so the hero card grows into
 * the Power Tools screen (container transform) and the back gesture previews the way out
 * (predictive back: the screen shrinks and rounds off under your finger).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppRoot(
    start: Screen,
    mainVm: MainViewModel,
    powerVm: PowertoolsViewModel,
    audioVm: Lazy<AudioShareViewModel>,
    appSoundVm: Lazy<SeparateAppSoundViewModel>,
    dockVm: Lazy<DockSettingsViewModel>,
    historyVm: Lazy<DockHistoryViewModel>,
    openScreen: String?,
    networkVm: Lazy<NetworkBandsViewModel>,
    resumed: Boolean,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    highlightKey: String?,
    navigator: AppNavigator,
) {
    // Navigation stack. Saved across rotation; the first entry is where this activity started.
    var backStack by rememberSaveable { mutableStateOf(listOf(start)) }
    val screen = backStack.last()
    val forward = remember { booleanArrayOf(true) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    val mainSnack = remember { SnackbarHostState() }
    val powerSnack = remember { SnackbarHostState() }
    val audioSnack = remember { SnackbarHostState() }
    val dockSnack = remember { SnackbarHostState() }
    val networkSnack = remember { SnackbarHostState() }

    fun push(target: Screen) {
        if (backStack.last() != target) {
            forward[0] = true
            backStack = backStack + target
        }
    }

    /** Pops one screen; false when already at the root (caller should finish the activity). */
    fun pop(): Boolean {
        if (backStack.size <= 1) return false
        forward[0] = false
        backStack = backStack.dropLast(1)
        return true
    }

    LaunchedEffect(openScreen) {
        if (openScreen == "dock_history" && backStack.last() != Screen.DockHistory) push(Screen.DockHistory)
    }
    LaunchedEffect(mainVm) { mainVm.messages.collect { mainSnack.showSnackbar(it) } }
    LaunchedEffect(powerVm) { powerVm.messages.collect { powerSnack.showSnackbar(it) } }

    // Poll hardware only while the activity is on screen, and only for the visible screen.
    LaunchedEffect(resumed, screen) {
        mainVm.onPause()
        powerVm.onPause()
        // only touch a lazy VM that was ever created
        if (networkVm.isInitialized()) networkVm.value.onPause()
        if (resumed) {
            when (screen) {
                Screen.Main -> mainVm.onResume()
                Screen.Powertools -> powerVm.onResume()
                Screen.AudioShare -> audioVm.value.onResume()
                Screen.SeparateAppSound -> Unit
                Screen.DockSettings -> dockVm.value.refresh()
                Screen.DockHistory -> historyVm.value.load()
                Screen.NetworkBands -> networkVm.value.onResume()
            }
        }
    }

    PredictiveBackHandler(enabled = backStack.size > 1) { events: Flow<BackEventCompat> ->
        try {
            events.collect { backProgress = it.progress }
            pop()
        } catch (e: CancellationException) {
            // gesture cancelled: spring back
        } finally {
            backProgress = 0f
        }
    }

    SharedTransitionLayout {
        val sharedScope = this
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                val containerPair = (initialState == Screen.Main && targetState == Screen.Powertools) ||
                    (initialState == Screen.Powertools && targetState == Screen.Main)
                if (containerPair) {
                    // the hero card and Power Tools share bounds, so only fade the rest
                    (fadeIn(tween(260, delayMillis = 60)) + scaleIn(tween(320), initialScale = 0.94f)) togetherWith
                        fadeOut(tween(140))
                } else if (forward[0]) {
                    (slideInHorizontally(tween(320)) { it / 8 } + fadeIn(tween(260, delayMillis = 60))) togetherWith
                        (slideOutHorizontally(tween(320)) { -it / 10 } + fadeOut(tween(160)))
                } else {
                    (slideInHorizontally(tween(320)) { -it / 10 } + fadeIn(tween(260, delayMillis = 60))) togetherWith
                        (slideOutHorizontally(tween(320)) { it / 8 } + fadeOut(tween(160)))
                }
            },
            label = "root",
        ) { target ->
            val visibilityScope = this
            CompositionLocalProvider(
                LocalSharedTransitionScope provides sharedScope,
                LocalNavVisibilityScope provides visibilityScope,
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = if (target == screen && backStack.size > 1) backProgress else 0f
                            scaleX = 1f - 0.1f * p
                            scaleY = 1f - 0.1f * p
                            shape = RoundedCornerShape((32 * p).dp)
                            clip = p > 0f
                        }
                ) {
                    when (target) {
                        Screen.Main -> MainScreen(
                            vm = mainVm,
                            snackbarHostState = mainSnack,
                            highlightKey = highlightKey,
                            dynamicColor = dynamicColor,
                            onDynamicColorChange = onDynamicColorChange,
                            onOpenPowertools = { push(Screen.Powertools) },
                            onOpenNetworkBands = { push(Screen.NetworkBands) },
                            onOpenAudioShare = { push(Screen.AudioShare) },
                            onOpenDockSettings = { push(Screen.DockSettings) },
                            onBack = navigator::close,
                        )
                        Screen.Powertools -> PowertoolsScreen(
                            vm = powerVm,
                            snackbarHostState = powerSnack,
                            onBack = { if (!pop()) navigator.close() },
                        )
                        Screen.AudioShare -> {
                            val vm = audioVm.value
                            LaunchedEffect(vm) { vm.messages.collect { audioSnack.showSnackbar(it) } }
                            AudioShareScreen(
                                vm = vm,
                                snackbarHostState = audioSnack,
                                onOpenSeparateAppSound = { push(Screen.SeparateAppSound) },
                                onBack = { if (!pop()) navigator.close() },
                            )
                        }
                        Screen.NetworkBands -> {
                            val vm = networkVm.value
                            LaunchedEffect(vm) { vm.messages.collect { networkSnack.showSnackbar(it) } }
                            NetworkBandsScreen(
                                vm = vm,
                                snackbarHostState = networkSnack,
                                onBack = { if (!pop()) navigator.close() },
                            )
                        }
                        Screen.DockSettings -> {
                            val vm = dockVm.value
                            LaunchedEffect(vm) { vm.messages.collect { dockSnack.showSnackbar(it) } }
                            DockSettingsScreen(
                                vm = vm,
                                snackbarHostState = dockSnack,
                                onOpenHistory = { push(Screen.DockHistory) },
                                onBack = { if (!pop()) navigator.close() },
                            )
                        }
                        Screen.DockHistory -> {
                            val vm = historyVm.value
                            LaunchedEffect(vm) { vm.messages.collect { dockSnack.showSnackbar(it) } }
                            DockHistoryScreen(
                                vm = vm,
                                snackbarHostState = dockSnack,
                                onBack = { if (!pop()) navigator.close() },
                            )
                        }
                        Screen.SeparateAppSound -> SeparateAppSoundScreen(
                            vm = appSoundVm.value,
                            onBack = { if (!pop()) navigator.close() },
                        )
                    }
                }
            }
        }
    }
    if (screen != Screen.DockHistory) {
        FirstUseDialog(
            resumed = resumed,
            onOpenPasteSettings = { dockVm.value.openAccessibilitySettings() },
        )
    }
}

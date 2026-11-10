/* Copyright (C) 2026 LunarisOS
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package org.lineageos.device.DeviceSettings.dock

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.android.settingslib.display.BrightnessUtils
import kotlin.math.roundToInt
import org.lineageos.device.DeviceSettings.R
import org.lineageos.device.DeviceSettings.Utils
import org.lineageos.device.DeviceSettings.defaultPrefs
import org.lineageos.device.DeviceSettings.ui.components.SymbolBadge
import org.lineageos.device.DeviceSettings.ui.symbols.Symbol
import org.lineageos.device.DeviceSettings.ui.theme.DeviceSettingsTheme
import org.lineageos.device.DeviceSettings.ui.theme.Motion

/** The handle owns the original pointer; Compose supplies the same controls for direct interaction. */
internal class DockQuickControls(
    private val context: Context,
    private val windows: WindowManager,
) {
    private var panel: ComposeView? = null
    private var glass by mutableStateOf(false)
    private val owner = OverlayOwner()
    private val gesture = DockSliderGesture(dp(16).toFloat(), dp(236).toFloat())
    private val lanes = arrayOf(Rect.Zero, Rect.Zero)
    private val location = IntArray(2)
    private val values = mutableStateListOf(0, 0)
    private val enabled = mutableStateListOf(true, true)
    private val maximum = intArrayOf(BrightnessUtils.GAMMA_SPACE_MAX, 1)
    private var selected by mutableIntStateOf(-1)
    private var waitingLane = -1
    private var lastDragX = 0f
    private var lastDragY = 0f
    private val settleLane = Runnable { drag(lastDragX, lastDragY) }
    private var minimumBrightness = 0f
    private var maximumBrightness = 1f
    private var pendingBrightness: Float? = null
    private val display = context.getSystemService(DisplayManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val users = context.getSystemService(UserManager::class.java)

    fun show(right: Boolean, anchorY: Int) {
        val info = display.getDisplay(Display.DEFAULT_DISPLAY)?.brightnessInfo
        minimumBrightness = info?.brightnessMinimum ?: 0f
        maximumBrightness = info?.brightnessMaximum ?: 1f
        val brightness = info?.brightness ?: display.getBrightness(Display.DEFAULT_DISPLAY)
        values[0] = BrightnessUtils.convertLinearToGammaFloat(
            brightness.takeIf { it.isFinite() }?.coerceIn(minimumBrightness, maximumBrightness)
                ?: minimumBrightness, minimumBrightness, maximumBrightness)
        enabled[0] = !users.hasUserRestriction(UserManager.DISALLOW_CONFIG_BRIGHTNESS)
        maximum[1] = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        values[1] = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        enabled[1] = !audio.isVolumeFixed && !users.hasUserRestriction(UserManager.DISALLOW_ADJUST_VOLUME)
        val bounds = windows.currentWindowMetrics.bounds
        val insets = windows.currentWindowMetrics.windowInsets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val width = minOf(dp(208), bounds.width() - insets.left - insets.right - dp(32))
        val height = minOf(dp(296), bounds.height() - insets.top - insets.bottom - dp(24))
        val params = WindowManager.LayoutParams(width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or if (right) Gravity.RIGHT else Gravity.LEFT
        params.x = (if (right) insets.right else insets.left) + dp(16)
        params.y = (anchorY - height / 2).coerceIn(insets.top,
            maxOf(insets.top, bounds.height() - insets.bottom - height))
        params.setFitInsetsTypes(0)
        val view = ComposeView(context)
        panel = view
        DockPanelBackground.install(view, windows,
            context.defaultPrefs().getBoolean(SmartDockService.KEY_BLUR, false), 32) { glass = it }
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setContent { DeviceSettingsTheme(dynamicColor = Utils.useDynamicColors(context)) { Content() } }
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) dismiss()
            false
        }
        windows.addView(view, params)
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        if (ValueAnimator.areAnimatorsEnabled()) {
            // grows out of the handle with a soft overshoot
            view.pivotX = if (right) width.toFloat() else 0f
            view.pivotY = height / 2f
            view.alpha = 0f
            view.scaleX = 0.84f
            view.scaleY = 0.84f
            view.translationX = dp(if (right) 20 else -20).toFloat()
            view.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).setDuration(320)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start()
        }
    }

    @Composable
    private fun Content() {
        val hint = stringResource(R.string.dock_quick_hint)
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = if (glass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.semantics { contentDescription = hint },
        ) {
            Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(2) { index -> Control(index, Modifier.weight(1f)) }
            }
        }
    }

    @Composable
    private fun Control(index: Int, modifier: Modifier) {
        val active = selected == index
        val radius by animateDpAsState(if (active) 30.dp else 22.dp, Motion.spatialFast(), label = "controlShape")
        val tint by animateColorAsState(if (active) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh, Motion.effects(), label = "controlColour")
        val title = stringResource(if (index == 0) R.string.dock_brightness else R.string.dock_media_volume)
        val state = remember(index) { SliderState(valueRange = 0f..maximum[index].toFloat()) }
        state.value = values[index].toFloat()
        state.onValueChange = { selected = index; change(index, it.roundToInt()) }
        state.onValueChangeFinished = { finishBrightness() }
        Surface(modifier.fillMaxHeight().onGloballyPositioned { lanes[index] = it.boundsInRoot() },
            shape = RoundedCornerShape(radius), color = tint) {
            Column(Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SymbolBadge(if (index == 0) Symbol.LIGHT_MODE else if (values[1] == 0) Symbol.VOLUME_OFF else Symbol.VOLUME_UP,
                    active = active && enabled[index], actionKey = values[index])
                Text("${values[index] * 100 / maximum[index]}%", style = MaterialTheme.typography.titleSmall)
                VerticalSlider(state, enabled = enabled[index], reverseDirection = true,
                    modifier = Modifier.weight(1f).semantics { contentDescription = title })
            }
        }
    }

    private fun change(index: Int, progress: Int) {
        if (panel == null || !enabled[index]) return
        val next = progress.coerceIn(0, maximum[index])
        if (next == values[index]) return
        try {
            if (index == 0) {
                val value = BrightnessUtils.convertGammaToLinearFloat(next, minimumBrightness, maximumBrightness)
                    .coerceIn(minimumBrightness, maximumBrightness)
                display.setTemporaryBrightness(Display.DEFAULT_DISPLAY, value)
                pendingBrightness = value
                values[index] = next
            } else {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                values[index] = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
        } catch (e: RuntimeException) {
            Log.w("FileDock", "Quick control refused", e)
            enabled[index] = false
            Toast.makeText(context, R.string.dock_control_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    fun drag(rawX: Float, rawY: Float) {
        val view = panel ?: return
        lastDragX = rawX
        lastDragY = rawY
        view.getLocationOnScreen(location)
        val x = rawX - location[0]
        // Cross 12 dp inside the next card before its dwell timer begins.
        val margin = dp(12)
        var lane = lanes.indices.firstOrNull { enabled[it] && x >= lanes[it].left + margin &&
            x < lanes[it].right - margin } ?: -1
        val active = gesture.active
        if (lane < 0 && active >= 0 && enabled[active] && x >= lanes[active].left - margin &&
            x < lanes[active].right + margin) lane = active
        if (lane >= 0 && lane != active) {
            if (waitingLane != lane) {
                waitingLane = lane
                view.removeCallbacks(settleLane)
                view.postDelayed(settleLane, 180)
            }
        } else {
            waitingLane = -1
            view.removeCallbacks(settleLane)
        }
        val next = gesture.update(lane, rawY, SystemClock.uptimeMillis(),
            if (lane >= 0) values[lane] else 0, if (lane >= 0) maximum[lane] else 0)
        selected = gesture.active
        if (next != null && lane >= 0) change(lane, next)
    }

    private fun finishBrightness() {
        val value = pendingBrightness ?: return
        pendingBrightness = null
        try {
            display.setBrightness(Display.DEFAULT_DISPLAY, value)
        } catch (e: RuntimeException) {
            Log.w("FileDock", "Brightness commit refused", e)
            Toast.makeText(context, R.string.dock_control_unavailable, Toast.LENGTH_SHORT).show()
        } finally {
            try { display.setTemporaryBrightness(Display.DEFAULT_DISPLAY, Float.NaN) }
            catch (e: RuntimeException) { Log.w("FileDock", "Temporary brightness reset refused", e) }
        }
    }

    fun dismiss() {
        val view = panel ?: return
        panel = null
        view.removeCallbacks(settleLane)
        finishBrightness()
        view.animate().cancel()
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        view.disposeComposition()
        if (view.isAttachedToWindow) windows.removeView(view)
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    internal class OverlayOwner : SavedStateRegistryOwner {
        override val lifecycle = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val savedStateRegistry get() = controller.savedStateRegistry
        init {
            controller.performAttach()
            controller.performRestore(null)
            lifecycle.currentState = Lifecycle.State.CREATED
        }
    }

    companion object {
        internal fun batteryColor(percent: Int): Int = when {
            percent < 0 -> 0xFF888888.toInt()
            percent <= 30 -> 0xFFF44336.toInt()
            percent <= 60 -> 0xFFFFC107.toInt()
            percent <= 80 -> 0xFF4CAF50.toInt()
            else -> 0xFF2196F3.toInt()
        }
    }
}

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

@file:OptIn(
    ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.ui.text.ExperimentalTextApi::class,
)

package org.lineageos.device.DeviceSettings.ui.symbols

import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.launch
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.lineageos.device.DeviceSettings.R
import org.lineageos.device.DeviceSettings.ui.theme.Motion

private const val FILL_STEPS = 16
private const val MIN_WEIGHT = 300
private const val MAX_WEIGHT = 700

val LocalSymbolPressed = staticCompositionLocalOf { false }

private fun symbolFamily(fill: Float, weight: Int): FontFamily = FontFamily(
    Font(
        resId = R.font.material_symbols,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight),
            FontVariation.Setting("FILL", fill),
        ),
    )
)

/**
 * A Material Symbols (Rounded) glyph drawn from the bundled variable font.
 *
 * [fill] (0..1) and [weight] (300..700) are real font axes, so they can be animated smoothly - this
 * is what powers the outline -> filled transition on selection. Fill is quantised to 1/16 steps so
 * the platform typeface cache stays small while the animation still looks continuous.
 */
@Composable
fun SymbolIcon(
    symbol: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
    fill: Float = 0f,
    weight: Int = 400,
    contentDescription: String? = null,
) {
    var iconPressed by remember { mutableStateOf(false) }
    val pressed = iconPressed || LocalSymbolPressed.current
    val pressScale by animateFloatAsState(
        if (pressed) 0.85f else 1f, Motion.spatialFast(), label = "symbolPress",
    )
    val steps = (fill.coerceIn(0f, 1f) * FILL_STEPS).roundToInt()
    val w = (weight / 25 * 25).coerceIn(MIN_WEIGHT, MAX_WEIGHT)
    val family = remember(steps, w) { symbolFamily(steps / FILL_STEPS.toFloat(), w) }
    val fontSize = with(LocalDensity.current) { size.toSp() }
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier.clearAndSetSemantics { }
    }
    Box(modifier.size(size).then(semanticsModifier).pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            iconPressed = true
            try { waitForUpOrCancellation(pass = PointerEventPass.Initial) }
            finally { iconPressed = false }
        }
    }.graphicsLayer { scaleX = pressScale; scaleY = pressScale }, contentAlignment = Alignment.Center) {
        BasicText(
            text = symbol,
            style = TextStyle(fontFamily = family, fontSize = fontSize, lineHeight = fontSize, color = tint),
        )
    }
}

/**
 * [SymbolIcon] that animates between outline and filled when [active] flips, with a small
 * springy "pop" on activation. Weight also rises slightly while active.
 */
@Composable
fun AnimatedSymbol(
    symbol: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
    actionKey: Any? = null,
) {
    val fill by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = Motion.spatialFast(),
        label = "symbolFill",
    )
    val pop = remember { Animatable(1f) }
    val shake = remember { Animatable(0f) }
    val nudgeX = remember { Animatable(0f) }
    val nudgeY = remember { Animatable(0f) }
    val pressed = LocalSymbolPressed.current
    val firstRun = remember { booleanArrayOf(true) }
    LaunchedEffect(active, symbol, actionKey, pressed) {
        if (firstRun[0]) {
            firstRun[0] = false
            return@LaunchedEffect
        }
        shake.snapTo(0f)
        nudgeX.snapTo(0f)
        nudgeY.snapTo(0f)
        when (symbol) {
            // Gear: an eighth of a turn looks identical to the start, so the snap back is invisible.
            Symbol.SETTINGS -> {
                shake.animateTo(45f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
                shake.snapTo(0f)
            }
            // Spinners: one full turn.
            Symbol.REFRESH, Symbol.SYNC, Symbol.RESTART_ALT, Symbol.AUTO_MODE -> {
                shake.animateTo(360f, tween(560, easing = FastOutSlowInEasing))
                shake.snapTo(0f)
            }
            // Arrows that swap: half a turn.
            Symbol.SWAP_HORIZ, Symbol.SWAP_VERT -> {
                shake.animateTo(180f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow))
                shake.snapTo(0f)
            }
            Symbol.VIBRATION -> if (active) {
                shake.animateTo(0f, keyframes {
                    durationMillis = 280
                    -12f at 40
                    12f at 90
                    -8f at 140
                    8f at 190
                    0f at 280
                })
            } else {
                pop.snapTo(0.9f)
                pop.animateTo(1f, spring(dampingRatio = 0.34f, stiffness = Spring.StiffnessMediumLow))
            }
            // Bin: a quick no-no wobble.
            Symbol.DELETE -> shake.animateTo(0f, keyframes {
                durationMillis = 360
                -14f at 50
                14f at 120
                -10f at 190
                8f at 260
                0f at 360
            })
            // Sound: the speaker bumps outward like a wave.
            Symbol.VOLUME_UP, Symbol.VOLUME_DOWN, Symbol.VOLUME_OFF, Symbol.HEADPHONES, Symbol.HEADSET -> {
                pop.snapTo(0.82f)
                launch { nudgeX.animateTo(0f, keyframes { durationMillis = 320; 3f at 80; -1.5f at 170; 0f at 320 }) }
                pop.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = Spring.StiffnessMedium))
            }
            // Things that hand something off hop upward.
            Symbol.CONTENT_COPY, Symbol.CONTENT_PASTE, Symbol.SHARE, Symbol.UPLOAD, Symbol.DOWNLOAD -> {
                nudgeY.animateTo(0f, keyframes { durationMillis = 340; -5f at 110; 1.5f at 220; 0f at 340 })
            }
            // Charge / power: a bright flash-pop.
            Symbol.BOLT, Symbol.FLASH_ON, Symbol.OFFLINE_BOLT, Symbol.POWER_SETTINGS_NEW -> {
                pop.snapTo(1.38f)
                pop.animateTo(1f, spring(dampingRatio = 0.28f, stiffness = Spring.StiffnessMedium))
            }
            // Confirmations pop bigger than ordinary icons.
            Symbol.CHECK, Symbol.CHECK_CIRCLE, Symbol.DONE -> {
                pop.snapTo(0.55f)
                pop.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = Spring.StiffnessMedium))
            }
            Symbol.SEARCH -> shake.animateTo(0f, keyframes { durationMillis = 320; -18f at 90; 10f at 190; 0f at 320 })
            // Chevrons nudge in the direction they point.
            Symbol.CHEVRON_RIGHT -> nudgeX.animateTo(0f, keyframes { durationMillis = 300; 5f at 90; 0f at 300 })
            Symbol.EXPAND_MORE -> nudgeY.animateTo(0f, keyframes { durationMillis = 300; 4f at 90; 0f at 300 })
            Symbol.EXPAND_LESS -> nudgeY.animateTo(0f, keyframes { durationMillis = 300; -4f at 90; 0f at 300 })
            Symbol.BLUETOOTH, Symbol.BLUETOOTH_AUDIO, Symbol.CELL_TOWER, Symbol.SIGNAL_CELLULAR_ALT, Symbol.WIFI -> {
                pop.snapTo(1.22f)
                pop.animateTo(0.94f, tween(120))
                pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
            }
            else -> {
                pop.snapTo(if (pressed) 0.85f else 0.9f)
                pop.animateTo(1f, spring(dampingRatio = 0.34f, stiffness = Spring.StiffnessMediumLow))
            }
        }
    }
    SymbolIcon(
        symbol = symbol,
        modifier = modifier.graphicsLayer {
            scaleX = pop.value
            scaleY = pop.value
            rotationZ = shake.value
            translationX = nudgeX.value * density
            translationY = nudgeY.value * density
        },
        size = size,
        tint = tint,
        fill = fill,
        weight = 400 + (200 * fill.coerceIn(0f, 1f)).roundToInt(),
        contentDescription = contentDescription,
    )
}

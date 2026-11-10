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
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.lineageos.device.DeviceSettings.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.roundToInt
import org.lineageos.device.DeviceSettings.ui.symbols.AnimatedSymbol
import org.lineageos.device.DeviceSettings.ui.symbols.Symbol
import org.lineageos.device.DeviceSettings.ui.theme.Motion
import org.lineageos.device.DeviceSettings.ui.theme.rememberMorphedShape

/** Big shaped badge: [polygon] morphs when it changes, optionally spinning, with a fixed icon on top. */
@Composable
fun MorphBadge(
    polygon: RoundedPolygon,
    symbol: String,
    spinning: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = rememberMorphedShape(polygon)
    val loop = rememberInfiniteTransition(label = "morphLoop")
    val angle by loop.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(20_000, easing = LinearEasing), RepeatMode.Restart),
        label = "morphAngle",
    )
    // the icon breathes and swings a little while something is running
    val breathe by loop.animateFloat(
        initialValue = 0.9f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "morphBreathe",
    )
    val swing by loop.animateFloat(
        initialValue = -7f, targetValue = 7f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "morphSwing",
    )
    val container by animateColorAsState(
        if (spinning) scheme.primaryContainer else scheme.surfaceContainerHighest, Motion.effects(), label = "morphBg",
    )
    // The shape is drawn 12% smaller than the slot, so its points never touch the box edge while it
    // rotates (no more flat "cut" edges on the burst).
    val inner = size * 0.88f
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(inner).graphicsLayer { rotationZ = if (spinning) angle else 0f }.clip(shape).background(container)
        )
        AnimatedSymbol(
            symbol, active = spinning, size = size * 0.42f, tint = scheme.onPrimaryContainer,
            modifier = Modifier.graphicsLayer {
                val k = if (spinning) breathe else 1f
                scaleX = k
                scaleY = k
                rotationZ = if (spinning) swing else 0f
            },
        )
    }
}

/** Continuous value slider (volume, ms offsets) with haptic ticks and snap-back on refusal. */
@Composable
fun ValueSliderRow(
    position: GroupPosition,
    symbol: String,
    title: String,
    valueText: String,
    value: Int,
    max: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    resetKey: Any? = null,
    hapticEvery: Int = 10,
    min: Int = 0,
    step: Int = 1,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    var local by remember(value, resetKey) { mutableFloatStateOf(value.toFloat()) }
    var lastSent by remember(value, resetKey) { mutableIntStateOf(value) }
    GroupCard(position, modifier, enabled = enabled) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SymbolBadge(symbol, active = enabled && local > 0f, actionKey = local.roundToInt())
                Text(
                    title, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
                )
                ValuePill(valueText.ifEmpty { local.roundToInt().toString() })
            }
            Slider(
                value = local,
                onValueChange = { v ->
                    local = v
                    val next = v.roundToInt()
                    if (next != lastSent) {
                        if (hapticEvery <= 1 || next % hapticEvery == 0) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        lastSent = next
                        onValueChange(next)
                    }
                },
                valueRange = min.toFloat()..max.toFloat(),
                onValueChangeFinished = onValueChangeFinished,
                steps = if (step > 1) ((max - min) / step - 1).coerceAtLeast(0) else 0,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).semantics {
                    stateDescription = valueText.ifEmpty { local.roundToInt().toString() }
                },
            )
        }
    }
}

/** Row whose icon does a springy half turn on every tap. */
@Composable
fun SwapRow(
    position: GroupPosition,
    title: String,
    summary: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var turns by remember { mutableIntStateOf(0) }
    val rotation by animateFloatAsState(
        targetValue = turns * 180f, animationSpec = Motion.spatial(), label = "swapTurn",
    )
    GroupCard(position, modifier, onClick = { turns++; onClick() }, enabled = enabled, role = Role.Button) {
        Row(
            Modifier.heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SymbolBadge(Symbol.SWAP_HORIZ, active = enabled, modifier = Modifier.graphicsLayer { rotationZ = rotation })
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                if (!summary.isNullOrEmpty()) {
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Small pill for a codec name (LDAC, aptX HD, AAC, SBC...). */
@Composable
fun CodecPill(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

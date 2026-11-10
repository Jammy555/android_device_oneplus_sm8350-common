/* Copyright (C) 2026 LunarisOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.device.DeviceSettings.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.lineageos.device.DeviceSettings.R
import org.lineageos.device.DeviceSettings.ui.symbols.Symbol
import org.lineageos.device.DeviceSettings.ui.theme.Motion

/** Off stays available; the three hardware strengths have one, two and three wave pairs. */
@Composable
fun VibrationStrengthRow(
    position: GroupPosition,
    value: Int,
    max: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
    highlighted: Boolean = false,
) {
    val labels = listOf(R.string.vib_level_off, R.string.vib_level_light,
        R.string.vib_level_medium, R.string.vib_level_strong).map { stringResource(it) }
    val level = value.coerceIn(0, max.coerceIn(0, 3))
    val title = stringResource(R.string.vib_strength_title_head)
    GroupCard(position, enabled = enabled, highlighted = highlighted) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SymbolBadge(Symbol.VIBRATION, active = enabled && level > 0) { colour ->
                    VibrationSymbol(level, enabled, colour, Modifier.size(32.dp))
                }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                ValuePill(level.toString())
            }
            Slider(
                value = level.toFloat(),
                onValueChange = { next ->
                    val step = next.roundToInt()
                    if (step != level) onValueChange(step)
                },
                valueRange = 0f..max.toFloat(), steps = max - 1, enabled = enabled,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).semantics {
                    contentDescription = title
                    stateDescription = labels[level]
                },
            )
        }
    }
}

@Composable
private fun VibrationSymbol(level: Int, enabled: Boolean, colour: Color, modifier: Modifier = Modifier) {
    val shake = remember { Animatable(0f) }
    val waveScale by animateFloatAsState(0.7f + level * 0.1f, Motion.spatialFast(), label = "vibrationWaves")
    LaunchedEffect(level, enabled) {
        shake.snapTo(0f)
        if (enabled && level > 0) {
            val amplitude = level * 4f
            shake.animateTo(0f, keyframes {
                durationMillis = 320
                -amplitude at 40
                amplitude at 90
                -amplitude * 0.65f at 145
                amplitude * 0.4f at 210
                0f at 320
            })
        }
    }
    Canvas(modifier) {
        val u = size.minDimension / 40f
        val stroke = Stroke(2f * u, cap = StrokeCap.Round)
        rotate(shake.value) {
            drawRoundRect(colour, Offset(center.x - 5f * u, center.y - 10f * u),
                Size(10f * u, 20f * u), CornerRadius(2f * u), style = stroke)
            if (level == 0) {
                drawLine(colour, Offset(center.x - 11f * u, center.y + 12f * u),
                    Offset(center.x + 11f * u, center.y - 12f * u), strokeWidth = 2f * u, cap = StrokeCap.Round)
            }
            repeat(level.coerceIn(0, 3)) { wave ->
                for (side in listOf(-1, 1)) {
                    val x = center.x + side * (9f + wave * 4f) * u
                    val h = (5f + wave * 2f) * u * waveScale
                    val path = Path().apply {
                        moveTo(x, center.y - h)
                        cubicTo(x + side * 3f * u, center.y - h / 2,
                            x + side * 3f * u, center.y + h / 2, x, center.y + h)
                    }
                    drawPath(path, colour, style = stroke)
                }
            }
        }
    }
}

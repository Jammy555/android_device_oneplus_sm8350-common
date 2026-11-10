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

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.lineageos.device.DeviceSettings.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import org.lineageos.device.DeviceSettings.R
import org.lineageos.device.DeviceSettings.powertools.PowerProfileUtil
import org.lineageos.device.DeviceSettings.ui.symbols.AnimatedSymbol
import org.lineageos.device.DeviceSettings.ui.symbols.Symbol
import org.lineageos.device.DeviceSettings.ui.theme.Motion
import org.lineageos.device.DeviceSettings.ui.theme.ProfileShapes
import org.lineageos.device.DeviceSettings.ui.theme.rememberMorphedShape

data class ProfileUi(val mode: Int, @param:StringRes val labelRes: Int, val symbol: String)

val Profiles = listOf(
    ProfileUi(PowerProfileUtil.MODE_BATTERY_SAVER, R.string.powerprofile_mode_battery_saver, Symbol.ECO),
    ProfileUi(PowerProfileUtil.MODE_BALANCE, R.string.powerprofile_mode_balance, Symbol.BALANCE),
    ProfileUi(PowerProfileUtil.MODE_PERFORMANCE, R.string.powerprofile_mode_performance, Symbol.ROCKET_LAUNCH),
)

internal fun profileDialAngle(mode: Int): Float = when (mode) {
    PowerProfileUtil.MODE_BATTERY_SAVER -> 220f
    PowerProfileUtil.MODE_PERFORMANCE -> 320f
    else -> 270f
}

/** Compact speedometer whose needle follows the current hardware profile. */
@Composable
fun ProfileDial(mode: Int, tint: Color, modifier: Modifier = Modifier, diameter: Dp = 28.dp) {
    val angle by animateFloatAsState(profileDialAngle(mode), Motion.spatial(), label = "profileNeedle")
    Canvas(modifier.size(diameter)) {
        val stroke = size.minDimension * 0.10f
        val centre = Offset(size.width / 2f, size.height * 0.68f)
        val radius = size.minDimension * 0.40f
        drawArc(
            color = tint.copy(alpha = 0.42f),
            startAngle = 200f,
            sweepAngle = 140f,
            useCenter = false,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        val radians = Math.toRadians(angle.toDouble())
        drawLine(
            color = tint,
            start = centre,
            end = Offset(
                centre.x + cos(radians).toFloat() * radius,
                centre.y + sin(radians).toFloat() * radius,
            ),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(tint, radius = stroke * 0.72f, center = centre)
    }
}

/**
 * Three-way profile switch. The selected button grows (weight spring), rounds up, fills its icon;
 * the tapped one shows an expressive loading indicator until the hardware confirms.
 */
@Composable
fun ProfilePicker(
    selectedMode: Int,
    pendingMode: Int?,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Profiles.forEach { profile ->
            val pending = pendingMode == profile.mode
            val selected = pendingMode == null && selectedMode == profile.mode
            val weight by animateFloatAsState(
                targetValue = if (selected || pending) 1.5f else 1f,
                animationSpec = Motion.spatial(), label = "profileWeight",
            )
            ProfileButton(
                profile = profile,
                selected = selected,
                pending = pending,
                enabled = enabled,
                onClick = { onSelect(profile.mode) },
                modifier = Modifier.weight(weight.coerceAtLeast(0.2f)),
            )
        }
    }
}

@Composable
private fun ProfileButton(
    profile: ProfileUi,
    selected: Boolean,
    pending: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = when {
            pressed -> 16.dp
            selected || pending -> 36.dp
            else -> 24.dp
        },
        animationSpec = Motion.spatialFast(), label = "profileCorner",
    )
    val container by animateColorAsState(
        if (selected || pending) scheme.primary else scheme.surfaceContainerHighest,
        Motion.effects(), label = "profileBg",
    )
    val content by animateColorAsState(
        if (selected || pending) scheme.onPrimary else scheme.onSurfaceVariant,
        Motion.effects(), label = "profileFg",
    )
    val label = stringResource(profile.labelRes)
    Column(
        modifier
            .height(92.dp)
            .clip(RoundedCornerShape(corner.coerceAtLeast(0.dp)))
            .background(container)
            .semantics { this.selected = selected }
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.foundation.LocalIndication.current,
                enabled = enabled && !selected && !pending,
                role = Role.RadioButton,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .graphicsLayer { alpha = if (enabled || selected || pending) 1f else 0.5f },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (pending) {
                LoadingIndicator(Modifier.size(32.dp), color = content)
            } else {
                AnimatedSymbol(profile.symbol, active = selected, size = 28.dp, tint = content)
            }
        }
        Text(
            label,
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Big shaped badge for the current profile. Shape morphs between profiles; non-saver shapes
 * rotate slowly behind a fixed icon.
 */
@Composable
fun ProfileBadge(mode: Int, modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val scheme = MaterialTheme.colorScheme
    val shape = rememberMorphedShape(ProfileShapes.forMode(mode))
    val spin = rememberInfiniteTransition(label = "badgeSpin")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart),
        label = "badgeAngle",
    )
    val rotation = if (mode == PowerProfileUtil.MODE_BATTERY_SAVER) 0f else angle
    val symbol = Profiles.firstOrNull { it.mode == mode }?.symbol ?: Symbol.BALANCE
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .graphicsLayer { rotationZ = rotation }
                .clip(shape)
                .background(scheme.primaryContainer)
        )
        AnimatedSymbol(symbol, active = true, size = size * 0.46f, tint = scheme.onPrimaryContainer)
    }
}

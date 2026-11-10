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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.lineageos.device.DeviceSettings.ui.symbols.AnimatedSymbol
import org.lineageos.device.DeviceSettings.ui.symbols.LocalSymbolPressed
import org.lineageos.device.DeviceSettings.ui.symbols.Symbol
import org.lineageos.device.DeviceSettings.ui.symbols.SymbolIcon
import org.lineageos.device.DeviceSettings.ui.theme.Motion
import org.lineageos.device.DeviceSettings.ui.theme.rememberMorphedShape

enum class GroupPosition { Single, First, Middle, Last }

private val CornerBig = 28.dp
private val CornerSmall = 6.dp

class GroupScope internal constructor() {
    internal val items = mutableListOf<Pair<String, @Composable (GroupPosition) -> Unit>>()

    /** Adds one row. [key] must be stable; it is also what search deep links highlight. */
    fun item(key: String, content: @Composable (GroupPosition) -> Unit) {
        items += key to content
    }
}

/** Expressive grouped list: big outer corners, small inner corners, 2dp gaps. */
@Composable
fun SettingsGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: GroupScope.() -> Unit,
) {
    val scope = GroupScope().apply(content)
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val last = scope.items.lastIndex
            scope.items.forEachIndexed { i, (k, row) ->
                val position = when {
                    last == 0 -> GroupPosition.Single
                    i == 0 -> GroupPosition.First
                    i == last -> GroupPosition.Last
                    else -> GroupPosition.Middle
                }
                key(k) { row(position) }
            }
        }
    }
}

/**
 * Surface of one row. Corners spring to fully round while pressed (M3 Expressive "shape morph on
 * press"), then settle back to their group position.
 */
@Composable
fun GroupCard(
    position: GroupPosition,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    role: Role? = null,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    semanticsModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val topBig = position == GroupPosition.First || position == GroupPosition.Single
    val bottomBig = position == GroupPosition.Last || position == GroupPosition.Single
    val top by animateDpAsState(
        targetValue = if (pressed || topBig) CornerBig else CornerSmall,
        animationSpec = Motion.spatialFast(), label = "cornerTop",
    )
    val bottom by animateDpAsState(
        targetValue = if (pressed || bottomBig) CornerBig else CornerSmall,
        animationSpec = Motion.spatialFast(), label = "cornerBottom",
    )
    val shape = RoundedCornerShape(
        topStart = top.coerceAtLeast(0.dp), topEnd = top.coerceAtLeast(0.dp),
        bottomEnd = bottom.coerceAtLeast(0.dp), bottomStart = bottom.coerceAtLeast(0.dp),
    )
    val background by animateColorAsState(
        targetValue = if (highlighted) MaterialTheme.colorScheme.primaryContainer else containerColor,
        animationSpec = Motion.effects(), label = "cardBg",
    )
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .then(semanticsModifier)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        enabled = enabled,
                        role = role,
                        onClick = onClick,
                    )
                } else Modifier
            )
            .alpha(if (enabled) 1f else 0.42f)
    ) { CompositionLocalProvider(LocalSymbolPressed provides pressed) { content() } }
}

/** Icon in a container that morphs circle -> cookie and fills in when [active]. */
@Composable
fun SymbolBadge(symbol: String, active: Boolean, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 44.dp, actionKey: Any? = null, icon: (@Composable (Color) -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    val filled = active || LocalSymbolPressed.current
    val container by animateColorAsState(
        if (filled) scheme.primaryContainer else scheme.surfaceContainerHighest,
        Motion.effects(), label = "badgeBg",
    )
    val content by animateColorAsState(
        if (filled) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        Motion.effects(), label = "badgeFg",
    )
    val shape = rememberMorphedShape(if (filled) MaterialShapes.Cookie6Sided else MaterialShapes.Circle)
    Box(modifier.size(size).clip(shape).background(container), contentAlignment = Alignment.Center) {
        if (icon != null) icon(content)
        else AnimatedSymbol(symbol, filled, size = size * 0.55f, tint = content, actionKey = actionKey)
    }
}

@Composable
private fun RowText(title: String, summary: String?, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (!summary.isNullOrEmpty()) {
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SwitchRow(
    position: GroupPosition,
    symbol: String?,
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    highlighted: Boolean = false,
) {
    GroupCard(
        position = position,
        onClick = { onCheckedChange(!checked) },
        enabled = enabled,
        highlighted = highlighted,
        role = Role.Switch,
        semanticsModifier = Modifier.semantics(mergeDescendants = true) {
            toggleableState = ToggleableState(checked)
        },
    ) {
        Row(
            Modifier.heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (symbol != null) SymbolBadge(symbol, active = checked)
            RowText(title, summary, Modifier.weight(1f))
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                thumbContent = {
                    AnimatedSymbol(
                        symbol = if (checked) Symbol.CHECK else Symbol.CLOSE,
                        active = checked,
                        size = SwitchDefaults.IconSize,
                    )
                },
            )
        }
    }
}

@Composable
fun NavRow(
    position: GroupPosition,
    symbol: String?,
    title: String,
    summary: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    trailingText: String? = null,
    expanded: Boolean? = null,
    icon: (@Composable (Color) -> Unit)? = null,
) {
    GroupCard(position, onClick = onClick, enabled = enabled, highlighted = highlighted, role = Role.Button) {
        Row(
            Modifier.heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (symbol != null) SymbolBadge(symbol, active = enabled, icon = icon)
            Column(Modifier.weight(1f)) {
                RowText(title, summary, Modifier.fillMaxWidth())
                if (!trailingText.isNullOrEmpty()) {
                    Text(trailingText, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            AnimatedSymbol(
                if (expanded == null) Symbol.CHEVRON_RIGHT else if (expanded) Symbol.EXPAND_LESS else Symbol.EXPAND_MORE,
                active = expanded == true, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Quiet helper line under a row (the old non-selectable "warning" preferences). */
@Composable
fun NoteRow(position: GroupPosition, text: String) {
    GroupCard(position, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SymbolIcon(Symbol.INFO, size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Discrete slider with a haptic tick on every step and the current value as a pill. */
@Composable
fun StepSliderRow(
    position: GroupPosition,
    symbol: String,
    title: String,
    summary: String?,
    value: Int,
    max: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
    highlighted: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    GroupCard(position, enabled = enabled, highlighted = highlighted) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SymbolBadge(symbol, active = enabled && local > 0, actionKey = local.roundToInt())
                RowText(title, summary, Modifier.weight(1f))
                ValuePill(local.roundToInt().toString())
            }
            Slider(
                value = local,
                onValueChange = { v ->
                    val step = v.roundToInt()
                    if (step != local.roundToInt()) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onValueChange(step)
                    }
                    local = v
                },
                onValueChangeFinished = { local = local.roundToInt().toFloat() },
                valueRange = 0f..max.toFloat(),
                steps = (max - 1).coerceAtLeast(0),
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

@Composable
fun ValuePill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

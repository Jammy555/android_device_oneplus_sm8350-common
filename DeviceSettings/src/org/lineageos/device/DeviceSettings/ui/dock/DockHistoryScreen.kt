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
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package org.lineageos.device.DeviceSettings.ui.dock

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.lineageos.device.DeviceSettings.R
import org.lineageos.device.DeviceSettings.ui.components.SettingsScreen
import org.lineageos.device.DeviceSettings.ui.components.StaggerIn
import org.lineageos.device.DeviceSettings.ui.symbols.AnimatedSymbol
import org.lineageos.device.DeviceSettings.ui.symbols.Symbol
import org.lineageos.device.DeviceSettings.ui.symbols.SymbolIcon
import org.lineageos.device.DeviceSettings.ui.theme.Motion

/** Every clip the File Dock saved: filter, expand, copy, share, open, delete. */
@Composable
fun DockHistoryScreen(
    vm: DockHistoryViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsState()
    var expandedId by remember { mutableLongStateOf(-1L) }
    val visible = s.visible
    val filters = listOf(
        R.string.dock_filter_all, R.string.dock_filter_text, R.string.dock_filter_links,
        R.string.dock_filter_images, R.string.dock_filter_files,
    )

    SettingsScreen(
        title = stringResource(R.string.dock_history_page_title),
        subtitle = stringResource(R.string.dock_history_count, s.items.size),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        itemSpacing = 8.dp,
        modifier = modifier,
    ) {
        item(key = "filters") {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                filters.forEachIndexed { i, label ->
                    FilterChip(selected = s.filter == i, onClick = { vm.setFilter(i) }, label = { Text(stringResource(label)) })
                }
            }
        }

        if (s.loading) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            }
        } else if (s.error) {
            item(key = "error") {
                Text(
                    stringResource(R.string.dock_storage_error), style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else if (visible.isEmpty()) {
            item(key = "empty") {
                StaggerIn(0) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 56.dp, horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        AnimatedSymbol(Symbol.CONTENT_PASTE, active = true, size = 48.dp, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            stringResource(R.string.dock_history_empty), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        items(visible, key = { it.id }) { entry ->
            HistoryCard(
                entry = entry,
                expanded = expandedId == entry.id,
                onToggle = { expandedId = if (expandedId == entry.id) -1L else entry.id },
                onCopy = { vm.copy(entry.id) },
                onShare = { vm.share(entry.id) },
                onOpen = { vm.open(entry.id) },
                onDelete = { vm.delete(entry.id) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

private fun symbolFor(kind: HistoryKind) = when (kind) {
    HistoryKind.Text -> Symbol.TEXT_FIELDS
    HistoryKind.Link -> Symbol.LINK
    HistoryKind.Image -> Symbol.IMAGE
    HistoryKind.File -> Symbol.FOLDER_OPEN
}

internal fun compactShelfCardDense(compact: Boolean, maxWidthDp: Float): Boolean = compact && maxWidthDp < 260f

@Composable
internal fun HistoryCard(
    entry: HistoryEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onPaste: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        (if (expanded) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh)
            .copy(alpha = if (compact) 0.78f else 1f),
        Motion.effects(), label = "histBg",
    )
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val dense = compactShelfCardDense(compact, maxWidth.value)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(if (dense) 18.dp else if (compact) 20.dp else 24.dp))
                .background(container)
                .combinedClickable(role = Role.Button, onClick = onToggle, onLongClick = onLongClick)
                .padding(if (dense) 8.dp else if (compact) 10.dp else 14.dp)
                .animateContentSize(Motion.spatial()),
            verticalArrangement = Arrangement.spacedBy(if (dense) 6.dp else if (compact) 8.dp else 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (dense) 8.dp else if (compact) 12.dp else 14.dp),
            ) {
                if (!compact || entry.kind == HistoryKind.Image) Leading(entry, compact, dense)
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.title,
                        style = if (dense) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                        maxLines = if (expanded && dense) 6 else if (expanded) 12 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (compact) {
                        Text(
                            entry.source,
                            style = if (dense) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            entry.time,
                            style = if (dense) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(
                            entry.source + " • " + entry.time,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                SymbolIcon(
                    if (expanded) Symbol.EXPAND_LESS else Symbol.EXPAND_MORE,
                    size = if (dense) 20.dp else 24.dp,
                    tint = scheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(expanded, enter = expandVertically(Motion.spatial()) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onPaste != null) ActionPill(Symbol.CONTENT_PASTE, stringResource(R.string.dock_paste_action), onPaste, iconOnly = compact)
                    ActionPill(Symbol.CONTENT_COPY, stringResource(R.string.dock_action_copy), onCopy, iconOnly = compact)
                    ActionPill(Symbol.SHARE, stringResource(R.string.dock_action_share), onShare, iconOnly = compact)
                    if (entry.kind != HistoryKind.Text) {
                        ActionPill(Symbol.PLAY_ARROW, stringResource(R.string.dock_action_open), onOpen, iconOnly = compact)
                    }
                    ActionPill(Symbol.DELETE, stringResource(R.string.dock_action_delete), onDelete, danger = true, iconOnly = compact)
                }
            }
        }
    }
}

@Composable
private fun Leading(entry: HistoryEntry, compact: Boolean, dense: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val size = if (dense) 40.dp else if (compact) 48.dp else 56.dp
    val thumbnail by produceState<Bitmap?>(null, entry.thumbnailPath) {
        value = withContext(Dispatchers.IO) { DockHistoryViewModel.decode(entry.thumbnailPath) }
    }
    val image = thumbnail?.asImageBitmap()
    if (image != null) {
        Image(
            image, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(RoundedCornerShape(if (compact) 14.dp else 16.dp)),
        )
    } else {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(if (compact) 14.dp else 16.dp))
                .background(scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedSymbol(
                symbolFor(entry.kind), active = true,
                size = if (dense) 20.dp else if (compact) 24.dp else 26.dp,
                tint = scheme.onPrimaryContainer,
            )
        }
    }
}

/** Action pill; the icon plays its own motion every tap (copy hops, bin wobbles, ...). */
@Composable
private fun ActionPill(
    symbol: String,
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false,
    iconOnly: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    var taps by remember { mutableIntStateOf(0) }
    val bg = if (danger) scheme.errorContainer else scheme.secondaryContainer
    val fg = if (danger) scheme.onErrorContainer else scheme.onSecondaryContainer
    Row(
        Modifier
            .then(if (iconOnly) Modifier.size(48.dp) else Modifier.heightIn(min = 48.dp))
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(role = Role.Button) {
                taps++
                onClick()
            }
            .then(if (iconOnly) Modifier.semantics { contentDescription = label } else Modifier)
            .padding(horizontal = if (iconOnly) 0.dp else 16.dp, vertical = if (iconOnly) 0.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (iconOnly) Arrangement.Center else Arrangement.spacedBy(8.dp),
    ) {
        AnimatedSymbol(symbol, active = false, size = 20.dp, tint = fg, actionKey = taps)
        if (!iconOnly) Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

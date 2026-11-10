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

package org.lineageos.device.DeviceSettings.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 Expressive theme.
 *
 * - Colour: Material You (dynamic) when the user keeps it on, otherwise the warm peach palette the
 *   old UI used as its fallback accent (#E5A376 dark / #D97736 light).
 * - Motion: the expressive motion scheme, i.e. bouncy spatial springs and quiet effect springs.
 */
@Composable
fun DeviceSettingsTheme(
    dynamicColor: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> PeachDark
        else -> PeachLight
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

private val PeachLight = lightColorScheme(
    primary = Color(0xFF8F4C00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCC2),
    onPrimaryContainer = Color(0xFF2E1500),
    secondary = Color(0xFF745943),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFDDCC4),
    onSecondaryContainer = Color(0xFF2A1708),
    tertiary = Color(0xFF5F6134),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE4E6AD),
    onTertiaryContainer = Color(0xFF1B1D00),
    background = Color(0xFFFFF8F5),
    onBackground = Color(0xFF221A14),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF221A14),
    surfaceVariant = Color(0xFFF2DFD2),
    onSurfaceVariant = Color(0xFF51443A),
    outline = Color(0xFF84746A),
    outlineVariant = Color(0xFFD7C3B7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1E8),
    surfaceContainer = Color(0xFFFAEBDF),
    surfaceContainerHigh = Color(0xFFF4E5D9),
    surfaceContainerHighest = Color(0xFFEEDFD4),
)

private val PeachDark = darkColorScheme(
    primary = Color(0xFFE5A376),
    onPrimary = Color(0xFF4C2700),
    primaryContainer = Color(0xFF6D3A00),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFFE3BFA6),
    onSecondary = Color(0xFF422B19),
    secondaryContainer = Color(0xFF5B4130),
    onSecondaryContainer = Color(0xFFFDDCC4),
    tertiary = Color(0xFFC8CA93),
    onTertiary = Color(0xFF303108),
    tertiaryContainer = Color(0xFF47491E),
    onTertiaryContainer = Color(0xFFE4E6AD),
    background = Color(0xFF18120D),
    onBackground = Color(0xFFEFE0D5),
    surface = Color(0xFF18120D),
    onSurface = Color(0xFFEFE0D5),
    surfaceVariant = Color(0xFF51443A),
    onSurfaceVariant = Color(0xFFD7C3B7),
    outline = Color(0xFF9F8E82),
    outlineVariant = Color(0xFF51443A),
    surfaceContainerLowest = Color(0xFF120D08),
    surfaceContainerLow = Color(0xFF211A14),
    surfaceContainer = Color(0xFF251E18),
    surfaceContainerHigh = Color(0xFF302822),
    surfaceContainerHighest = Color(0xFF3B332D),
)

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

import androidx.compose.animation.core.Animatable
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon

/** Shapes used to express each power profile. */
object ProfileShapes {
    val Saver: RoundedPolygon get() = MaterialShapes.Circle
    val Balanced: RoundedPolygon get() = MaterialShapes.Cookie9Sided
    val Performance: RoundedPolygon get() = MaterialShapes.Burst

    fun forMode(mode: Int): RoundedPolygon = when (mode) {
        0 -> Saver
        2 -> Performance
        else -> Balanced
    }
}

/**
 * A shape that morphs to [target] whenever it changes, driven by the theme's spatial spring (so it
 * overshoots and settles, M3 Expressive style).
 */
@Composable
fun rememberMorphedShape(target: RoundedPolygon): Shape {
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    val spec = Motion.spatial<Float>()
    LaunchedEffect(target) {
        if (target != to) {
            from = to
            to = target
            progress.snapTo(0f)
            progress.animateTo(1f, spec)
        }
    }
    val morph = remember(from, to) { Morph(from, to) }
    return morph.toShape(progress.value)
}

fun Morph.toShape(progress: Float): Shape = object : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val path = this@toShape.toPath(progress)
        val matrix = Matrix()
        matrix.scale(size.width, size.height)
        path.transform(matrix)
        val pathCenter = path.getBounds().center
        val boundsCenter = size.center
        path.translate(boundsCenter - pathCenter)
        return Outline.Generic(path)
    }
}

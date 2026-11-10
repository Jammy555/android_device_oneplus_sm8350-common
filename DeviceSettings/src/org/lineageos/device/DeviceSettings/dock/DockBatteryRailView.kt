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
package org.lineageos.device.DeviceSettings.dock

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator

/**
 * The handle's grip, drawn as a thin battery: the fill height is the charge, the colour follows
 * the level (red up to 30, yellow up to 60, green up to 80, blue above), level and colour animate,
 * and while charging a soft highlight keeps sweeping up the fill.
 */
class DockBatteryRailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val plain = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val rect = RectF()

    private var shownLevel = 0f
    private var targetLevel = 0
    private var shownColor = DockQuickControls.batteryColor(0)
    private var charging = false
    private var sweep = 0f
    private var enabledRail = true

    private var levelAnimator: ValueAnimator? = null
    private var colorAnimator: ValueAnimator? = null
    private var sweepAnimator: ValueAnimator? = null

    /** Off = back to the plain white grip. */
    fun setRailEnabled(enabled: Boolean) {
        enabledRail = enabled
        updateSweep()
        invalidate()
    }

    fun setBattery(level: Int, isCharging: Boolean, animate: Boolean = true) {
        val clamped = level.coerceIn(0, 100)
        val newColor = colorFor(clamped)
        val colorChanged = newColor != colorFor(targetLevel) || !animate
        val from = shownLevel
        targetLevel = clamped
        charging = isCharging
        levelAnimator?.cancel()
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            levelAnimator = ValueAnimator.ofFloat(from, clamped.toFloat()).apply {
                duration = 520
                interpolator = OvershootInterpolator(0.7f)
                addUpdateListener {
                    shownLevel = (it.animatedValue as Float).coerceIn(0f, 100f)
                    invalidate()
                }
                start()
            }
        } else {
            shownLevel = clamped.toFloat()
        }
        if (colorChanged) {
            colorAnimator?.cancel()
            if (animate && ValueAnimator.areAnimatorsEnabled()) {
                val start = shownColor
                colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), start, newColor).apply {
                    duration = 420
                    addUpdateListener {
                        shownColor = it.animatedValue as Int
                        invalidate()
                    }
                    start()
                }
            } else {
                shownColor = newColor
            }
        }
        updateSweep()
        invalidate()
    }

    private fun updateSweep() {
        val want = charging && enabledRail && isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()
        if (want && sweepAnimator == null) {
            sweepAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1700
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    sweep = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else if (!want) {
            sweepAnimator?.cancel()
            sweepAnimator = null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSweep()
    }

    override fun onDetachedFromWindow() {
        levelAnimator?.cancel()
        colorAnimator?.cancel()
        sweepAnimator?.cancel()
        sweepAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = w / 2f
        rect.set(0f, 0f, w, h)
        if (!enabledRail) {
            canvas.drawRoundRect(rect, radius, radius, plain)
            return
        }
        canvas.drawRoundRect(rect, radius, radius, track)

        val fillHeight = h * shownLevel / 100f
        if (fillHeight <= 0.5f) return
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        fill.color = shownColor
        canvas.drawRect(0f, h - fillHeight, w, h, fill)
        if (charging) {
            // a highlight band travelling bottom -> top across the filled part
            val band = h * 0.45f
            val y = h - fillHeight * sweep + band / 2f
            sheen.shader = LinearGradient(
                0f, y - band, 0f, y,
                intArrayOf(0x00FFFFFF, 0x99FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, h - fillHeight, w, h, sheen)
        }
        canvas.restore()
    }

    companion object {
        /** 1-30 red, 31-60 yellow, 61-80 green, above blue (shared with the quick controls). */
        @JvmStatic
        fun colorFor(level: Int): Int = DockQuickControls.batteryColor(level)
    }
}

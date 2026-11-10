/* Copyright (C) 2026 LunarisOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.device.DeviceSettings.dock

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import java.util.function.Consumer
import org.lineageos.device.DeviceSettings.R

/** Rounded background blur follows the panel's bounds, including move and resize animations. */
internal object DockPanelBackground {
    fun install(view: View, windows: WindowManager, enabled: Boolean, cornersDp: Int,
        revealDurationMs: Long = 0L,
        onBlurChanged: (Boolean) -> Unit = {}) {
        val density = view.resources.displayMetrics.density
        val corners = cornersDp * density
        var blur: BackgroundBlurDrawable? = null
        var blurAnimator: ValueAnimator? = null
        var wasActive = false
        val fallback = view.background
        fun update(available: Boolean) {
            val active = enabled && available && view.isAttachedToWindow && view.isHardwareAccelerated
            if (active) {
                val background = blur ?: view.viewRootImpl.createBackgroundBlurDrawable().also { blur = it }
                val targetRadius = (32 * density).toInt()
                if (!wasActive && revealDurationMs > 0L && ValueAnimator.areAnimatorsEnabled()) {
                    background.setBlurRadius(0)
                    blurAnimator = ValueAnimator.ofInt(0, targetRadius).apply {
                        duration = revealDurationMs
                        interpolator = DecelerateInterpolator()
                        addUpdateListener {
                            val value = it.animatedValue as Int
                            background.setBlurRadius(value)
                        }
                        start()
                    }
                } else if (!wasActive || blurAnimator?.isRunning != true) {
                    background.setBlurRadius(targetRadius)
                }
                background.setCornerRadius(corners)
                background.setColor(Color.TRANSPARENT)
                background.setApplyRenderNodeAlpha(true)
                val glass = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(view.context.getColor(R.color.dock_glass_highlight),
                        view.context.getColor(R.color.dock_glass_tint)))
                glass.cornerRadius = corners
                glass.setStroke(maxOf(1, density.toInt()), view.context.getColor(R.color.dock_glass_stroke))
                view.background = LayerDrawable(arrayOf(background, glass))
            } else {
                blurAnimator?.cancel()
                blurAnimator = null
                blur?.setBlurRadius(0)
                view.background = fallback
            }
            wasActive = active
            onBlurChanged(active)
        }
        val listener = Consumer<Boolean> { available -> view.post { update(available) } }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (enabled) windows.addCrossWindowBlurEnabledListener(view.context.mainExecutor, listener)
                update(windows.isCrossWindowBlurEnabled)
            }
            override fun onViewDetachedFromWindow(v: View) {
                if (enabled) windows.removeCrossWindowBlurEnabledListener(listener)
                blurAnimator?.cancel()
                blurAnimator = null
                blur?.setBlurRadius(0)
                blur = null
                wasActive = false
                onBlurChanged(false)
            }
        })
    }
}

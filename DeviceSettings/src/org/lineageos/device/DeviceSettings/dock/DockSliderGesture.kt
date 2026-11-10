/* Copyright (C) 2026 LunarisOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.device.DeviceSettings.dock

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** Relative control: settle in a lane first, then move beyond finger jitter. */
internal class DockSliderGesture(private val deadZone: Float, private val travel: Float) {
    var active = -1
        private set
    private var pending = -1
    private var pendingSince = 0L
    private var anchorY = 0f
    private var anchorValue = 0

    fun update(lane: Int, y: Float, now: Long, value: Int, maximum: Int): Int? {
        if (lane < 0) {
            active = -1
            pending = -1
            return null
        }
        if (lane != active) {
            if (lane != pending) {
                pending = lane
                pendingSince = now
            }
            if (now - pendingSince < 180) return null
            active = lane
            pending = -1
            anchorY = y
            anchorValue = value
            return null
        }
        if (pending >= 0) {
            // Returning from an attempted switch must not apply that movement to this lane.
            pending = -1
            anchorY = y
            anchorValue = value
        }
        val distance = anchorY - y
        val delta = sign(distance) * (abs(distance) - deadZone).coerceAtLeast(0f)
        return (anchorValue + delta / travel.coerceAtLeast(1f) * maximum)
            .roundToInt().coerceIn(0, maximum)
    }
}

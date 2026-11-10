/* Copyright (C) 2026 LunarisOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.device.DeviceSettings.dock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.lineageos.device.DeviceSettings.defaultPrefs

/** Clears only the clip that was scheduled; saved dock items have their own lifetime. */
class DockClipboardTimeout : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val timestamp = clipboard.primaryClipDescription?.timestamp ?: return
        val expected = intent.getLongExtra("timestamp", -1)
        val minutes = context.defaultPrefs().getInt(SmartDockService.KEY_CLIPBOARD_TIMEOUT, 0)
        if (minutes in listOf(1, 10) && timestamp == expected &&
            System.currentTimeMillis() >= timestamp + minutes * 60_000L
        ) clipboard.clearPrimaryClip()
    }

    companion object {
        fun schedule(context: Context) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val timestamp = clipboard.primaryClipDescription?.timestamp
            val alarm = context.getSystemService(AlarmManager::class.java)
            val intent = Intent(context, DockClipboardTimeout::class.java)
                .putExtra("timestamp", timestamp ?: -1)
            val pending = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarm.cancel(pending)
            val minutes = context.defaultPrefs().getInt(SmartDockService.KEY_CLIPBOARD_TIMEOUT, 0)
            if (timestamp == null || minutes !in listOf(1, 10)) return
            val remaining = (timestamp + minutes * 60_000L - System.currentTimeMillis()).coerceAtLeast(0)
            // Non-wakeup alarm: expiration does not wake a sleeping phone.
            alarm.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + remaining, pending)
        }
    }
}

/*
 * Copyright (C) 2025 Lunaris Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package org.lineageos.device.DeviceSettings.powertools

import android.app.ActivityManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemProperties
import android.widget.RemoteViews
import java.io.BufferedReader
import java.io.FileReader
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import org.lineageos.device.DeviceSettings.R

/**
 * Home-screen system monitor. Widgets are RemoteViews, so this stays on the old layout; only
 * the code is Kotlin. Updates every 3 s while the screen is on and sleeps with it.
 */
class SystemMonitorWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ensureReceiverRegistered(ctx)
        processUpdateAsync(ctx, mgr, ids)
    }

    override fun onEnabled(ctx: Context) {
        super.onEnabled(ctx)
        ensureReceiverRegistered(ctx)
        scheduleNextUpdate(ctx)
    }

    override fun onDisabled(ctx: Context) {
        super.onDisabled(ctx)
        cancelUpdates()
        screenReceiver?.let {
            try {
                ctx.applicationContext.unregisterReceiver(it)
            } catch (_: Exception) {
            }
            screenReceiver = null
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action ?: return) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON" -> {
                ensureReceiverRegistered(ctx)
                scheduleNextUpdate(ctx)
            }
            ACTION_TICK -> {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, SystemMonitorWidget::class.java))
                if (ids != null && ids.isNotEmpty()) processUpdateAsync(ctx, mgr, ids)
            }
        }
    }

    private fun processUpdateAsync(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        appContext = ctx.applicationContext
        val pendingResult = goAsync()
        backgroundExecutor.execute {
            try {
                // Always update when called - sleeping is handled solely by SCREEN_OFF
                buildAndApplyViews(ctx, mgr, ids)
                if (screenOn) {
                    handler.removeCallbacks(updateRunnable)
                    handler.postDelayed(updateRunnable, UPDATE_INTERVAL_MS)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val ACTION_TICK = "org.lineageos.device.DeviceSettings.WIDGET_TICK"
        const val UPDATE_INTERVAL_MS = 3000L

        const val BATTERY_TEMP = "/sys/class/power_supply/battery/temp"
        const val CPU_TEMP = "/sys/class/thermal/thermal_zone39/temp"
        const val GPU_TEMP = "/sys/class/thermal/thermal_zone54/temp"
        const val GPU_MAX_FREQ_PATH = "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq"

        var lastCpuTotal = 0L
        var lastCpuIdle = 0L
        val backgroundExecutor = Executors.newSingleThreadExecutor()
        var screenReceiver: BroadcastReceiver? = null
        val handler = Handler(Looper.getMainLooper())

        @Volatile
        var screenOn = true
        var appContext: Context? = null

        val updateRunnable: Runnable = object : Runnable {
            override fun run() {
                if (!screenOn) return
                val ctx = appContext ?: return
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, SystemMonitorWidget::class.java))
                if (ids != null && ids.isNotEmpty()) {
                    backgroundExecutor.execute {
                        try {
                            buildAndApplyViews(ctx, mgr, ids)
                        } finally {
                            if (screenOn) handler.postDelayed(this, UPDATE_INTERVAL_MS)
                        }
                    }
                }
            }
        }

        fun ensureReceiverRegistered(ctx: Context) {
            if (screenReceiver != null) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action ?: return) {
                        // Phone screen turned off / locked: go to sleep
                        Intent.ACTION_SCREEN_OFF -> {
                            cancelUpdates()
                            showSleepingState(context)
                        }
                        // Screen on or unlocked: resume updates
                        Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> scheduleNextUpdate(context)
                    }
                }
            }
            screenReceiver = receiver
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ctx.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        }

        fun scheduleNextUpdate(ctx: Context?) {
            if (ctx != null) appContext = ctx.applicationContext
            screenOn = true
            handler.removeCallbacks(updateRunnable)
            handler.post(updateRunnable)
        }

        fun cancelUpdates() {
            screenOn = false
            handler.removeCallbacks(updateRunnable)
        }

        fun showSleepingState(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SystemMonitorWidget::class.java))
            if (ids == null || ids.isEmpty()) return
            val v = RemoteViews(ctx.packageName, R.layout.widget_system_monitor)
            v.setTextViewText(R.id.widget_mode, "Asleep (\uD83D\uDCA4)")
            v.setTextColor(R.id.widget_mode, 0xFF888888.toInt())
            for (bar in intArrayOf(
                R.id.widget_cpu_bar, R.id.widget_gpu_bar, R.id.widget_mem_bar,
                R.id.widget_swap_bar, R.id.widget_bat_bar, R.id.widget_storage_bar,
            )) v.setProgressBar(bar, 100, 0, false)
            for (text in intArrayOf(
                R.id.widget_cpu_text, R.id.widget_gpu_text, R.id.widget_mem_text,
                R.id.widget_swap_text, R.id.widget_bat_text, R.id.widget_storage_text,
            )) v.setTextViewText(text, "--%")
            v.setTextViewText(R.id.widget_cpu_temp, "\u2699\uFE0F C: --\u00b0C")
            v.setTextViewText(R.id.widget_gpu_temp, "\uD83C\uDFAE G: --\u00b0C")
            v.setTextViewText(R.id.widget_bat_temp, "\uD83D\uDD0B B: --\u00b0C")
            applyClickIntent(ctx, v)
            mgr.updateAppWidget(ids, v)
        }

        fun applyClickIntent(ctx: Context, v: RemoteViews) {
            val pi = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, PowertoolsActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.widget_root, pi)
        }

        fun buildAndApplyViews(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val cpuPct = cpuUsage()
            val gpuPct = gpuUsage()
            val memPct = memoryPercent(ctx)
            val swapPct = swapPercent()
            val bat = batteryLevel(ctx)
            val storPct = storagePercent()
            val mode = currentMode()

            val batTemp = readTempC(BATTERY_TEMP, 10f)
            val cpuTemp = readTempC(CPU_TEMP, 1000f)
            val gpuTemp = readTempC(GPU_TEMP, 1000f)

            val v = RemoteViews(ctx.packageName, R.layout.widget_system_monitor)
            v.setTextViewText(R.id.widget_mode, modeLabel(ctx, mode))
            v.setTextColor(R.id.widget_mode, modeColor(mode))

            bar(v, R.id.widget_cpu_bar, R.id.widget_cpu_text, cpuPct, 0xFF6C63FF.toInt())
            v.setTextViewText(R.id.widget_cpu_temp, String.format("\u2699\uFE0F C: %.0f\u00b0C", cpuTemp))
            bar(v, R.id.widget_gpu_bar, R.id.widget_gpu_text, gpuPct, 0xFF43E97B.toInt())
            v.setTextViewText(R.id.widget_gpu_temp, String.format("\uD83C\uDFAE G: %.0f\u00b0C", gpuTemp))
            bar(v, R.id.widget_mem_bar, R.id.widget_mem_text, memPct, 0xFFFFA726.toInt())
            bar(v, R.id.widget_swap_bar, R.id.widget_swap_text, swapPct, 0xFFCE93D8.toInt())
            bar(v, R.id.widget_bat_bar, R.id.widget_bat_text, bat, 0xFF29B6F6.toInt())
            v.setTextViewText(R.id.widget_bat_temp, String.format("\uD83D\uDD0B B: %.0f\u00b0C", batTemp))
            bar(v, R.id.widget_storage_bar, R.id.widget_storage_text, storPct, 0xFF26C6DA.toInt())

            applyClickIntent(ctx, v)
            mgr.updateAppWidget(ids, v)
        }

        fun bar(v: RemoteViews, barId: Int, textId: Int, pct: Int, normalColor: Int) {
            v.setProgressBar(barId, 100, pct, false)
            v.setTextViewText(textId, "$pct%")
            v.setProgressTintList(barId, ColorStateList.valueOf(if (pct >= 85) 0xFFEF5350.toInt() else normalColor))
        }

        fun cpuUsage(): Int = try {
            RandomAccessFile("/proc/stat", "r").use { r ->
                val line = r.readLine() ?: return 0
                val p = line.split(Regex("\\s+"))
                val idle = p[4].toLong() + p[5].toLong()
                var total = 0L
                for (i in 1 until p.size) total += p[i].toLong()
                var pct = 0
                if (lastCpuTotal > 0) {
                    val dTotal = total - lastCpuTotal
                    val dIdle = idle - lastCpuIdle
                    pct = if (dTotal > 0) ((dTotal - dIdle) * 100 / dTotal).toInt() else 0
                }
                lastCpuTotal = total
                lastCpuIdle = idle
                max(0, min(100, pct))
            }
        } catch (e: Exception) {
            0
        }

        fun gpuUsage(): Int = try {
            val busy = readSysfs("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
            var pct = 0
            if (busy.isNotEmpty()) pct = busy.trim().split(Regex("\\s+"))[0].toInt()
            // the max-frequency read must succeed too, as before
            readSysfs(GPU_MAX_FREQ_PATH).toLong()
            min(100, max(0, pct))
        } catch (e: Exception) {
            0
        }

        fun memoryPercent(ctx: Context): Int = try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val total = mi.totalMem / (1024 * 1024)
            val used = total - (mi.availMem / (1024 * 1024))
            (used * 100 / total).toInt()
        } catch (e: Exception) {
            0
        }

        fun swapPercent(): Int = try {
            BufferedReader(FileReader("/proc/meminfo")).use { br ->
                var swapTotal = 0L
                var swapFree = 0L
                var line = br.readLine()
                while (line != null) {
                    if (line.startsWith("SwapTotal:")) swapTotal = parseMemLine(line)
                    else if (line.startsWith("SwapFree:")) swapFree = parseMemLine(line)
                    line = br.readLine()
                }
                if (swapTotal == 0L) 0 else ((swapTotal - swapFree) * 100 / swapTotal).toInt()
            }
        } catch (e: Exception) {
            0
        }

        fun parseMemLine(line: String): Long {
            val parts = line.split(Regex("\\s+"))
            return if (parts.size >= 2) parts[1].toLong() else 0
        }

        fun batteryLevel(ctx: Context): Int = try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager?
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        } catch (e: Exception) {
            0
        }

        fun storagePercent(): Int = try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val total = stat.totalBytes
            val used = total - stat.availableBytes
            if (total > 0) (used * 100 / total).toInt() else 0
        } catch (e: Exception) {
            0
        }

        fun currentMode(): Int = try {
            SystemProperties.getInt("sys.perf_mode_active", PowerProfileUtil.MODE_BALANCE)
        } catch (_: Exception) {
            PowerProfileUtil.MODE_BALANCE
        }

        fun modeLabel(ctx: Context, mode: Int): String = ctx.getString(
            when (mode) {
                PowerProfileUtil.MODE_BATTERY_SAVER -> R.string.powerprofile_mode_battery_saver
                PowerProfileUtil.MODE_PERFORMANCE -> R.string.powerprofile_mode_performance
                PowerProfileUtil.MODE_BALANCE -> R.string.powerprofile_mode_balance
                else -> R.string.powerprofile_mode_unknown
            }
        )

        fun modeColor(mode: Int): Int = when (mode) {
            PowerProfileUtil.MODE_BATTERY_SAVER -> 0xFF29B6F6.toInt()
            PowerProfileUtil.MODE_PERFORMANCE -> 0xFF43E97B.toInt()
            else -> 0xFF6C63FF.toInt()
        }

        fun readTempC(path: String, divisor: Float): Float = try {
            readSysfs(path).toInt() / divisor
        } catch (e: Exception) {
            0f
        }

        fun readSysfs(path: String): String = try {
            BufferedReader(FileReader(path)).use { it.readLine()?.trim() ?: "0" }
        } catch (e: Exception) {
            "0"
        }
    }
}

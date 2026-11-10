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

package org.lineageos.device.DeviceSettings

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.util.Log
import android.util.TypedValue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/** Same file the preference-based UI used (`PreferenceManager.getDefaultSharedPreferences`). */
fun Context.defaultPrefs(): SharedPreferences =
    getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)

object Utils {

    private const val TAG = "DeviceSettingsUtils"
    private const val UI_PREFS = "band_lock_prefs"
    private const val KEY_DYNAMIC_COLORS = "use_dynamic_colors"

    private val PEACH_NIGHT = Color.parseColor("#E5A376")
    private val PEACH_DAY = Color.parseColor("#D97736")

    /** Write a string value to the specified file using NIO. */
    @JvmStatic
    fun writeValue(filename: String?, value: String?) {
        if (filename == null || value == null) return
        try {
            Files.write(Paths.get(filename), value.toByteArray(Charsets.UTF_8), StandardOpenOption.WRITE)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write value to $filename", e)
        }
    }

    /** Reads the first line of a file, trimmed; null if unreadable or empty. */
    @JvmStatic
    fun readLine(filename: String?): String? {
        if (filename == null) return null
        return try {
            Files.readAllLines(Paths.get(filename), Charsets.UTF_8).firstOrNull()?.trim()
        } catch (e: IOException) {
            null
        }
    }

    @JvmStatic
    fun getFileValue(filename: String?, defValue: String): String = readLine(filename) ?: defValue

    @JvmStatic
    fun getFileValueAsBoolean(filename: String?, defValue: Boolean): Boolean {
        val v = readLine(filename) ?: return defValue
        return v != "0"
    }

    @JvmStatic
    fun fileExists(filename: String?): Boolean = filename != null && File(filename).exists()

    @JvmStatic
    fun fileWritable(filename: String?): Boolean = filename != null && File(filename).canWrite()

    /** User choice between Material You colours and the warm-peach fallback palette. */
    @JvmStatic
    fun useDynamicColors(context: Context): Boolean =
        context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DYNAMIC_COLORS, true)

    @JvmStatic
    fun setUseDynamicColors(context: Context, enabled: Boolean) {
        context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DYNAMIC_COLORS, enabled).apply()
    }

    /** Still used by the legacy (View based) sub screens until they are migrated. */
    @JvmStatic
    fun applyAppTheme(activity: Activity?) {
        if (activity == null) return
        if (useDynamicColors(activity)) {
            activity.setTheme(R.style.Theme_DeviceSettings)
        } else {
            activity.setTheme(R.style.Theme_DeviceSettings_WarmPeach)
        }
    }

    @JvmStatic
    fun getSystemAccentColor(context: Context?): Int {
        if (context == null) return PEACH_NIGHT
        val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val fallback = if (isNight) PEACH_NIGHT else PEACH_DAY
        if (!useDynamicColors(context)) return fallback

        var accent = 0
        try {
            val name = if (isNight) "system_accent1_300" else "system_accent1_600"
            val resId = context.resources.getIdentifier(name, "color", "android")
            if (resId != 0) accent = context.getColor(resId)
        } catch (_: Exception) {
        }
        if (accent == 0) {
            try {
                val tv = TypedValue()
                if (context.theme.resolveAttribute(android.R.attr.colorAccent, tv, true) && tv.data != 0) {
                    accent = tv.data
                }
            } catch (_: Exception) {
            }
        }
        return if (accent == 0) fallback else accent
    }
}

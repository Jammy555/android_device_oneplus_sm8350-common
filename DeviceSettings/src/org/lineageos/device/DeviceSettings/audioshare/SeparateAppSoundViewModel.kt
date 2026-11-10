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
package org.lineageos.device.DeviceSettings.audioshare

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppItem(val label: String, val packageName: String, val icon: Bitmap?)

data class AppSoundUiState(
    val enabled: Boolean = false,
    /** "speaker" or "bluetooth" - same values the manager reads from Settings.System. */
    val device: String = "speaker",
    val apps: List<AppItem> = emptyList(),
    val selected: Set<String> = emptySet(),
    val loading: Boolean = true,
)

/** State behind Separate App Sound. Settings.System keys and the change broadcast are unchanged. */
class SeparateAppSoundViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app
    private val resolver = ctx.contentResolver

    private val _state = MutableStateFlow(
        AppSoundUiState(
            enabled = Settings.System.getInt(resolver, SeparateAppSoundManager.SETTING_ENABLED, 0) == 1,
            device = Settings.System.getString(resolver, SeparateAppSoundManager.SETTING_DEVICE)
                ?.takeIf { it.isNotEmpty() } ?: "speaker",
            selected = readSelected(),
        )
    )
    val state: StateFlow<AppSoundUiState> = _state.asStateFlow()

    init {
        loadApplications()
    }

    private fun readSelected(): Set<String> {
        val raw = Settings.System.getString(resolver, SeparateAppSoundManager.SETTING_PACKAGES)
        if (raw.isNullOrEmpty()) return emptySet()
        return raw.split(",").filter { it.isNotEmpty() }.toSet()
    }

    private fun loadApplications() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = ctx.packageManager
                val launcher = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
                val seen = HashSet<String>()
                pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
                    .filter { it.activityInfo != null && seen.add(it.activityInfo.packageName) }
                    .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm).toBitmap(ICON_PX)) }
                    .sortedBy { it.label.lowercase() }
            }
            _state.update { it.copy(apps = apps, loading = false, selected = readSelected()) }
        }
    }

    fun setEnabled(enabled: Boolean) {
        Settings.System.putInt(resolver, SeparateAppSoundManager.SETTING_ENABLED, if (enabled) 1 else 0)
        _state.update { it.copy(enabled = enabled) }
        notifyRoutingChanged()
    }

    fun setDevice(device: String) {
        Settings.System.putString(resolver, SeparateAppSoundManager.SETTING_DEVICE, device)
        _state.update { it.copy(device = device) }
        notifyRoutingChanged()
    }

    fun setAppSelected(packageName: String, selected: Boolean) {
        val packages = readSelected().toMutableSet()
        if (selected) packages.add(packageName) else packages.remove(packageName)
        Settings.System.putString(resolver, SeparateAppSoundManager.SETTING_PACKAGES, packages.joinToString(","))
        _state.update { it.copy(selected = packages) }
        notifyRoutingChanged()
    }

    private fun notifyRoutingChanged() {
        SeparateAppSoundManager.getInstance(ctx).updateRouting()
        ctx.sendBroadcast(Intent(SeparateAppSoundManager.ACTION_CHANGED))
    }

    private fun Drawable.toBitmap(px: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, px, px)
        draw(canvas)
        return bitmap
    }

    private companion object {
        const val ICON_PX = 96
    }
}

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

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.audiopolicy.AudioMix
import android.media.audiopolicy.AudioMixingRule
import android.media.audiopolicy.AudioPolicy
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Routes the selected apps' audio to the phone speaker (or the shared Bluetooth headsets) with an
 * AudioPolicy mix, so they stay out of the shared stream.
 */
class SeparateAppSoundManager private constructor(private val context: Context) {

    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)
    private var audioPolicy: AudioPolicy? = null

    init {
        audioManager?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = updateRouting()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = updateRouting()
        }, Handler(Looper.getMainLooper()))
    }

    @Synchronized
    fun updateRouting() {
        val am = audioManager ?: return
        val resolver = context.contentResolver
        val enabled = Settings.System.getInt(resolver, SETTING_ENABLED, 0) == 1
        val packagesRaw = Settings.System.getString(resolver, SETTING_PACKAGES)
        val targetDevType = Settings.System.getString(resolver, SETTING_DEVICE) ?: "speaker"

        audioPolicy?.let {
            try {
                am.unregisterAudioPolicyAsync(it)
                Log.i(TAG, "Unregistered previous AudioPolicy")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to unregister AudioPolicy", t)
            }
            audioPolicy = null
        }

        if (!enabled || packagesRaw.isNullOrEmpty()) {
            Log.i(TAG, "Separate App Sound disabled or no packages selected")
            return
        }

        val pm = context.packageManager
        val uids = ArrayList<Int>()
        for (pkg in packagesRaw.split(",")) {
            if (pkg.isEmpty()) continue
            try {
                uids.add(pm.getPackageUid(pkg, PackageManager.PackageInfoFlags.of(0)))
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve UID for pkg: $pkg")
            }
        }
        if (uids.isEmpty()) {
            Log.i(TAG, "No valid UIDs found for separate app sound")
            return
        }

        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val desiredType = if (targetDevType.equals("speaker", ignoreCase = true)) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
        var target = devices.firstOrNull { it.type == desiredType }
        if (target == null && desiredType != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            target = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        }
        if (target == null) {
            Log.w(TAG, "No matching output AudioDeviceInfo found for Separate App Sound")
            return
        }

        try {
            val rule = AudioMixingRule.Builder().apply {
                for (uid in uids) addMixRule(AudioMixingRule.RULE_MATCH_UID, uid)
            }.build()
            val mix = AudioMix.Builder(rule)
                .setFormat(
                    AudioFormat.Builder()
                        .setSampleRate(44100)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setRouteFlags(AudioMix.ROUTE_FLAG_RENDER)
                .setDevice(target)
                .build()
            val policy = AudioPolicy.Builder(context)
                .setLooper(Looper.getMainLooper())
                .addMix(mix)
                .build()
            val res = am.registerAudioPolicy(policy)
            if (res != AudioManager.SUCCESS) {
                Log.w(TAG, "registerAudioPolicy failed with result=$res")
            } else {
                audioPolicy = policy
                Log.i(TAG, "registerAudioPolicy SUCCESS for ${target.productName} (${target.type}) with ${uids.size} UIDs")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register Separate App Sound AudioPolicy", t)
        }
    }

    companion object {
        private const val TAG = "SeparateAppSoundManager"
        const val SETTING_ENABLED = "separate_app_sound_enabled"
        const val SETTING_PACKAGES = "separate_app_sound_packages"
        const val SETTING_DEVICE = "separate_app_sound_device"
        const val ACTION_CHANGED = "org.lineageos.device.DeviceSettings.ACTION_SEPARATE_APP_SOUND_CHANGED"

        @Volatile
        private var instance: SeparateAppSoundManager? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): SeparateAppSoundManager =
            instance ?: SeparateAppSoundManager(context.applicationContext).also { instance = it }
    }
}

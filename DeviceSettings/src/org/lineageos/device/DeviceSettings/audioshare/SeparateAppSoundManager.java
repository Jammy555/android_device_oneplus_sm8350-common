/*
 * Copyright (C) 2026 LunarisOS
 * Separate App Sound Routing Engine using Android AudioPolicy UID Rules
 */

package org.lineageos.device.DeviceSettings.audioshare;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioPolicy;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class SeparateAppSoundManager {
    private static final String TAG = "SeparateAppSoundManager";
    private static final String SETTING_ENABLED = "separate_app_sound_enabled";
    private static final String SETTING_PACKAGES = "separate_app_sound_packages";
    private static final String SETTING_DEVICE = "separate_app_sound_device";

    private static SeparateAppSoundManager sInstance;
    private final Context mContext;
    private final AudioManager mAudioManager;
    private AudioPolicy mAudioPolicy;

    public static synchronized SeparateAppSoundManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new SeparateAppSoundManager(context.getApplicationContext());
        }
        return sInstance;
    }

    private SeparateAppSoundManager(Context context) {
        mContext = context;
        mAudioManager = context.getSystemService(AudioManager.class);
        if (mAudioManager != null) {
            mAudioManager.registerAudioDeviceCallback(new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                    updateRouting();
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                    updateRouting();
                }
            }, new Handler(Looper.getMainLooper()));
        }
    }

    public synchronized void updateRouting() {
        if (mAudioManager == null) return;
        boolean enabled = Settings.System.getInt(mContext.getContentResolver(), SETTING_ENABLED, 0) == 1;
        String pkgsRaw = Settings.System.getString(mContext.getContentResolver(), SETTING_PACKAGES);
        String targetDevType = Settings.System.getString(mContext.getContentResolver(), SETTING_DEVICE);
        if (targetDevType == null) {
            targetDevType = "speaker";
        }

        if (mAudioPolicy != null) {
            try {
                mAudioManager.unregisterAudioPolicyAsync(mAudioPolicy);
                Log.i(TAG, "Unregistered previous AudioPolicy");
            } catch (Throwable t) {
                Log.w(TAG, "Failed to unregister AudioPolicy", t);
            }
            mAudioPolicy = null;
        }

        if (!enabled || TextUtils.isEmpty(pkgsRaw)) {
            Log.i(TAG, "Separate App Sound disabled or no packages selected");
            return;
        }

        String[] pkgs = pkgsRaw.split(",");
        PackageManager pm = mContext.getPackageManager();
        List<Integer> uids = new ArrayList<>();
        for (String pkg : pkgs) {
            if (TextUtils.isEmpty(pkg)) continue;
            try {
                int uid = pm.getPackageUid(pkg, 0);
                uids.add(uid);
            } catch (Exception e) {
                Log.w(TAG, "Could not resolve UID for pkg: " + pkg);
            }
        }

        if (uids.isEmpty()) {
            Log.i(TAG, "No valid UIDs found for separate app sound");
            return;
        }

        AudioDeviceInfo targetDevice = null;
        AudioDeviceInfo[] devices = mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        int desiredType = "speaker".equalsIgnoreCase(targetDevType)
                ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                : AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;

        for (AudioDeviceInfo dev : devices) {
            if (dev.getType() == desiredType) {
                targetDevice = dev;
                break;
            }
        }

        if (targetDevice == null && desiredType != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            for (AudioDeviceInfo dev : devices) {
                if (dev.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    targetDevice = dev;
                    break;
                }
            }
        }

        if (targetDevice == null) {
            Log.w(TAG, "No matching output AudioDeviceInfo found for Separate App Sound");
            return;
        }

        try {
            AudioMixingRule.Builder ruleBuilder = new AudioMixingRule.Builder();
            for (int uid : uids) {
                ruleBuilder.addMixRule(AudioMixingRule.RULE_MATCH_UID, uid);
            }
            AudioMixingRule mixingRule = ruleBuilder.build();

            AudioMix mix = new AudioMix.Builder(mixingRule)
                    .setFormat(new AudioFormat.Builder()
                            .setSampleRate(44100)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setRouteFlags(AudioMix.ROUTE_FLAG_RENDER)
                    .setDevice(targetDevice)
                    .build();

            mAudioPolicy = new AudioPolicy.Builder(mContext)
                    .setLooper(Looper.getMainLooper())
                    .addMix(mix)
                    .build();

            int res = mAudioManager.registerAudioPolicy(mAudioPolicy);
            if (res != AudioManager.SUCCESS) {
                Log.w(TAG, "registerAudioPolicy failed with result=" + res);
                mAudioPolicy = null;
            } else {
                Log.i(TAG, "registerAudioPolicy SUCCESS for targetDevice=" + targetDevice.getProductName()
                        + " (" + targetDevice.getType() + ") with " + uids.size() + " UIDs");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to register Separate App Sound AudioPolicy", t);
        }
    }
}

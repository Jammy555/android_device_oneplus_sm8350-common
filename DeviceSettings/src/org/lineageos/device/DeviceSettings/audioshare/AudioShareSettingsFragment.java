/*
 * Copyright (C) 2026 LunarisOS
 * Bluetooth Dual Audio Sharing UI for OnePlus 9 (SM8350)
 */

package org.lineageos.device.DeviceSettings.audioshare;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import com.oplus.bluetooth.OplusA2dpSharingManager;
import org.lineageos.device.DeviceSettings.R;
import org.lineageos.device.DeviceSettings.audioshare.AudioVolumePreference;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AudioShareSettingsFragment extends PreferenceFragmentCompat
        implements Preference.OnPreferenceChangeListener, Preference.OnPreferenceClickListener {

    private static final String TAG = "AudioShareSettings";

    private static final String KEY_MASTER_SWITCH = "audio_share_master_switch";
    private static final String KEY_PRIMARY_DEVICE_INFO = "primary_device_info";
    private static final String KEY_CODEC_INFO = "codec_info";
    private static final String KEY_PRIMARY_VOLUME = "primary_device_volume";
    private static final String KEY_SECONDARY_SELECT = "secondary_device_select";
    private static final String KEY_SECONDARY_CODEC_INFO = "secondary_codec_info";
    private static final String KEY_SECONDARY_ACTION = "secondary_share_action";
    private static final String KEY_SECONDARY_VOLUME = "secondary_device_volume";
    private static final String KEY_SYNC_VOLUMES = "audio_share_sync_volumes";
    private static final String KEY_SWAP_ROLES = "audio_share_swap_roles";
    private static final String KEY_SEPARATE_APP_SOUND = "separate_app_sound_pref";
    private static final String KEY_A2DP_HW_OFFLOAD = "bluetooth_a2dp_hw_offload";
    private static final String KEY_HARDWARE_ENGINE_INFO = "hardware_engine_info";

    private static final java.util.concurrent.ExecutorService sOffloadExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private SwitchPreferenceCompat mMasterSwitch;
    private SwitchPreferenceCompat mHwOffloadSwitch;
    private Preference mHardwareEngineInfoPref;
    private volatile boolean mIsOffloadSwitching = false;
    private Preference mPrimaryDeviceInfo;
    private Preference mCodecInfoPref;
    private AudioVolumePreference mPrimaryVolumePref;
    private ListPreference mSecondarySelectPref;
    private Preference mSecondaryCodecInfoPref;
    private Preference mSecondaryActionPref;
    private AudioVolumePreference mSecondaryVolumePref;
    private SwitchPreferenceCompat mSyncVolumesSwitch;
    private Preference mSwapRolesPref;
    private Preference mSeparateAppSoundPref;

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothProfile mBluetoothA2dp;
    private AudioManager mAudioManager;
    private OplusA2dpSharingManager mSharingManager;

    private BluetoothDevice mPrimaryDevice;
    private BluetoothDevice mSecondaryDevice;
    private boolean mIsSharingActive = false;
    private int mLastPrimaryPercent = 80;
    private String mSecondarySummaryText = null;

    private void updateSecondarySummary(String text) {
        mSecondarySummaryText = text;
        if (mSecondarySelectPref != null) {
            mSecondarySelectPref.setSummaryProvider(preference -> mSecondarySummaryText);
        }
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final BluetoothProfile.ServiceListener mProfileListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.A2DP) {
                mBluetoothA2dp = proxy;
                refreshBluetoothDevices();
                mHandler.postDelayed(() -> refreshCodecInfo(), 400);
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.A2DP) {
                mBluetoothA2dp = null;
                refreshBluetoothDevices();
                mHandler.post(() -> refreshCodecInfo());
            }
        }
    };

    private final OplusA2dpSharingManager.StateCallback mStateCallback = new OplusA2dpSharingManager.StateCallback() {
        @Override
        public void onSharingStarted(int role, int type, int reason, BluetoothDevice primary, BluetoothDevice secondary) {
            mHandler.post(() -> {
                Log.i(TAG, "Audio sharing started callback: primary=" + primary + ", sec=" + secondary);
                mIsSharingActive = true;
                if (mMasterSwitch != null && !mMasterSwitch.isChecked()) {
                    mMasterSwitch.setChecked(true);
                }
                if (getContext() != null) {
                    PreferenceManager.getDefaultSharedPreferences(requireContext())
                            .edit().putBoolean(KEY_MASTER_SWITCH, true).apply();
                }
                if (primary != null) mPrimaryDevice = primary;
                if (secondary != null) mSecondaryDevice = secondary;
                refreshBluetoothDevices();
                updateUiState();
            });
        }

        @Override
        public void onSharingStopped(int role, int type, int reason, BluetoothDevice primary, BluetoothDevice secondary) {
            mHandler.post(() -> {
                Log.i(TAG, "Audio sharing stopped callback");
                mIsSharingActive = false;
                if (mMasterSwitch != null && mMasterSwitch.isChecked()) {
                    mMasterSwitch.setChecked(false);
                }
                if (getContext() != null) {
                    PreferenceManager.getDefaultSharedPreferences(requireContext())
                            .edit().putBoolean(KEY_MASTER_SWITCH, false).apply();
                }
                // Preserve mSecondaryDevice selection across pause/resume if device is still connected
                refreshBluetoothDevices();
                updateUiState();
            });
        }
    };

    private final OplusA2dpSharingManager.VolumeCallback mVolumeCallback = (device, volume) -> {
        mHandler.post(() -> {
            if (device != null && mSecondaryDevice != null
                    && device.getAddress().equalsIgnoreCase(mSecondaryDevice.getAddress())
                    && mSecondaryVolumePref != null) {
                // Convert incoming AVRCP absolute volume (0 - 127) to percentage (0 - 100)
                int percent = Math.max(0, Math.min(100, Math.round((float) volume * 100.0f / 127.0f)));
                mSecondaryVolumePref.setValue(percent);
            }
        });
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if ("android.media.VOLUME_CHANGED_ACTION".equals(action)) {
                if (mAudioManager != null) {
                    int curVol = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                    int maxVol = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    int percent = (maxVol > 0) ? (curVol * 100 / maxVol) : 80;
                    if (mPrimaryVolumePref != null) {
                        mPrimaryVolumePref.setValue(percent);
                    }
                    boolean syncEnabled = (mSyncVolumesSwitch != null && mSyncVolumesSwitch.isChecked());
                    if (syncEnabled && mIsSharingActive && mSecondaryDevice != null && mSecondaryVolumePref != null) {
                        int delta = percent - mLastPrimaryPercent;
                        int curSec = mSecondaryVolumePref.getValue();
                        int newSec;
                        if (percent == 0) {
                            newSec = 0;
                        } else if (mLastPrimaryPercent == 0 && percent > 0) {
                            newSec = percent;
                        } else {
                            newSec = Math.max(0, Math.min(100, curSec + delta));
                        }
                        mSecondaryVolumePref.setValue(newSec);
                        if (mSharingManager != null) {
                            int avrcpVol = Math.max(0, Math.min(127, Math.round((float) newSec * 127.0f / 100.0f)));
                            mSharingManager.setSharingDeviceAbsoluteVolume(mSecondaryDevice, avrcpVol);
                        }
                    }
                    mLastPrimaryPercent = percent;
                }
                return;
            }

            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)
                    || BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                    || BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                    || BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED.equals(action)) {
                if (BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED.equals(action)) {
                    BluetoothDevice activeDev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    Log.i(TAG, "ACTION_ACTIVE_DEVICE_CHANGED received: " + activeDev);
                    if (activeDev != null) {
                        mPrimaryDevice = activeDev;
                        if (mSecondaryDevice != null
                                && mSecondaryDevice.getAddress().equalsIgnoreCase(activeDev.getAddress())) {
                            mSecondaryDevice = null;
                            if (mSecondarySelectPref != null) {
                                mSecondarySelectPref.setValue(null);
                                updateSecondarySummary(null);
                            }
                        }
                    }
                    mHandler.postDelayed(() -> refreshCodecInfo(), 500);
                }
                refreshBluetoothDevices();
            }
        }
    };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.audio_share_preferences);

        Context context = requireContext();
        OplusA2dpSharingManager.init(context);
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mAudioManager = context.getSystemService(AudioManager.class);
        mSharingManager = OplusA2dpSharingManager.getInstance();

        mMasterSwitch = findPreference(KEY_MASTER_SWITCH);
        mPrimaryDeviceInfo = findPreference(KEY_PRIMARY_DEVICE_INFO);
        mCodecInfoPref = findPreference(KEY_CODEC_INFO);
        mPrimaryVolumePref = findPreference(KEY_PRIMARY_VOLUME);
        mSecondarySelectPref = findPreference(KEY_SECONDARY_SELECT);
        mSecondaryCodecInfoPref = findPreference(KEY_SECONDARY_CODEC_INFO);
        mSecondaryActionPref = findPreference(KEY_SECONDARY_ACTION);
        mSecondaryVolumePref = findPreference(KEY_SECONDARY_VOLUME);
        mSyncVolumesSwitch = findPreference(KEY_SYNC_VOLUMES);
        mSwapRolesPref = findPreference(KEY_SWAP_ROLES);
        mSeparateAppSoundPref = findPreference(KEY_SEPARATE_APP_SOUND);
        mHwOffloadSwitch = findPreference(KEY_A2DP_HW_OFFLOAD);
        mHardwareEngineInfoPref = findPreference(KEY_HARDWARE_ENGINE_INFO);

        if (mHwOffloadSwitch != null) {
            mHwOffloadSwitch.setOnPreferenceChangeListener(this);
        }
        if (mMasterSwitch != null) {
            mMasterSwitch.setOnPreferenceChangeListener(this);
        }
        if (mPrimaryDeviceInfo != null) {
            mPrimaryDeviceInfo.setOnPreferenceClickListener(this);
        }
        if (mPrimaryVolumePref != null) {
            mPrimaryVolumePref.setOnPreferenceChangeListener(this);
            int curVol = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            int maxVol = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int percent = (maxVol > 0) ? (curVol * 100 / maxVol) : 80;
            mLastPrimaryPercent = percent;
            mPrimaryVolumePref.setValue(percent);
        }
        if (mSecondarySelectPref != null) {
            mSecondarySelectPref.setOnPreferenceChangeListener(this);
            mSecondarySelectPref.setSummaryProvider(preference -> mSecondarySummaryText);
        }
        if (mSecondaryActionPref != null) {
            mSecondaryActionPref.setOnPreferenceClickListener(this);
        }
        if (mSecondaryVolumePref != null) {
            mSecondaryVolumePref.setOnPreferenceChangeListener(this);
        }
        if (mSwapRolesPref != null) {
            mSwapRolesPref.setOnPreferenceClickListener(this);
        }
        if (mSeparateAppSoundPref != null) {
            mSeparateAppSoundPref.setOnPreferenceClickListener(this);
        }

        if (mBluetoothAdapter != null) {
            mBluetoothAdapter.getProfileProxy(context, mProfileListener, BluetoothProfile.A2DP);
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED);
        filter.addAction("android.media.VOLUME_CHANGED_ACTION");
        context.registerReceiver(mReceiver, filter);

        if (mSharingManager != null) {
            mSharingManager.registerStateCallback(null, mStateCallback);
            mSharingManager.registerVolumeCallback(null, mVolumeCallback);
            mIsSharingActive = mSharingManager.inSharingMode();
        }

        refreshBluetoothDevices();
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefListener = (sharedPreferences, key) -> {
        if (KEY_MASTER_SWITCH.equals(key) && mMasterSwitch != null) {
            boolean val = sharedPreferences.getBoolean(KEY_MASTER_SWITCH, false);
            if (mMasterSwitch.isChecked() != val) {
                mMasterSwitch.setChecked(val);
                updateUiState();
            }
        }
    };

    @Override
    public void onResume() {
        super.onResume();
        if (mPrimaryVolumePref != null && mAudioManager != null) {
            int curVol = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            int maxVol = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int percent = (maxVol > 0) ? (curVol * 100 / maxVol) : 80;
            mLastPrimaryPercent = percent;
            mPrimaryVolumePref.setValue(percent);
        }
        if (mSharingManager != null) {
            mIsSharingActive = mSharingManager.inSharingMode();
        }
        if (getContext() != null) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
            prefs.registerOnSharedPreferenceChangeListener(mPrefListener);
            boolean shouldBeChecked = mIsSharingActive
                    || (mSharingManager != null && mSharingManager.isA2dpSharingEnabled())
                    || prefs.getBoolean(KEY_MASTER_SWITCH, false);
            if (mMasterSwitch != null && mMasterSwitch.isChecked() != shouldBeChecked) {
                mMasterSwitch.setChecked(shouldBeChecked);
            }
            updateUiState();
        }
        refreshHardwareOffloadSwitch();
        refreshBluetoothDevices();
        refreshCodecInfo();
        updateHardwareEngineDebugInfo();
    }

    private void refreshHardwareOffloadSwitch() {
        if (mHwOffloadSwitch != null) {
            boolean offloadDisabled = android.os.SystemProperties.getBoolean("persist.bluetooth.a2dp_offload.disabled", false);
            mHwOffloadSwitch.setChecked(!offloadDisabled);
            mHwOffloadSwitch.setSummary(!offloadDisabled
                    ? R.string.bluetooth_a2dp_hw_offload_summary_on
                    : R.string.bluetooth_a2dp_hw_offload_summary_off);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        Context context = getContext();
        if (context != null) {
            try {
                PreferenceManager.getDefaultSharedPreferences(context)
                        .unregisterOnSharedPreferenceChangeListener(mPrefListener);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Context context = getContext();
        if (context != null) {
            try {
                context.unregisterReceiver(mReceiver);
            } catch (Exception ignored) {}
        }
        if (mBluetoothAdapter != null && mBluetoothA2dp != null) {
            mBluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, mBluetoothA2dp);
        }
        if (mSharingManager != null) {
            mSharingManager.unregisterStateCallback(mStateCallback);
            mSharingManager.unregisterVolumeCallback(mVolumeCallback);
        }
    }

    private void refreshBluetoothDevices() {
        if (!isAdded() || getContext() == null) {
            return;
        }

        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            if (mPrimaryDeviceInfo != null) {
                mPrimaryDeviceInfo.setSummary(R.string.audio_share_no_primary_device);
            }
            if (mSecondarySelectPref != null) {
                mSecondarySelectPref.setEntries(new CharSequence[0]);
                mSecondarySelectPref.setEntryValues(new CharSequence[0]);
                mSecondarySelectPref.setEnabled(false);
            }
            updateUiState();
            return;
        }

        // Primary connected device resolution via active A2DP device
        List<BluetoothDevice> connectedA2dpDevices = new ArrayList<>();
        if (mBluetoothA2dp != null) {
            List<BluetoothDevice> connected = mBluetoothA2dp.getConnectedDevices();
            if (connected != null) {
                connectedA2dpDevices.addAll(connected);
            }

            BluetoothDevice activeDev = getActiveA2dpDevice();
            if (activeDev != null) {
                mPrimaryDevice = activeDev;
            } else if (mPrimaryDevice != null) {
                // Keep current primary if it is still connected even if playback is paused
                boolean primStillConnected = false;
                for (BluetoothDevice dev : connectedA2dpDevices) {
                    if (dev != null && dev.getAddress().equalsIgnoreCase(mPrimaryDevice.getAddress())) {
                        primStillConnected = true;
                        break;
                    }
                }
                if (!primStillConnected) {
                    mPrimaryDevice = !connectedA2dpDevices.isEmpty() ? connectedA2dpDevices.get(0) : null;
                }
            } else if (!connectedA2dpDevices.isEmpty()) {
                mPrimaryDevice = connectedA2dpDevices.get(0);
            } else {
                mPrimaryDevice = null;
            }
        }

        if (mPrimaryDeviceInfo != null) {
            if (mPrimaryDevice != null) {
                String name = getDeviceDisplayName(mPrimaryDevice);
                int battery = mPrimaryDevice.getBatteryLevel();
                String battStr = (battery >= 0) ? (" \u2022 " + battery + "%") : "";
                mPrimaryDeviceInfo.setSummary(name + battStr + " (" + mPrimaryDevice.getAddress() + ")");
            } else {
                mPrimaryDeviceInfo.setSummary(R.string.audio_share_no_primary_device);
            }
        }

        // If currently selected secondary is the same as the active primary, reset secondary
        if (mSecondaryDevice != null && mPrimaryDevice != null
                && mSecondaryDevice.getAddress().equalsIgnoreCase(mPrimaryDevice.getAddress())) {
            mSecondaryDevice = null;
            if (mSecondarySelectPref != null) {
                mSecondarySelectPref.setValue(null);
                updateSecondarySummary(null);
            }
        }

        // Build list of potential secondary devices — ONLY actively connected A2DP devices.
        List<CharSequence> entries = new ArrayList<>();
        List<CharSequence> values = new ArrayList<>();
        Set<String> addedAddresses = new HashSet<>();

        if (mPrimaryDevice != null) {
            addedAddresses.add(mPrimaryDevice.getAddress().toUpperCase(Locale.ROOT));
        }

        for (BluetoothDevice dev : connectedA2dpDevices) {
            if (dev == null) continue;
            String addr = dev.getAddress().toUpperCase(Locale.ROOT);
            if (!addedAddresses.contains(addr)) {
                addedAddresses.add(addr);
                int battery = dev.getBatteryLevel();
                String battStr = (battery >= 0) ? (" \u2022 " + battery + "%") : "";
                entries.add(getDeviceDisplayName(dev) + battStr + " (Connected)");
                values.add(dev.getAddress());
            }
        }

        // Auto-clear secondary selection if its device has since disconnected
        if (mSecondaryDevice != null) {
            String secAddr = mSecondaryDevice.getAddress().toUpperCase(Locale.ROOT);
            boolean secStillConnected = false;
            for (BluetoothDevice dev : connectedA2dpDevices) {
                if (dev != null && dev.getAddress().toUpperCase(Locale.ROOT).equals(secAddr)) {
                    secStillConnected = true;
                    break;
                }
            }
            if (!secStillConnected) {
                Log.w(TAG, "Secondary device disconnected, clearing: " + mSecondaryDevice.getAddress());
                mSecondaryDevice = null;
                if (mSecondarySelectPref != null) {
                    mSecondarySelectPref.setValue(null);
                    updateSecondarySummary(null);
                }
            }
        }

        if (mSecondarySelectPref != null) {
            mSecondarySelectPref.setEntries(entries.toArray(new CharSequence[0]));
            mSecondarySelectPref.setEntryValues(values.toArray(new CharSequence[0]));
            mSecondarySelectPref.setEnabled(!entries.isEmpty() && !mIsSharingActive);

            if (mSecondaryDevice != null) {
                mSecondarySelectPref.setValue(mSecondaryDevice.getAddress());
                int battery = mSecondaryDevice.getBatteryLevel();
                String battStr = (battery >= 0) ? (" \u2022 " + battery + "%") : "";
                updateSecondarySummary(getDeviceDisplayName(mSecondaryDevice) + battStr);
            } else {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
                String lastSec = prefs.getString("last_secondary_device", null);
                if (lastSec != null && addedAddresses.contains(lastSec.toUpperCase(Locale.ROOT))
                        && (mPrimaryDevice == null || !lastSec.equalsIgnoreCase(mPrimaryDevice.getAddress()))) {
                    mSecondaryDevice = mBluetoothAdapter.getRemoteDevice(lastSec);
                    mSecondarySelectPref.setValue(lastSec);
                    int battery = mSecondaryDevice.getBatteryLevel();
                    String battStr = (battery >= 0) ? (" \u2022 " + battery + "%") : "";
                    updateSecondarySummary(getDeviceDisplayName(mSecondaryDevice) + battStr);
                } else if (!values.isEmpty()) {
                    String autoSec = values.get(0).toString();
                    mSecondaryDevice = mBluetoothAdapter.getRemoteDevice(autoSec);
                    mSecondarySelectPref.setValue(autoSec);
                    int battery = mSecondaryDevice.getBatteryLevel();
                    String battStr = (battery >= 0) ? (" \u2022 " + battery + "%") : "";
                    updateSecondarySummary(getDeviceDisplayName(mSecondaryDevice) + battStr);
                    prefs.edit().putString("last_secondary_device", autoSec).apply();
                } else {
                    mSecondarySelectPref.setValue(null);
                    updateSecondarySummary(null);
                }
            }
        }

        updateUiState();
    }

    private void updateUiState() {
        if (!isAdded() || getContext() == null) {
            return;
        }

        boolean masterEnabled = mMasterSwitch != null && mMasterSwitch.isChecked();
        boolean btOk = mBluetoothAdapter != null && mBluetoothAdapter.isEnabled();

        if (mHwOffloadSwitch != null) {
            mHwOffloadSwitch.setEnabled(masterEnabled && !mIsOffloadSwitching);
        }

        if (mPrimaryDeviceInfo != null) {
            mPrimaryDeviceInfo.setEnabled(masterEnabled && btOk);
        }
        if (mCodecInfoPref != null) {
            mCodecInfoPref.setEnabled(masterEnabled && btOk && mPrimaryDevice != null);
        }
        if (mPrimaryVolumePref != null) {
            mPrimaryVolumePref.setEnabled(masterEnabled && btOk && mPrimaryDevice != null);
        }
        if (mSecondarySelectPref != null) {
            mSecondarySelectPref.setEnabled(masterEnabled && btOk && !mIsSharingActive);
        }
        if (mSecondaryCodecInfoPref != null) {
            mSecondaryCodecInfoPref.setEnabled(masterEnabled && btOk && mSecondaryDevice != null);
        }

        if (mSecondaryActionPref != null) {
            if (mIsSharingActive) {
                mSecondaryActionPref.setTitle(R.string.audio_share_stop_action);
                String secName = (mSecondaryDevice != null) ? getDeviceDisplayName(mSecondaryDevice) : "Secondary";
                mSecondaryActionPref.setSummary(getString(R.string.audio_share_active_status) + " (" + secName + ")");
                mSecondaryActionPref.setEnabled(masterEnabled && btOk);
                if (mSecondaryVolumePref != null) {
                    mSecondaryVolumePref.setEnabled(masterEnabled && btOk);
                }
            } else {
                mSecondaryActionPref.setTitle(R.string.audio_share_start_action);
                mSecondaryActionPref.setSummary(R.string.audio_share_idle_status);
                mSecondaryActionPref.setEnabled(masterEnabled && btOk);
                if (mSecondaryVolumePref != null) {
                    mSecondaryVolumePref.setEnabled(false);
                }
            }
        }

        if (mSwapRolesPref != null) {
            mSwapRolesPref.setEnabled(masterEnabled && btOk && mPrimaryDevice != null && mSecondaryDevice != null);
        }
        if (mSyncVolumesSwitch != null) {
            mSyncVolumesSwitch.setEnabled(masterEnabled && btOk && mIsSharingActive);
        }
        if (mSeparateAppSoundPref != null) {
            mSeparateAppSoundPref.setEnabled(masterEnabled && btOk);
        }
    }

    private String getDeviceDisplayName(BluetoothDevice device) {
        if (device == null) return "Unknown";
        String alias = device.getAlias();
        if (!TextUtils.isEmpty(alias)) return alias;
        String name = device.getName();
        if (!TextUtils.isEmpty(name)) return name;
        return device.getAddress();
    }

    private BluetoothDevice getActiveA2dpDevice() {
        if (mBluetoothAdapter != null) {
            try {
                List<BluetoothDevice> actives = mBluetoothAdapter.getActiveDevices(BluetoothProfile.A2DP);
                if (actives != null && !actives.isEmpty()) {
                    return actives.get(0);
                }
            } catch (Throwable ignored) {}
        }
        if (mBluetoothA2dp != null) {
            try {
                java.lang.reflect.Method m = mBluetoothA2dp.getClass().getMethod("getActiveDevice");
                Object dev = m.invoke(mBluetoothA2dp);
                if (dev instanceof BluetoothDevice) {
                    return (BluetoothDevice) dev;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mMasterSwitch) {
            boolean enabled = (Boolean) newValue;
            if (mSharingManager != null) {
                mSharingManager.setA2dpSharingEnabled(enabled);
            }
            if (mMasterSwitch != null) {
                mMasterSwitch.setChecked(enabled);
            }
            if (getContext() != null) {
                PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .edit().putBoolean(KEY_MASTER_SWITCH, enabled).apply();
            }
            refreshBluetoothDevices();
            mHandler.postDelayed(this::refreshCodecInfo, 400);
            updateUiState();
            updateHardwareEngineDebugInfo();
            return true;
        } else if (preference == mPrimaryVolumePref) {
            int percent = (Integer) newValue;
            int maxVol = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int newVol = (percent * maxVol) / 100;
            mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0);

            boolean syncEnabled = (mSyncVolumesSwitch != null && mSyncVolumesSwitch.isChecked());
            if (syncEnabled && mIsSharingActive && mSecondaryDevice != null && mSecondaryVolumePref != null) {
                int delta = percent - mLastPrimaryPercent;
                int curSec = mSecondaryVolumePref.getValue();
                int newSec;
                if (percent == 0) {
                    newSec = 0;
                } else if (mLastPrimaryPercent == 0 && percent > 0) {
                    newSec = percent;
                } else {
                    newSec = Math.max(0, Math.min(100, curSec + delta));
                }
                mSecondaryVolumePref.setValue(newSec);
                if (mSharingManager != null) {
                    int avrcpVol = Math.max(0, Math.min(127, Math.round((float) newSec * 127.0f / 100.0f)));
                    mSharingManager.setSharingDeviceAbsoluteVolume(mSecondaryDevice, avrcpVol);
                }
            }
            mLastPrimaryPercent = percent;
            return true;
        } else if (preference == mSecondarySelectPref) {
            String address = (String) newValue;
            if (!TextUtils.isEmpty(address) && mBluetoothAdapter != null) {
                BluetoothDevice candidate = mBluetoothAdapter.getRemoteDevice(address);
                if (!isA2dpConnected(candidate)) {
                    Toast.makeText(getContext(),
                            R.string.audio_share_secondary_not_connected, Toast.LENGTH_SHORT).show();
                    mSecondarySelectPref.setValue(
                            mSecondaryDevice != null ? mSecondaryDevice.getAddress() : null);
                    return true;
                }
                mSecondaryDevice = candidate;
                mSecondarySelectPref.setValue(address);
                updateSecondarySummary(getDeviceDisplayName(mSecondaryDevice));
                try {
                    PreferenceManager.getDefaultSharedPreferences(requireContext())
                            .edit().putString("last_secondary_device", address).apply();
                } catch (Throwable ignored) {}
                updateUiState();
            }
            return true;
        } else if (preference == mSecondaryVolumePref) {
            int percent = (Integer) newValue;
            if (mSecondaryDevice != null && mSharingManager != null) {
                // Convert percentage (0 - 100) to AVRCP absolute volume (0 - 127)
                int avrcpVolume = Math.max(0, Math.min(127, Math.round((float) percent * 127.0f / 100.0f)));
                mSharingManager.setSharingDeviceAbsoluteVolume(mSecondaryDevice, avrcpVolume);
            }
        } else if (preference == mHwOffloadSwitch) {
            boolean enableOffload = (Boolean) newValue;
            boolean currentOffload = !android.os.SystemProperties.getBoolean("persist.bluetooth.a2dp_offload.disabled", false);
            if (enableOffload == currentOffload) {
                if (getContext() != null) {
                    Toast.makeText(requireContext(),
                            enableOffload ? R.string.bluetooth_a2dp_hw_offload_already_hw
                                          : R.string.bluetooth_a2dp_hw_offload_already_sw,
                            Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            mIsOffloadSwitching = true;
            mHwOffloadSwitch.setEnabled(false);
            mHwOffloadSwitch.setChecked(enableOffload);
            mHwOffloadSwitch.setSummary(enableOffload
                    ? R.string.bluetooth_a2dp_hw_offload_summary_on
                    : R.string.bluetooth_a2dp_hw_offload_summary_off);
            if (getContext() != null) {
                Toast.makeText(requireContext(), R.string.bluetooth_a2dp_hw_offload_toast, Toast.LENGTH_SHORT).show();
            }

            final Context appContext = (getContext() != null) ? getContext().getApplicationContext() : null;
            sOffloadExecutor.execute(() -> {
                boolean disabled = !enableOffload;
                try {
                    android.os.SystemProperties.set("persist.bluetooth.a2dp_offload.disabled", Boolean.toString(disabled));
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to set persist.bluetooth.a2dp_offload.disabled", t);
                }
                try {
                    android.os.SystemProperties.set("ctl.restart", "audioserver");
                } catch (Throwable t) {
                    Log.w(TAG, "Failed ctl.restart audioserver", t);
                }
                try {
                    BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
                    if (bt != null && bt.isEnabled()) {
                        if (appContext != null) {
                            final BroadcastReceiver[] cycleReceiverHolder = new BroadcastReceiver[1];
                            cycleReceiverHolder[0] = new BroadcastReceiver() {
                                @Override
                                public void onReceive(Context context, Intent intent) {
                                    if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                                        int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                                        if (state == BluetoothAdapter.STATE_OFF) {
                                            try {
                                                context.unregisterReceiver(this);
                                            } catch (Exception ignored) {}
                                            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                                            if (adapter != null) {
                                                adapter.enable();
                                            }
                                        }
                                    }
                                }
                            };
                            appContext.registerReceiver(cycleReceiverHolder[0],
                                    new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
                            // Safety guard: unregister after 5 seconds if state change didn't fire
                            mHandler.postDelayed(() -> {
                                try {
                                    if (cycleReceiverHolder[0] != null) {
                                        appContext.unregisterReceiver(cycleReceiverHolder[0]);
                                    }
                                } catch (Exception ignored) {}
                            }, 5000);
                        }
                        bt.disable();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to cycle BluetoothAdapter", t);
                }

                mHandler.postDelayed(() -> {
                    mIsOffloadSwitching = false;
                    if (isAdded()) {
                        refreshHardwareOffloadSwitch();
                        updateUiState();
                        refreshBluetoothDevices();
                        refreshCodecInfo();
                        updateHardwareEngineDebugInfo();
                    }
                }, 2500);
            });
            return true;
        }
        return false;
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        if (preference == mSecondaryActionPref) {
            Log.d(TAG, "Audio share action clicked. Current mIsSharingActive=" + mIsSharingActive);
            if (mIsSharingActive) {
                if (mSharingManager != null) {
                    mSharingManager.stopSharing();
                }
                mIsSharingActive = false;
                updateUiState();
                Toast.makeText(getContext(), "Audio sharing stopped", Toast.LENGTH_SHORT).show();
            } else {
                if (mPrimaryDevice == null) {
                    mPrimaryDevice = getActiveA2dpDevice();
                }
                if (mSecondaryDevice == null && mSecondarySelectPref != null && mSecondarySelectPref.getValue() != null) {
                    mSecondaryDevice = mBluetoothAdapter.getRemoteDevice(mSecondarySelectPref.getValue());
                }
                if (mPrimaryDevice == null) {
                    Toast.makeText(getContext(), "No active primary device. Please play music on your main headset first.", Toast.LENGTH_LONG).show();
                    return true;
                }
                if (mSecondaryDevice == null) {
                    Toast.makeText(getContext(), "Please select a secondary sharing device above first.", Toast.LENGTH_LONG).show();
                    return true;
                }
                // Guard: secondary must be actively connected at the moment sharing starts
                if (!isA2dpConnected(mSecondaryDevice)) {
                    Toast.makeText(getContext(),
                            R.string.audio_share_secondary_not_connected, Toast.LENGTH_LONG).show();
                    mSecondaryDevice = null;
                    if (mSecondarySelectPref != null) {
                        mSecondarySelectPref.setValue(null);
                        updateSecondarySummary(null);
                    }
                    refreshBluetoothDevices();
                    return true;
                }
                if (mSecondaryDevice.getAddress().equalsIgnoreCase(mPrimaryDevice.getAddress())) {
                    Toast.makeText(getContext(), "Primary and secondary device cannot be the same", Toast.LENGTH_SHORT).show();
                    return true;
                }
                Log.d(TAG, "Starting audio share: primary=" + mPrimaryDevice.getAddress() + " -> secondary=" + mSecondaryDevice.getAddress());
                boolean started = false;
                if (mSharingManager != null) {
                    started = mSharingManager.startSharing(mSecondaryDevice, OplusA2dpSharingManager.SHARING_TYPE_BONDED);
                }
                if (started) {
                    Toast.makeText(getContext(), "Starting audio sharing with " + getDeviceDisplayName(mSecondaryDevice) + "...", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getContext(), "Failed to start sharing. Check bluetooth logs.", Toast.LENGTH_LONG).show();
                }
            }
            return true;
        } else if (preference == mPrimaryDeviceInfo) {
            showSelectPrimaryDeviceDialog();
            return true;
        } else if (preference == mSwapRolesPref) {
            swapRoles();
            return true;
        } else if (preference == mSeparateAppSoundPref) {
            Intent intent = new Intent(getActivity(), SeparateAppSoundActivity.class);
            startActivity(intent);
            return true;
        }
        return false;
    }

    private void swapRoles() {
        if (mPrimaryDevice == null || mSecondaryDevice == null || mBluetoothA2dp == null) {
            Toast.makeText(getContext(), "Both primary and secondary headsets must be connected", Toast.LENGTH_SHORT).show();
            return;
        }
        BluetoothDevice oldPrim = mPrimaryDevice;
        BluetoothDevice oldSec = mSecondaryDevice;
        Log.i(TAG, "swapRoles requested: oldPrim=" + oldPrim + ", oldSec=" + oldSec);

        boolean wasSharing = mIsSharingActive;
        if (wasSharing && mSharingManager != null) {
            mSharingManager.stopSharing();
        }

        try {
            Method setActiveDeviceMethod = mBluetoothA2dp.getClass().getMethod("setActiveDevice", BluetoothDevice.class);
            setActiveDeviceMethod.invoke(mBluetoothA2dp, oldSec);
        } catch (Exception e) {
            Log.w(TAG, "Failed to invoke setActiveDevice reflectively", e);
        }

        mPrimaryDevice = oldSec;
        mSecondaryDevice = oldPrim;

        refreshBluetoothDevices();
        refreshCodecInfo();

        if (wasSharing && mSharingManager != null) {
            mHandler.postDelayed(() -> {
                Log.i(TAG, "swapRoles: re-initiating sharing with new secondary=" + oldPrim);
                if (mSecondaryDevice != null && mSharingManager != null) {
                    mSharingManager.startSharing(mSecondaryDevice, OplusA2dpSharingManager.SHARING_TYPE_BONDED);
                }
            }, 800);

        }
    }

    private void showSelectPrimaryDeviceDialog() {
        if (mBluetoothA2dp == null || getContext() == null) return;
        List<BluetoothDevice> connected = mBluetoothA2dp.getConnectedDevices();
        if (connected == null || connected.isEmpty()) {
            Toast.makeText(getContext(), R.string.audio_share_no_primary_device, Toast.LENGTH_SHORT).show();
            return;
        }
        if (connected.size() == 1) {
            Toast.makeText(getContext(), "Only one Bluetooth audio device connected", Toast.LENGTH_SHORT).show();
            return;
        }

        CharSequence[] names = new CharSequence[connected.size()];
        int selectedIndex = 0;
        for (int i = 0; i < connected.size(); i++) {
            BluetoothDevice dev = connected.get(i);
            names[i] = getDeviceDisplayName(dev) + " (" + dev.getAddress() + ")";
            if (mPrimaryDevice != null && dev.getAddress().equalsIgnoreCase(mPrimaryDevice.getAddress())) {
                selectedIndex = i;
            }
        }

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.audio_share_primary_device)
                .setSingleChoiceItems(names, selectedIndex, (dialog, which) -> {
                    BluetoothDevice target = connected.get(which);
                    if (mBluetoothAdapter != null) {
                        try {
                            mBluetoothAdapter.setActiveDevice(target, BluetoothAdapter.ACTIVE_DEVICE_AUDIO);
                            mPrimaryDevice = target;
                            refreshBluetoothDevices();
                        } catch (Throwable t) {
                            Log.e(TAG, "Failed to switch active A2DP device", t);
                        }
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }


    /** True when the given device has an active A2DP connection (not just paired). */
    private boolean isA2dpConnected(BluetoothDevice device) {
        if (device == null || mBluetoothA2dp == null) return false;
        return mBluetoothA2dp.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED;
    }

    /**
     * Reads the current A2DP codec from the stack for mPrimaryDevice and mSecondaryDevice
     * and updates the respective codec preferences so the user sees live negotiated codecs.
     */
    private void refreshCodecInfo() {
        // --- Primary Device Codec ---
        if (mCodecInfoPref != null) {
            if (!(mBluetoothA2dp instanceof BluetoothA2dp) || mPrimaryDevice == null) {
                mCodecInfoPref.setSummary(R.string.audio_share_codec_not_connected);
            } else {
                try {
                    BluetoothCodecStatus status =
                            ((BluetoothA2dp) mBluetoothA2dp).getCodecStatus(mPrimaryDevice);
                    if (status != null && status.getCodecConfig() != null) {
                        BluetoothCodecConfig cfg = status.getCodecConfig();
                        String name = codecTypeName(cfg.getCodecType());
                        String sr = sampleRateString(cfg.getSampleRate());
                        String bits = bitsPerSampleString(cfg.getBitsPerSample());
                        String ch = channelModeString(cfg.getChannelMode());
                        StringBuilder sb = new StringBuilder(name);
                        if (!sr.isEmpty()) sb.append(" \u2022 ").append(sr);
                        if (!bits.isEmpty()) sb.append(" \u2022 ").append(bits);
                        if (!ch.isEmpty()) sb.append(" \u2022 ").append(ch);
                        mCodecInfoPref.setSummary(sb.toString());
                    } else {
                        mCodecInfoPref.setSummary("Unknown");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "refreshCodecInfo primary: " + t.getMessage());
                    mCodecInfoPref.setSummary("N/A");
                }
            }
        }

        // --- Secondary Device Codec ---
        if (mSecondaryCodecInfoPref != null) {
            if (mSecondaryDevice == null) {
                mSecondaryCodecInfoPref.setSummary(R.string.audio_share_secondary_codec_not_connected);
            } else if (mIsSharingActive) {
                mSecondaryCodecInfoPref.setSummary("SBC \u2022 44.1 kHz \u2022 16-bit \u2022 Stereo (Sharing)");
            } else if (mBluetoothA2dp instanceof BluetoothA2dp) {
                try {
                    BluetoothCodecStatus secStatus =
                            ((BluetoothA2dp) mBluetoothA2dp).getCodecStatus(mSecondaryDevice);
                    if (secStatus != null && secStatus.getCodecConfig() != null) {
                        BluetoothCodecConfig cfg = secStatus.getCodecConfig();
                        String name = codecTypeName(cfg.getCodecType());
                        String sr = sampleRateString(cfg.getSampleRate());
                        String bits = bitsPerSampleString(cfg.getBitsPerSample());
                        String ch = channelModeString(cfg.getChannelMode());
                        StringBuilder sb = new StringBuilder(name);
                        if (!sr.isEmpty()) sb.append(" \u2022 ").append(sr);
                        if (!bits.isEmpty()) sb.append(" \u2022 ").append(bits);
                        if (!ch.isEmpty()) sb.append(" \u2022 ").append(ch);
                        sb.append(" (Standby)");
                        mSecondaryCodecInfoPref.setSummary(sb.toString());
                    } else {
                        mSecondaryCodecInfoPref.setSummary(R.string.audio_share_secondary_codec_idle);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "refreshCodecInfo secondary: " + t.getMessage());
                    mSecondaryCodecInfoPref.setSummary(R.string.audio_share_secondary_codec_idle);
                }
            } else {
                mSecondaryCodecInfoPref.setSummary(R.string.audio_share_secondary_codec_idle);
            }
        }

        updateHardwareEngineDebugInfo();
    }

    private void updateHardwareEngineDebugInfo() {
        if (mHardwareEngineInfoPref == null || !isAdded()) return;
        boolean offloadDisabled = android.os.SystemProperties.getBoolean("persist.bluetooth.a2dp_offload.disabled", false);
        boolean masterOn = mMasterSwitch != null && mMasterSwitch.isChecked();
        String engine = offloadDisabled
                ? "Fluoride Software Encoding (Dual-A2DP ready)"
                : "Qualcomm Hexagon DSP Offload";
        String primCodec = (mCodecInfoPref != null && mCodecInfoPref.getSummary() != null)
                ? mCodecInfoPref.getSummary().toString()
                : "None";
        String secCodec = (mSecondaryCodecInfoPref != null && mSecondaryCodecInfoPref.getSummary() != null)
                ? mSecondaryCodecInfoPref.getSummary().toString()
                : "None";

        StringBuilder sb = new StringBuilder();
        sb.append("• Engine: ").append(engine).append("\n");
        sb.append("• Prop: persist.bluetooth.a2dp_offload.disabled=").append(offloadDisabled).append("\n");
        sb.append("• Master Switch: ").append(masterOn ? "Enabled (SBC enforced)" : "Disabled").append("\n");
        sb.append("• Sharing: ").append(mIsSharingActive ? "Active (Dual Stream)" : "Standby").append("\n");
        sb.append("• Primary: ").append(primCodec).append("\n");
        sb.append("• Secondary: ").append(secCodec);
        mHardwareEngineInfoPref.setSummary(sb.toString());
    }

    private static String sampleRateString(int sampleRate) {
        if ((sampleRate & BluetoothCodecConfig.SAMPLE_RATE_44100) != 0) return "44.1 kHz";
        if ((sampleRate & BluetoothCodecConfig.SAMPLE_RATE_48000) != 0) return "48 kHz";
        if ((sampleRate & BluetoothCodecConfig.SAMPLE_RATE_88200) != 0) return "88.2 kHz";
        if ((sampleRate & BluetoothCodecConfig.SAMPLE_RATE_96000) != 0) return "96 kHz";
        if ((sampleRate & BluetoothCodecConfig.SAMPLE_RATE_176400) != 0) return "176.4 kHz";
        if ((sampleRate & BluetoothCodecConfig.SAMPLE_RATE_192000) != 0) return "192 kHz";
        return "";
    }

    private static String bitsPerSampleString(int bits) {
        if ((bits & BluetoothCodecConfig.BITS_PER_SAMPLE_16) != 0) return "16-bit";
        if ((bits & BluetoothCodecConfig.BITS_PER_SAMPLE_24) != 0) return "24-bit";
        if ((bits & BluetoothCodecConfig.BITS_PER_SAMPLE_32) != 0) return "32-bit";
        return "";
    }

    private static String channelModeString(int mode) {
        if ((mode & BluetoothCodecConfig.CHANNEL_MODE_STEREO) != 0) return "Stereo";
        if ((mode & BluetoothCodecConfig.CHANNEL_MODE_MONO) != 0) return "Mono";
        return "";
    }

    private static String codecTypeName(int type) {
        switch (type) {
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC:     return "SBC";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC:     return "AAC";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX:    return "aptX";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD: return "aptX HD";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC:    return "LDAC";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_LC3:     return "LC3";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS:    return "Opus";
            default:                                              return "Unknown";
        }
    }
}


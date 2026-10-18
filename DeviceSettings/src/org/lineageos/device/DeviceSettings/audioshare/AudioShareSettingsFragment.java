/*
 * Copyright (C) 2026 LunarisOS
 * Bluetooth Dual Audio Sharing UI for OnePlus 9 (SM8350)
 */

package org.lineageos.device.DeviceSettings.audioshare;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.oplus.bluetooth.OplusA2dpSharingManager;
import org.lineageos.device.DeviceSettings.CustomSeekBarPreference;
import org.lineageos.device.DeviceSettings.R;

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
    private static final String KEY_PRIMARY_VOLUME = "primary_device_volume";
    private static final String KEY_SECONDARY_SELECT = "secondary_device_select";
    private static final String KEY_SECONDARY_ACTION = "secondary_share_action";
    private static final String KEY_SECONDARY_VOLUME = "secondary_device_volume";

    private SwitchPreferenceCompat mMasterSwitch;
    private Preference mPrimaryDeviceInfo;
    private CustomSeekBarPreference mPrimaryVolumePref;
    private ListPreference mSecondarySelectPref;
    private Preference mSecondaryActionPref;
    private CustomSeekBarPreference mSecondaryVolumePref;

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothProfile mBluetoothA2dp;
    private AudioManager mAudioManager;
    private OplusA2dpSharingManager mSharingManager;

    private BluetoothDevice mPrimaryDevice;
    private BluetoothDevice mSecondaryDevice;
    private boolean mIsSharingActive = false;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final BluetoothProfile.ServiceListener mProfileListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.A2DP) {
                mBluetoothA2dp = proxy;
                refreshBluetoothDevices();
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.A2DP) {
                mBluetoothA2dp = null;
                refreshBluetoothDevices();
            }
        }
    };

    private final OplusA2dpSharingManager.StateCallback mStateCallback = new OplusA2dpSharingManager.StateCallback() {
        @Override
        public void onSharingStarted(int role, int type, int reason, BluetoothDevice primary, BluetoothDevice secondary) {
            mHandler.post(() -> {
                Log.i(TAG, "Audio sharing started callback: primary=" + primary + ", sec=" + secondary);
                mIsSharingActive = true;
                if (primary != null) mPrimaryDevice = primary;
                if (secondary != null) mSecondaryDevice = secondary;
                refreshBluetoothDevices();
            });
        }

        @Override
        public void onSharingStopped(int role, int type, int reason, BluetoothDevice primary, BluetoothDevice secondary) {
            mHandler.post(() -> {
                Log.i(TAG, "Audio sharing stopped callback");
                mIsSharingActive = false;
                mSecondaryDevice = null;
                refreshBluetoothDevices();
            });
        }
    };

    private final OplusA2dpSharingManager.VolumeCallback mVolumeCallback = (device, volume) -> {
        mHandler.post(() -> {
            if (device != null && mSecondaryDevice != null
                    && device.getAddress().equalsIgnoreCase(mSecondaryDevice.getAddress())
                    && mSecondaryVolumePref != null) {
                mSecondaryVolumePref.setValue(volume);
            }
        });
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
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
                                mSecondarySelectPref.setSummary(null);
                            }
                        }
                    }
                }
                refreshBluetoothDevices();
            }
        }
    };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.audio_share_preferences);

        Context context = requireContext();
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mAudioManager = context.getSystemService(AudioManager.class);
        mSharingManager = OplusA2dpSharingManager.getInstance();

        mMasterSwitch = findPreference(KEY_MASTER_SWITCH);
        mPrimaryDeviceInfo = findPreference(KEY_PRIMARY_DEVICE_INFO);
        mPrimaryVolumePref = findPreference(KEY_PRIMARY_VOLUME);
        mSecondarySelectPref = findPreference(KEY_SECONDARY_SELECT);
        mSecondaryActionPref = findPreference(KEY_SECONDARY_ACTION);
        mSecondaryVolumePref = findPreference(KEY_SECONDARY_VOLUME);

        if (mMasterSwitch != null) {
            mMasterSwitch.setOnPreferenceChangeListener(this);
        }
        if (mPrimaryVolumePref != null) {
            mPrimaryVolumePref.setOnPreferenceChangeListener(this);
            int curVol = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            int maxVol = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int percent = (maxVol > 0) ? (curVol * 100 / maxVol) : 80;
            mPrimaryVolumePref.setValue(percent);
        }
        if (mSecondarySelectPref != null) {
            mSecondarySelectPref.setOnPreferenceChangeListener(this);
        }
        if (mSecondaryActionPref != null) {
            mSecondaryActionPref.setOnPreferenceClickListener(this);
        }
        if (mSecondaryVolumePref != null) {
            mSecondaryVolumePref.setOnPreferenceChangeListener(this);
        }

        if (mBluetoothAdapter != null) {
            mBluetoothAdapter.getProfileProxy(context, mProfileListener, BluetoothProfile.A2DP);
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED);
        context.registerReceiver(mReceiver, filter);

        if (mSharingManager != null) {
            mSharingManager.registerStateCallback(null, mStateCallback);
            mSharingManager.registerVolumeCallback(null, mVolumeCallback);
            mIsSharingActive = mSharingManager.inSharingMode();
        }

        refreshBluetoothDevices();
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
            } else {
                mPrimaryDevice = null;
            }
        }

        if (mPrimaryDeviceInfo != null) {
            if (mPrimaryDevice != null) {
                String name = getDeviceDisplayName(mPrimaryDevice);
                mPrimaryDeviceInfo.setSummary(name + " (" + mPrimaryDevice.getAddress() + ")");
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
                mSecondarySelectPref.setSummary(null);
            }
        }

        // Build list of potential secondary devices
        List<CharSequence> entries = new ArrayList<>();
        List<CharSequence> values = new ArrayList<>();
        Set<String> addedAddresses = new HashSet<>();

        if (mPrimaryDevice != null) {
            addedAddresses.add(mPrimaryDevice.getAddress().toUpperCase(Locale.ROOT));
        }

        // 1. Connected A2DP devices (ready for immediate sharing)
        for (BluetoothDevice dev : connectedA2dpDevices) {
            if (dev == null) continue;
            String addr = dev.getAddress().toUpperCase(Locale.ROOT);
            if (!addedAddresses.contains(addr)) {
                addedAddresses.add(addr);
                entries.add(getDeviceDisplayName(dev) + " (Connected)");
                values.add(dev.getAddress());
            }
        }

        // 2. Bonded devices (not currently connected)
        Set<BluetoothDevice> bonded = mBluetoothAdapter.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice dev : bonded) {
                if (dev == null) continue;
                String addr = dev.getAddress().toUpperCase(Locale.ROOT);
                if (!addedAddresses.contains(addr)) {
                    addedAddresses.add(addr);
                    entries.add(getDeviceDisplayName(dev) + " (Paired)");
                    values.add(dev.getAddress());
                }
            }
        }

        if (mSecondarySelectPref != null) {
            mSecondarySelectPref.setEntries(entries.toArray(new CharSequence[0]));
            mSecondarySelectPref.setEntryValues(values.toArray(new CharSequence[0]));
            mSecondarySelectPref.setEnabled(!entries.isEmpty() && !mIsSharingActive);

            if (mSecondaryDevice != null) {
                mSecondarySelectPref.setValue(mSecondaryDevice.getAddress());
                mSecondarySelectPref.setSummary(getDeviceDisplayName(mSecondaryDevice));
            } else if (mSecondarySelectPref.getValue() != null
                    && addedAddresses.contains(mSecondarySelectPref.getValue().toUpperCase(Locale.ROOT))
                    && (mPrimaryDevice == null || !mSecondarySelectPref.getValue().equalsIgnoreCase(mPrimaryDevice.getAddress()))) {
                mSecondaryDevice = mBluetoothAdapter.getRemoteDevice(mSecondarySelectPref.getValue());
                mSecondarySelectPref.setSummary(getDeviceDisplayName(mSecondaryDevice));
            } else {
                mSecondarySelectPref.setValue(null);
                mSecondarySelectPref.setSummary(null);
            }
        }

        updateUiState();
    }

    private void updateUiState() {
        if (!isAdded() || getContext() == null) {
            return;
        }

        if (mSecondaryActionPref != null) {
            if (mIsSharingActive) {
                mSecondaryActionPref.setTitle(R.string.audio_share_stop_action);
                String secName = (mSecondaryDevice != null) ? getDeviceDisplayName(mSecondaryDevice) : "Secondary";
                mSecondaryActionPref.setSummary(getString(R.string.audio_share_active_status) + " (" + secName + ")");
                mSecondaryActionPref.setEnabled(true);
                if (mSecondaryVolumePref != null) {
                    mSecondaryVolumePref.setEnabled(true);
                }
            } else {
                mSecondaryActionPref.setTitle(R.string.audio_share_start_action);
                mSecondaryActionPref.setSummary(R.string.audio_share_idle_status);
                // Keep clickable so clicks always fire and give feedback/diagnostics
                mSecondaryActionPref.setEnabled(true);
                if (mSecondaryVolumePref != null) {
                    mSecondaryVolumePref.setEnabled(false);
                }
            }
        }
        if (mMasterSwitch != null) {
            mMasterSwitch.setChecked(mIsSharingActive);
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
            if (!enabled && mIsSharingActive && mSharingManager != null) {
                mSharingManager.stopSharing();
            }
            return true;
        } else if (preference == mPrimaryVolumePref) {
            int percent = (Integer) newValue;
            int maxVol = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int newVol = (percent * maxVol) / 100;
            mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0);
            return true;
        } else if (preference == mSecondarySelectPref) {
            String address = (String) newValue;
            if (!TextUtils.isEmpty(address) && mBluetoothAdapter != null) {
                mSecondaryDevice = mBluetoothAdapter.getRemoteDevice(address);
                mSecondarySelectPref.setValue(address);
                mSecondarySelectPref.setSummary(getDeviceDisplayName(mSecondaryDevice));
                updateUiState();
            }
            return true;
        } else if (preference == mSecondaryVolumePref) {
            int volume = (Integer) newValue;
            if (mSecondaryDevice != null && mSharingManager != null) {
                mSharingManager.setSharingDeviceAbsoluteVolume(mSecondaryDevice, volume);
            }
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
        }
        return false;
    }
}

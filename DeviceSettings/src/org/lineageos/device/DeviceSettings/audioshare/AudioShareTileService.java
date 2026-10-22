/*
 * Copyright (C) 2026 LunarisOS
 * Quick Settings Tile Service for Dual Bluetooth Audio Sharing
 */

package org.lineageos.device.DeviceSettings.audioshare;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.text.TextUtils;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.oplus.bluetooth.OplusA2dpSharingManager;
import org.lineageos.device.DeviceSettings.R;

import java.lang.reflect.Method;
import java.util.List;

public class AudioShareTileService extends TileService {
    private static final String TAG = "AudioShareTileService";
    private static final String PREF_MASTER_SWITCH = "audio_share_master_switch";
    private static final long DEBOUNCE_DELAY_MS = 1200L;

    private OplusA2dpSharingManager mSharingManager;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothProfile mBluetoothA2dp;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long mLastClickTime = 0L;

    private final BroadcastReceiver mBtReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateTile();
        }
    };

    private final BluetoothProfile.ServiceListener mProfileListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.A2DP) {
                mBluetoothA2dp = proxy;
                updateTile();
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.A2DP) {
                mBluetoothA2dp = null;
                updateTile();
            }
        }
    };

    private final OplusA2dpSharingManager.StateCallback mStateCallback = new OplusA2dpSharingManager.StateCallback() {
        @Override
        public void onSharingStarted(int role, int type, int reason, BluetoothDevice primary, BluetoothDevice secondary) {
            mHandler.post(() -> updateTile());
        }

        @Override
        public void onSharingStopped(int role, int type, int reason, BluetoothDevice primary, BluetoothDevice secondary) {
            mHandler.post(() -> updateTile());
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        OplusA2dpSharingManager.init(this);
        mSharingManager = OplusA2dpSharingManager.getInstance();
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter != null) {
            mBluetoothAdapter.getProfileProxy(this, mProfileListener, BluetoothProfile.A2DP);
        }
        if (mSharingManager != null) {
            mSharingManager.registerStateCallback(null, mStateCallback);
        }
    }

    @Override
    public void onDestroy() {
        if (mSharingManager != null) {
            mSharingManager.unregisterStateCallback(mStateCallback);
        }
        if (mBluetoothAdapter != null && mBluetoothA2dp != null) {
            mBluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, mBluetoothA2dp);
        }
        super.onDestroy();
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        if (mBluetoothA2dp == null && mBluetoothAdapter != null) {
            mBluetoothAdapter.getProfileProxy(this, mProfileListener, BluetoothProfile.A2DP);
        }
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED);
            registerReceiver(mBtReceiver, filter);
        } catch (Exception e) {
            Log.w(TAG, "Failed to register mBtReceiver", e);
        }
        updateTile();
    }

    @Override
    public void onStopListening() {
        try {
            unregisterReceiver(mBtReceiver);
        } catch (Exception ignored) {}
        super.onStopListening();
    }

    @Override
    public void onClick() {
        long now = SystemClock.elapsedRealtime();
        if (now - mLastClickTime < DEBOUNCE_DELAY_MS) {
            Log.d(TAG, "onClick: debounced, ignoring tap within " + (now - mLastClickTime) + "ms");
            return;
        }
        mLastClickTime = now;

        if (mSharingManager == null || mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            launchActivity();
            return;
        }

        boolean offloadDisabled = android.os.SystemProperties.getBoolean("persist.bluetooth.a2dp_offload.disabled", false);
        if (!offloadDisabled) {
            Toast.makeText(this, R.string.audio_share_hw_offload_must_be_disabled, Toast.LENGTH_LONG).show();
            launchActivity();
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean isSharing = mSharingManager.inSharingMode();

        if (isSharing) {
            Log.i(TAG, "onClick: stopping sharing");
            mSharingManager.stopSharing();
            updateTile();
        } else {
            BluetoothDevice secondary = getCandidateSecondaryDevice();
            if (secondary == null) {
                Log.i(TAG, "onClick: no candidate secondary device, toggling master switch and opening UI");
                boolean curMaster = (mSharingManager != null && mSharingManager.isA2dpSharingEnabled());
                boolean newMaster = !curMaster;
                if (mSharingManager != null) {
                    mSharingManager.setA2dpSharingEnabled(newMaster);
                }
                launchActivity();
                return;
            }

            Log.i(TAG, "onClick: enabling master switch and starting sharing to " + secondary.getAddress());
            if (mSharingManager != null) {
                mSharingManager.setA2dpSharingEnabled(true);
                mSharingManager.startSharing(secondary, OplusA2dpSharingManager.SHARING_TYPE_BONDED);
            }
            updateTile();
        }
    }

    private void launchActivity() {
        Intent intent = new Intent(this, AudioShareActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityAndCollapse(intent);
    }

    private BluetoothDevice getActiveA2dpDevice() {
        if (mBluetoothA2dp == null) return null;
        try {
            Method m = mBluetoothA2dp.getClass().getMethod("getActiveDevice");
            Object dev = m.invoke(mBluetoothA2dp);
            if (dev instanceof BluetoothDevice) return (BluetoothDevice) dev;
        } catch (Throwable ignored) {}
        return null;
    }

    private BluetoothDevice getCandidateSecondaryDevice() {
        if (mBluetoothA2dp == null || mBluetoothAdapter == null) return null;
        List<BluetoothDevice> connected = mBluetoothA2dp.getConnectedDevices();
        if (connected == null || connected.isEmpty()) return null;

        BluetoothDevice active = getActiveA2dpDevice();

        String lastSec = PreferenceManager.getDefaultSharedPreferences(this)
                .getString("last_secondary_device", null);
        if (lastSec != null) {
            for (BluetoothDevice dev : connected) {
                if (dev.getAddress().equalsIgnoreCase(lastSec)
                        && (active == null || !dev.getAddress().equalsIgnoreCase(active.getAddress()))) {
                    return dev;
                }
            }
        }

        for (BluetoothDevice dev : connected) {
            if (active == null || !dev.getAddress().equalsIgnoreCase(active.getAddress())) {
                return dev;
            }
        }
        return null;
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean isSharing = (mSharingManager != null) && mSharingManager.inSharingMode();
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_audio_share));
        tile.setLabel(getString(R.string.audio_share_tile_label));

        if (isSharing) {
            tile.setState(Tile.STATE_ACTIVE);
            List<BluetoothDevice> devs = (mSharingManager != null) ? mSharingManager.getSharingDevices() : null;
            if (devs != null && devs.size() >= 2) {
                String d1 = getDeviceName(devs.get(0));
                String d2 = getDeviceName(devs.get(1));
                tile.setSubtitle(getString(R.string.audio_share_tile_streaming_to, d1, d2));
            } else {
                tile.setSubtitle(getString(R.string.audio_share_active_status));
            }
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            BluetoothDevice sec = getCandidateSecondaryDevice();
            if (sec != null) {
                tile.setSubtitle(getDeviceName(sec));
            } else {
                tile.setSubtitle(getString(R.string.audio_share_idle_status));
            }
        }
        tile.updateTile();
    }

    private String getDeviceName(BluetoothDevice dev) {
        if (dev == null) return "Device";
        String alias = dev.getAlias();
        if (!TextUtils.isEmpty(alias)) return alias;
        String name = dev.getName();
        if (!TextUtils.isEmpty(name)) return name;
        return dev.getAddress();
    }
}

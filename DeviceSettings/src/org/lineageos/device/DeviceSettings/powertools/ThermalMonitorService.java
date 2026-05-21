/*
 * Copyright (C) 2025 kenrow214
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.DeviceSettings.powertools;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import org.lineageos.device.DeviceSettings.R;

import android.content.BroadcastReceiver;
import android.content.IntentFilter;

public class ThermalMonitorService extends Service {

    private static final String TAG = "ThermalMonitorService";
    private static final String NOTIF_CHANNEL = "thermal_monitor";
    private static final int NOTIF_ID = 2001;

    private static final String BATTERY_TEMP_PATH = "/sys/class/power_supply/battery/temp";
    private static final String CPU_TEMP_PATH     = "/sys/class/thermal/thermal_zone43/temp";
    private static final String GPU_TEMP_PATH     = "/sys/class/thermal/thermal_zone54/temp";
    private static final String SKIN_TEMP_PATH    = "/sys/class/thermal/thermal_zone90/temp";

    public static final int STATE_NORMAL = 0;
    public static final int STATE_LIGHT  = 1;
    public static final int STATE_MEDIUM = 2;
    public static final int STATE_HEAVY  = 3;

    private static final long ESCALATE_COOLDOWN_MS = 10_000L;

    private static final int BG_LIMIT_NORMAL = -1;
    private static final int BG_LIMIT_MEDIUM = 4;
    private static final int BG_LIMIT_HEAVY  = 2;

    private static final int[] SETTING_BLUR_DISABLE = {0, 0, 1, 1};
    private static final String[] STATE_LABELS = {
        "no throttle", "LIGHT throttle", "MEDIUM throttle", "HEAVY throttle"
    };

    private static volatile int   sCurrentState = -1;
    private static volatile float sBatteryTempC;
    private static volatile float sCpuTempC;
    private static volatile float sGpuTempC;
    private static volatile float sSkinTempC;
    private static volatile float sEffectiveTempC;
    private boolean mNotifDismissed = false;

    private HandlerThread mWorkerThread;
    private Handler       mHandler;
    private Runnable      mMonitorRunnable;
    private boolean       mFirstTick = true;
    private long          mLastStateChangeMs;
    private int           mSavedBgLimit = -1;

    private final BroadcastReceiver mDismissReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            mNotifDismissed = true;
        }
    };

    public static int   getCurrentState()   { return Math.max(0, sCurrentState); }
    public static float getBatteryTempC()   { return sBatteryTempC; }
    public static float getCpuTempC()       { return sCpuTempC; }
    public static float getGpuTempC()       { return sGpuTempC; }
    public static float getEffectiveTempC() { return sEffectiveTempC; }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Starting thermal service");
        BlurUtils.clearPowersaveBackup(this);
        setupNotificationChannel();
        startForegroundServiceSafe();

        mWorkerThread = new HandlerThread("ThermalMonitorThread", Process.THREAD_PRIORITY_BACKGROUND);
        mWorkerThread.start();
        mHandler = new Handler(mWorkerThread.getLooper());
        mLastStateChangeMs = SystemClock.elapsedRealtime();

        registerReceiver(mDismissReceiver,
                new IntentFilter("org.lineageos.device.THERMAL_NOTIF_DISMISSED"),
                android.content.Context.RECEIVER_NOT_EXPORTED);

        mSavedBgLimit = Settings.Global.getInt(getContentResolver(),
                Settings.Global.ALWAYS_FINISH_ACTIVITIES, -1);

        startMonitoring();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopMonitoring();
        try { unregisterReceiver(mDismissReceiver); } catch (Exception ignored) {}
        if (mHandler != null) {
            mHandler.post(this::resetToNormal);
            mWorkerThread.quitSafely();
        }
        stopForeground(true);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(NOTIF_ID);
            nm.cancel(STATUS_NOTIF_ID);
        }
        Log.i(TAG, "Stopped thermal service");
        super.onDestroy();
    }

    private void startMonitoring() {
        mFirstTick = true;
        mMonitorRunnable = new Runnable() {
            @Override public void run() {
                readTemperatures();

                int newState = evaluateState();
                applyIfAllowed(newState);

                if (mFirstTick) {
                    mFirstTick = false;
                    updateNotificationTemp();
                }
                mHandler.postDelayed(this, getPollingMs());
            }
        };
        mHandler.post(mMonitorRunnable);
    }

    private void stopMonitoring() {
        if (mHandler != null && mMonitorRunnable != null)
            mHandler.removeCallbacks(mMonitorRunnable);
    }

    private void readTemperatures() {
        sBatteryTempC = SysfsUtils.readInt(BATTERY_TEMP_PATH, 0) / 10f;

        int rawCPU = SysfsUtils.readInt(CPU_TEMP_PATH, 0);
        sCpuTempC = rawCPU > 1000 ? rawCPU / 1000f : rawCPU / 10f;

        int rawGPU = SysfsUtils.readInt(GPU_TEMP_PATH, 0);
        sGpuTempC = rawGPU > 1000 ? rawGPU / 1000f : rawGPU / 10f;

        int rawSkin = SysfsUtils.readInt(SKIN_TEMP_PATH, 0);
        sSkinTempC = rawSkin > 1000 ? rawSkin / 1000f : rawSkin / 10f;

        sEffectiveTempC = Math.max(sSkinTempC, sBatteryTempC);
    }

    private int evaluateState() {
        float skin = sSkinTempC;
        float bat  = sBatteryTempC;
        if (skin >= 54f || (bat >= 50f && skin >= 48f))  return STATE_HEAVY;
        if (skin >= 48f || bat >= 50f)                    return STATE_MEDIUM;
        if (skin >= 44f || bat >= 45f)                    return STATE_LIGHT;

        return STATE_NORMAL;
    }

    private void applyIfAllowed(int target) {
        int cur = Math.max(0, sCurrentState);
        if (target == cur) return;

        long now = SystemClock.elapsedRealtime();
        long elapsed = now - mLastStateChangeMs;

        if (target > cur) {
            if (elapsed >= ESCALATE_COOLDOWN_MS) commitState(target, now);
        } else {
            commitState(target, now);
        }
    }

    private void commitState(int state, long now) {
        sCurrentState = state;
        mLastStateChangeMs = now;

        applyHardware(state);
        applySettings(state);

        Log.i(TAG, String.format("Thermal → %s (Skin:%.1f Bat:%.1f)",
                STATE_LABELS[state], sSkinTempC, sBatteryTempC));

        mNotifDismissed = false;
        updateNotification(STATE_LABELS[state]);
    }

    private void applyHardware(int state) {
        try {
            SystemProperties.set("sys.thermal_state", String.valueOf(state));
        } catch (Exception e) {
            Log.e(TAG, "Failed to set thermal_state", e);
        }
    }

    private void applySettings(int state) {
        try {
            boolean batterySaver = (state == STATE_HEAVY) || (state == STATE_MEDIUM && sBatteryTempC >= 50f);
            Settings.Global.putInt(getContentResolver(), "low_power", batterySaver ? 1 : 0);
            BlurUtils.setBlurDisabled(this, SETTING_BLUR_DISABLE[state] == 1);

            int bgLimit;
            switch (state) {
                case STATE_HEAVY:  bgLimit = BG_LIMIT_HEAVY;  break;
                case STATE_MEDIUM: bgLimit = BG_LIMIT_MEDIUM; break;
                default:           bgLimit = BG_LIMIT_NORMAL; break;
            }
            setBackgroundProcessLimit(bgLimit);
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply settings", e);
        }
    }

    private void setBackgroundProcessLimit(int limit) {
        try {
            if (limit >= 0) {
                Settings.Global.putInt(getContentResolver(), "activity_manager_constants_bg_limit", limit);
                Log.d(TAG, "Background process limit set to " + limit);
            } else {
                Settings.Global.putInt(getContentResolver(), "activity_manager_constants_bg_limit", 32);
                Log.d(TAG, "Background process limit restored to default");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to set bg process limit", e);
        }
    }

    private void resetToNormal() {
        applyHardware(STATE_NORMAL);
        try {
            Settings.Global.putInt(getContentResolver(), "low_power", 0);
            BlurUtils.setBlurDisabled(this, false);
            setBackgroundProcessLimit(BG_LIMIT_NORMAL);
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore normal settings", e);
        }
        sCurrentState = STATE_NORMAL;
        try {
            SystemProperties.set("sys.perf_mode_active", String.valueOf(PowerProfileUtil.MODE_BALANCE));
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore perf mode", e);
        }
    }

    private int getPollingMs() {
        int s = Math.max(0, sCurrentState);
        return s == STATE_HEAVY ? 2000 : s == STATE_MEDIUM ? 3000 : s == STATE_LIGHT ? 4000 : 6000;
    }

    private static final int STATUS_NOTIF_ID = 2002;

    private void startForegroundServiceSafe() {
        Notification n = new Notification.Builder(this, NOTIF_CHANNEL)
                .setSmallIcon(R.drawable.ic_thermal_balance)
                .setContentTitle("Thermal Monitor")
                .setContentText("Active")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, 0);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void setupNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                NOTIF_CHANNEL,
                getString(R.string.auto_thermal_notif_channel),
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private void updateNotification(String stateLabel) {
        if (mNotifDismissed) return;
        Notification n = new Notification.Builder(this, NOTIF_CHANNEL)
                .setSmallIcon(R.drawable.ic_thermal_balance)
                .setContentTitle("Auto Thermal")
                .setContentText(stateLabel)
                .setOngoing(false)
                .setAutoCancel(false)
                .setDeleteIntent(getDismissIntent())
                .build();
        getSystemService(NotificationManager.class).notify(STATUS_NOTIF_ID, n);
    }

    private android.app.PendingIntent getDismissIntent() {
        Intent i = new Intent("org.lineageos.device.THERMAL_NOTIF_DISMISSED");
        i.setPackage(getPackageName());
        return android.app.PendingIntent.getBroadcast(this, 0, i,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
    }

    private void updateNotificationTemp() {
        if (mNotifDismissed) return;
        int state = getCurrentState();
        String label = (state >= 0 && state < STATE_LABELS.length) ? STATE_LABELS[state] : "Normal";
        updateNotification(label);
    }
}
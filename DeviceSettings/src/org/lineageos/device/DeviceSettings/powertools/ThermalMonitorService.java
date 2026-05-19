/*
 * Copyright (C) 2025 kenrow214
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.DeviceSettings.powertools;

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
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import org.lineageos.device.DeviceSettings.R;

public class ThermalMonitorService extends Service {

    private static final String TAG = "ThermalMonitorService";

    private static final String NOTIF_CHANNEL = "thermal_monitor";
    private static final int NOTIF_ID = 2001;

    private static final String BATTERY_TEMP_PATH = "/sys/class/power_supply/battery/temp";
    private static final String CPU_TEMP_PATH = "/sys/class/thermal/thermal_zone39/temp";
    private static final String GPU_TEMP_PATH = "/sys/class/thermal/thermal_zone54/temp";

    public static final int STATE_NORMAL = 0;
    public static final int STATE_LIGHT = 1;
    public static final int STATE_MEDIUM = 2;
    public static final int STATE_HEAVY = 3;

    public static final int THRESH_LIGHT = 45;
    public static final int THRESH_MEDIUM = 49;
    public static final int THRESH_HEAVY = 55;

    // Hysteresis: must drop this many degrees BELOW the threshold to step down.
    // Prevents rapid flapping when CPU temp hovers around a boundary.
    private static final int HYSTERESIS = 3;

    // Debounce: new state must be seen for this many consecutive polls before applying.
    // Prevents reacting to transient CPU spikes (45→80→50 in 1 second).
    private static final int DEBOUNCE_TICKS = 2;


    // HEAVY throttle should NOT enable battery saver — that makes the phone
    // terribly slow and unusable. Only disable blur effects at high temps.
    private static final int[] SETTING_LOW_POWER = {0, 0, 0, 0};
    private static final int[] SETTING_BLUR_DISABLE = {0, 0, 1, 1}; // 1 at MEDIUM and HEAVY
    
    private static final String[] STATE_LABELS = {
        "no throttle",
        "LIGHT throttle (\u226545\u00b0C)",
        "MEDIUM throttle (\u226549\u00b0C)",
        "HEAVY throttle (\u226555\u00b0C)"
    };

    private static volatile int sCurrentState = -1; // -1 forces initial application
    private static volatile float sBatteryTempC = 0f;
    private static volatile float sCpuTempC = 0f;
    private static volatile float sGpuTempC = 0f;
    private static volatile float sEffectiveTempC = 0f;

    private HandlerThread mWorkerThread;
    private Handler mHandler;
    private Runnable mMonitorRunnable;
    private boolean mFirstTick = true;
    private int mPendingState = -1;   // state being debounced
    private int mDebounceCount = 0;   // consecutive ticks at mPendingState

    public static int getCurrentState() { return Math.max(0, sCurrentState); }
    public static float getBatteryTempC() { return sBatteryTempC; }
    public static float getCpuTempC() { return sCpuTempC; }
    public static float getGpuTempC() { return sGpuTempC; }
    public static float getEffectiveTempC() { return sEffectiveTempC; }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Starting thermal service");

        // Discard any stale powersave blur backup left from before reboot.
        // This prevents the first thermal tick from accidentally restoring blur.
        BlurUtils.clearPowersaveBackup(this);

        setupNotificationChannel();
        startForegroundServiceSafe();
        
        mWorkerThread = new HandlerThread("ThermalMonitorThread", Process.THREAD_PRIORITY_BACKGROUND);
        mWorkerThread.start();
        mHandler = new Handler(mWorkerThread.getLooper());
        
        startMonitoring();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopMonitoring();
        
        if (mHandler != null) {
            mHandler.post(this::resetHardwareToNormal);
            mWorkerThread.quitSafely();
        }

        stopForeground(true);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIF_ID);

        Log.i(TAG, "Stopped thermal service");
        super.onDestroy();
    }


    private void startMonitoring() {
        mFirstTick = true;
        mMonitorRunnable = new Runnable() {
            @Override
            public void run() {
                readAllTemperatures();

                // Use the HOTTEST sensor to drive throttle decisions.
                float effectiveF = Math.max(sBatteryTempC,
                                      Math.max(sCpuTempC, sGpuTempC));
                sEffectiveTempC = effectiveF;
                int effectiveC = Math.round(effectiveF);

                int newState = calculateState(effectiveC);
                
                applyStateIfChanged(newState);

                if (mFirstTick) {
                    mFirstTick = false;
                    updateNotificationTemp();
                }

                mHandler.postDelayed(this, getPollingDelayMs(effectiveC));
            }
        };
        mHandler.post(mMonitorRunnable);
    }

    private void stopMonitoring() {
        if (mHandler != null && mMonitorRunnable != null) {
            mHandler.removeCallbacks(mMonitorRunnable);
        }
    }

    private void readAllTemperatures() {
        sBatteryTempC = SysfsUtils.readInt(BATTERY_TEMP_PATH, 0) / 10f;
        
        int rawCPU = SysfsUtils.readInt(CPU_TEMP_PATH, 0);
        sCpuTempC = rawCPU > 1000 ? rawCPU / 1000f : rawCPU / 10f;

        int rawGPU = SysfsUtils.readInt(GPU_TEMP_PATH, 0);
        sGpuTempC = rawGPU > 1000 ? rawGPU / 1000f : rawGPU / 10f;
    }

    /**
     * Calculate target state with hysteresis.
     * Stepping UP uses the normal thresholds.
     * Stepping DOWN requires the temp to drop HYSTERESIS degrees
     * below the current state's threshold to prevent flapping.
     */
    private int calculateState(int tempC) {
        int cur = Math.max(0, sCurrentState);

        // --- ESCALATION: always step UP immediately ---
        if (tempC >= THRESH_HEAVY)  return STATE_HEAVY;
        if (tempC >= THRESH_MEDIUM) return STATE_MEDIUM;
        if (tempC >= THRESH_LIGHT)  return STATE_LIGHT;

        // --- DE-ESCALATION: require HYSTERESIS drop below current threshold ---
        // If we're in HEAVY, stay in HEAVY until we drop below THRESH_HEAVY - HYSTERESIS
        switch (cur) {
            case STATE_HEAVY:
                if (tempC >= THRESH_HEAVY - HYSTERESIS) return STATE_HEAVY;
                // fell through heavy hysteresis, check medium
                if (tempC >= THRESH_MEDIUM - HYSTERESIS) return STATE_MEDIUM;
                if (tempC >= THRESH_LIGHT - HYSTERESIS)  return STATE_LIGHT;
                return STATE_NORMAL;
            case STATE_MEDIUM:
                if (tempC >= THRESH_MEDIUM - HYSTERESIS) return STATE_MEDIUM;
                if (tempC >= THRESH_LIGHT - HYSTERESIS)  return STATE_LIGHT;
                return STATE_NORMAL;
            case STATE_LIGHT:
                if (tempC >= THRESH_LIGHT - HYSTERESIS) return STATE_LIGHT;
                return STATE_NORMAL;
            default:
                return STATE_NORMAL;
        }
    }

    private int getPollingDelayMs(int effectiveC) {
        if (effectiveC >= THRESH_HEAVY) return 1500;
        if (effectiveC >= THRESH_MEDIUM) return 2000;
        if (effectiveC >= THRESH_LIGHT) return 2500;
        return 4000; // Normal polling interval
    }

    private void applyStateIfChanged(int targetState) {
        if (targetState == sCurrentState) {
            // Stable — reset debounce
            mPendingState = -1;
            mDebounceCount = 0;
            return;
        }

        // Debounce: must see the same new state for DEBOUNCE_TICKS consecutive polls
        if (targetState == mPendingState) {
            mDebounceCount++;
        } else {
            mPendingState = targetState;
            mDebounceCount = 1;
        }

        if (mDebounceCount < DEBOUNCE_TICKS) {
            Log.d(TAG, String.format("Debounce: want %d, tick %d/%d (eff=%.0f°C)",
                    targetState, mDebounceCount, DEBOUNCE_TICKS, sEffectiveTempC));
            return;
        }

        // Debounce passed — commit the state change
        mPendingState = -1;
        mDebounceCount = 0;
        sCurrentState = targetState;

        applyProfileToHardware(targetState);
        updateGlobalSettings(targetState);

        // Identify which sensor is driving the throttle decision
        String hottest = (sCpuTempC >= sGpuTempC && sCpuTempC >= sBatteryTempC) ? "CPU"
                       : (sGpuTempC >= sBatteryTempC) ? "GPU" : "Battery";
        Log.i(TAG, String.format("Auto Thermal: %s=%.1f\u00b0C (Bat:%.1f CPU:%.1f GPU:%.1f) -> %s",
                hottest, sEffectiveTempC, sBatteryTempC, sCpuTempC, sGpuTempC, STATE_LABELS[targetState]));

        updateNotification(STATE_LABELS[targetState], 
            String.format("Bat:%.0f\u00b0C CPU:%.0f\u00b0C GPU:%.0f\u00b0C", sBatteryTempC, sCpuTempC, sGpuTempC));
    }

    private void applyProfileToHardware(int stateIndex) {
        try {
            SystemProperties.set("sys.thermal_state", String.valueOf(stateIndex));
        } catch (Exception e) {
            Log.e(TAG, "Failed to set thermal profile property", e);
        }
    }

    private void updateGlobalSettings(int stateIndex) {
        try {
            Settings.Global.putInt(getContentResolver(), "low_power", SETTING_LOW_POWER[stateIndex]);
            BlurUtils.setBlurDisabled(this, SETTING_BLUR_DISABLE[stateIndex] == 1);
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply global settings", e);
        }
    }

    private void resetHardwareToNormal() {
        applyProfileToHardware(STATE_NORMAL);
        updateGlobalSettings(STATE_NORMAL);
        sCurrentState = STATE_NORMAL;

        try {
            SystemProperties.set("sys.perf_mode_active", String.valueOf(PowerProfileUtil.MODE_BALANCE));
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore normal perf mode on thermal stop", e);
        }
    }


    private void startForegroundServiceSafe() {
        Notification notification = buildNotification("Thermal Monitor", "Starting...");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, 0); // 0 = no specific type
        } else {
            startForeground(NOTIF_ID, notification);
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

    private Notification buildNotification(String title, String text) {
        return new Notification.Builder(this, NOTIF_CHANNEL)
                .setSmallIcon(R.drawable.ic_thermal_balance)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String stateLabel, String tempValues) {
        Notification n = buildNotification(
                getString(R.string.auto_thermal_notif_title),
                getString(R.string.auto_thermal_notif_text, tempValues, stateLabel));
        getSystemService(NotificationManager.class).notify(NOTIF_ID, n);
    }

    private void updateNotificationTemp() {
        int state = getCurrentState();
        String temps = String.format("Bat:%.0f\u00b0C  CPU:%.0f\u00b0C  GPU:%.0f\u00b0C", 
                                     sBatteryTempC, sCpuTempC, sGpuTempC);
        
        String label = (state >= 0 && state < STATE_LABELS.length) ? STATE_LABELS[state] : "Normal";
        
        if (label.contains("(")) label = label.substring(0, label.indexOf("(")).trim();
        
        updateNotification(label, temps);
    }
}
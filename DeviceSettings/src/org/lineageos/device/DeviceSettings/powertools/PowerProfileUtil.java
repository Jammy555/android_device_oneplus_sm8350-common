/*
 * Copyright (C) 2025 kenrow214
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.DeviceSettings.powertools;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemProperties;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.device.DeviceSettings.R;
import org.lineageos.device.DeviceSettings.Utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PowerProfileUtil {

    private static final String TAG = "PowerProfileUtil";
    private static final String SYS_PROP = "sys.perf_mode_active";

    /**
     * KProfiles kernel sysfs node.
     * Mode mapping (PowerTools → KProfiles):
     *   MODE_BATTERY_SAVER (0) → kp_mode = 1  (Battery)
     *   MODE_BALANCE       (1) → kp_mode = 2  (Balanced)
     *   MODE_PERFORMANCE   (2) → kp_mode = 3  (Performance)
     *
     * Per commit 99b6b76 the kernel auto-promotes kp_mode=0 to 2,
     * so userspace must never write 0; minimum write value is 1.
     */
    private static final String KPROFILES_NODE = "/sys/kernel/kprofiles/kp_mode";

    private static final String FILE_GAME = "/proc/touchpanel/game_switch_enable";
    private static final String FILE_EDGE = "/proc/touchpanel/oplus_tp_direction";
    private static final String KEY_LAST_PROFILE = "powertools_last_profile";

    public static final int MODE_BATTERY_SAVER = 0;
    public static final int MODE_BALANCE = 1;
    public static final int MODE_PERFORMANCE = 2;
    public static final int MODE_UNKNOWN = 4;

    public static final String KEY_GPU_MIN_FREQ = "gpu_min_frequency";
    public static final String KEY_GPU_MAX_FREQ = "gpu_max_frequency";
    public static final String KEY_GPU_GOVERNOR = "gpu_governor";
    public static final String KEY_CPU_LITTLE_MIN_FREQ = "cpu_little_min_frequency";
    public static final String KEY_CPU_LITTLE_MAX_FREQ = "cpu_little_max_frequency";
    public static final String KEY_CPU_LITTLE_GOVERNOR = "cpu_little_governor";
    public static final String KEY_CPU_BIG_MIN_FREQ = "cpu_big_min_frequency";
    public static final String KEY_CPU_BIG_MAX_FREQ = "cpu_big_max_frequency";
    public static final String KEY_CPU_BIG_GOVERNOR = "cpu_big_governor";
    public static final String KEY_CPU_PRIME_MIN_FREQ = "cpu_prime_min_frequency";
    public static final String KEY_CPU_PRIME_MAX_FREQ = "cpu_prime_max_frequency";
    public static final String KEY_CPU_PRIME_GOVERNOR = "cpu_prime_governor";
    public static final String KEY_IO_SCHEDULER = "io_scheduler";

    public static final String[] PERSIST_KEYS = {
        KEY_CPU_LITTLE_MIN_FREQ, KEY_CPU_LITTLE_MAX_FREQ, KEY_CPU_LITTLE_GOVERNOR,
        KEY_CPU_BIG_MIN_FREQ, KEY_CPU_BIG_MAX_FREQ, KEY_CPU_BIG_GOVERNOR,
        KEY_CPU_PRIME_MIN_FREQ, KEY_CPU_PRIME_MAX_FREQ, KEY_CPU_PRIME_GOVERNOR,
        KEY_GPU_MIN_FREQ, KEY_GPU_MAX_FREQ, KEY_GPU_GOVERNOR,
        KEY_IO_SCHEDULER
    };

    private static final Map<String, String[]> PROFILE_DEFAULTS = new HashMap<>();
    static {
        PROFILE_DEFAULTS.put(KEY_CPU_LITTLE_GOVERNOR, new String[]{"schedutil", "schedutil", "performance"});
        PROFILE_DEFAULTS.put(KEY_CPU_BIG_GOVERNOR,    new String[]{"schedutil", "schedutil", "schedutil"});
        PROFILE_DEFAULTS.put(KEY_CPU_PRIME_GOVERNOR,  new String[]{"schedutil", "schedutil", "schedutil"});
        PROFILE_DEFAULTS.put(KEY_GPU_GOVERNOR,        new String[]{"userspace", "msm-adreno-tz", "performance"});
        PROFILE_DEFAULTS.put(KEY_IO_SCHEDULER,        new String[]{"bfq", "bfq", "kyber"});
        
        PROFILE_DEFAULTS.put(KEY_CPU_LITTLE_MIN_FREQ, new String[]{"300000", "300000", "300000"});
        PROFILE_DEFAULTS.put(KEY_CPU_BIG_MIN_FREQ,    new String[]{"710400", "710400", "844800"});
        PROFILE_DEFAULTS.put(KEY_CPU_PRIME_MIN_FREQ,  new String[]{"844800", "844800", "960000"});
        
        PROFILE_DEFAULTS.put(KEY_CPU_LITTLE_MAX_FREQ, new String[]{"1804800", "1804800", "1804800"});
        PROFILE_DEFAULTS.put(KEY_CPU_BIG_MAX_FREQ,    new String[]{"2227200", "2419200", "2419200"});
        PROFILE_DEFAULTS.put(KEY_CPU_PRIME_MAX_FREQ,  new String[]{"2592000", "2841600", "2841600"});
        
        PROFILE_DEFAULTS.put(KEY_GPU_MIN_FREQ,        new String[]{"315000000", "315000000", "315000000"});
        PROFILE_DEFAULTS.put(KEY_GPU_MAX_FREQ,        new String[]{"579000000", "840000000", "840000000"});
    }

    private final Context mContext;
    private int mCurrentMode = MODE_BALANCE;
    private final String[] mModes;

    public PowerProfileUtil(Context context) {
        mContext = context;
        mModes = new String[]{
                mContext.getString(R.string.powerprofile_mode_battery_saver),
                mContext.getString(R.string.powerprofile_mode_balance),
                mContext.getString(R.string.powerprofile_mode_performance),
                "", // Blank placeholder for index 3
                mContext.getString(R.string.powerprofile_mode_unknown)
        };
    }



    // -------------------------------------------------------------------------
    // KProfiles ↔ PowerTools mode mapping helpers
    // -------------------------------------------------------------------------

    /**
     * Converts a PowerTools mode constant (0-2) to a KProfiles sysfs value (1-3).
     * Battery Saver (0) → 1, Balanced (1) → 2, Performance (2) → 3.
     */
    private static int ptModeToKpValue(int ptMode) {
        // Simple +1 shift; ptMode is always 0, 1, or 2 from PowerTools constants.
        return ptMode + 1;
    }

    /**
     * Converts a KProfiles sysfs value (1-3) back to a PowerTools mode constant (0-2).
     * Returns MODE_BALANCE as a safe fallback for any unexpected value.
     */
    private static int kpValueToPtMode(int kpValue) {
        if (kpValue >= 1 && kpValue <= 3) return kpValue - 1;
        return MODE_BALANCE; // safe fallback
    }

    /**
     * Returns the currently active PowerTools profile mode.
     *
     * <p>Primary source: reads {@code /sys/kernel/kprofiles/kp_mode} and converts
     * the KProfiles value (1/2/3) back to the PowerTools constant (0/1/2).</p>
     *
     * <p>Fallback: reads {@code sys.perf_mode_active} system property if the sysfs
     * node is unavailable (e.g., on a kernel without KProfiles compiled in).</p>
     */
    public int getCurrentMode() {
        // Primary: KProfiles sysfs node
        String raw = SysfsUtils.readLine(KPROFILES_NODE);
        if (raw != null && !raw.isEmpty()) {
            try {
                int kpValue = Integer.parseInt(raw.trim());
                return kpValueToPtMode(kpValue);
            } catch (NumberFormatException ignored) {
                Log.w(TAG, "Unexpected kp_mode value: " + raw + ", falling back to sysprop");
            }
        }
        // Fallback: sysprop mirror (written on every setMode call below)
        return SystemProperties.getInt(SYS_PROP, MODE_BALANCE);
    }

    public boolean setMode(int mode) {
        mCurrentMode = mode;
        saveLastProfile(mode);

        boolean success = setPerformanceModeActive(mode);
        syncUiToMode(mode);

        applyUserTouchPanel();
        BlurUtils.setBlurDisabled(mContext, mode == MODE_BATTERY_SAVER);

        return success;
    }

    /**
     * Same as {@link #setMode(int)} but does NOT touch blur.
     * Used on boot so that Settings.Global.disable_window_blurs
     * is left exactly as the system persisted it across reboot.
     */
    public boolean setModeOnBoot(int mode) {
        mCurrentMode = mode;
        saveLastProfile(mode);

        boolean success = setPerformanceModeActive(mode);
        syncUiToMode(mode);

        applyUserTouchPanel();
        // Intentionally skip BlurUtils — let Settings.Global persist naturally.

        return success;
    }


    private void saveLastProfile(int mode) {
        SharedPreferences prefs = mContext.getSharedPreferences(
                mContext.getPackageName() + "_preferences", Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LAST_PROFILE, String.valueOf(mode)).apply();
    }
    private final List<String> mFallbackMessages = new ArrayList<>();

    public void syncUiToMode(int mode) {
        mFallbackMessages.clear();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        SharedPreferences.Editor editor = prefs.edit();

        for (String key : PERSIST_KEYS) {
            String val = getValidatedStockValueForMode(mode, key);
            editor.putString(key, val);
        }

        editor.apply();
    }

    public List<String> getAndClearFallbacks() {
        List<String> copy = new ArrayList<>(mFallbackMessages);
        mFallbackMessages.clear();
        return copy;
    }

    public String getStockValueForMode(int mode, String key) {
        int targetIndex = (mode == MODE_BATTERY_SAVER || mode == MODE_PERFORMANCE) ? mode : MODE_BALANCE;

        String[] values = PROFILE_DEFAULTS.get(key);
        return values != null ? values[targetIndex] : "";
    }

    public String getValidatedStockValueForMode(int mode, String key) {
        return validateKernelValue(key, getStockValueForMode(mode, key));
    }

    private String validateKernelValue(String key, String requested) {
        String[] sysfsPaths = getSysfsPathsForKey(key);
        if (sysfsPaths == null || requested == null || requested.isEmpty()) return requested;

        String[] available = KernelOptionUtils.readAvailableValues(sysfsPaths, shouldDropNone(key));
        if (available.length == 0) return requested;

        for (String value : available) {
            if (value.equals(requested)) return requested;
        }

        String fallback = isFrequencyKey(key)
                ? findNearestFrequencyValue(key, requested, available)
                : available[0];
        // mFallbackMessages.add(buildFallbackMessage(key, requested, fallback));
        // Log.w(TAG, mFallbackMessages.get(mFallbackMessages.size() - 1));
        return fallback;
    }

    private String[] getSysfsPathsForKey(String key) {
        switch (key) {
            case KEY_CPU_LITTLE_MIN_FREQ:
            case KEY_CPU_LITTLE_MAX_FREQ:
                return new String[] { KernelOptionUtils.CPU_LITTLE_AVAILABLE_FREQUENCIES };
            case KEY_CPU_BIG_MIN_FREQ:
            case KEY_CPU_BIG_MAX_FREQ:
                return new String[] { KernelOptionUtils.CPU_BIG_AVAILABLE_FREQUENCIES };
            case KEY_CPU_PRIME_MIN_FREQ:
            case KEY_CPU_PRIME_MAX_FREQ:
                return new String[] { KernelOptionUtils.CPU_PRIME_AVAILABLE_FREQUENCIES };
            case KEY_CPU_LITTLE_GOVERNOR:
                return new String[] { KernelOptionUtils.CPU_LITTLE_AVAILABLE_GOVERNORS };
            case KEY_CPU_BIG_GOVERNOR:
                return new String[] { KernelOptionUtils.CPU_BIG_AVAILABLE_GOVERNORS };
            case KEY_CPU_PRIME_GOVERNOR:
                return new String[] { KernelOptionUtils.CPU_PRIME_AVAILABLE_GOVERNORS };
            case KEY_GPU_MIN_FREQ:
            case KEY_GPU_MAX_FREQ:
                return KernelOptionUtils.GPU_AVAILABLE_FREQUENCY_PATHS;
            case KEY_GPU_GOVERNOR:
                return new String[] { KernelOptionUtils.GPU_AVAILABLE_GOVERNORS };
            case KEY_IO_SCHEDULER:
                return new String[] { KernelOptionUtils.IO_SCHEDULER };
            default: return null;
        }
    }

    private boolean shouldDropNone(String key) {
        return KEY_IO_SCHEDULER.equals(key);
    }

    private boolean isFrequencyKey(String key) {
        return key.endsWith("_frequency");
    }

    private String findNearestFrequencyValue(String key, String requested, String[] available) {
        try {
            long requestedValue = Long.parseLong(requested);
            String fallback = null;
            long fallbackValue = 0L;

            for (String value : available) {
                long candidate = Long.parseLong(value);
                if (key.contains("_min_frequency")) {
                    if (candidate >= requestedValue && (fallback == null || candidate < fallbackValue)) {
                        fallback = value;
                        fallbackValue = candidate;
                    }
                } else if (key.contains("_max_frequency")) {
                    if (candidate <= requestedValue && (fallback == null || candidate > fallbackValue)) {
                        fallback = value;
                        fallbackValue = candidate;
                    }
                }
            }

            if (fallback != null) return fallback;

            fallback = available[0];
            fallbackValue = Long.parseLong(fallback);
            for (String value : available) {
                long candidate = Long.parseLong(value);
                if (key.contains("_min_frequency")) {
                    if (candidate > fallbackValue) {
                        fallback = value;
                        fallbackValue = candidate;
                    }
                } else if (candidate < fallbackValue) {
                    fallback = value;
                    fallbackValue = candidate;
                }
            }
            return fallback;
        } catch (NumberFormatException e) {
            return available[0];
        }
    }

    private String buildFallbackMessage(String key, String requested, String fallback) {
        return mContext.getString(R.string.powertools_fallback_message,
                KernelOptionUtils.displayValue(requested),
                getPreferenceLabel(key),
                KernelOptionUtils.displayValue(fallback));
    }

    private String getPreferenceLabel(String key) {
        if (KEY_IO_SCHEDULER.equals(key)) return mContext.getString(R.string.powertools_label_io_scheduler);
        if (KEY_GPU_MIN_FREQ.equals(key)) return mContext.getString(R.string.powertools_label_gpu_min_frequency);
        if (KEY_GPU_MAX_FREQ.equals(key)) return mContext.getString(R.string.powertools_label_gpu_max_frequency);
        if (KEY_GPU_GOVERNOR.equals(key)) return mContext.getString(R.string.powertools_label_gpu_governor);
        if (KEY_CPU_LITTLE_MIN_FREQ.equals(key)) return mContext.getString(R.string.powertools_label_cpu_little_min_frequency);
        if (KEY_CPU_LITTLE_MAX_FREQ.equals(key)) return mContext.getString(R.string.powertools_label_cpu_little_max_frequency);
        if (KEY_CPU_LITTLE_GOVERNOR.equals(key)) return mContext.getString(R.string.powertools_label_cpu_little_governor);
        if (KEY_CPU_BIG_MIN_FREQ.equals(key)) return mContext.getString(R.string.powertools_label_cpu_big_min_frequency);
        if (KEY_CPU_BIG_MAX_FREQ.equals(key)) return mContext.getString(R.string.powertools_label_cpu_big_max_frequency);
        if (KEY_CPU_BIG_GOVERNOR.equals(key)) return mContext.getString(R.string.powertools_label_cpu_big_governor);
        if (KEY_CPU_PRIME_MIN_FREQ.equals(key)) return mContext.getString(R.string.powertools_label_cpu_prime_min_frequency);
        if (KEY_CPU_PRIME_MAX_FREQ.equals(key)) return mContext.getString(R.string.powertools_label_cpu_prime_max_frequency);
        if (KEY_CPU_PRIME_GOVERNOR.equals(key)) return mContext.getString(R.string.powertools_label_cpu_prime_governor);
        return key.replace("_", " ");
    }

    private void applyUserTouchPanel() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        boolean isGameEnabled;
        boolean isEdgeEnabled;

        if (mCurrentMode == MODE_PERFORMANCE) {
            isGameEnabled = isEdgeEnabled = true;
        } else if (mCurrentMode == MODE_BATTERY_SAVER) {
            isGameEnabled = isEdgeEnabled = false;
        } else {
            isGameEnabled = prefs.getBoolean("game_mode", false);
            isEdgeEnabled = prefs.getBoolean("edge_touch", false);
        }

        if (mCurrentMode == MODE_PERFORMANCE || mCurrentMode == MODE_BATTERY_SAVER) {
            prefs.edit()
                 .putBoolean("game_mode", isGameEnabled)
                 .putBoolean("edge_touch", isEdgeEnabled)
                 .apply();
        }

        if (Utils.fileWritable(FILE_GAME)) Utils.writeValue(FILE_GAME, isGameEnabled ? "1" : "0");
        if (Utils.fileWritable(FILE_EDGE)) Utils.writeValue(FILE_EDGE, isEdgeEnabled ? "1" : "0");
    }

    public int getManagedMode() {
        return getCurrentMode();
    }

    public String getModeLabel() {
        int mode = getManagedMode();
        if (mode == MODE_BATTERY_SAVER) return mModes[MODE_BATTERY_SAVER];
        if (mode == MODE_BALANCE) return mModes[MODE_BALANCE];
        return (mode >= 0 && mode < mModes.length) ? mModes[mode] : mModes[MODE_UNKNOWN];
    }

    public void toggleMode() {
        int currentMode = getManagedMode();
        int newMode = (currentMode == MODE_BALANCE) ? MODE_PERFORMANCE : 
                      (currentMode == MODE_PERFORMANCE) ? MODE_BATTERY_SAVER : MODE_BALANCE;
        setMode(newMode);
    }

    /**
     * Activates the given PowerTools profile mode on the kernel and system property layer.
     *
     * <ol>
     *   <li><b>Primary</b>: writes the mapped value to {@code /sys/kernel/kprofiles/kp_mode}.
     *       This is the authoritative control for KProfiles-aware cpufreq/devfreq boosts.</li>
     *   <li><b>Mirror</b>: updates {@code sys.perf_mode_active} system property so that any
     *       other userspace consumers (vendor perf HAL, Qualcomm ADSP, etc.) continue to
     *       observe the correct mode index.</li>
     * </ol>
     *
     * @param mode PowerTools mode constant: 0 = Battery Saver, 1 = Balanced, 2 = Performance
     * @return {@code true} if at least the KProfiles sysfs write succeeded;
     *         {@code false} if both the sysfs write and the sysprop write failed.
     */
    private boolean setPerformanceModeActive(int mode) {
        // --- Primary: KProfiles sysfs node ---
        int kpValue = ptModeToKpValue(mode); // 0→1, 1→2, 2→3
        boolean kpOk = SysfsUtils.isWritable(KPROFILES_NODE)
                && SysfsUtils.writeValue(KPROFILES_NODE, String.valueOf(kpValue));
        if (!kpOk) {
            Log.w(TAG, "KProfiles node not writable or write failed ("
                    + KPROFILES_NODE + "), falling back to sysprop only");
        } else {
            Log.d(TAG, "KProfiles kp_mode set to " + kpValue
                    + " (PowerTools mode " + mode + ")");
        }

        // --- Proactive Rewrite (Fix for libperfmgr thermal throttling statelessness) ---
        // We MUST do this AFTER the kp_mode write above so kp_active_mode is no longer 1.
        try {
            SystemProperties.set("sys.kprofiles.restore_freq", "0");
            SystemProperties.set("sys.kprofiles.restore_freq", "1");
        } catch (Exception e) {
            Log.e(TAG, "Failed to trigger restore_freq", e);
        }

        // --- Mirror: sys.perf_mode_active sysprop ---
        // Kept for compatibility with any userspace that reads the sysprop directly.
        // The -1 bounce forces observers to detect the change even if the new value
        // equals the previous value.
        try {
            SystemProperties.set(SYS_PROP, "-1");
            Thread.sleep(50);
            SystemProperties.set(SYS_PROP, String.valueOf(mode));
            SystemProperties.set("persist.sys.perf_mode_saved", String.valueOf(mode));
            return true; // Full success: both sysfs and sysprop written
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "Interrupted while bouncing performance mode property", e);
            return kpOk; // Acceptable if at least the sysfs write worked
        } catch (Exception e) {
            Log.e(TAG, "Failed to set performance mode system properties", e);
            return kpOk;
        }
    }
}

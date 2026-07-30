/*
 * Copyright (C) 2025 kenrow214
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.DeviceSettings.powertools;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import org.lineageos.device.DeviceSettings.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PowertoolsSettingsFragment extends PreferenceFragmentCompat
        implements Preference.OnPreferenceChangeListener {

    private static final String KEY_POWER_PROFILE_MODE = "power_profile_mode";
    private static final String KEY_MODE_STATUS = "mode_status_info";

    private static final String KEY_STORAGE_ENABLE = "storage_enable";
    private static final String KEY_IO_SCHEDULER = PowerProfileUtil.KEY_IO_SCHEDULER;
    private static final String KEY_TCP_CONGESTION = "tcp_congestion_control";

    private static final String KEY_GPU_ENABLE = "gpu_enable";
    private static final String KEY_GPU_MIN_FREQ = PowerProfileUtil.KEY_GPU_MIN_FREQ;
    private static final String KEY_GPU_MAX_FREQ = PowerProfileUtil.KEY_GPU_MAX_FREQ;
    private static final String KEY_GPU_GOVERNOR = PowerProfileUtil.KEY_GPU_GOVERNOR;

    private static final String KEY_CPU_ENABLE = "cpu_enable";
    private static final String KEY_CPU_LITTLE_MIN_FREQ = PowerProfileUtil.KEY_CPU_LITTLE_MIN_FREQ;
    private static final String KEY_CPU_LITTLE_MAX_FREQ = PowerProfileUtil.KEY_CPU_LITTLE_MAX_FREQ;
    private static final String KEY_CPU_LITTLE_GOVERNOR = PowerProfileUtil.KEY_CPU_LITTLE_GOVERNOR;
    private static final String KEY_CPU_BIG_MIN_FREQ = PowerProfileUtil.KEY_CPU_BIG_MIN_FREQ;
    private static final String KEY_CPU_BIG_MAX_FREQ = PowerProfileUtil.KEY_CPU_BIG_MAX_FREQ;
    private static final String KEY_CPU_BIG_GOVERNOR = PowerProfileUtil.KEY_CPU_BIG_GOVERNOR;
    private static final String KEY_CPU_PRIME_MIN_FREQ = PowerProfileUtil.KEY_CPU_PRIME_MIN_FREQ;
    private static final String KEY_CPU_PRIME_MAX_FREQ = PowerProfileUtil.KEY_CPU_PRIME_MAX_FREQ;
    private static final String KEY_CPU_PRIME_GOVERNOR = PowerProfileUtil.KEY_CPU_PRIME_GOVERNOR;

    private SwitchPreferenceCompat mStorageEnablePref, mGpuEnablePref, mCpuEnablePref;
    private Preference mModeStatusPref;
    private ListPreference mPowerProfilePref, mIoSchedulerPref, mTcpCongestionPref;
    private ListPreference mGpuMinFreqPref, mGpuMaxFreqPref, mGpuGovernorPref;
    private ListPreference mCpuLittleMinFreqPref, mCpuLittleMaxFreqPref, mCpuLittleGovernorPref;
    private ListPreference mCpuBigMinFreqPref, mCpuBigMaxFreqPref, mCpuBigGovernorPref;
    private ListPreference mCpuPrimeMinFreqPref, mCpuPrimeMaxFreqPref, mCpuPrimeGovernorPref;

    private PowerProfileUtil mPowerProfileUtil;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final List<Preference> mAllControlPrefs = new ArrayList<>();
    private boolean mApplying = false;


    @Override
    public void onViewCreated(android.view.View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        androidx.recyclerview.widget.RecyclerView listView = getListView();
        if (listView != null) {
            listView.setClipToPadding(false);
            int paddingBottom = (int) (24 * getResources().getDisplayMetrics().density);
            listView.setPadding(
                listView.getPaddingLeft(),
                listView.getPaddingTop(),
                listView.getPaddingRight(),
                paddingBottom
            );
        }
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.powertools_settings, rootKey);
        mPowerProfileUtil = new PowerProfileUtil(requireContext());

        mPowerProfilePref = bindPref(KEY_POWER_PROFILE_MODE);
        mModeStatusPref = findPreference(KEY_MODE_STATUS);

        mStorageEnablePref = bindPref(KEY_STORAGE_ENABLE);
        mIoSchedulerPref = bindPref(KEY_IO_SCHEDULER);
        mTcpCongestionPref = bindPref(KEY_TCP_CONGESTION);

        mGpuEnablePref = bindPref(KEY_GPU_ENABLE);
        mGpuMinFreqPref = bindPref(KEY_GPU_MIN_FREQ);
        mGpuMaxFreqPref = bindPref(KEY_GPU_MAX_FREQ);
        mGpuGovernorPref = bindPref(KEY_GPU_GOVERNOR);

        mCpuEnablePref = bindPref(KEY_CPU_ENABLE);
        mCpuLittleMinFreqPref = bindPref(KEY_CPU_LITTLE_MIN_FREQ);
        mCpuLittleMaxFreqPref = bindPref(KEY_CPU_LITTLE_MAX_FREQ);
        mCpuLittleGovernorPref = bindPref(KEY_CPU_LITTLE_GOVERNOR);
        mCpuBigMinFreqPref = bindPref(KEY_CPU_BIG_MIN_FREQ);
        mCpuBigMaxFreqPref = bindPref(KEY_CPU_BIG_MAX_FREQ);
        mCpuBigGovernorPref = bindPref(KEY_CPU_BIG_GOVERNOR);
        mCpuPrimeMinFreqPref = bindPref(KEY_CPU_PRIME_MIN_FREQ);
        mCpuPrimeMaxFreqPref = bindPref(KEY_CPU_PRIME_MAX_FREQ);
        mCpuPrimeGovernorPref = bindPref(KEY_CPU_PRIME_GOVERNOR);

        initializeControlGroups();

        androidx.preference.PreferenceScreen screen = getPreferenceScreen();
        if (screen != null) {
            for (int i = 0; i < screen.getPreferenceCount(); i++) {
                Preference p = screen.getPreference(i);
                if (p instanceof androidx.preference.PreferenceCategory) {
                    androidx.preference.PreferenceCategory category = (androidx.preference.PreferenceCategory) p;
                    category.setLayoutResource(R.layout.preference_category_card_header);
                    for (int j = 0; j < category.getPreferenceCount(); j++) {
                        Preference child = category.getPreference(j);
                        String childKey = child.getKey();
                        if (KEY_POWER_PROFILE_MODE.equals(childKey) ||
                            KEY_GPU_ENABLE.equals(childKey) ||
                            KEY_CPU_ENABLE.equals(childKey) ||
                            KEY_STORAGE_ENABLE.equals(childKey)) {
                            child.setLayoutResource(R.layout.preference_card_item);
                        } else {
                            child.setLayoutResource(R.layout.preference_m3);
                        }
                    }
                } else if (!"mode_card_header".equals(p.getKey())) {
                    p.setLayoutResource(R.layout.preference_card_item);
                }
            }
        }

        // Pre-populate mode card, summaries, and active card backgrounds immediately
        // so there is zero delay or pop-in flash when opening Power Tools.
        syncActiveModeUI();
        refreshModeState();
    }

    @SuppressWarnings("unchecked")
    private <T extends Preference> T bindPref(String key) {
        T pref = findPreference(key);
        if (pref != null) {
            if (KEY_POWER_PROFILE_MODE.equals(key) ||
                KEY_GPU_ENABLE.equals(key) ||
                KEY_CPU_ENABLE.equals(key) ||
                KEY_STORAGE_ENABLE.equals(key)) {
                pref.setLayoutResource(R.layout.preference_card_item);
            } else {
                pref.setLayoutResource(R.layout.preference_m3);
            }
            pref.setOnPreferenceChangeListener(this);
        }
        return pref;
    }

    private void initializeControlGroups() {
        String[] controlKeys = {
            KEY_POWER_PROFILE_MODE, "power_profile_category", "power_profile_footer", KEY_MODE_STATUS,
            KEY_STORAGE_ENABLE, "storage_category", KEY_IO_SCHEDULER,
            KEY_GPU_ENABLE, "gpu_freq_category", KEY_GPU_MIN_FREQ, KEY_GPU_MAX_FREQ, KEY_GPU_GOVERNOR,
            KEY_CPU_ENABLE, "cpu_little_category", KEY_CPU_LITTLE_MIN_FREQ, KEY_CPU_LITTLE_MAX_FREQ, KEY_CPU_LITTLE_GOVERNOR,
            "cpu_big_category", KEY_CPU_BIG_MIN_FREQ, KEY_CPU_BIG_MAX_FREQ, KEY_CPU_BIG_GOVERNOR,
            "cpu_prime_category", KEY_CPU_PRIME_MIN_FREQ, KEY_CPU_PRIME_MAX_FREQ, KEY_CPU_PRIME_GOVERNOR
        };

        for (String key : controlKeys) {
            Preference p = findPreference(key);
            if (p != null) mAllControlPrefs.add(p);
        }
    }

    @Override
    public void onResume() {
        super.onResume();


        syncActiveModeUI();
        refreshUI();
    }

    @Override
    public void onPause() {
        super.onPause();

        mMainHandler.removeCallbacksAndMessages(null);
    }


    private void syncActiveModeUI() {
        if (mPowerProfilePref == null || mPowerProfileUtil == null) return;
        String activeMode = String.valueOf(mPowerProfileUtil.getCurrentMode());
        if (!activeMode.equals(mPowerProfilePref.getValue())) {
            mPowerProfilePref.setValue(activeMode);
        }
    }

    private void refreshUI() {
        setControlsEnabled(mAllControlPrefs, true);
        if (mPowerProfilePref != null) mPowerProfilePref.setVisible(true);

        refreshModeState();
    }

    private final java.util.concurrent.ExecutorService mExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

    private void refreshModeState() {
        mExecutor.execute(() -> {
            updateDynamicDropdowns();
            mMainHandler.post(() -> {
                if (!isAdded()) return;
                SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
                syncAllListPrefsToData(prefs);
                configurePresetModeUI();
            });
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mExecutor.shutdownNow();
    }


    private void configurePresetModeUI() {
        int mode = getCurrentProfileMode();

        if (mPowerProfilePref != null) {
            mPowerProfilePref.setEnabled(true);
            mPowerProfilePref.setSummary(mPowerProfilePref.getEntry());
        }

        updateModeDisplays(mode);

        boolean cpuEnabled = isChecked(mCpuEnablePref);
        boolean gpuEnabled = isChecked(mGpuEnablePref);
        boolean storageEnabled = isChecked(mStorageEnablePref);

        safeSetEnabled(mCpuEnablePref, true);
        safeSetEnabled(mGpuEnablePref, true);
        safeSetEnabled(mStorageEnablePref, true);

        // Keep sub-preferences enabled so frequency values stay 100% crisp & full opacity
        safeSetEnabled(mCpuLittleMinFreqPref, true);
        safeSetEnabled(mCpuLittleMaxFreqPref, true);
        safeSetEnabled(mCpuLittleGovernorPref, true);
        safeSetEnabled(mCpuBigMinFreqPref, true);
        safeSetEnabled(mCpuBigMaxFreqPref, true);
        safeSetEnabled(mCpuBigGovernorPref, true);
        safeSetEnabled(mCpuPrimeMinFreqPref, true);
        safeSetEnabled(mCpuPrimeMaxFreqPref, true);
        safeSetEnabled(mCpuPrimeGovernorPref, true);

        safeSetEnabled(mGpuMinFreqPref, true);
        safeSetEnabled(mGpuMaxFreqPref, true);
        safeSetEnabled(mGpuGovernorPref, true);

        safeSetEnabled(mIoSchedulerPref, true);

        // Dynamic system accent color tint on card background when turned ON
        updateCardActiveBackground(mCpuEnablePref, cpuEnabled);
        updateCardActiveBackground(mGpuEnablePref, gpuEnabled);
        updateCardActiveBackground(mStorageEnablePref, storageEnabled);

        if (!cpuEnabled) resetHardwareCategoryToDefaults(KEY_CPU_ENABLE, mode);
        if (!gpuEnabled) resetHardwareCategoryToDefaults(KEY_GPU_ENABLE, mode);
        if (!storageEnabled) resetHardwareCategoryToDefaults(KEY_STORAGE_ENABLE, mode);
    }

    private void updateCardActiveBackground(Preference pref, boolean active) {
        if (pref != null) {
            int newLayout = active ? R.layout.preference_card_item_active : R.layout.preference_card_item;
            if (pref.getLayoutResource() != newLayout) {
                pref.setLayoutResource(newLayout);
            }
            if (pref.getIcon() != null) {
                android.graphics.drawable.Drawable icon = pref.getIcon().mutate();
                if (active) {
                    icon.setTint(org.lineageos.device.DeviceSettings.Utils.getSystemAccentColor(requireContext()));
                } else {
                    icon.setTintList(null);
                }
                pref.setIcon(icon);
            }
        }
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (isCpuSubPref(preference) && !isChecked(mCpuEnablePref)) {
            showToast("Enable CPU Tweaking to modify frequency limits");
            return true;
        }
        if (isGpuSubPref(preference) && !isChecked(mGpuEnablePref)) {
            showToast("Enable GPU Manual Control to modify frequency limits");
            return true;
        }
        if (preference == mIoSchedulerPref && !isChecked(mStorageEnablePref)) {
            showToast("Enable Storage I/O Manual Control to modify scheduler");
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }

    private boolean isCpuSubPref(Preference p) {
        return isCpuLittlePref(p) || isCpuBigPref(p) || isCpuPrimePref(p);
    }

    private boolean isGpuSubPref(Preference p) {
        return p == mGpuMinFreqPref || p == mGpuMaxFreqPref || p == mGpuGovernorPref;
    }






    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();
        String newValStr = newValue.toString();

        switch (key) {

            case KEY_POWER_PROFILE_MODE:
                handleProfileModeChange(newValStr);
                return true;

            case KEY_STORAGE_ENABLE:
            case KEY_GPU_ENABLE:
            case KEY_CPU_ENABLE:
                handleHardwareToggleChange(key, (Boolean) newValue);
                return true;
                
            default:
                return handleHardwareValueChange(preference, key, newValStr);
        }
    }




    private void handleProfileModeChange(String newValue) {
        if (mApplying) return;
        lockForApply(getString(R.string.powertools_toast_applying));
        int mode = Integer.parseInt(newValue);

        if (mCpuEnablePref != null) mCpuEnablePref.setChecked(false);
        if (mGpuEnablePref != null) mGpuEnablePref.setChecked(false);
        if (mStorageEnablePref != null) mStorageEnablePref.setChecked(false);

        mPowerProfileUtil.setMode(mode);
        showFallbackToasts();

        if (mPowerProfilePref != null) {
            mPowerProfilePref.setValue(newValue);
            mMainHandler.post(() -> mPowerProfilePref.setSummary(mPowerProfilePref.getEntry()));
        }

        refreshUI();
        mMainHandler.postDelayed(() -> {
            refreshModeState();
            String label = mPowerProfilePref != null ? mPowerProfilePref.getEntry().toString() : "Mode";
            unlockAfterApply(getString(R.string.powertools_toast_mode_applied, label));
        }, 1500);
    }

    private void handleHardwareToggleChange(String key, boolean enabled) {
        int mode = getCurrentProfileMode();
        
        if (!enabled) {
            resetHardwareCategoryToDefaults(key, mode);
            pushHardwareSettingsCategory(key);
            showToast(getString(R.string.powertools_toast_defaults_restored));
        } else {
            showToast(getString(R.string.powertools_toast_manual_enabled));
        }
        
        mMainHandler.postDelayed(this::refreshUI, 150);
    }

    private boolean handleHardwareValueChange(Preference preference, String key, String newValue) {
        String restriction = checkRestrictions(preference, newValue);
        if (restriction != null) {
            showToast(restriction);
            return false;
        }

        applyHardwareSetting(preference, key, newValue);
        updateListPreferenceSafely(preference, newValue);

        getPreferenceManager().getSharedPreferences().edit().putString(key, newValue).apply();
        return false; // Handled manually
    }


    private void applyHardwareSetting(Preference preference, String key, String newValue) {
        if (preference == mIoSchedulerPref) {
            StorageUtils.setIoScheduler(newValue);
        } else if (preference == mTcpCongestionPref) {
            SysfsUtils.writeValue(KernelOptionUtils.TCP_CONGESTION_CONTROL, newValue);
        } else if (preference == mGpuMinFreqPref) {
            GPUUtils.setGPUMinFrequency(newValue);
        } else if (preference == mGpuMaxFreqPref) {
            GPUUtils.setGPUMaxFrequency(newValue);
        } else if (preference == mGpuGovernorPref) {
            GPUUtils.setGPUGovernor(newValue);
        } else if (isCpuLittlePref(preference)) {
            CPUUtils.setCPULittleFreq(
                resolveVal(key, KEY_CPU_LITTLE_MIN_FREQ, newValue, mCpuLittleMinFreqPref, getDefaultValue(KEY_CPU_LITTLE_MIN_FREQ)),
                resolveVal(key, KEY_CPU_LITTLE_MAX_FREQ, newValue, mCpuLittleMaxFreqPref, getDefaultValue(KEY_CPU_LITTLE_MAX_FREQ)),
                resolveVal(key, KEY_CPU_LITTLE_GOVERNOR, newValue, mCpuLittleGovernorPref, getDefaultValue(KEY_CPU_LITTLE_GOVERNOR))
            );
        } else if (isCpuBigPref(preference)) {
            CPUUtils.setCPUBigFreq(
                resolveVal(key, KEY_CPU_BIG_MIN_FREQ, newValue, mCpuBigMinFreqPref, getDefaultValue(KEY_CPU_BIG_MIN_FREQ)),
                resolveVal(key, KEY_CPU_BIG_MAX_FREQ, newValue, mCpuBigMaxFreqPref, getDefaultValue(KEY_CPU_BIG_MAX_FREQ)),
                resolveVal(key, KEY_CPU_BIG_GOVERNOR, newValue, mCpuBigGovernorPref, getDefaultValue(KEY_CPU_BIG_GOVERNOR))
            );
        } else if (isCpuPrimePref(preference)) {
            CPUUtils.setCPUPrimeFreq(
                resolveVal(key, KEY_CPU_PRIME_MIN_FREQ, newValue, mCpuPrimeMinFreqPref, getDefaultValue(KEY_CPU_PRIME_MIN_FREQ)),
                resolveVal(key, KEY_CPU_PRIME_MAX_FREQ, newValue, mCpuPrimeMaxFreqPref, getDefaultValue(KEY_CPU_PRIME_MAX_FREQ)),
                resolveVal(key, KEY_CPU_PRIME_GOVERNOR, newValue, mCpuPrimeGovernorPref, getDefaultValue(KEY_CPU_PRIME_GOVERNOR))
            );
        }
    }

    private void pushHardwareSettingsCategory(String categoryKey) {
        SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
        
        switch (categoryKey) {
            case KEY_STORAGE_ENABLE:
                StorageUtils.setIoScheduler(prefs.getString(KEY_IO_SCHEDULER, getDefaultValue(KEY_IO_SCHEDULER)));
                break;
            case KEY_GPU_ENABLE:
                GPUUtils.setGPUMinFrequency(prefs.getString(KEY_GPU_MIN_FREQ, getDefaultValue(KEY_GPU_MIN_FREQ)));
                GPUUtils.setGPUMaxFrequency(prefs.getString(KEY_GPU_MAX_FREQ, getDefaultValue(KEY_GPU_MAX_FREQ)));
                GPUUtils.setGPUGovernor(prefs.getString(KEY_GPU_GOVERNOR, getDefaultValue(KEY_GPU_GOVERNOR)));
                break;
            case KEY_CPU_ENABLE:
                CPUUtils.setCPULittleFreq(
                    prefs.getString(KEY_CPU_LITTLE_MIN_FREQ, getDefaultValue(KEY_CPU_LITTLE_MIN_FREQ)),
                    prefs.getString(KEY_CPU_LITTLE_MAX_FREQ, getDefaultValue(KEY_CPU_LITTLE_MAX_FREQ)),
                    prefs.getString(KEY_CPU_LITTLE_GOVERNOR, getDefaultValue(KEY_CPU_LITTLE_GOVERNOR))
                );
                CPUUtils.setCPUBigFreq(
                    prefs.getString(KEY_CPU_BIG_MIN_FREQ, getDefaultValue(KEY_CPU_BIG_MIN_FREQ)),
                    prefs.getString(KEY_CPU_BIG_MAX_FREQ, getDefaultValue(KEY_CPU_BIG_MAX_FREQ)),
                    prefs.getString(KEY_CPU_BIG_GOVERNOR, getDefaultValue(KEY_CPU_BIG_GOVERNOR))
                );
                CPUUtils.setCPUPrimeFreq(
                    prefs.getString(KEY_CPU_PRIME_MIN_FREQ, getDefaultValue(KEY_CPU_PRIME_MIN_FREQ)),
                    prefs.getString(KEY_CPU_PRIME_MAX_FREQ, getDefaultValue(KEY_CPU_PRIME_MAX_FREQ)),
                    prefs.getString(KEY_CPU_PRIME_GOVERNOR, getDefaultValue(KEY_CPU_PRIME_GOVERNOR))
                );
                break;
        }
    }

    private void resetHardwareCategoryToDefaults(String categoryKey, int targetMode) {
        SharedPreferences.Editor editor = getPreferenceManager().getSharedPreferences().edit();
        List<String> keysToReset = new ArrayList<>();

        switch (categoryKey) {
            case KEY_STORAGE_ENABLE:
                keysToReset.add(KEY_IO_SCHEDULER);
                break;
            case KEY_GPU_ENABLE:
                keysToReset.addAll(Arrays.asList(KEY_GPU_MIN_FREQ, KEY_GPU_MAX_FREQ, KEY_GPU_GOVERNOR));
                break;
            case KEY_CPU_ENABLE:
                keysToReset.addAll(Arrays.asList(
                    KEY_CPU_LITTLE_MIN_FREQ, KEY_CPU_LITTLE_MAX_FREQ, KEY_CPU_LITTLE_GOVERNOR,
                    KEY_CPU_BIG_MIN_FREQ, KEY_CPU_BIG_MAX_FREQ, KEY_CPU_BIG_GOVERNOR,
                    KEY_CPU_PRIME_MIN_FREQ, KEY_CPU_PRIME_MAX_FREQ, KEY_CPU_PRIME_GOVERNOR
                ));
                break;
        }

        for (String baseKey : keysToReset) {
            String defaultVal = mPowerProfileUtil.getValidatedStockValueForMode(targetMode, baseKey);
            editor.putString(baseKey, defaultVal);

            Preference p = findPreference(baseKey);
            if (p instanceof ListPreference) {
                ListPreference lp = (ListPreference) p;
                lp.setValue(defaultVal);
                CharSequence entry = lp.getEntry();
                lp.setSummary(entry != null ? entry : defaultVal);
            }
        }
        editor.apply();
    }


    private void lockForApply(String status) {
        mApplying = true;
        setControlsEnabled(mAllControlPrefs, false);
        if (mModeStatusPref != null) mModeStatusPref.setSummary(status);
    }

    private void unlockAfterApply(String toast) {
        mApplying = false;
        refreshUI();
        showToast(toast);
    }

    private String resolveVal(String targetKey, String matchKey, String newValue, ListPreference pref, String fallback) {
        if (targetKey.equals(matchKey)) return newValue;
        return (pref != null && pref.getValue() != null) ? pref.getValue() : fallback;
    }

    private String getDefaultValue(String key) {
        return mPowerProfileUtil.getValidatedStockValueForMode(getCurrentProfileMode(), key);
    }

    private void updateModeDisplays(int mode) {
        if (mModeStatusPref != null) {
            mModeStatusPref.setSummary(getString(getStatusSummaryForMode(mode)));
        }
        updateModeCard(mode);
    }

    private int getStatusSummaryForMode(int mode) {
        switch (mode) {
            case PowerProfileUtil.MODE_PERFORMANCE: return R.string.mode_status_performance;
            case PowerProfileUtil.MODE_BATTERY_SAVER: return R.string.mode_status_battery_saver;
            default: return R.string.mode_status_balanced;
        }
    }

    private void updateModeCard(int mode) {
        Preference card = findPreference("mode_card_header");
        if (card == null) return;

        switch (mode) {
            case PowerProfileUtil.MODE_PERFORMANCE:
                card.setTitle(R.string.powertools_mode_card_performance_title);
                card.setSummary(R.string.powertools_mode_card_performance_summary);
                card.setIcon(R.drawable.ic_thermal_performance);
                break;
            case PowerProfileUtil.MODE_BATTERY_SAVER:
                card.setTitle(R.string.powertools_mode_card_powersave_title);
                card.setSummary(R.string.powertools_mode_card_powersave_summary);
                card.setIcon(R.drawable.ic_thermal_battery_saver);
                break;
            default:
                card.setTitle(R.string.powertools_mode_card_balanced_title);
                card.setSummary(R.string.powertools_mode_card_balanced_summary);
                card.setIcon(R.drawable.ic_thermal_balance);
                break;
        }
    }

    private String checkRestrictions(Preference preference, String value) {
        int mode = getCurrentProfileMode();
        boolean isCpuGov = isCpuGovernorPref(preference);
        boolean isGpuGov = (preference == mGpuGovernorPref);
        boolean isIoSched = (preference == mIoSchedulerPref);

        if (preference instanceof ListPreference && preference.getKey() != null
                && preference.getKey().endsWith("_frequency")) {
            try {
                long freqVal = Long.parseLong(value);
                if (mode == PowerProfileUtil.MODE_BATTERY_SAVER
                        && preference.getKey().contains("_max_frequency")) {
                    String defaultValue = mPowerProfileUtil.getValidatedStockValueForMode(mode, preference.getKey());
                    long defaultMax = Long.parseLong(defaultValue);
                    if (freqVal > defaultMax) {
                        return getString(R.string.powertools_restricted_powersave_max,
                                KernelOptionUtils.formatFrequency(defaultValue));
                    }
                } else if (mode == PowerProfileUtil.MODE_PERFORMANCE
                        && preference.getKey().contains("_min_frequency")) {
                    String defaultValue = mPowerProfileUtil.getValidatedStockValueForMode(mode, preference.getKey());
                    long defaultMin = Long.parseLong(defaultValue);
                    if (freqVal < defaultMin) {
                        return getString(R.string.powertools_restricted_performance_min,
                                KernelOptionUtils.formatFrequency(defaultValue));
                    }
                }
            } catch (NumberFormatException ignored) {}
        }

        if (mode == PowerProfileUtil.MODE_BATTERY_SAVER) {
            if (isCpuGov && "performance".equals(value)) {
                return getString(R.string.governor_restricted_powersave, getDisplayValue(preference, value));
            }
            if (isGpuGov && ("performance".equals(value) || "msm-adreno-tz".equals(value))) {
                return getString(R.string.governor_restricted_powersave, getDisplayValue(preference, value));
            }
            if (isIoSched && "kyber".equals(value)) {
                return getString(R.string.governor_restricted_powersave, getDisplayValue(preference, value));
            }
            
        } else if (mode == PowerProfileUtil.MODE_PERFORMANCE) {
            if (isCpuGov && ("conservative".equals(value) || "powersave".equals(value))) {
                return getString(R.string.governor_restricted_performance, getDisplayValue(preference, value));
            }
            if (isGpuGov && ("userspace".equals(value) || "powersave".equals(value))) {
                return getString(R.string.governor_restricted_performance, getDisplayValue(preference, value));
            }
            if (isIoSched && "bfq".equals(value)) {
                return getString(R.string.governor_restricted_performance, getDisplayValue(preference, value));
            }
        }
        return null;
    }

    private String getDisplayValue(Preference preference, String value) {
        if (preference instanceof ListPreference) {
            ListPreference listPreference = (ListPreference) preference;
            CharSequence[] entries = listPreference.getEntries();
            CharSequence[] values = listPreference.getEntryValues();
            if (entries != null && values != null) {
                for (int i = 0; i < values.length && i < entries.length; i++) {
                    if (value.equals(values[i].toString())) return entries[i].toString();
                }
            }
        }
        return KernelOptionUtils.displayValue(value);
    }

    private void syncAllListPrefsToData(SharedPreferences prefs) {
        syncListPrefToData(mIoSchedulerPref, prefs, KEY_IO_SCHEDULER);
        syncListPrefToData(mTcpCongestionPref, prefs, KEY_TCP_CONGESTION);
        syncListPrefToData(mGpuMinFreqPref, prefs, KEY_GPU_MIN_FREQ);
        syncListPrefToData(mGpuMaxFreqPref, prefs, KEY_GPU_MAX_FREQ);
        syncListPrefToData(mGpuGovernorPref, prefs, KEY_GPU_GOVERNOR);
        syncListPrefToData(mCpuLittleMinFreqPref, prefs, KEY_CPU_LITTLE_MIN_FREQ);
        syncListPrefToData(mCpuLittleMaxFreqPref, prefs, KEY_CPU_LITTLE_MAX_FREQ);
        syncListPrefToData(mCpuLittleGovernorPref, prefs, KEY_CPU_LITTLE_GOVERNOR);
        syncListPrefToData(mCpuBigMinFreqPref, prefs, KEY_CPU_BIG_MIN_FREQ);
        syncListPrefToData(mCpuBigMaxFreqPref, prefs, KEY_CPU_BIG_MAX_FREQ);
        syncListPrefToData(mCpuBigGovernorPref, prefs, KEY_CPU_BIG_GOVERNOR);
        syncListPrefToData(mCpuPrimeMinFreqPref, prefs, KEY_CPU_PRIME_MIN_FREQ);
        syncListPrefToData(mCpuPrimeMaxFreqPref, prefs, KEY_CPU_PRIME_MAX_FREQ);
        syncListPrefToData(mCpuPrimeGovernorPref, prefs, KEY_CPU_PRIME_GOVERNOR);
    }

    private void syncListPrefToData(ListPreference pref, SharedPreferences prefs, String key) {
        if (pref == null) return;
        String savedVal = prefs.getString(key, "");
        
        if (isKernelOptionKey(key)) {
            String trueVal = getTrueActiveValue(key);
            if (!hasEntryValue(pref, savedVal)) {
                savedVal = trueVal != null && hasEntryValue(pref, trueVal) ? trueVal : getDefaultValue(key);
                prefs.edit().putString(key, savedVal).apply();
            }
        }

        if (!savedVal.isEmpty()) {
            pref.setValue(savedVal);
            CharSequence entry = pref.getEntry();
            pref.setSummary(entry != null ? entry : savedVal);
        }
    }

    private boolean hasEntryValue(ListPreference pref, String value) {
        if (value == null || value.isEmpty()) return false;
        CharSequence[] values = pref.getEntryValues();
        if (values == null) return false;
        for (CharSequence item : values) {
            if (value.equals(item.toString())) return true;
        }
        return false;
    }

    private boolean isKernelOptionKey(String key) {
        return KEY_CPU_LITTLE_MIN_FREQ.equals(key) || KEY_CPU_LITTLE_MAX_FREQ.equals(key) ||
               KEY_CPU_LITTLE_GOVERNOR.equals(key) || KEY_CPU_BIG_MIN_FREQ.equals(key) ||
               KEY_CPU_BIG_MAX_FREQ.equals(key) || KEY_CPU_BIG_GOVERNOR.equals(key) ||
               KEY_CPU_PRIME_MIN_FREQ.equals(key) || KEY_CPU_PRIME_MAX_FREQ.equals(key) ||
               KEY_CPU_PRIME_GOVERNOR.equals(key) || KEY_GPU_MIN_FREQ.equals(key) ||
               KEY_GPU_MAX_FREQ.equals(key) || KEY_GPU_GOVERNOR.equals(key) ||
               KEY_IO_SCHEDULER.equals(key) || KEY_TCP_CONGESTION.equals(key);
    }

    private String getTrueActiveValue(String key) {
        String path = null;
        boolean isIo = false;
        switch (key) {
            case KEY_CPU_LITTLE_MIN_FREQ: path = KernelOptionUtils.CPU_LITTLE_MIN_FREQ; break;
            case KEY_CPU_LITTLE_MAX_FREQ: path = KernelOptionUtils.CPU_LITTLE_MAX_FREQ; break;
            case KEY_CPU_LITTLE_GOVERNOR: path = KernelOptionUtils.CPU_LITTLE_GOVERNOR; break;
            case KEY_CPU_BIG_MIN_FREQ: path = KernelOptionUtils.CPU_BIG_MIN_FREQ; break;
            case KEY_CPU_BIG_MAX_FREQ: path = KernelOptionUtils.CPU_BIG_MAX_FREQ; break;
            case KEY_CPU_BIG_GOVERNOR: path = KernelOptionUtils.CPU_BIG_GOVERNOR; break;
            case KEY_CPU_PRIME_MIN_FREQ: path = KernelOptionUtils.CPU_PRIME_MIN_FREQ; break;
            case KEY_CPU_PRIME_MAX_FREQ: path = KernelOptionUtils.CPU_PRIME_MAX_FREQ; break;
            case KEY_CPU_PRIME_GOVERNOR: path = KernelOptionUtils.CPU_PRIME_GOVERNOR; break;
            case KEY_GPU_MIN_FREQ: path = KernelOptionUtils.GPU_MIN_FREQ; break;
            case KEY_GPU_MAX_FREQ: path = KernelOptionUtils.GPU_MAX_FREQ; break;
            case KEY_GPU_GOVERNOR: path = KernelOptionUtils.GPU_GOVERNOR; break;
            case KEY_TCP_CONGESTION:
                return SysfsUtils.readLine(KernelOptionUtils.TCP_CONGESTION_CONTROL);
            case KEY_IO_SCHEDULER: 
                path = KernelOptionUtils.IO_SCHEDULER;
                isIo = true;
                break;
        }
        if (path == null) return null;
        if (isIo) return KernelOptionUtils.readBracketedValue(path);
        String raw = SysfsUtils.readLine(path);
        if (raw == null || raw.isEmpty()) return null;
        return raw;
    }

    private void updateListPreferenceSafely(Preference preference, String newValue) {
        if (preference instanceof ListPreference) {
            ListPreference lp = (ListPreference) preference;
            lp.setValue(newValue);
            CharSequence entry = lp.getEntry();
            String displayText = (entry != null) ? entry.toString() : newValue;
            lp.setSummary(displayText);
            showToast(getString(R.string.powertools_toast_value_applied, displayText));
        }
    }

    private void updateDynamicDropdowns() {
        populateFrequencyFromSysfs(mCpuLittleMinFreqPref,
                KernelOptionUtils.CPU_LITTLE_AVAILABLE_FREQUENCIES,
                R.array.cpu_little_freq_entries, R.array.cpu_little_freq_values);
        populateFrequencyFromSysfs(mCpuLittleMaxFreqPref,
                KernelOptionUtils.CPU_LITTLE_AVAILABLE_FREQUENCIES,
                R.array.cpu_little_freq_entries, R.array.cpu_little_freq_values);
        populateNameFromSysfs(mCpuLittleGovernorPref,
                KernelOptionUtils.CPU_LITTLE_AVAILABLE_GOVERNORS,
                R.array.cpu_governor_entries, R.array.cpu_governor_values);

        populateFrequencyFromSysfs(mCpuBigMinFreqPref,
                KernelOptionUtils.CPU_BIG_AVAILABLE_FREQUENCIES,
                R.array.cpu_big_freq_entries, R.array.cpu_big_freq_values);
        populateFrequencyFromSysfs(mCpuBigMaxFreqPref,
                KernelOptionUtils.CPU_BIG_AVAILABLE_FREQUENCIES,
                R.array.cpu_big_freq_entries, R.array.cpu_big_freq_values);
        populateNameFromSysfs(mCpuBigGovernorPref,
                KernelOptionUtils.CPU_BIG_AVAILABLE_GOVERNORS,
                R.array.cpu_governor_entries, R.array.cpu_governor_values);

        populateFrequencyFromSysfs(mCpuPrimeMinFreqPref,
                KernelOptionUtils.CPU_PRIME_AVAILABLE_FREQUENCIES,
                R.array.cpu_prime_freq_entries, R.array.cpu_prime_freq_values);
        populateFrequencyFromSysfs(mCpuPrimeMaxFreqPref,
                KernelOptionUtils.CPU_PRIME_AVAILABLE_FREQUENCIES,
                R.array.cpu_prime_freq_entries, R.array.cpu_prime_freq_values);
        populateNameFromSysfs(mCpuPrimeGovernorPref,
                KernelOptionUtils.CPU_PRIME_AVAILABLE_GOVERNORS,
                R.array.cpu_governor_entries, R.array.cpu_governor_values);

        populateFrequencyFromSysfs(mGpuMinFreqPref,
                KernelOptionUtils.GPU_AVAILABLE_FREQUENCY_PATHS,
                R.array.gpu_frequency_entries, R.array.gpu_frequency_values);
        populateFrequencyFromSysfs(mGpuMaxFreqPref,
                KernelOptionUtils.GPU_AVAILABLE_FREQUENCY_PATHS,
                R.array.gpu_frequency_entries, R.array.gpu_frequency_values);
        populateNameFromSysfs(mGpuGovernorPref,
                KernelOptionUtils.GPU_AVAILABLE_GOVERNORS,
                R.array.gpu_governor_entries, R.array.gpu_governor_values);
        populateNameFromSysfs(mIoSchedulerPref,
                KernelOptionUtils.IO_SCHEDULER,
                R.array.io_scheduler_entries, R.array.io_scheduler_values);
        populateNameFromSysfs(mTcpCongestionPref,
                KernelOptionUtils.TCP_AVAILABLE_CONGESTION_CONTROL,
                R.array.tcp_congestion_entries, R.array.tcp_congestion_values);
    }

    private void populateFrequencyFromSysfs(ListPreference pref, String sysfsPath,
            int fallbackEntries, int fallbackValues) {
        populateFrequencyFromSysfs(pref, new String[] { sysfsPath }, fallbackEntries, fallbackValues);
    }

    private void populateFrequencyFromSysfs(ListPreference pref, String[] sysfsPaths,
            int fallbackEntries, int fallbackValues) {
        if (pref == null) return;
        String[] values = KernelOptionUtils.readAvailableValues(sysfsPaths, false);
        if (values.length == 0) {
            pref.setEntries(fallbackEntries);
            pref.setEntryValues(fallbackValues);
            return;
        }

        java.util.Arrays.sort(values, (a, b) -> {
            try {
                return Long.compare(Long.parseLong(a), Long.parseLong(b));
            } catch (NumberFormatException e) {
                return a.compareTo(b);
            }
        });

        String[] entries = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            entries[i] = KernelOptionUtils.formatFrequency(values[i]);
        }
        pref.setEntries(entries);
        pref.setEntryValues(values);
    }

    private void populateNameFromSysfs(ListPreference pref, String sysfsPath,
            int fallbackEntries, int fallbackValues) {
        if (pref == null) return;
        boolean dropNone = pref == mIoSchedulerPref;
        String[] values = KernelOptionUtils.readAvailableValues(sysfsPath, dropNone);
        if (values.length == 0) {
            pref.setEntries(fallbackEntries);
            pref.setEntryValues(fallbackValues);
            return;
        }

        String[] entries = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            entries[i] = KernelOptionUtils.prettifyName(values[i]);
        }
        pref.setEntries(entries);
        pref.setEntryValues(values);
    }

    private void setControlsEnabled(List<Preference> prefs, boolean enabled) {
        for (Preference p : prefs) safeSetEnabled(p, enabled);
    }

    private void safeSetEnabled(Preference pref, boolean enabled) {
        if (pref != null) pref.setEnabled(enabled);
    }

    private boolean isChecked(SwitchPreferenceCompat pref) {
        return pref != null && pref.isChecked();
    }

    private int getCurrentProfileMode() {
        if (mPowerProfilePref == null) return PowerProfileUtil.MODE_BALANCE;
        try {
            return Integer.parseInt(mPowerProfilePref.getValue());
        } catch (NumberFormatException ignored) {
            return PowerProfileUtil.MODE_BALANCE;
        }
    }

    private boolean isCpuGovernorPref(Preference p) {
        return p == mCpuLittleGovernorPref || p == mCpuBigGovernorPref || p == mCpuPrimeGovernorPref;
    }
    
    private boolean isCpuLittlePref(Preference p) { return p == mCpuLittleMinFreqPref || p == mCpuLittleMaxFreqPref || p == mCpuLittleGovernorPref; }
    private boolean isCpuBigPref(Preference p) { return p == mCpuBigMinFreqPref || p == mCpuBigMaxFreqPref || p == mCpuBigGovernorPref; }
    private boolean isCpuPrimePref(Preference p) { return p == mCpuPrimeMinFreqPref || p == mCpuPrimeMaxFreqPref || p == mCpuPrimeGovernorPref; }

    private void showToast(String message) {
        try {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
        } catch (IllegalStateException ignored) {
        }
    }

    private void showFallbackToasts() {
        java.util.List<String> fallbacks = mPowerProfileUtil.getAndClearFallbacks();
        for (String msg : fallbacks) {
            try {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show();
            } catch (IllegalStateException ignored) {
            }
        }
    }
}

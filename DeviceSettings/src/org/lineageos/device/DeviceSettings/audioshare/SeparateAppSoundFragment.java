/*
 * Copyright (C) 2026 LunarisOS
 * Separate App Sound Routing Fragment
 */

package org.lineageos.device.DeviceSettings.audioshare;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.preference.CheckBoxPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import org.lineageos.device.DeviceSettings.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SeparateAppSoundFragment extends PreferenceFragmentCompat
        implements Preference.OnPreferenceChangeListener {

    private static final String PREF_ENABLE = "separate_app_sound_enable";
    private static final String PREF_DEVICE = "separate_app_sound_device";
    private static final String PREF_CATEGORY_APPS = "separate_app_sound_category_apps";
    private static final String SETTING_PACKAGES = "separate_app_sound_packages";
    private static final String SETTING_ENABLED = "separate_app_sound_enabled";
    private static final String SETTING_DEVICE = "separate_app_sound_device";

    private SwitchPreferenceCompat mEnableSwitch;
    private ListPreference mDevicePref;
    private PreferenceCategory mAppsCategory;
    private SharedPreferences mPrefs;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = requireContext();
        setPreferencesFromResource(R.xml.separate_app_sound_preferences, rootKey);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        mEnableSwitch = findPreference(PREF_ENABLE);
        mDevicePref = findPreference(PREF_DEVICE);
        mAppsCategory = findPreference(PREF_CATEGORY_APPS);

        boolean enabled = Settings.System.getInt(context.getContentResolver(), SETTING_ENABLED, 0) == 1;

        if (mEnableSwitch != null) {
            mEnableSwitch.setChecked(enabled);
            mEnableSwitch.setOnPreferenceChangeListener(this);
        }
        if (mDevicePref != null) {
            String currentDevice = Settings.System.getString(context.getContentResolver(), SETTING_DEVICE);
            if (!TextUtils.isEmpty(currentDevice)) {
                mDevicePref.setValue(currentDevice);
            }
            mDevicePref.setOnPreferenceChangeListener(this);
            mDevicePref.setSummary(mDevicePref.getEntry());
        }

        loadApplications(context, enabled);
        updateUiState(enabled);
    }

    private void loadApplications(Context context, boolean isFeatureEnabled) {
        if (mAppsCategory == null) return;
        mAppsCategory.removeAll();

        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            PackageManager pm = context.getPackageManager();
            Intent launchIntent = new Intent(Intent.ACTION_MAIN, null);
            launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> launchables = pm.queryIntentActivities(launchIntent, 0);
            List<AppItem> appList = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Set<String> selectedPackages = getSelectedPackages();

            for (ResolveInfo ri : launchables) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (seen.add(pkg)) {
                    String label = ri.loadLabel(pm).toString();
                    appList.add(new AppItem(label, pkg, ri.loadIcon(pm)));
                }
            }

            Collections.sort(appList, (a, b) -> a.label.compareToIgnoreCase(b.label));

            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (mAppsCategory == null || getContext() == null) return;
                mAppsCategory.removeAll();
                boolean currentEnabled = (mEnableSwitch != null) ? mEnableSwitch.isChecked() : isFeatureEnabled;
                for (AppItem item : appList) {
                    CheckBoxPreference pref = new CheckBoxPreference(context);
                    pref.setKey("app_" + item.packageName);
                    pref.setTitle(item.label);
                    pref.setSummary(item.packageName);
                    pref.setIcon(item.icon);
                    pref.setPersistent(false);
                    pref.setChecked(selectedPackages.contains(item.packageName));
                    pref.setEnabled(currentEnabled);
                    pref.setOnPreferenceChangeListener((preference, newValue) -> {
                        boolean checked = (Boolean) newValue;
                        updatePackageSelection(item.packageName, checked);
                        return true;
                    });
                    mAppsCategory.addPreference(pref);
                }
            });
        });
    }

    private Set<String> getSelectedPackages() {
        Context context = getContext();
        if (context == null) return new HashSet<>();
        String raw = Settings.System.getString(context.getContentResolver(), SETTING_PACKAGES);
        if (TextUtils.isEmpty(raw)) {
            return new HashSet<>();
        }
        return new HashSet<>(Arrays.asList(raw.split(",")));
    }

    private void updatePackageSelection(String packageName, boolean selected) {
        Context context = getContext();
        if (context == null) return;
        Set<String> packages = getSelectedPackages();
        if (selected) {
            packages.add(packageName);
        } else {
            packages.remove(packageName);
        }
        String serialized = TextUtils.join(",", packages);
        Settings.System.putString(context.getContentResolver(), SETTING_PACKAGES, serialized);
        notifyRoutingChanged();
    }

    private void notifyRoutingChanged() {
        Context context = getContext();
        if (context != null) {
            SeparateAppSoundManager.getInstance(context).updateRouting();
            Intent intent = new Intent("org.lineageos.device.DeviceSettings.ACTION_SEPARATE_APP_SOUND_CHANGED");
            context.sendBroadcast(intent);
        }
    }

    private void updateUiState(boolean enabled) {
        if (mDevicePref != null) {
            mDevicePref.setEnabled(enabled);
        }
        if (mAppsCategory != null) {
            mAppsCategory.setEnabled(enabled);
            int count = mAppsCategory.getPreferenceCount();
            for (int i = 0; i < count; i++) {
                Preference p = mAppsCategory.getPreference(i);
                if (p != null) {
                    p.setEnabled(enabled);
                }
            }
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        Context context = getContext();
        if (preference == mEnableSwitch) {
            boolean enabled = (Boolean) newValue;
            if (context != null) {
                Settings.System.putInt(context.getContentResolver(), SETTING_ENABLED, enabled ? 1 : 0);
            }
            if (mEnableSwitch != null) {
                mEnableSwitch.setChecked(enabled);
            }
            updateUiState(enabled);
            notifyRoutingChanged();
            return true;
        } else if (preference == mDevicePref) {
            String val = (String) newValue;
            int index = mDevicePref.findIndexOfValue(val);
            if (index >= 0) {
                mDevicePref.setSummary(mDevicePref.getEntries()[index]);
            }
            if (context != null) {
                Settings.System.putString(context.getContentResolver(), SETTING_DEVICE, val);
            }
            notifyRoutingChanged();
            return true;
        }
        return false;
    }

    private static class AppItem {
        final String label;
        final String packageName;
        final Drawable icon;

        AppItem(String label, String packageName, Drawable icon) {
            this.label = label;
            this.packageName = packageName;
            this.icon = icon;
        }
    }
}

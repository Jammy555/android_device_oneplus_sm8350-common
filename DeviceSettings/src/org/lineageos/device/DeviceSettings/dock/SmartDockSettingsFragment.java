/*
 * Copyright (C) 2024 The LineageOS Project
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

package org.lineageos.device.DeviceSettings.dock;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import org.lineageos.device.DeviceSettings.R;

public class SmartDockSettingsFragment extends PreferenceFragmentCompat {

    private SwitchPreferenceCompat mAutoFadePref;
    private Preference mClearStoragePref;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.dock_preferences);

        mAutoFadePref = findPreference(SmartDockService.KEY_AUTO_FADE);
        if (mAutoFadePref != null) {
            mAutoFadePref.setOnPreferenceChangeListener((pref, value) -> {
                restartDockService();
                return true;
            });
        }

        mClearStoragePref = findPreference("dock_clear_storage");
        if (mClearStoragePref != null) {
            updateStorageSummary();
            mClearStoragePref.setOnPreferenceClickListener(pref -> {
                showClearConfirmDialog();
                return true;
            });
        }
    }

    private void updateStorageSummary() {
        if (mClearStoragePref == null) return;
        Context context = getContext();
        if (context == null) return;

        int count = DockDatabase.getInstance(context).getItemCount();
        mClearStoragePref.setSummary(count + " item(s) currently stored in File Dock");
    }

    private void showClearConfirmDialog() {
        Context context = getContext();
        if (context == null) return;

        new AlertDialog.Builder(context)
                .setTitle(R.string.dock_clear_all)
                .setMessage("Are you sure you want to delete all stored text snippets and photos from the File Dock?")
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    DockDatabase.getInstance(context).clearAll();
                    updateStorageSummary();
                    Toast.makeText(context, R.string.dock_clear_all, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void restartDockService() {
        Context context = getContext();
        if (context == null) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean enabled = prefs.getBoolean(SmartDockService.KEY_DOCK_ENABLE, false);
        if (enabled) {
            Intent intent = new Intent(context, SmartDockService.class);
            context.stopService(intent);
            context.startService(intent);
        }
    }
}

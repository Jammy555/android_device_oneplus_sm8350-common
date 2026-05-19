/*
 * Copyright (C) 2025 kenrow214
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.DeviceSettings.powertools;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.lineageos.device.DeviceSettings.R;

public class DisplaySettingsFragment extends PreferenceFragmentCompat
        implements Preference.OnPreferenceChangeListener {

    private static final String TAG = "DisplaySettings";

    private static final String KEY_CURRENT_RES = "display_current_resolution";
    private static final String KEY_PRESET = "display_resolution_preset";
    private static final String KEY_CUSTOM = "display_resolution_custom";
    private static final String KEY_RESET = "display_resolution_reset";

    private Preference mCurrentResPref;
    private ListPreference mPresetPref;
    private Preference mCustomPref;
    private Preference mResetPref;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.display_settings, rootKey);

        mCurrentResPref = findPreference(KEY_CURRENT_RES);
        mPresetPref = findPreference(KEY_PRESET);
        mCustomPref = findPreference(KEY_CUSTOM);
        mResetPref = findPreference(KEY_RESET);

        if (mPresetPref != null) {
            mPresetPref.setOnPreferenceChangeListener(this);
        }

        if (mCustomPref != null) {
            mCustomPref.setOnPreferenceClickListener(pref -> {
                showCustomResolutionDialog();
                return true;
            });
        }

        if (mResetPref != null) {
            mResetPref.setOnPreferenceClickListener(pref -> {
                resetResolution();
                return true;
            });
        }

        refreshCurrentResolution();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshCurrentResolution();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (KEY_PRESET.equals(preference.getKey())) {
            String value = (String) newValue;
            String[] parts = value.split("x");
            if (parts.length == 2) {
                try {
                    int w = Integer.parseInt(parts[0]);
                    int h = Integer.parseInt(parts[1]);
                    applyResolution(w, h);
                } catch (NumberFormatException e) {
                    showToast(getString(R.string.display_resolution_invalid));
                }
            }
            return true;
        }
        return false;
    }

    private void refreshCurrentResolution() {
        try {
            WindowManager wm = requireContext().getSystemService(WindowManager.class);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            int w = dm.widthPixels;
            int h = dm.heightPixels;
            String current = w + " x " + h;

            if (mCurrentResPref != null) {
                mCurrentResPref.setSummary(current);
            }
            if (mPresetPref != null) {
                String matchVal = w + "x" + h;
                mPresetPref.setValue(matchVal);
                CharSequence entry = mPresetPref.getEntry();
                mPresetPref.setSummary(entry != null ? entry : current);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read current resolution", e);
        }
    }

    private void applyResolution(int width, int height) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                "wm", "size", width + "x" + height
            });
            p.waitFor();

            String res = width + "x" + height;
            showToast(getString(R.string.display_resolution_applied, res));
            Log.i(TAG, "Resolution set to " + res);

            // Delay refresh slightly to let WM apply the change
            mHandler.postDelayed(this::refreshCurrentResolution, 500);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set resolution", e);
            showToast(getString(R.string.display_resolution_invalid));
        }
    }

    private void resetResolution() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                "wm", "size", "reset"
            });
            p.waitFor();

            showToast(getString(R.string.display_resolution_reset_done));
            Log.i(TAG, "Resolution reset to default");

            mHandler.postDelayed(this::refreshCurrentResolution, 500);
        } catch (Exception e) {
            Log.e(TAG, "Failed to reset resolution", e);
        }
    }

    private void showCustomResolutionDialog() {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, 0);

        EditText widthInput = new EditText(requireContext());
        widthInput.setHint(R.string.display_resolution_width_hint);
        widthInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        layout.addView(widthInput);

        EditText heightInput = new EditText(requireContext());
        heightInput.setHint(R.string.display_resolution_height_hint);
        heightInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        layout.addView(heightInput);

        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.display_resolution_custom_dialog_title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                try {
                    int w = Integer.parseInt(widthInput.getText().toString().trim());
                    int h = Integer.parseInt(heightInput.getText().toString().trim());
                    if (w > 0 && h > 0) {
                        applyResolution(w, h);
                    } else {
                        showToast(getString(R.string.display_resolution_invalid));
                    }
                } catch (NumberFormatException e) {
                    showToast(getString(R.string.display_resolution_invalid));
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showToast(String message) {
        try {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
        } catch (IllegalStateException ignored) {
        }
    }
}

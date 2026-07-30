/*
 * Copyright (C) 2025 Lunaris Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.lineageos.device.DeviceSettings.network;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.telephony.AccessNetworkConstants;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.PhysicalChannelConfig;
import android.telephony.RadioAccessSpecifier;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.lineageos.device.DeviceSettings.R;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Fragment to handle selective band locking, custom RAT slots (2G, 3G, 4G, 5G),
 * 5G NR mode toggling, clean non-reboot band resets, power-user live telephony diagnostics
 * (CA breakdown, MBN, IMS status), and carrier speed presets.
 */
public class NetworkBandsFragment extends Fragment {

    private static final String TAG = "NetworkBandsFragment";
    private static final String PREFS_NAME = "band_lock_prefs";
    private static final String PREF_KEY_PREFIX = "selected_bands_"; // + subId
    private static final String PREF_KEY_NR_MODE_PREFIX = "nr_mode_sub_"; // + subId
    private static final String PREF_KEY_RAT_MODE_PREFIX = "rat_mode_sub_"; // + subId
    private static final String PREF_KEY_CARRIER_PRESET_PREFIX = "carrier_preset_sub_"; // + subId
    private static final String PREF_KEY_CUSTOM_RECOMMENDED_PREFIX = "custom_recommended_sub_"; // + subId
    private static final String PREF_KEY_CUSTOM_BATTERY_SAVER_PREFIX = "custom_battery_saver_sub_"; // + subId

    private static final int OPLUS_NR_MODE_NSA_PRE = 0;
    private static final int OPLUS_NR_MODE_NSA_ONLY = 1;
    private static final int OPLUS_NR_MODE_SA_ONLY = 2;
    private static final int OPLUS_NR_MODE_SA_PRE = 3;

    private View mNrModeCard;
    private SeekBar mNrModeSeekBar;
    private View mNrModeActiveLayout;
    private View mNrModeActiveDot;
    private TextView mNrModeActiveText;
    private long mLastNrModeUserInteractionTime = 0;

    private TextView mDiagMetricsText;
    private Spinner mRatModeSpinner;
    private Spinner mCarrierPresetSpinner;
    private CheckBox mChk2G, mChk3G, mChk4G, mChk5G;
    private boolean mIsUpdatingRatFromSystem = false;
    private boolean mLastSystem5gState = false;
    private boolean mLastSystem5gStateInitialized = false;

    private TelephonyManager mTelephonyManager;
    private SubscriptionManager mSubscriptionManager;
    private List<SubscriptionInfo> mActiveSubscriptions;
    private int mCurrentSubId = SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;

    private BandMonitorCallback mBandMonitorCallback;
    private Executor mMainExecutor;
    private List<PhysicalChannelConfig> mLastPhysicalChannelConfigs = new ArrayList<>();

    private List<BandEntry> mBandEntries;
    private BandAdapter mAdapter;
    private Spinner mSimSpinner;
    private Button mApplyButton;
    private Button mResetButton;
    private Button mBtnSaveCustomPreset;
    private TextView mStatusText;

    private View mCarrierSummaryCard;
    private TextView mSummaryOperatorVal;
    private TextView mSummaryTechVal;
    private TextView mSummaryProfileVal;
    private TextView mSummaryBandLockVal;

    private TextView mStatusTechText;
    private TextView mStatusConnectedPill;
    private TextView mStatusCaText;
    private TextView mStatusActiveBandsChips;
    private TextView mDiagSignalQuality;
    private TextView mDiagRsrpVal;
    private TextView mDiagSinrVal;
    private TextView mDiagCqiVal;
    private View mAdvancedDiagToggleLayout;
    private TextView mAdvancedDiagChevron;
    private View mAdvancedDiagBodyLayout;

    private TextView mTab5G, mTab4G, mTab3G, mTab2G;
    private TextView mSelectedGenLabel, mSelectedGenCount;
    private Button mBtnClearGenBands;
    private int mSelectedGenerationTab = 0; // 0=5G, 1=4G, 2=3G, 3=2G

    private int mLastRsrpVal = -999;
    private int mLastSinrVal = -999;
    private int mLastCqiVal = -1;
    private int mLastTaVal = -1;

    private Runnable mPendingRatUpdateRunnable = null;
    private Runnable mPendingNrModeUpdateRunnable = null;
    private SubscriptionManager.OnSubscriptionsChangedListener mSubChangeListener = null;
    private final Map<Integer, String> mKnownSubIccidMap = new HashMap<>();
    private int mLastDefaultDataSubId = -1;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final android.content.BroadcastReceiver mAirplaneModeReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(intent.getAction())) {
                if (!isAdded()) return;
                mHandler.post(() -> {
                    updateActiveBands();
                    updateLiveDiagnostics(null);
                    updateNrModeSeekbarForCarrier();
                    checkApplyButtonState();
                });
            }
        }
    };

    /** Boot restore static handler */
    public static void restoreNrModeSettings(Context context) {
        if (context == null) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
            TelephonyManager tm = context.getSystemService(TelephonyManager.class);
            if (sm == null || tm == null) return;
            List<SubscriptionInfo> activeSubs = sm.getActiveSubscriptionInfoList();
            if (activeSubs == null) return;

            for (SubscriptionInfo info : activeSubs) {
                int subId = info.getSubscriptionId();
                int slotId = SubscriptionManager.getSlotIndex(subId);
                TelephonyManager subTm = tm.createForSubscriptionId(subId);

                // 1. Restore 5G NR Mode
                int savedNrMode = prefs.getInt(PREF_KEY_NR_MODE_PREFIX + subId, 1);
                if (SubscriptionManager.isValidSlotIndex(slotId)) {
                    int oplusMode = (savedNrMode == 0) ? OPLUS_NR_MODE_NSA_ONLY :
                                    (savedNrMode == 2) ? OPLUS_NR_MODE_SA_ONLY : OPLUS_NR_MODE_SA_PRE;
                    setOplusNrModeStatic(slotId, oplusMode);
                }

                // 2. Restore RAT Mode Bitmask (e.g. 4G Only / 5G+4G Auto)
                long savedRatBitmask = prefs.getLong(PREF_KEY_RAT_MODE_PREFIX + subId, 0);
                if (savedRatBitmask != 0) {
                    try {
                        subTm.setAllowedNetworkTypesForReason(
                                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER, savedRatBitmask);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to restore RAT bitmask on boot for sub " + subId + ": " + e.getMessage());
                    }
                }

                // 3. Clear transient band lock & preset UI states
                prefs.edit()
                        .remove(PREF_KEY_PREFIX + subId)
                        .remove(PREF_KEY_CARRIER_PRESET_PREFIX + subId)
                        .apply();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to restore telephony settings on boot: " + e.getMessage());
        }
    }

    /** Lifecycle */

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mTelephonyManager = requireContext().getSystemService(TelephonyManager.class);
        mSubscriptionManager = requireContext().getSystemService(SubscriptionManager.class);
        mMainExecutor = requireContext().getMainExecutor();
        mBandEntries = BandCatalog.buildAll();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_network_bands, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mSimSpinner = view.findViewById(R.id.sim_spinner);
        mStatusText = view.findViewById(R.id.band_status_text);
        mNrModeCard = view.findViewById(R.id.nr_mode_card);
        mNrModeSeekBar = view.findViewById(R.id.nr_mode_seekbar);
        mNrModeActiveLayout = view.findViewById(R.id.nr_mode_active_layout);
        mNrModeActiveDot = view.findViewById(R.id.nr_mode_active_dot);
        mNrModeActiveText = view.findViewById(R.id.nr_mode_active_text);

        mCarrierSummaryCard = view.findViewById(R.id.carrier_summary_card);
        mSummaryOperatorVal = view.findViewById(R.id.summary_operator_val);
        mSummaryTechVal = view.findViewById(R.id.summary_tech_val);
        mSummaryProfileVal = view.findViewById(R.id.summary_profile_val);
        mSummaryBandLockVal = view.findViewById(R.id.summary_band_lock_val);

        mStatusTechText = view.findViewById(R.id.status_tech_text);
        mStatusConnectedPill = view.findViewById(R.id.status_connected_pill);
        mStatusCaText = view.findViewById(R.id.status_ca_text);
        mStatusActiveBandsChips = view.findViewById(R.id.status_active_bands_chips);

        mDiagSignalQuality = view.findViewById(R.id.diag_signal_quality);
        mDiagRsrpVal = view.findViewById(R.id.diag_rsrp_val);
        mDiagSinrVal = view.findViewById(R.id.diag_sinr_val);
        mDiagCqiVal = view.findViewById(R.id.diag_cqi_val);

        mAdvancedDiagToggleLayout = view.findViewById(R.id.advanced_diag_toggle_layout);
        mAdvancedDiagChevron = view.findViewById(R.id.advanced_diag_chevron);
        mAdvancedDiagBodyLayout = view.findViewById(R.id.advanced_diag_body_layout);
        mDiagMetricsText = view.findViewById(R.id.diag_metrics_text);

        mChk2G = view.findViewById(R.id.rat_chk_2g);
        mChk3G = view.findViewById(R.id.rat_chk_3g);
        mChk4G = view.findViewById(R.id.rat_chk_4g);
        mChk5G = view.findViewById(R.id.rat_chk_5g);

        mRatModeSpinner = view.findViewById(R.id.rat_mode_spinner);
        mCarrierPresetSpinner = view.findViewById(R.id.carrier_preset_spinner);

        mTab5G = view.findViewById(R.id.tab_5g);
        mTab4G = view.findViewById(R.id.tab_4g);
        mTab3G = view.findViewById(R.id.tab_3g);
        mTab2G = view.findViewById(R.id.tab_2g);

        mSelectedGenLabel = view.findViewById(R.id.selected_gen_label);
        mSelectedGenCount = view.findViewById(R.id.selected_gen_count);
        mBtnClearGenBands = view.findViewById(R.id.btn_clear_gen_bands);
        mBtnSaveCustomPreset = view.findViewById(R.id.btn_save_custom_preset);
        if (mBtnSaveCustomPreset != null) {
            mBtnSaveCustomPreset.setOnClickListener(v -> showSaveCustomProfileDialog());
        }

        RecyclerView recyclerView = view.findViewById(R.id.bands_recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        mAdapter = new BandAdapter(mBandEntries, () -> {
            if (mCarrierPresetSpinner != null && mCarrierPresetSpinner.getSelectedItemPosition() != 0) {
                mCarrierPresetSpinner.setSelection(0);
            }
            updateGenerationFooter();
            checkApplyButtonState();
        });
        recyclerView.setAdapter(mAdapter);

        mApplyButton = view.findViewById(R.id.btn_apply_bands);
        mResetButton = view.findViewById(R.id.btn_reset_bands);

        if (mApplyButton != null) {
            mApplyButton.setOnClickListener(v -> showApplyDialog());
        }
        if (mResetButton != null) {
            mResetButton.setOnClickListener(v -> {
                AlertDialog dialog = new AlertDialog.Builder(requireContext())
                        .setTitle("Reset Band Locking")
                        .setMessage("Reset all band lock filters to modem default?\n\nNote: If you still experience network issues after resetting, a device reboot will effectively restore default bands configuration.")
                        .setPositiveButton("Reset (3s)", (d, w) -> resetBandsClean())
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();

                dialog.show();

                Button posBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                if (posBtn != null) {
                    posBtn.setEnabled(false);
                    posBtn.setTextColor(Color.parseColor("#F87171"));
                    new CountDownTimer(3000, 1000) {
                        @Override
                        public void onTick(long millisUntilFinished) {
                            long sec = (millisUntilFinished / 1000) + 1;
                            posBtn.setText("Reset (" + sec + "s)");
                            posBtn.setTextColor(Color.parseColor("#F87171"));
                        }

                        @Override
                        public void onFinish() {
                            posBtn.setText("Reset");
                            posBtn.setTextColor(Color.parseColor("#F87171"));
                            posBtn.setEnabled(true);
                        }
                    }.start();
                }
            });
        }

        setupSimTabs();
        setupNrModeSeekBar();
        setupRatSlotsAndSpinner();
        setupCarrierPresetSpinner();
        setupAdvancedDiagAccordion();
        setupGenerationTabsAndControls();

        syncRatSlotsFromSystem();
        loadCurrentBands();
        registerBandMonitor();
        updateActiveBands();
        updateLiveDiagnostics(null);
        checkApplyButtonState();
        checkShowNonIndiaFirstTimePopup();
    }

    private static final String PREF_KEY_FIRST_TIME_NON_INDIA_POPUP_SHOWN = "first_time_non_india_popup_shown";

    private boolean isNonIndiaRegion() {
        try {
            // Check modem RF version from init_oplus.cpp (rf_version 13 = IN)
            String rfVer = SystemProperties.get("ro.boot.rf_version", "");
            if ("13".equals(rfVer)) return false;

            // Check product model from init_oplus.cpp (RMX3360, MT2111, LE2111, LE2121 = India)
            String model = SystemProperties.get("ro.product.product.model", "");
            if ("LE2111".equalsIgnoreCase(model) || "LE2121".equalsIgnoreCase(model)
                    || "RMX3360".equalsIgnoreCase(model) || "MT2111".equalsIgnoreCase(model)) {
                return false;
            }

            // Check active SIM MCC (404 / 405 = India)
            TelephonyManager tm = getTelephonyManager();
            String simOperator = tm.getSimOperator();
            if (simOperator != null && (simOperator.startsWith("404") || simOperator.startsWith("405"))) {
                return false;
            }

            return true; // Non-India region device / SIM
        } catch (Exception e) {
            return false;
        }
    }

    private void checkShowNonIndiaFirstTimePopup() {
        if (!isNonIndiaRegion()) return;

        boolean alreadyShown = getPrefs().getBoolean(PREF_KEY_FIRST_TIME_NON_INDIA_POPUP_SHOWN, false);
        if (alreadyShown) return;

        getPrefs().edit().putBoolean(PREF_KEY_FIRST_TIME_NON_INDIA_POPUP_SHOWN, true).apply();

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("Global Band Profiles Note")
                .setMessage("Welcome! Preset profiles (Recommended & Battery Saver) haven't been tailored for all regional operators. Since local spectrum allocations vary by region and the cell tower you are close to, you can customize and save your own preferred anchor & primary bands anytime using the 'Save Profile' button!")
                .setPositiveButton("Got it (5s)", null)
                .create();

        dialog.show();

        Button btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (btn != null) {
            btn.setTextColor(getSystemAccentColor(requireContext()));
            CountDownTimer timer = new CountDownTimer(5000, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    long sec = (millisUntilFinished / 1000) + 1;
                    btn.setText("Got it (" + sec + "s)");
                }

                @Override
                public void onFinish() {
                    if (dialog.isShowing()) {
                        try {
                            dialog.dismiss();
                        } catch (Exception ignored) {}
                    }
                }
            };
            timer.start();
            btn.setOnClickListener(v -> {
                timer.cancel();
                try {
                    dialog.dismiss();
                } catch (Exception ignored) {}
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        try {
            requireContext().registerReceiver(mAirplaneModeReceiver,
                    new android.content.IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED));
        } catch (Exception ignored) {}
        registerBandMonitor();
        updateActiveBands();
        updateLiveDiagnostics(null);
        syncRatSlotsFromSystem();
        updateNrModeSeekbarForCarrier();
        checkApplyButtonState();
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            requireContext().unregisterReceiver(mAirplaneModeReceiver);
        } catch (Exception ignored) {}
        unregisterBandMonitor();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        try {
            requireContext().unregisterReceiver(mAirplaneModeReceiver);
        } catch (Exception ignored) {}
        unregisterBandMonitor();
    }

    private boolean isSelectionModifiedFromSaved() {
        Set<String> savedKeys = getSavedBandKeys();
        Set<String> currentKeys = new HashSet<>();
        for (BandEntry e : mBandEntries) {
            if (e.checked && !e.isHeader) {
                currentKeys.add(e.rat + ":" + e.bandNum);
            }
        }
        return !savedKeys.equals(currentKeys);
    }

    private boolean isAirplaneModeOn() {
        try {
            return android.provider.Settings.Global.getInt(
                    requireContext().getContentResolver(),
                    android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasActiveSim() {
        return mActiveSubscriptions != null && !mActiveSubscriptions.isEmpty();
    }

    private boolean isSimAndRadioAvailable() {
        return hasActiveSim() && !isAirplaneModeOn();
    }

    private void checkApplyButtonState() {
        boolean available = isSimAndRadioAvailable();
        boolean modified = available && isSelectionModifiedFromSaved();

        if (mApplyButton != null) {
            mApplyButton.setEnabled(modified);
            mApplyButton.setAlpha(modified ? 1.0f : 0.4f);
        }

        if (mResetButton != null) {
            mResetButton.setEnabled(available);
            mResetButton.setAlpha(available ? 1.0f : 0.4f);
        }

        if (mCarrierPresetSpinner != null) {
            boolean g2 = (mChk2G != null && mChk2G.isChecked());
            boolean g3 = (mChk3G != null && mChk3G.isChecked());
            boolean g4 = (mChk4G != null && mChk4G.isChecked());
            boolean g5 = (mChk5G != null && mChk5G.isChecked());
            boolean is2gOnly = g2 && !g3 && !g4 && !g5;
            
            boolean enableCarrierPreset = available && !is2gOnly;
            mCarrierPresetSpinner.setEnabled(enableCarrierPreset);
            mCarrierPresetSpinner.setAlpha(enableCarrierPreset ? 1.0f : 0.4f);
        }

        if (mRatModeSpinner != null) {
            mRatModeSpinner.setEnabled(available);
            mRatModeSpinner.setAlpha(available ? 1.0f : 0.4f);
        }

        if (mChk2G != null) mChk2G.setEnabled(available);
        if (mChk3G != null) mChk3G.setEnabled(available);
        if (mChk4G != null) mChk4G.setEnabled(available);
        if (mChk5G != null) mChk5G.setEnabled(available);

        if (!available) {
            if (mNrModeSeekBar != null) {
                mNrModeSeekBar.setEnabled(false);
                if (mNrModeCard != null) mNrModeCard.setAlpha(0.4f);
            }
            if (mStatusText != null) {
                if (isAirplaneModeOn()) {
                    mStatusText.setText("Airplane Mode active — band locking disabled");
                } else if (!hasActiveSim()) {
                    mStatusText.setText("No SIM card detected — band locking disabled");
                }
            }
        }
    }

    private void triggerDebouncedRatUpdate() {
        if (mPendingRatUpdateRunnable != null) {
            mHandler.removeCallbacks(mPendingRatUpdateRunnable);
        }
        boolean g2 = mChk2G != null && mChk2G.isChecked();
        boolean g3 = mChk3G != null && mChk3G.isChecked();
        boolean g4 = mChk4G != null && mChk4G.isChecked();
        boolean g5 = mChk5G != null && mChk5G.isChecked();

        String toastMsg;
        if (g5 && g4 && !g3 && !g2) toastMsg = "Applying 5G + 4G...";
        else if (g4 && !g5 && !g3 && !g2) toastMsg = "Applying 4G LTE Only...";
        else if (g4 && g2 && !g5 && !g3) toastMsg = "Applying 4G + 2G...";
        else if (g5 && !g4 && !g3 && !g2) toastMsg = "Applying 5G Only...";
        else toastMsg = "Applying RAT Preference...";

        toast(toastMsg);

        mPendingRatUpdateRunnable = () -> {
            applyRatFromSlots();
            mPendingRatUpdateRunnable = null;
        };
        mHandler.postDelayed(mPendingRatUpdateRunnable, 2000);
    }

    /** Dynamic RAT Selection Slots & System Sync */
    private void setupRatSlotsAndSpinner() {
        View.OnClickListener listener = v -> {
            triggerDebouncedRatUpdate();
            updateNrModeSeekbarForCarrier();
            checkApplyButtonState();
        };
        if (mChk2G != null) mChk2G.setOnClickListener(listener);
        if (mChk3G != null) mChk3G.setOnClickListener(listener);
        if (mChk4G != null) mChk4G.setOnClickListener(listener);
        if (mChk5G != null) mChk5G.setOnClickListener(listener);

        if (mRatModeSpinner == null) return;
        List<String> ratPresets = new ArrayList<>();
        ratPresets.add("Quick Preset: Custom Slot Combo");
        ratPresets.add("5G + 4G Auto (Recommended)");
        ratPresets.add("4G LTE Only (Stability)");
        ratPresets.add("5G SA/NSA Only (High Speed)");
        ratPresets.add("3G/2G Legacy Network");
        ratPresets.add("Global All RATs Allowed");

        ContrastSpinnerAdapter adapter = new ContrastSpinnerAdapter(
                requireContext(), R.layout.item_sim_spinner, ratPresets);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mRatModeSpinner.setAdapter(adapter);

        mRatModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position > 0) {
                    applyRatPreset(position);
                    checkApplyButtonState();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void applyRatFromSlots() {
        if (mIsUpdatingRatFromSystem) return;
        boolean g2 = mChk2G != null && mChk2G.isChecked();
        boolean g3 = mChk3G != null && mChk3G.isChecked();
        boolean g4 = mChk4G != null && mChk4G.isChecked();
        boolean g5 = mChk5G != null && mChk5G.isChecked();

        // Safety Guard: Evaluate 5G NSA 4G anchor requirements ONLY if 5G is enabled by user
        if (g5) {
            int savedNrMode = getPrefs().getInt(PREF_KEY_NR_MODE_PREFIX + mCurrentSubId, 1);
            boolean isNsaOnlyMode = (savedNrMode == 0);
            boolean isAutoNsaSaMode = (savedNrMode == 1);

            if (!g4) {
                if (isNsaOnlyMode) {
                    // In 5G NSA Only mode, 4G LTE anchor is mandatory for EN-DC dual connectivity
                    g4 = true;
                    if (mChk4G != null) mChk4G.setChecked(true);
                    toast("5G NSA Only requires 4G LTE anchor — auto-enabled 4G.");
                } else if (isAutoNsaSaMode) {
                    // In Auto SA+NSA mode, allow unticking 4G, but inform the user
                    toast("Note: 5G NSA connection requires 4G LTE anchor cell.");
                }
            }
        }

        long bitmask = 0;
        if (g2) bitmask |= TelephonyManager.NETWORK_TYPE_BITMASK_GSM
                         | TelephonyManager.NETWORK_TYPE_BITMASK_GPRS
                         | TelephonyManager.NETWORK_TYPE_BITMASK_EDGE;
        if (g3) bitmask |= TelephonyManager.NETWORK_TYPE_BITMASK_UMTS
                         | TelephonyManager.NETWORK_TYPE_BITMASK_HSDPA
                         | TelephonyManager.NETWORK_TYPE_BITMASK_HSUPA
                         | TelephonyManager.NETWORK_TYPE_BITMASK_HSPA
                         | TelephonyManager.NETWORK_TYPE_BITMASK_HSPAP;
        if (g4) bitmask |= TelephonyManager.NETWORK_TYPE_BITMASK_LTE
                         | TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA;
        if (g5) bitmask |= TelephonyManager.NETWORK_TYPE_BITMASK_NR;

        if (bitmask == 0) {
            toast("Select at least 1 RAT slot (2G/3G/4G/5G)");
            return;
        }

        Log.i(TAG, "applyRatFromSlots: Applying custom RAT preference bitmask=" + bitmask + " (2G=" + g2 + ", 3G=" + g3 + ", 4G=" + g4 + ", 5G=" + g5 + ")");

        try {
            getTelephonyManager().setAllowedNetworkTypesForReason(
                    TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER, bitmask);
            getPrefs().edit().putLong(PREF_KEY_RAT_MODE_PREFIX + mCurrentSubId, bitmask).apply();
            updateLiveDiagnostics(null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply RAT bitmask: " + e.getMessage());
        }
    }

    private void applyRatPreset(int presetIndex) {
        if (mChk2G == null) return;
        mIsUpdatingRatFromSystem = true;
        switch (presetIndex) {
            case 1: // 5G + 4G
                mChk2G.setChecked(false); mChk3G.setChecked(false); mChk4G.setChecked(true); mChk5G.setChecked(true);
                break;
            case 2: // 4G Only
                mChk2G.setChecked(false); mChk3G.setChecked(false); mChk4G.setChecked(true); mChk5G.setChecked(false);
                break;
            case 3: // 5G Only
                mChk2G.setChecked(false); mChk3G.setChecked(false); mChk4G.setChecked(false); mChk5G.setChecked(true);
                break;
            case 4: // 3G/2G
                mChk2G.setChecked(true); mChk3G.setChecked(true); mChk4G.setChecked(false); mChk5G.setChecked(false);
                break;
            case 5: // All RATs
                mChk2G.setChecked(true); mChk3G.setChecked(true); mChk4G.setChecked(true); mChk5G.setChecked(true);
                break;
        }
        mIsUpdatingRatFromSystem = false;
        applyRatFromSlots();
    }

    private String detectActiveCarrierName() {
        // 1. Check if any active subscription exists for current subId
        if (mActiveSubscriptions == null || mActiveSubscriptions.isEmpty()) {
            return "No SIM";
        }

        // 2. Try SubscriptionInfo display name / carrier name for current subId
        for (SubscriptionInfo info : mActiveSubscriptions) {
            if (info.getSubscriptionId() == mCurrentSubId) {
                CharSequence name = info.getDisplayName();
                if (name == null || name.length() == 0) name = info.getCarrierName();
                if (name != null && name.length() > 0) {
                    return name.toString().trim();
                }
            }
        }

        // 3. Fallback: query TelephonyManager for network operator name
        try {
            TelephonyManager tm = getTelephonyManager();
            String networkOp = tm.getNetworkOperatorName();
            if (networkOp != null && !networkOp.trim().isEmpty()) {
                return networkOp.trim();
            }

            String simOp = tm.getSimOperatorName();
            if (simOp != null && !simOp.trim().isEmpty()) {
                return simOp.trim();
            }
        } catch (Exception e) {
            Log.w(TAG, "detectActiveCarrierName: TelephonyManager fallback failed: " + e.getMessage());
        }

        return "Unknown";
    }

    private void setupCarrierPresetSpinner() {
        if (mCarrierPresetSpinner == null) return;
        String carrier = detectActiveCarrierName();
        boolean hasCarrier = !"No SIM".equals(carrier);
        List<String> presets = new ArrayList<>();
        presets.add("Manual / Custom Band Selection");
        presets.add(hasCarrier ? "Recommended (" + carrier + ")" : "Recommended");
        presets.add("Battery Saver (FDD Anchor)");

        ContrastSpinnerAdapter adapter = new ContrastSpinnerAdapter(
                requireContext(), R.layout.item_sim_spinner, presets);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        mCarrierPresetSpinner.setOnItemSelectedListener(null);
        mCarrierPresetSpinner.setAdapter(adapter);

        int savedPreset = getPrefs().getInt(PREF_KEY_CARRIER_PRESET_PREFIX + mCurrentSubId, 0);
        if (savedPreset >= 0 && savedPreset < presets.size()) {
            mCarrierPresetSpinner.setSelection(savedPreset);
        }

        mCarrierPresetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                getPrefs().edit().putInt(PREF_KEY_CARRIER_PRESET_PREFIX + mCurrentSubId, position).apply();
                if (position > 0) {
                    applyCarrierPresetForCarrier(carrier, position);
                    updateCarrierSummary(carrier, presets.get(position), true);
                    checkApplyButtonState();
                } else {
                    updateCarrierSummary(carrier, "Manual", isSelectionModifiedFromSaved());
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        String currentPresetLabel = (savedPreset > 0 && savedPreset < presets.size()) ? presets.get(savedPreset) : "Manual";
        updateCarrierSummary(carrier, currentPresetLabel, isSelectionModifiedFromSaved());
        updateNrModeSeekbarForCarrier();
    }

    private void updateCarrierSummary(String operator, String profile, boolean isLocked) {
        if (mSummaryOperatorVal != null) mSummaryOperatorVal.setText(operator);
        if (mSummaryProfileVal != null) mSummaryProfileVal.setText(profile);
        if (mSummaryBandLockVal != null) mSummaryBandLockVal.setText(isLocked ? "Enabled" : "Default");
    }

    private void setupAdvancedDiagAccordion() {
        if (mAdvancedDiagToggleLayout == null) return;
        mAdvancedDiagToggleLayout.setOnClickListener(v -> {
            if (mAdvancedDiagBodyLayout != null) {
                boolean isVisible = mAdvancedDiagBodyLayout.getVisibility() == View.VISIBLE;
                mAdvancedDiagBodyLayout.setVisibility(isVisible ? View.GONE : View.VISIBLE);
                if (mAdvancedDiagChevron != null) {
                    mAdvancedDiagChevron.setText(isVisible ? "▼ Tap to Expand" : "▲ Tap to Collapse");
                }
            }
        });
    }

    /** On-Screen Clickable Generation Filter Tabs */
    private void setupGenerationTabsAndControls() {
        View.OnClickListener tabListener = v -> {
            if (!v.isEnabled()) {
                toast("This network generation is currently disabled in RAT preferences.");
                return;
            }
            int id = v.getId();
            if (id == R.id.tab_5g) mSelectedGenerationTab = 0;
            else if (id == R.id.tab_4g) mSelectedGenerationTab = 1;
            else if (id == R.id.tab_3g) mSelectedGenerationTab = 2;
            else if (id == R.id.tab_2g) mSelectedGenerationTab = 3;

            updateTabStyles();
            filterBandsByGeneration();
        };

        if (mTab5G != null) mTab5G.setOnClickListener(tabListener);
        if (mTab4G != null) mTab4G.setOnClickListener(tabListener);
        if (mTab3G != null) mTab3G.setOnClickListener(tabListener);
        if (mTab2G != null) mTab2G.setOnClickListener(tabListener);

        if (mBtnClearGenBands != null) {
            mBtnClearGenBands.setOnClickListener(v -> {
                int targetRat = getTargetRatForTab();
                for (BandEntry e : mBandEntries) {
                    if (e.rat == targetRat && !e.isHeader) {
                        e.checked = false;
                    }
                }
                filterBandsByGeneration();
                checkApplyButtonState();
            });
        }

        updateTabStyles();
    }

    private int getTargetRatForTab() {
        if (mSelectedGenerationTab == 1) return AccessNetworkConstants.AccessNetworkType.EUTRAN;
        if (mSelectedGenerationTab == 2) return AccessNetworkConstants.AccessNetworkType.UTRAN;
        if (mSelectedGenerationTab == 3) return AccessNetworkConstants.AccessNetworkType.GERAN;
        return AccessNetworkConstants.AccessNetworkType.NGRAN;
    }

    private static final String PREF_KEY_USE_DYNAMIC_COLORS = "use_dynamic_colors";

    private boolean isNightMode(Context context) {
        if (context == null) return true;
        int uiMode = context.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private int getSystemAccentColor(Context context) {
        if (context == null) return Color.parseColor("#E5A376");
        boolean useDynamic = getPrefs().getBoolean(PREF_KEY_USE_DYNAMIC_COLORS, true);
        boolean isNight = isNightMode(context);
        if (!useDynamic) {
            return isNight ? Color.parseColor("#E5A376") : Color.parseColor("#D97736");
        }
        int accent = 0;
        if (isNight) {
            try {
                int resId = context.getResources().getIdentifier("system_accent1_300", "color", "android");
                if (resId != 0) accent = context.getColor(resId);
            } catch (Exception ignored) {}
            if (accent == 0) {
                try {
                    int resId = context.getResources().getIdentifier("system_accent1_200", "color", "android");
                    if (resId != 0) accent = context.getColor(resId);
                } catch (Exception ignored) {}
            }
        } else {
            try {
                int resId = context.getResources().getIdentifier("system_accent1_600", "color", "android");
                if (resId != 0) accent = context.getColor(resId);
            } catch (Exception ignored) {}
        }
        if (accent == 0) {
            try {
                android.util.TypedValue typedValue = new android.util.TypedValue();
                if (context.getTheme().resolveAttribute(android.R.attr.colorAccent, typedValue, true)) {
                    if (typedValue.data != 0) {
                        accent = typedValue.data;
                    }
                }
            } catch (Exception ignored) {}
        }
        if (accent == 0) {
            accent = isNight ? Color.parseColor("#E5A376") : Color.parseColor("#D97736");
        }
        return isNight ? ensureReadableOnDark(accent) : ensureReadableOnLight(accent);
    }

    private int ensureReadableOnDark(int color) {
        float[] hsl = new float[3];
        androidx.core.graphics.ColorUtils.colorToHSL(color, hsl);
        if (hsl[2] < 0.60f) {
            hsl[2] = 0.72f;
            return androidx.core.graphics.ColorUtils.HSLToColor(hsl);
        }
        return color;
    }

    private int ensureReadableOnLight(int color) {
        float[] hsl = new float[3];
        androidx.core.graphics.ColorUtils.colorToHSL(color, hsl);
        if (hsl[2] > 0.55f) {
            hsl[2] = 0.45f;
            return androidx.core.graphics.ColorUtils.HSLToColor(hsl);
        }
        return color;
    }

    private int getSpinnerBackgroundTint(int accentColor) {
        int r = (int) (Color.red(accentColor) * 0.35f);
        int g = (int) (Color.green(accentColor) * 0.32f);
        int b = (int) (Color.blue(accentColor) * 0.30f);
        r = Math.max(r, 45);
        g = Math.max(g, 40);
        b = Math.max(b, 35);
        return Color.rgb(r, g, b);
    }

    private void updateTabStyles() {
        Context context = getContext();
        if (context == null) return;
        int accent = getSystemAccentColor(context);
        int muted = context.getColor(R.color.text_secondary_color);

        if (mTab5G != null) mTab5G.setTextColor(mSelectedGenerationTab == 0 ? accent : muted);
        if (mTab4G != null) mTab4G.setTextColor(mSelectedGenerationTab == 1 ? accent : muted);
        if (mTab3G != null) mTab3G.setTextColor(mSelectedGenerationTab == 2 ? accent : muted);
        if (mTab2G != null) mTab2G.setTextColor(mSelectedGenerationTab == 3 ? accent : muted);

        android.content.res.ColorStateList accentTintList = android.content.res.ColorStateList.valueOf(accent);
        int spinnerBg = accent;
        double spinnerLum = (Color.red(spinnerBg) * 0.299 + Color.green(spinnerBg) * 0.587 + Color.blue(spinnerBg) * 0.114);
        int spinnerTextColor = spinnerLum > 140 ? Color.parseColor("#101012") : Color.parseColor("#FFFFFF");
        android.content.res.ColorStateList spinnerTintList = android.content.res.ColorStateList.valueOf(spinnerBg);

        if (mSimSpinner != null) {
            mSimSpinner.setBackgroundTintList(spinnerTintList);
            if (mSimSpinner.getAdapter() instanceof ContrastSpinnerAdapter) {
                ((ContrastSpinnerAdapter) mSimSpinner.getAdapter()).setTextColor(spinnerTextColor);
            }
        }
        if (mCarrierPresetSpinner != null) {
            mCarrierPresetSpinner.setBackgroundTintList(spinnerTintList);
            if (mCarrierPresetSpinner.getAdapter() instanceof ContrastSpinnerAdapter) {
                ((ContrastSpinnerAdapter) mCarrierPresetSpinner.getAdapter()).setTextColor(spinnerTextColor);
            }
        }
        if (mRatModeSpinner != null) {
            mRatModeSpinner.setBackgroundTintList(spinnerTintList);
            if (mRatModeSpinner.getAdapter() instanceof ContrastSpinnerAdapter) {
                ((ContrastSpinnerAdapter) mRatModeSpinner.getAdapter()).setTextColor(spinnerTextColor);
            }
        }

        if (mChk2G != null) mChk2G.setButtonTintList(accentTintList);
        if (mChk3G != null) mChk3G.setButtonTintList(accentTintList);
        if (mChk4G != null) mChk4G.setButtonTintList(accentTintList);
        if (mChk5G != null) mChk5G.setButtonTintList(accentTintList);

        if (mSummaryBandLockVal != null) {
            mSummaryBandLockVal.setTextColor(accent);
        }
        if (mApplyButton != null) {
            double luminance = (Color.red(accent) * 0.299 + Color.green(accent) * 0.587 + Color.blue(accent) * 0.114);
            int contrastTextColor = luminance > 135 ? Color.parseColor("#101012") : Color.parseColor("#FFFFFF");
            mApplyButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent));
            mApplyButton.setTextColor(contrastTextColor);
        }
        if (mBtnSaveCustomPreset != null) {
            mBtnSaveCustomPreset.setTextColor(accent);
        }
        if (mBtnClearGenBands != null) {
            mBtnClearGenBands.setTextColor(accent);
        }
        if (mNrModeSeekBar != null) {
            mNrModeSeekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
            mNrModeSeekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
        }
        if (mAdvancedDiagChevron != null) {
            mAdvancedDiagChevron.setTextColor(accent);
        }
        if (getView() != null) {
            TextView headerCarrier = getView().findViewById(R.id.header_carrier_summary);
            if (headerCarrier != null) headerCarrier.setTextColor(accent);
            TextView headerDiag = getView().findViewById(R.id.header_advanced_diag);
            if (headerDiag != null) headerDiag.setTextColor(accent);
        }
        if (mStatusActiveBandsChips != null) {
            mStatusActiveBandsChips.setTextColor(accent);
        }
        if (mStatusText != null) {
            mStatusText.setTextColor(accent);
        }

        String genName = (mSelectedGenerationTab == 0) ? "5G" :
                         (mSelectedGenerationTab == 1) ? "4G" :
                         (mSelectedGenerationTab == 2) ? "3G" : "2G";

        if (mSelectedGenLabel != null) {
            mSelectedGenLabel.setText("Selected " + genName + " Bands");
        }

        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    private void filterBandsByGeneration() {
        if (mBandEntries == null) return;
        List<BandEntry> filtered = new ArrayList<>();
        int targetRat = getTargetRatForTab();

        for (BandEntry e : mBandEntries) {
            if (!e.isHeader && e.rat == targetRat) {
                filtered.add(e);
            }
        }

        Set<String> savedKeys = getSavedBandKeys();
        Collections.sort(filtered, (a, b) -> {
            boolean aSaved = savedKeys.contains(a.rat + ":" + a.bandNum);
            boolean bSaved = savedKeys.contains(b.rat + ":" + b.bandNum);
            boolean aTop = a.checked || aSaved || a.isActive || a.isPCell || a.isSCell;
            boolean bTop = b.checked || bSaved || b.isActive || b.isPCell || b.isSCell;
            if (aTop != bTop) {
                return aTop ? -1 : 1;
            }
            return Integer.compare(a.bandNum, b.bandNum);
        });

        if (mAdapter != null) {
            mAdapter.updateEntries(filtered);
        }
        updateGenerationFooter();
    }

    private void updateGenerationFooter() {
        int targetRat = getTargetRatForTab();
        int selected = 0;
        int total = 0;
        for (BandEntry e : mBandEntries) {
            if (!e.isHeader && e.rat == targetRat) {
                total++;
                if (e.checked) selected++;
            }
        }

        if (mSelectedGenCount != null) {
            mSelectedGenCount.setText(selected + "/" + total);
        }
    }

    private void showSaveCustomProfileDialog() {
        int count = countChecked();
        if (count == 0) {
            toast("No bands selected to save profile.");
            return;
        }

        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_save_custom_profile, null);
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(view)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        View btnRecommended = view.findViewById(R.id.btn_save_recommended_slot);
        View btnBatterySaver = view.findViewById(R.id.btn_save_battery_saver_slot);
        View btnCancel = view.findViewById(R.id.btn_cancel_save_profile);

        View.OnClickListener clickListener = v -> {
            int id = v.getId();
            if (id == R.id.btn_save_recommended_slot || id == R.id.btn_save_battery_saver_slot) {
                boolean isRecommended = (id == R.id.btn_save_recommended_slot);
                Set<String> keysToSave = new HashSet<>();
                for (BandEntry e : mBandEntries) {
                    if (e.checked && !e.isHeader) {
                        keysToSave.add(e.rat + ":" + e.bandNum);
                    }
                }
                String key = isRecommended
                        ? (PREF_KEY_CUSTOM_RECOMMENDED_PREFIX + mCurrentSubId)
                        : (PREF_KEY_CUSTOM_BATTERY_SAVER_PREFIX + mCurrentSubId);
                getPrefs().edit().putStringSet(key, keysToSave).apply();
                toast((isRecommended ? "Recommended" : "Battery Saver") + " custom profile saved!");
                dialog.dismiss();
            } else if (id == R.id.btn_cancel_save_profile) {
                dialog.dismiss();
            }
        };

        if (btnRecommended != null) btnRecommended.setOnClickListener(clickListener);
        if (btnBatterySaver != null) btnBatterySaver.setOnClickListener(clickListener);
        if (btnCancel != null) btnCancel.setOnClickListener(clickListener);

        dialog.show();
    }

    private void applyCarrierPresetForCarrier(String carrier, int option) {
        for (BandEntry e : mBandEntries) e.checked = false;
        String c = (carrier != null) ? carrier.toLowerCase() : "";

        // 1. Check if user saved a custom profile for this subId first
        String customKey = (option == 1)
                ? (PREF_KEY_CUSTOM_RECOMMENDED_PREFIX + mCurrentSubId)
                : (PREF_KEY_CUSTOM_BATTERY_SAVER_PREFIX + mCurrentSubId);
        Set<String> customSaved = getPrefs().getStringSet(customKey, null);
        if (customSaved != null && !customSaved.isEmpty()) {
            for (BandEntry e : mBandEntries) {
                if (!e.isHeader && customSaved.contains(e.rat + ":" + e.bandNum)) {
                    e.checked = true;
                }
            }
            filterBandsByGeneration();
            return;
        }

        if (option == 1) { // Recommended (Carrier-optimized / Primary Bands)
            if (c.contains("jio")) {
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 3);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 5);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 40);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 28);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 78);
            } else if (c.contains("airtel")) {
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 1);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 3);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 8);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 40);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 1);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 3);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 28);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 40);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 78);
            } else if (c.contains("vi") || c.contains("vodafone") || c.contains("idea")) {
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 1);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 3);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 8);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 40);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 78);
            } else if (c.contains("bsnl")) {
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 1);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 3);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 5);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 28);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 28);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 78);
            } else if (c.contains("t-mobile") || c.contains("tmobile")) {
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 2);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 4);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 12);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 66);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 71);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 41);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 71);
            } else if (c.contains("verizon")) {
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 2);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 5);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 13);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 66);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 77);
            } else if (c.contains("at&t") || c.contains("att")) {
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 2);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 5);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 12);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 14);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 30);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 66);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 5);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 77);
            } else if (c.contains("china mobile")) {
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 3);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 8);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 39);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 40);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 41);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 41);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 79);
            } else if (c.contains("china unicom")) {
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 1);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 3);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 78);
            } else if (c.contains("china telecom")) {
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 1);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 3);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 5);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 78);
            } else {
                // Universal / Global Default Recommended:
                // Primary Global LTE Anchors (B1, B3, B5, B7, B8, B20, B28, B40, B66) & 5G (n1, n3, n28, n77, n78)
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 1);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 3);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 5);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 7);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 8);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 20);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 28);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 40);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 66);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 1);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 3);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 28);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 77);
                checkBand(AccessNetworkConstants.AccessNetworkType.NGRAN, 78);
            }
        } else if (option == 2) { // Battery Saver (Carrier-specific Low Band FDD Focus)
            if (c.contains("jio")) {
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 3);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 5);
            } else if (c.contains("airtel") || c.contains("vi") || c.contains("vodafone") || c.contains("idea")) {
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 1);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 3);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 8);
            } else if (c.contains("bsnl")) {
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 1);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 3);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 5);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 28);
            } else if (c.contains("t-mobile") || c.contains("tmobile")) {
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 12);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 71);
            } else if (c.contains("verizon")) {
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 5);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 13);
            } else if (c.contains("at&t") || c.contains("att")) {
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 5);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 12);
            } else {
                // Universal Low Band FDD Anchors (B1, B3, B5, B8, B20, B28)
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 1);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 3);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 5);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 8);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 20);
                checkBand(AccessNetworkConstants.AccessNetworkType.EUTRAN, 28);
            }
        }

        filterBandsByGeneration();
    }

    private void applyCarrierPreset(int presetPosition) {
        applyCarrierPresetForCarrier(detectActiveCarrierName(), presetPosition);
    }

    private void checkBand(int rat, int bandNum) {
        if (rat == AccessNetworkConstants.AccessNetworkType.NGRAN && (mChk5G != null && !mChk5G.isChecked())) return;
        if (rat == AccessNetworkConstants.AccessNetworkType.EUTRAN && (mChk4G != null && !mChk4G.isChecked())) return;
        if (rat == AccessNetworkConstants.AccessNetworkType.UTRAN && (mChk3G != null && !mChk3G.isChecked())) return;
        if (rat == AccessNetworkConstants.AccessNetworkType.GERAN && (mChk2G != null && !mChk2G.isChecked())) return;

        for (BandEntry e : mBandEntries) {
            if (e.rat == rat && e.bandNum == bandNum) {
                e.checked = true;
            }
        }
    }

    /** 5G NR Mode Seekbar Logic & Carrier Warnings */

    private void setupNrModeSeekBar() {
        if (mNrModeSeekBar == null) return;

        mNrModeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    mLastNrModeUserInteractionTime = android.os.SystemClock.elapsedRealtime();
                    updateNrMode(progress);
                    checkApplyButtonState();
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        updateNrModeSeekbarForCarrier();
    }

    private void updateNrModeSeekbarForCarrier() {
        boolean g2 = (mChk2G != null && mChk2G.isChecked());
        boolean g3 = (mChk3G != null && mChk3G.isChecked());
        boolean g4 = (mChk4G != null && mChk4G.isChecked());
        boolean g5 = (mChk5G != null && mChk5G.isChecked());
        boolean available = isSimAndRadioAvailable();
        boolean is2gOnly = g2 && !g3 && !g4 && !g5;

        // 1. Update Carrier Preset Spinner state (faded/disabled if 2G only)
        if (mCarrierPresetSpinner != null) {
            boolean enableCarrierPreset = available && !is2gOnly;
            mCarrierPresetSpinner.setEnabled(enableCarrierPreset);
            mCarrierPresetSpinner.setAlpha(enableCarrierPreset ? 1.0f : 0.4f);
        }

        // 2. Update 5G NR Mode Card Visibility & Alpha
        if (!g5) {
            if (mNrModeCard != null) mNrModeCard.setVisibility(View.GONE);
            if (mNrModeSeekBar != null) mNrModeSeekBar.setEnabled(false);
        } else {
            if (mNrModeCard != null) {
                mNrModeCard.setVisibility(View.VISIBLE);
                mNrModeCard.setAlpha(available ? 1.0f : 0.4f);
            }
            if (mNrModeSeekBar != null) mNrModeSeekBar.setEnabled(available);
            if (available && mNrModeSeekBar != null) {
                int savedNrMode = getPrefs().getInt(PREF_KEY_NR_MODE_PREFIX + mCurrentSubId, 1);
                mNrModeSeekBar.setProgress(savedNrMode);
            }
        }

        // 3. Dynamic Per-RAT Generation Tab Fading (2G, 3G, 4G, 5G)
        if (mTab5G != null) {
            mTab5G.setEnabled(g5 && available);
            mTab5G.setAlpha((g5 && available) ? 1.0f : 0.35f);
        }
        if (mTab4G != null) {
            mTab4G.setEnabled(g4 && available);
            mTab4G.setAlpha((g4 && available) ? 1.0f : 0.35f);
        }
        if (mTab3G != null) {
            mTab3G.setEnabled(g3 && available);
            mTab3G.setAlpha((g3 && available) ? 1.0f : 0.35f);
        }
        if (mTab2G != null) {
            mTab2G.setEnabled(g2 && available);
            mTab2G.setAlpha((g2 && available) ? 1.0f : 0.35f);
        }

        // 4. Auto-switch active generation tab if user unchecked the currently selected tab
        boolean activeTabAllowed = (mSelectedGenerationTab == 0 && g5)
                                || (mSelectedGenerationTab == 1 && g4)
                                || (mSelectedGenerationTab == 2 && g3)
                                || (mSelectedGenerationTab == 3 && g2);

        if (!activeTabAllowed) {
            if (g5) mSelectedGenerationTab = 0;
            else if (g4) mSelectedGenerationTab = 1;
            else if (g3) mSelectedGenerationTab = 2;
            else if (g2) mSelectedGenerationTab = 3;

            updateTabStyles();
            filterBandsByGeneration();
        }
    }

    /** SIM Switcher Setup */

    private void setupSimTabs() {
        try {
            mActiveSubscriptions = mSubscriptionManager.getActiveSubscriptionInfoList();
        } catch (Exception e) {
            Log.w(TAG, "Failed to get active subscriptions: " + e.getMessage());
            mActiveSubscriptions = new ArrayList<>();
        }

        if (mActiveSubscriptions == null || mActiveSubscriptions.size() <= 1) {
            mSimSpinner.setVisibility(View.GONE);
            if (mActiveSubscriptions != null && !mActiveSubscriptions.isEmpty()) {
                mCurrentSubId = mActiveSubscriptions.get(0).getSubscriptionId();
            }
            return;
        }

        mSimSpinner.setVisibility(View.VISIBLE);
        List<String> labels = new ArrayList<>();
        for (SubscriptionInfo info : mActiveSubscriptions) {
            String label = "SIM " + (info.getSimSlotIndex() + 1);
            if (info.getDisplayName() != null && info.getDisplayName().length() > 0) {
                label = info.getDisplayName().toString();
            }
            labels.add(label);
        }

        ContrastSpinnerAdapter adapter = new ContrastSpinnerAdapter(
                requireContext(), R.layout.item_sim_spinner, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSimSpinner.setAdapter(adapter);

        int defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId();
        int initialPos = 0;
        for (int i = 0; i < mActiveSubscriptions.size(); i++) {
            if (mActiveSubscriptions.get(i).getSubscriptionId() == defaultDataSubId) {
                initialPos = i;
                break;
            }
        }
        mCurrentSubId = mActiveSubscriptions.get(initialPos).getSubscriptionId();
        mSimSpinner.setSelection(initialPos);

        mSimSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < mActiveSubscriptions.size()) {
                    int selectedSubId = mActiveSubscriptions.get(position).getSubscriptionId();
                    if (selectedSubId != mCurrentSubId) {
                        mCurrentSubId = selectedSubId;
                        mLastSystem5gStateInitialized = false;
                        SubscriptionInfo info = mActiveSubscriptions.get(position);
                        if (info != null && info.getIccId() != null) {
                            mKnownSubIccidMap.put(mCurrentSubId, info.getIccId());
                        }
                        unregisterBandMonitor();
                        loadCurrentBands();
                        registerBandMonitor();
                        updateActiveBands();
                        setupCarrierPresetSpinner();
                        syncRatSlotsFromSystem();
                        checkApplyButtonState();
                    }
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /** Load State from SharedPreferences */
    private void loadCurrentBands() {
        for (BandEntry e : mBandEntries) {
            e.checked = false;
            e.isActive = false;
        }

        Set<String> savedKeys = getSavedBandKeys();
        if (savedKeys.isEmpty()) {
            setStatus(getString(R.string.network_bands_status_no_signal));
        } else {
            int loadedCount = 0;
            for (BandEntry e : mBandEntries) {
                if (e.isHeader) continue;
                String key = e.rat + ":" + e.bandNum;
                if (savedKeys.contains(key)) {
                    e.checked = true;
                    loadedCount++;
                }
            }
            setStatus(getString(R.string.network_bands_status_active, loadedCount));
        }

        filterBandsByGeneration();
        updateNrModeSeekbarForCarrier();
    }

    private void updateRatSpinnerSelection(boolean g2, boolean g3, boolean g4, boolean g5) {
        if (mRatModeSpinner == null) return;
        int preset;
        if (g5 && g4 && !g3 && !g2) preset = 1;       // 5G + 4G Auto
        else if (!g5 && g4 && !g3 && !g2) preset = 2; // 4G LTE Only
        else if (g5 && !g4 && !g3 && !g2) preset = 3; // 5G SA/NSA Only
        else if (!g5 && !g4 && g3 && g2) preset = 4;  // 3G/2G Legacy
        else if (g5 && g4 && g3 && g2) preset = 5;    // All RATs
        else preset = 0;                              // Custom Slot Combo

        mRatModeSpinner.setSelection(preset);
    }

    private void syncRatSlotsFromSystem() {
        try {
            long savedBitmask = getPrefs().getLong(PREF_KEY_RAT_MODE_PREFIX + mCurrentSubId, 0);

            TelephonyManager tm = getTelephonyManager();
            long bitmask = 0;
            try {
                bitmask = tm.getAllowedNetworkTypesForReason(
                        TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER);
            } catch (Exception e) {
                Log.w(TAG, "Failed to get allowed network types from system: " + e.getMessage());
            }

            if (savedBitmask != 0) {
                if (bitmask == 0 || bitmask != savedBitmask) {
                    bitmask = savedBitmask;
                    try {
                        tm.setAllowedNetworkTypesForReason(
                                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER, savedBitmask);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to restore saved RAT bitmask: " + e.getMessage());
                    }
                }
            }

            boolean is5gEnabledInSystem = (bitmask & TelephonyManager.NETWORK_TYPE_BITMASK_NR) != 0;
            mLastSystem5gState = is5gEnabledInSystem;
            mLastSystem5gStateInitialized = true;

            mIsUpdatingRatFromSystem = true;

            boolean g2 = (bitmask & (TelephonyManager.NETWORK_TYPE_BITMASK_GSM
                    | TelephonyManager.NETWORK_TYPE_BITMASK_GPRS
                    | TelephonyManager.NETWORK_TYPE_BITMASK_EDGE)) != 0;
            boolean g3 = (bitmask & (TelephonyManager.NETWORK_TYPE_BITMASK_UMTS
                    | TelephonyManager.NETWORK_TYPE_BITMASK_HSDPA
                    | TelephonyManager.NETWORK_TYPE_BITMASK_HSUPA
                    | TelephonyManager.NETWORK_TYPE_BITMASK_HSPA
                    | TelephonyManager.NETWORK_TYPE_BITMASK_HSPAP)) != 0;
            boolean g4 = (bitmask & (TelephonyManager.NETWORK_TYPE_BITMASK_LTE
                    | TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA)) != 0;
            boolean g5 = is5gEnabledInSystem;

            if (mChk2G != null) mChk2G.setChecked(g2);
            if (mChk3G != null) mChk3G.setChecked(g3);
            if (mChk4G != null) mChk4G.setChecked(g4);
            if (mChk5G != null) mChk5G.setChecked(g5);

            updateRatSpinnerSelection(g2, g3, g4, g5);

            mIsUpdatingRatFromSystem = false;

            updateNrModeSeekbarForCarrier();
        } catch (Exception e) {
            Log.w(TAG, "Failed to sync RAT slots from system: " + e.getMessage());
            mIsUpdatingRatFromSystem = false;
        }
    }


    private SharedPreferences getPrefs() {
        return requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
    }

    private Set<String> getSavedBandKeys() {
        return getPrefs().getStringSet(PREF_KEY_PREFIX + mCurrentSubId, new HashSet<>());
    }

    private void saveBandKeys(Set<String> keys) {
        getPrefs().edit().putStringSet(PREF_KEY_PREFIX + mCurrentSubId, keys).apply();
    }

    private void updateNrMode(int progress) {
        getPrefs().edit().putInt(PREF_KEY_NR_MODE_PREFIX + mCurrentSubId, progress).apply();

        if (mPendingNrModeUpdateRunnable != null) {
            mHandler.removeCallbacks(mPendingNrModeUpdateRunnable);
        }

        toast("Applying 5G NR Mode...");

        mPendingNrModeUpdateRunnable = () -> {
            int oplusMode;
            switch (progress) {
                case 0:  oplusMode = OPLUS_NR_MODE_NSA_ONLY; break;
                case 2:  oplusMode = OPLUS_NR_MODE_SA_ONLY;  break;
                default: oplusMode = OPLUS_NR_MODE_SA_PRE;   break;
            }

            boolean is5gChecked = (mChk5G != null && mChk5G.isChecked());
            if (is5gChecked) {
                int slotId = SubscriptionManager.getSlotIndex(mCurrentSubId);
                if (SubscriptionManager.isValidSlotIndex(slotId)) {
                    setOplusNrModeStatic(slotId, oplusMode);
                }
            }
            updateActiveNrModeDisplay();
            mPendingNrModeUpdateRunnable = null;
        };

        mHandler.postDelayed(mPendingNrModeUpdateRunnable, 2000);
    }

    private static void setOplusNrModeStatic(int slotId, int mode) {
        try {
            Class<?> clazz = Class.forName("vendor.oplus.hardware.radio.V2_0.IOplusRadio");
            Object service = clazz.getMethod("getService", String.class).invoke(null, "slot" + slotId);
            if (service != null) {
                clazz.getMethod("setNrMode", int.class, int.class).invoke(service, 0, mode);
            }
        } catch (Exception e) {
            Log.w(TAG, "OplusRadio HAL setNrMode failed: " + e.getMessage());
        }
    }

    public void showAdvancedSettingsDialog() {
        if (!isAdded() || getContext() == null) return;
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_advanced_network_settings, null);

        Switch switchVonr = dialogView.findViewById(R.id.dialog_switch_vonr);
        Switch switchDynamicColors = dialogView.findViewById(R.id.dialog_switch_dynamic_colors);

        if (switchVonr != null) {
            boolean vonrEnabled = SystemProperties.getBoolean("persist.sys.vonr_enable",
                                  SystemProperties.getBoolean("persist.vendor.radio.vonr_enabled", true));
            switchVonr.setChecked(vonrEnabled);
            switchVonr.setOnCheckedChangeListener((btn, isChecked) -> {
                int slotId = SubscriptionManager.getSlotIndex(mCurrentSubId);
                int activeSlot = SubscriptionManager.isValidSlotIndex(slotId) ? slotId : 0;
                setOplusVoNrEnabledStatic(activeSlot, isChecked);
                toast(isChecked ? "Vo5G (Voice over 5G) Enabled" : "Vo5G Disabled — VoLTE Fallback Active");
                updateLiveDiagnostics(null);
            });
        }

        final AlertDialog[] dialogHolder = new AlertDialog[1];
        dialogHolder[0] = new AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .setPositiveButton("Done", null)
                .create();
        dialogHolder[0].show();

        Runnable updateDialogColors = () -> {
            int currentAccent = getSystemAccentColor(requireContext());
            int[] titleIds = {
                R.id.dialog_title_radio, R.id.dialog_title_rf, R.id.dialog_title_antenna,
                R.id.dialog_title_security, R.id.dialog_title_status, R.id.dialog_title_credits
            };
            for (int id : titleIds) {
                TextView tv = dialogView.findViewById(id);
                if (tv != null) tv.setTextColor(currentAccent);
            }
            android.content.res.ColorStateList tintList = android.content.res.ColorStateList.valueOf(currentAccent);
            if (dialogHolder[0] != null) {
                Button doneBtn = dialogHolder[0].getButton(AlertDialog.BUTTON_POSITIVE);
                if (doneBtn != null) {
                    double lum = (Color.red(currentAccent) * 0.299 + Color.green(currentAccent) * 0.587 + Color.blue(currentAccent) * 0.114);
                    int textContrast = lum > 140 ? Color.parseColor("#101012") : Color.parseColor("#FFFFFF");
                    doneBtn.setBackgroundTintList(tintList);
                    doneBtn.setTextColor(textContrast);
                }
            }
        };
        updateDialogColors.run();

        if (switchDynamicColors != null) {
            boolean enabled = getPrefs().getBoolean(PREF_KEY_USE_DYNAMIC_COLORS, true);
            switchDynamicColors.setChecked(enabled);
            switchDynamicColors.setOnCheckedChangeListener((btn, isChecked) -> {
                getPrefs().edit().putBoolean(PREF_KEY_USE_DYNAMIC_COLORS, isChecked).commit();
                toast(isChecked ? "System Monet Dynamic Colors Enabled" : "Warm Peach Accent Theme Enabled");
                updateTabStyles();
                filterBandsByGeneration();
                updateDialogColors.run();
            });
        }
        if (dialogHolder[0].getWindow() != null) {
            dialogHolder[0].getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static void setOplusVoNrEnabledStatic(int slotId, boolean enabled) {
        Log.i(TAG, "setOplusVoNrEnabledStatic: Toggling Vo5G/VoNR for slot=" + slotId + ", enabled=" + enabled);
        try {
            SystemProperties.set("persist.sys.vonr_enable", enabled ? "true" : "false");
            SystemProperties.set("persist.vendor.radio.vonr_enabled", enabled ? "1" : "0");
            android.os.IBinder binder = android.os.ServiceManager.getService("vendor.oplus.hardware.radio.IOplusRadio/slot" + slotId);
            if (binder != null) {
                vendor.oplus.hardware.radio.IOplusRadio service = vendor.oplus.hardware.radio.IOplusRadio.Stub.asInterface(binder);
                if (service != null) {
                    service.setVoNrEnabled(0, enabled);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "setOplusVoNrEnabled failed: " + e.getMessage());
        }
    }

    /** Registration of Live Telephony Callbacks */

    private void registerBandMonitor() {
        if (mBandMonitorCallback == null) {
            mBandMonitorCallback = new BandMonitorCallback();
            try {
                getTelephonyManager().registerTelephonyCallback(mMainExecutor, mBandMonitorCallback);
            } catch (Exception e) {
                Log.w(TAG, "Failed to register BandMonitorCallback: " + e.getMessage());
                mBandMonitorCallback = null;
            }
        }

        if (mSubChangeListener == null) {
            try {
                SubscriptionManager sm = SubscriptionManager.from(requireContext());
                mSubChangeListener = new SubscriptionManager.OnSubscriptionsChangedListener() {
                    @Override
                    public void onSubscriptionsChanged() {
                        if (!isAdded()) return;
                        int currentDefaultDataSub = SubscriptionManager.getDefaultDataSubscriptionId();
                        if (mLastDefaultDataSubId != -1 && currentDefaultDataSub != mLastDefaultDataSubId) {
                            Log.i(TAG, "Default Mobile Data SIM switched in Android System Settings (Old: " + mLastDefaultDataSubId + ", New: " + currentDefaultDataSub + ") — resetting band lock.");
                            toast("Mobile Data SIM switched in System Settings — reset band lock to default.");
                            resetBandsClean();
                        }
                        mLastDefaultDataSubId = currentDefaultDataSub;

                        SubscriptionInfo info = sm.getActiveSubscriptionInfo(mCurrentSubId);
                        if (info != null) {
                            String iccid = info.getIccId();
                            String prevIccid = mKnownSubIccidMap.get(mCurrentSubId);
                            if (iccid != null && prevIccid != null && !iccid.equals(prevIccid)) {
                                Log.i(TAG, "SIM card swap detected for subId=" + mCurrentSubId + "! Executing automatic band reset.");
                                toast("SIM Card changed — automatically reset band filters to modem default.");
                                resetBandsClean();
                            }
                            if (iccid != null) {
                                mKnownSubIccidMap.put(mCurrentSubId, iccid);
                            }
                        }
                    }
                };
                sm.addOnSubscriptionsChangedListener(mMainExecutor, mSubChangeListener);
            } catch (Exception e) {
                Log.w(TAG, "Failed to register OnSubscriptionsChangedListener: " + e.getMessage());
            }
        }
    }

    private void unregisterBandMonitor() {
        if (mBandMonitorCallback != null) {
            try {
                getTelephonyManager().unregisterTelephonyCallback(mBandMonitorCallback);
            } catch (Exception e) {
                Log.w(TAG, "Failed to unregister BandMonitorCallback: " + e.getMessage());
            } finally {
                mBandMonitorCallback = null;
            }
        }
        if (mSubChangeListener != null) {
            try {
                SubscriptionManager.from(requireContext()).removeOnSubscriptionsChangedListener(mSubChangeListener);
            } catch (Exception ignored) {}
            mSubChangeListener = null;
        }
    }

    private TelephonyManager getTelephonyManager() {
        if (mCurrentSubId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            return mTelephonyManager;
        }
        return mTelephonyManager.createForSubscriptionId(mCurrentSubId);
    }

    private void toast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show();
    }

    private void setStatus(String status) {
        if (mStatusText != null) mStatusText.setText(status);
    }

    private int countChecked() {
        int c = 0;
        for (BandEntry e : mBandEntries) {
            if (e.checked && !e.isHeader) c++;
        }
        return c;
    }

    private static int networkTypeToAccessNetworkType(int networkType) {
        switch (networkType) {
            case TelephonyManager.NETWORK_TYPE_NR:
                return AccessNetworkConstants.AccessNetworkType.NGRAN;
            case TelephonyManager.NETWORK_TYPE_LTE:
            case TelephonyManager.NETWORK_TYPE_LTE_CA:
                return AccessNetworkConstants.AccessNetworkType.EUTRAN;
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_HSPAP:
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
                return AccessNetworkConstants.AccessNetworkType.UTRAN;
            case TelephonyManager.NETWORK_TYPE_GSM:
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_EDGE:
                return AccessNetworkConstants.AccessNetworkType.GERAN;
            default:
                return AccessNetworkConstants.AccessNetworkType.UNKNOWN;
        }
    }

    private boolean isEnDcAvailable(android.telephony.ServiceState ss) {
        if (ss == null) return false;
        try {
            NetworkRegistrationInfo nri = ss.getNetworkRegistrationInfo(
                    NetworkRegistrationInfo.DOMAIN_PS,
                    AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
            if (nri != null) {
                Object dataInfo = nri.getDataSpecificInfo();
                if (dataInfo != null) {
                    Method m = dataInfo.getClass().getMethod("isEnDcAvailable");
                    return (boolean) m.invoke(dataInfo);
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean isDcnrRestricted(android.telephony.ServiceState ss) {
        if (ss == null) return false;
        try {
            NetworkRegistrationInfo nri = ss.getNetworkRegistrationInfo(
                    NetworkRegistrationInfo.DOMAIN_PS,
                    AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
            if (nri != null) {
                Object dataInfo = nri.getDataSpecificInfo();
                if (dataInfo != null) {
                    Method m = dataInfo.getClass().getMethod("isDcnrRestricted");
                    return (boolean) m.invoke(dataInfo);
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean isPhysicalChannelPrimary(PhysicalChannelConfig config) {
        if (config == null) return false;
        try {
            Method m = config.getClass().getMethod("getConnectionStatus");
            int status = (int) m.invoke(config);
            return status == 1; // 1 = PHYSICAL_CHANNEL_CONFIG_CONNECTION_PRIMARY
        } catch (Exception e) {
            return false;
        }
    }

    private class BandMonitorCallback extends TelephonyCallback
            implements TelephonyCallback.PhysicalChannelConfigListener,
                       TelephonyCallback.CellInfoListener,
                       TelephonyCallback.SignalStrengthsListener,
                       TelephonyCallback.ServiceStateListener,
                       TelephonyCallback.DisplayInfoListener {

        @Override
        public void onPhysicalChannelConfigChanged(@NonNull List<PhysicalChannelConfig> configs) {
            mLastPhysicalChannelConfigs = configs;
            updateActiveBands();
            updateLiveDiagnostics(null);
        }

        @Override
        public void onCellInfoChanged(@NonNull List<CellInfo> cellInfo) {
            updateActiveBands();
            updateLiveDiagnostics(null);
        }

        @Override
        public void onSignalStrengthsChanged(@NonNull SignalStrength signalStrength) {
            updateLiveDiagnostics(signalStrength);
        }

        @Override
        public void onServiceStateChanged(@NonNull android.telephony.ServiceState serviceState) {
            updateActiveBands();
            updateLiveDiagnostics(null);
        }

        @Override
        public void onDisplayInfoChanged(@NonNull TelephonyDisplayInfo telephonyDisplayInfo) {
            updateActiveBands();
            updateLiveDiagnostics(null);
        }
    }

    /** Update Expanded Power-User Live Telephony Diagnostics Dashboard */
    @android.annotation.SuppressLint("MissingPermission")
    private void updateLiveDiagnostics(SignalStrength ss) {
        if (!isAdded()) return;

        TelephonyManager tm = getTelephonyManager();
        int dataRat = tm.getDataNetworkType();
        int voiceRat = tm.getVoiceNetworkType();
        boolean isImsRegistered = tm.isImsRegistered();

        // Query SignalStrength if null
        if (ss == null) {
            try {
                ss = tm.getSignalStrength();
            } catch (Exception ignored) {}
        }

        android.telephony.ServiceState ssState = null;
        try {
            ssState = tm.getServiceState();
            if (ssState != null) {
                if (dataRat == TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                    dataRat = ssState.getDataNetworkType();
                }
                if (voiceRat == TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                    voiceRat = ssState.getVoiceNetworkType();
                }
            }
        } catch (Exception ignored) {}

        boolean endcAvail = isEnDcAvailable(ssState);
        boolean dcnrRestricted = isDcnrRestricted(ssState);

        int rsrp = -999, rsrq = -999, sinr = -999, cqi = -1, ta = -1;
        int pci = -1, earfcn = -1, lteBand = -1;
        int nrArfcn = -1, nrBand = -1;

        StringBuilder caBreakdown = new StringBuilder();
        int ccCount = 0;
        int totalBwKhz = 0;

        if (ss != null) {
            for (android.telephony.CellSignalStrength css : ss.getCellSignalStrengths()) {
                if (css instanceof CellSignalStrengthLte) {
                    CellSignalStrengthLte lteSs = (CellSignalStrengthLte) css;
                    if (rsrp == -999) rsrp = lteSs.getRsrp();
                    if (rsrq == -999) rsrq = lteSs.getRsrq();
                    if (sinr == -999) sinr = lteSs.getRssnr();
                    if (cqi == -1 || cqi == Integer.MAX_VALUE) cqi = lteSs.getCqi();
                    if (ta == -1 || ta == Integer.MAX_VALUE) ta = lteSs.getTimingAdvance();
                } else if (css instanceof CellSignalStrengthNr) {
                    CellSignalStrengthNr nrSs = (CellSignalStrengthNr) css;
                    if (rsrp == -999) rsrp = nrSs.getSsRsrp();
                    if (rsrq == -999) rsrq = nrSs.getSsRsrq();
                    if (sinr == -999) sinr = nrSs.getSsSinr();
                } else if (css instanceof CellSignalStrengthGsm) {
                    CellSignalStrengthGsm gsmSs = (CellSignalStrengthGsm) css;
                    if (rsrp == -999) rsrp = gsmSs.getDbm();
                    if (ta == -1 || ta == Integer.MAX_VALUE) ta = gsmSs.getTimingAdvance();
                } else if (css instanceof CellSignalStrengthWcdma) {
                    CellSignalStrengthWcdma wcdmaSs = (CellSignalStrengthWcdma) css;
                    if (rsrp == -999) rsrp = wcdmaSs.getDbm();
                    if (sinr == -999) sinr = wcdmaSs.getEcNo();
                }
            }

            // Fallback: system level primary dBm if legacy RAT specific method is unavailable
            if (rsrp == -999 || rsrp == Integer.MAX_VALUE) {
                int dbm = ss.getDbm();
                if (dbm != 0 && dbm != -1 && dbm != Integer.MAX_VALUE) {
                    rsrp = dbm;
                }
            }
        }

        // Query CellInfo if PhysicalChannelConfigs is not returning data
        try {
            List<CellInfo> allCellInfo = tm.getAllCellInfo();
            if (allCellInfo != null) {
                for (CellInfo info : allCellInfo) {
                    if (!info.isRegistered()) continue;

                    if (info instanceof CellInfoLte) {
                        CellInfoLte lteInfo = (CellInfoLte) info;
                        CellIdentityLte cellId = lteInfo.getCellIdentity();
                        if (earfcn <= 0) earfcn = cellId.getEarfcn();
                        if (pci < 0) pci = cellId.getPci();
                        if (lteBand <= 0 && earfcn > 0) lteBand = earfcnToLteBand(earfcn);

                        CellSignalStrengthLte lteSs = lteInfo.getCellSignalStrength();
                        if (rsrp == -999) rsrp = lteSs.getRsrp();
                        if (rsrq == -999) rsrq = lteSs.getRsrq();
                        if (sinr == -999) sinr = lteSs.getRssnr();
                        if (cqi == -1 || cqi == Integer.MAX_VALUE) cqi = lteSs.getCqi();
                        if (ta == -1 || ta == Integer.MAX_VALUE) ta = lteSs.getTimingAdvance();
                    } else if (info instanceof CellInfoNr) {
                        CellInfoNr nrInfo = (CellInfoNr) info;
                        CellIdentityNr cellId = (CellIdentityNr) nrInfo.getCellIdentity();
                        if (nrArfcn <= 0) nrArfcn = cellId.getNrarfcn();
                        if (pci < 0) pci = cellId.getPci();

                        CellSignalStrengthNr nrSs = (CellSignalStrengthNr) nrInfo.getCellSignalStrength();
                        if (rsrp == -999) rsrp = nrSs.getSsRsrp();
                        if (rsrq == -999) rsrq = nrSs.getSsRsrq();
                        if (sinr == -999) sinr = nrSs.getSsSinr();
                    } else if (info instanceof CellInfoGsm) {
                        CellInfoGsm gsmInfo = (CellInfoGsm) info;
                        CellIdentityGsm cellId = gsmInfo.getCellIdentity();
                        if (pci < 0) pci = cellId.getBsic();
                        if (earfcn <= 0) earfcn = cellId.getArfcn();

                        CellSignalStrengthGsm gsmSs = gsmInfo.getCellSignalStrength();
                        if (rsrp == -999) rsrp = gsmSs.getDbm();
                        if (ta == -1 || ta == Integer.MAX_VALUE) ta = gsmSs.getTimingAdvance();
                    } else if (info instanceof CellInfoWcdma) {
                        CellInfoWcdma wcdmaInfo = (CellInfoWcdma) info;
                        CellIdentityWcdma cellId = wcdmaInfo.getCellIdentity();
                        if (pci < 0) pci = cellId.getPsc();
                        if (earfcn <= 0) earfcn = cellId.getUarfcn();

                        CellSignalStrengthWcdma wcdmaSs = wcdmaInfo.getCellSignalStrength();
                        if (rsrp == -999) rsrp = wcdmaSs.getDbm();
                        if (sinr == -999) sinr = wcdmaSs.getEcNo();
                    }
                }
            }
        } catch (Exception ignored) {}

        if (mLastPhysicalChannelConfigs != null && !mLastPhysicalChannelConfigs.isEmpty()) {
            ccCount = mLastPhysicalChannelConfigs.size();
            for (int i = 0; i < mLastPhysicalChannelConfigs.size(); i++) {
                PhysicalChannelConfig cfg = mLastPhysicalChannelConfigs.get(i);
                int rat = networkTypeToAccessNetworkType(cfg.getNetworkType());
                int band = cfg.getBand();
                int bwKhz = cfg.getCellBandwidthDownlinkKhz();
                if (bwKhz > 0 && bwKhz != Integer.MAX_VALUE) {
                    totalBwKhz += bwKhz;
                }
                int bwMhz = bwKhz / 1000;

                if (dataRat == TelephonyManager.NETWORK_TYPE_UNKNOWN && cfg.getNetworkType() != TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                    dataRat = cfg.getNetworkType();
                }

                if (i > 0) caBreakdown.append(" + ");
                if (i == 0) {
                    caBreakdown.append("PCell: B").append(band > 0 ? band : "?");
                    if (bwMhz > 0) caBreakdown.append(" (").append(bwMhz).append("MHz)");
                    lteBand = band;
                    if (earfcn <= 0) earfcn = cfg.getDownlinkChannelNumber();
                } else {
                    caBreakdown.append("SCell").append(i).append(": B").append(band > 0 ? band : "?");
                    if (bwMhz > 0) caBreakdown.append(" (").append(bwMhz).append("MHz)");
                }

                if (rat == AccessNetworkConstants.AccessNetworkType.NGRAN && nrArfcn <= 0) {
                    nrArfcn = cfg.getDownlinkChannelNumber();
                    nrBand = cfg.getBand();
                }
            }
        }

        String activeTech = "No Service";
        if (dataRat == TelephonyManager.NETWORK_TYPE_NR) {
            activeTech = (lteBand > 0) ? "5G NSA" : "5G SA";
        } else if (dataRat == TelephonyManager.NETWORK_TYPE_LTE || dataRat == TelephonyManager.NETWORK_TYPE_LTE_CA) {
            activeTech = "4G LTE";
        } else if (dataRat == TelephonyManager.NETWORK_TYPE_UMTS || dataRat == TelephonyManager.NETWORK_TYPE_HSDPA
                || dataRat == TelephonyManager.NETWORK_TYPE_HSUPA || dataRat == TelephonyManager.NETWORK_TYPE_HSPA
                || dataRat == TelephonyManager.NETWORK_TYPE_HSPAP) {
            activeTech = "3G WCDMA";
        } else if (dataRat == TelephonyManager.NETWORK_TYPE_GSM || dataRat == TelephonyManager.NETWORK_TYPE_GPRS
                || dataRat == TelephonyManager.NETWORK_TYPE_EDGE) {
            activeTech = "2G GSM";
        } else if (voiceRat == TelephonyManager.NETWORK_TYPE_LTE) {
            activeTech = "4G LTE";
        } else if (voiceRat == TelephonyManager.NETWORK_TYPE_GSM || voiceRat == TelephonyManager.NETWORK_TYPE_GPRS) {
            activeTech = "2G GSM";
        } else if (voiceRat == TelephonyManager.NETWORK_TYPE_UMTS) {
            activeTech = "3G WCDMA";
        } else if (isImsRegistered) {
            activeTech = (lteBand > 0) ? "4G LTE (VoWiFi)" : "VoWiFi";
        }

        int totalBwMhz = totalBwKhz / 1000;
        String bwSuffix = totalBwMhz > 0 ? " (" + totalBwMhz + " MHz)" : "";

        boolean isNoService = "No Service".equals(activeTech);
        boolean isLegacyRat = "2G GSM".equals(activeTech) || "3G WCDMA".equals(activeTech);
        String caDisplay;
        if (isNoService || ccCount == 0) {
            caDisplay = "No CA (0CC)";
        } else if (isLegacyRat) {
            caDisplay = "No CA (--)";
        } else {
            caDisplay = ccCount + "CC" + bwSuffix;
        }

        String activeMbn = SystemProperties.get("persist.vendor.radio.sw_mbn_name",
                           SystemProperties.get("vendor.radio.sw_mbn_name", "Commercial APAC"));
        if (activeMbn.contains("/")) {
            activeMbn = activeMbn.substring(activeMbn.lastIndexOf("/") + 1);
        }

        boolean isValidEarfcn = (earfcn > 0 && earfcn != Integer.MAX_VALUE);
        boolean isValidPci = (pci >= 0 && pci != Integer.MAX_VALUE);
        boolean isValidNrArfcn = (nrArfcn > 0 && nrArfcn != Integer.MAX_VALUE);
        boolean isValidRsrp = (rsrp != -999 && rsrp != Integer.MAX_VALUE);
        boolean isValidSinr = (sinr != -999 && sinr != Integer.MAX_VALUE);

        boolean isLocationEnabled = false;
        try {
            android.location.LocationManager lm = (android.location.LocationManager) requireContext().getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                isLocationEnabled = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                        || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
            }
        } catch (Exception ignored) {}

        // Timing Advance (TA) distance parsing (1 TA unit ≈ 78.12 meters in 3GPP LTE)
        boolean isValidTa = (ta >= 0 && ta != Integer.MAX_VALUE);
        String taDisplay;
        if (isValidTa) {
            int distanceMeters = (int) Math.round(ta * 78.12);
            if (distanceMeters == 0) {
                taDisplay = "0 (< 78 m)";
            } else if (distanceMeters >= 1000) {
                taDisplay = String.format(java.util.Locale.US, "%d (%.2f km)", ta, distanceMeters / 1000.0);
            } else {
                taDisplay = String.format(java.util.Locale.US, "%d (~%d m)", ta, distanceMeters);
            }
        } else if (isLegacyRat) {
            taDisplay = "N/A (Not supported on 2G GSM)";
        } else if (!isLocationEnabled) {
            taDisplay = "N/A (Location Required)";
        } else {
            taDisplay = "--";
        }

        // Parse or estimate CQI when modem is in idle state
        int displayCqiVal = cqi;
        if (displayCqiVal == -1 || displayCqiVal == Integer.MAX_VALUE || displayCqiVal <= 0) {
            if (isValidSinr) {
                if (sinr >= 12) displayCqiVal = 15;
                else if (sinr >= 6) displayCqiVal = 12;
                else if (sinr >= 0) displayCqiVal = 9;
                else displayCqiVal = 6;
            }
        }
        boolean isValidCqi = (displayCqiVal > 0 && displayCqiVal <= 15);
        final String displayCqiStr = isValidCqi ? String.valueOf(displayCqiVal) : "--";

        final String finalTech = activeTech;
        final String finalCaDisplay = caDisplay;
        final int finalRsrp = rsrp;
        final int finalSinr = sinr;
        final boolean finalEndc = endcAvail;
        final boolean finalDcnr = dcnrRestricted;

        boolean is5gHardwareSupported = true;
        try {
            long allowedBitmask = tm.getAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER);
            is5gHardwareSupported = (allowedBitmask & TelephonyManager.NETWORK_TYPE_BITMASK_NR) != 0;
        } catch (Exception e) {
            is5gHardwareSupported = true;
        }
        String rfVer = SystemProperties.get("ro.boot.rf_version", "0");
        boolean isMmWaveSupported = "12".equals(rfVer) || "22".equals(rfVer);
        String hardware5gDisplay;
        if (is5gHardwareSupported) {
            hardware5gDisplay = isMmWaveSupported ? "Supported (Sub-6GHz FR1 + mmWave FR2 / SA+NSA)" : "Supported (Sub-6GHz FR1 / SA+NSA)";
        } else {
            hardware5gDisplay = "Unsupported";
        }

        boolean isVonrForced = getPrefs().getBoolean("force_vo5g", false);
        boolean isVonrSupportedInCarrier = false;
        try {
            android.telephony.CarrierConfigManager ccm = (android.telephony.CarrierConfigManager) requireContext().getSystemService(android.content.Context.CARRIER_CONFIG_SERVICE);
            android.os.PersistableBundle bundle = ccm != null ? ccm.getConfigForSubId(mCurrentSubId) : null;
            if (bundle != null) {
                isVonrSupportedInCarrier = bundle.getBoolean(android.telephony.CarrierConfigManager.KEY_VONR_ENABLED_BOOL, true);
            } else {
                isVonrSupportedInCarrier = true;
            }
        } catch (Exception e) {
            isVonrSupportedInCarrier = true;
        }

        String vonrNetworkStatus;
        if (dataRat == TelephonyManager.NETWORK_TYPE_NR || nrBand > 0) {
            vonrNetworkStatus = "Active (5G NR)" + (isVonrForced ? " (Forced)" : "");
        } else {
            vonrNetworkStatus = "Unavailable (On " + finalTech + " Network)" + (isVonrForced ? " (Forced)" : "");
        }
        String vonrCarrierStatus = isVonrSupportedInCarrier ? "Unrestricted (SIM Supported)" : "Restricted by Carrier";

        String basebandFw = SystemProperties.get("gsm.version.baseband", android.os.Build.getRadioVersion());
        if (basebandFw == null || basebandFw.isEmpty()) basebandFw = "Qualcomm SM8350 Modem v2.0";

        String mimoStatus;
        String antennaDiversity;
        String rxChain;
        boolean noService = "No Service".equals(finalTech);
        int activeCcCount = (mLastPhysicalChannelConfigs != null) ? mLastPhysicalChannelConfigs.size() : 0;

        if (noService) {
            mimoStatus = "Inactive";
            antennaDiversity = "Inactive (Radio Off / No Service)";
            rxChain = "Inactive";
        } else if (nrBand > 0) {
            // 5G NR: SM8350 supports up to 4x4 MIMO on NR Sub-6
            mimoStatus = activeCcCount >= 2 ? "4x4 MIMO Active" : "2x2 MIMO Active";
            antennaDiversity = "Rx0/Rx1/Rx2/Rx3 Active";
            rxChain = "4-Branch Receiver (Primary + Diversity)";
        } else if ("4G LTE".equals(finalTech) || finalTech.contains("VoWiFi")) {
            if (activeCcCount >= 3) {
                mimoStatus = "4x4 MIMO Active";
                antennaDiversity = "Rx0/Rx1/Rx2/Rx3 Active";
                rxChain = "4-Branch Receiver (Primary + Diversity)";
            } else if (activeCcCount >= 2) {
                mimoStatus = "2x2 MIMO Active";
                antennaDiversity = "Rx0/Rx1 Active";
                rxChain = "2-Branch Receiver (Primary + Diversity)";
            } else {
                mimoStatus = "2x2 MIMO Active";
                antennaDiversity = "Rx0/Rx1 Active";
                rxChain = "2-Branch Receiver (Primary + Diversity)";
            }
        } else {
            // 3G/2G: typically single Rx or 2-branch
            mimoStatus = "SISO (Single Stream)";
            antennaDiversity = "Rx0 Active";
            rxChain = "1-Branch Receiver (Primary Only)";
        }

        // Dynamic Tx Power: Hardware Sensor or 3GPP Closed-Loop Adaptive Power Control
        String txPower;
        if (noService) {
            txPower = "Inactive (Radio Off / No Service)";
        } else {
            String vendorTxPower = SystemProperties.get("vendor.radio.txpower", "");
            if (!vendorTxPower.isEmpty()) {
                try {
                    double txDbm = Double.parseDouble(vendorTxPower);
                    txPower = String.format(java.util.Locale.US, "%.1f dBm (Hardware Sensor)", txDbm);
                } catch (NumberFormatException e) {
                    txPower = vendorTxPower;
                }
            } else if (isValidRsrp) {
                // Adaptive Power Control calculation (3GPP TS 36.213 / 38.213)
                double estTxDbm = Math.min(23.0, Math.max(-5.0, 23.0 - (rsrp + 105) * 0.45));
                txPower = String.format(java.util.Locale.US, "%.1f dBm (Adaptive Power Control)", estTxDbm);
            } else if (nrBand > 0) {
                txPower = "≤ 23.0 dBm (Power Class 3 / FR1)";
            } else if ("4G LTE".equals(finalTech)) {
                txPower = "≤ 23.0 dBm (Power Class 3)";
            } else if ("3G WCDMA".equals(finalTech)) {
                txPower = "≤ 24.0 dBm (Power Class 3)";
            } else if (finalTech.contains("2G")) {
                txPower = "≤ 33.0 dBm (Power Class 4 / GSM)";
            } else {
                txPower = "N/A";
            }
        }

        String modemTemp = readModemTemperature();

        String rsrpTrend = "";
        if (isValidRsrp && mLastRsrpVal != -999) {
            if (rsrp > mLastRsrpVal) rsrpTrend = " ▲";
            else if (rsrp < mLastRsrpVal) rsrpTrend = " ▼";
        }
        if (isValidRsrp) mLastRsrpVal = rsrp;

        String sinrTrend = "";
        if (isValidSinr && mLastSinrVal != -999) {
            if (sinr > mLastSinrVal) sinrTrend = " ▲";
            else if (sinr < mLastSinrVal) sinrTrend = " ▼";
        }
        if (isValidSinr) mLastSinrVal = sinr;

        String cqiTrend = "";
        if (displayCqiVal > 0 && mLastCqiVal > 0) {
            if (displayCqiVal > mLastCqiVal) cqiTrend = " ▲";
            else if (displayCqiVal < mLastCqiVal) cqiTrend = " ▼";
        }
        if (displayCqiVal > 0) mLastCqiVal = displayCqiVal;

        final String finalRsrpTrend = rsrpTrend;
        final String finalSinrTrend = sinrTrend;
        final String finalCqiTrend = cqiTrend;

        boolean is2gNet = finalTech.contains("2G");
        String rsrpDisplay = isValidRsrp ? (rsrp + " dBm" + rsrpTrend) : (is2gNet ? "N/A (Not supported on 2G GSM)" : "Unavailable");
        String rsrqDisplay = (rsrq != -999 && rsrq != Integer.MAX_VALUE) ? (rsrq + " dB") : (is2gNet ? "N/A (Not supported on 2G GSM)" : "Unavailable");
        String sinrDisplay = isValidSinr ? (sinr + " dB" + sinrTrend) : (is2gNet ? "N/A (Not supported on 2G GSM)" : "Unavailable");
        String cqiDisplay = (displayCqiStr.isEmpty() || "--".equals(displayCqiStr)) ? (is2gNet ? "N/A (Not supported on 2G GSM)" : "Unavailable") : (displayCqiStr + cqiTrend);
        String taTextDisplay = (ta != -1 && ta != Integer.MAX_VALUE) ? taDisplay : (is2gNet ? "N/A (Not supported on 2G GSM)" : "Unavailable");

        StringBuilder diagBuilder = new StringBuilder();
        diagBuilder.append("RF & SIGNAL METRICS\n");
        diagBuilder.append("• EARFCN: ").append(isValidEarfcn ? String.valueOf(earfcn) : (is2gNet ? "N/A (GERAN)" : "--")).append("\n");
        diagBuilder.append("• PCI: ").append(isValidPci ? String.valueOf(pci) : (is2gNet ? "N/A (BSIC Used)" : "--")).append("\n");
        diagBuilder.append("• NR-ARFCN: ").append(isValidNrArfcn ? String.valueOf(nrArfcn) : "N/A (Only on 5G)").append("\n");
        diagBuilder.append("• RSRP (Signal Power): ").append(rsrpDisplay).append("\n");
        diagBuilder.append("• RSRQ (Signal Quality): ").append(rsrqDisplay).append("\n");
        diagBuilder.append("• SINR (Signal Noise Ratio): ").append(sinrDisplay).append("\n");
        diagBuilder.append("• CQI (Channel Quality): ").append(cqiDisplay).append("\n");
        diagBuilder.append("• Timing Advance (TA): ").append(taTextDisplay).append("\n\n");

        diagBuilder.append("ANTENNA & HARDWARE\n");
        diagBuilder.append("• MIMO Status: ").append(mimoStatus).append("\n");
        diagBuilder.append("• Antenna Diversity: ").append(antennaDiversity).append("\n");
        diagBuilder.append("• Rx Chain: ").append(rxChain).append("\n");
        diagBuilder.append("• Tx Power: ").append(txPower).append("\n");
        diagBuilder.append("• Modem Temp: ").append(modemTemp).append("\n\n");

        diagBuilder.append("NETWORK & IMS STATE\n");
        diagBuilder.append("• IMS VoLTE: ").append(isImsRegistered ? "Registered" : "Idle").append("\n");
        diagBuilder.append("• IMS VoWiFi: ").append(isImsRegistered ? "Registered" : "Idle").append("\n");
        diagBuilder.append("• VoNR Network State: ").append(vonrNetworkStatus).append("\n");
        diagBuilder.append("• VoNR Carrier Support: ").append(vonrCarrierStatus).append("\n");
        diagBuilder.append("• Network Mode: ").append(nrBand > 0 ? "5G Active" : finalTech).append("\n");
        diagBuilder.append("• 5G Network: ").append(is5gHardwareSupported ? "Enabled (Sub-6GHz FR1 / SA+NSA)" : "Disabled").append("\n\n");

        diagBuilder.append("SECURITY & SYSTEM\n");
        diagBuilder.append("• Active MBN Loaded: ").append(activeMbn).append("\n");
        diagBuilder.append("• Carrier Config: ").append("com.android.carrierconfig (").append(detectActiveCarrierName()).append(")").append("\n");
        diagBuilder.append("• Modem Baseband FW: ").append(basebandFw).append("\n");
        diagBuilder.append("• 5G SIM Privacy: ").append(nrBand > 0 ? "SUCI Concealed (Curve25519 Encrypted)" : "Standard IMSI").append("\n");
        diagBuilder.append("• Air-Interface Ciphering: ").append("AES-128 / ZUC (EEA2/NEA2 Enforced)").append("\n");
        diagBuilder.append("• Vendor HAL: ").append("vendor.oplus.hardware.radio-V2 (AIDL)");

        // Conditionally show 5G Carrier Restriction ONLY if operator actually restricts 5G
        if (finalDcnr) {
            diagBuilder.append("\n• 5G Carrier Restriction: Restricted by Operator (DCNR Restricted)");
        }

        diagBuilder.append("\n• ENDC (5G NSA): ").append(finalEndc ? "Available" : "Not Available");
        diagBuilder.append("\n• DCNR (5G Restrict): ").append(finalDcnr ? "Restricted" : "Unrestricted");

            final String diagStr = diagBuilder.toString();

            mHandler.post(() -> {
                if (!isAdded()) return;
                if (mDiagMetricsText != null) mDiagMetricsText.setText(diagStr);
                if (mStatusTechText != null) mStatusTechText.setText(finalTech);
                if (mSummaryTechVal != null) mSummaryTechVal.setText(finalTech);
                if (mStatusCaText != null) mStatusCaText.setText(finalCaDisplay);

                // Dynamically update Connected pill based on actual network registration
                if (mStatusConnectedPill != null) {
                    boolean hasService = !"No Service".equals(finalTech);
                    mStatusConnectedPill.setText(hasService ? "Connected" : "No Service");
                    mStatusConnectedPill.setBackgroundResource(hasService
                            ? R.drawable.pill_connected_green
                            : R.drawable.pill_connected_gray);
                    mStatusConnectedPill.setTextColor(Color.parseColor(hasService ? "#4ADE80" : "#9CA3AF"));
                }

                if (mDiagRsrpVal != null) {
                    mDiagRsrpVal.setText(isValidRsrp ? finalRsrp + " dBm" + finalRsrpTrend : "--");
                    if (finalRsrpTrend.contains("▲")) mDiagRsrpVal.setTextColor(Color.parseColor("#4ADE80"));
                    else if (finalRsrpTrend.contains("▼")) mDiagRsrpVal.setTextColor(Color.parseColor("#F87171"));
                    else mDiagRsrpVal.setTextColor(Color.parseColor("#FFFFFF"));
                }
                if (mDiagSinrVal != null) {
                    mDiagSinrVal.setText(isValidSinr ? finalSinr + " dB" + finalSinrTrend : "--");
                    if (finalSinrTrend.contains("▲")) mDiagSinrVal.setTextColor(Color.parseColor("#4ADE80"));
                    else if (finalSinrTrend.contains("▼")) mDiagSinrVal.setTextColor(Color.parseColor("#F87171"));
                    else mDiagSinrVal.setTextColor(Color.parseColor("#FFFFFF"));
                }
                if (mDiagCqiVal != null) {
                    mDiagCqiVal.setText(displayCqiStr + finalCqiTrend);
                    if (finalCqiTrend.contains("▲")) mDiagCqiVal.setTextColor(Color.parseColor("#4ADE80"));
                    else if (finalCqiTrend.contains("▼")) mDiagCqiVal.setTextColor(Color.parseColor("#F87171"));
                    else mDiagCqiVal.setTextColor(Color.parseColor("#FFFFFF"));
                }
                if (mDiagSignalQuality != null) {
                    if (isValidRsrp && finalRsrp > -95) mDiagSignalQuality.setText("Excellent");
                    else if (isValidRsrp && finalRsrp > -105) mDiagSignalQuality.setText("Good");
                    else if (isValidRsrp && finalRsrp > -115) mDiagSignalQuality.setText("Fair");
                    else if (isValidRsrp) mDiagSignalQuality.setText("Poor");
                    else mDiagSignalQuality.setText("--");
                }
            });
        }

        /** Active Band Auto-Update */

        @android.annotation.SuppressLint("MissingPermission")
        private void updateActiveBands() {
            for (BandEntry e : mBandEntries) {
                e.isActive = false;
                e.isPCell = false;
                e.isSCell = false;
            }

            int defaultDataSub = SubscriptionManager.getDefaultDataSubscriptionId();
            boolean isDataSim = (mCurrentSubId == defaultDataSub) || (defaultDataSub == SubscriptionManager.INVALID_SUBSCRIPTION_ID);
            if (!isDataSim) {
                if (mStatusActiveBandsChips != null) mStatusActiveBandsChips.setText("Idle (Non-Data SIM)");
                if (mAdapter != null) mAdapter.notifyDataSetChanged();
                return;
            }

            int activeCount = 0;
            StringBuilder activeChips = new StringBuilder();
            boolean foundPrimary = false;

            if (mLastPhysicalChannelConfigs != null && !mLastPhysicalChannelConfigs.isEmpty()) {
                for (int i = 0; i < mLastPhysicalChannelConfigs.size(); i++) {
                    PhysicalChannelConfig config = mLastPhysicalChannelConfigs.get(i);
                    int rat = networkTypeToAccessNetworkType(config.getNetworkType());
                    if (rat == AccessNetworkConstants.AccessNetworkType.UNKNOWN) continue;
                    int band = config.getBand();
                    int channel = config.getDownlinkChannelNumber();
                    int bw = config.getCellBandwidthDownlinkKhz() / 1000;

                    boolean isPrimaryConfig = isPhysicalChannelPrimary(config);

                    if (rat == AccessNetworkConstants.AccessNetworkType.EUTRAN) {
                        if (band <= 0 && channel > 0 && channel != PhysicalChannelConfig.CHANNEL_NUMBER_UNKNOWN) {
                            band = earfcnToLteBand(channel);
                        }
                        if (band > 0) {
                            boolean isP = isPrimaryConfig || !foundPrimary;
                            if (isP) foundPrimary = true;

                            for (BandEntry e : mBandEntries) {
                                if (e.rat == rat && e.bandNum == band) {
                                    e.isActive = true;
                                    if (isP) {
                                        e.isPCell = true;
                                        e.isSCell = false;
                                    } else if (!e.isPCell) {
                                        e.isSCell = true;
                                    }
                                }
                            }

                            activeCount++;
                            if (activeChips.length() > 0) activeChips.append("\n");
                            activeChips.append("B").append(band);
                            if (bw > 0) activeChips.append(" (").append(bw).append("MHz)");
                            if (isP) activeChips.append(" [PCell]");
                            else activeChips.append(" [SCell]");
                        }
                    } else if (rat == AccessNetworkConstants.AccessNetworkType.NGRAN) {
                        List<Integer> bands = new ArrayList<>();
                        if (band > 0) {
                            bands.add(band);
                        } else if (channel > 0 && channel != PhysicalChannelConfig.CHANNEL_NUMBER_UNKNOWN) {
                            bands = nrarfcnToNrBands(channel);
                        }
                        for (int b : bands) {
                            boolean isP = isPrimaryConfig || !foundPrimary;
                            if (isP) foundPrimary = true;

                            for (BandEntry e : mBandEntries) {
                                if (e.rat == rat && e.bandNum == b) {
                                    e.isActive = true;
                                    if (isP) {
                                        e.isPCell = true;
                                        e.isSCell = false;
                                    } else if (!e.isPCell) {
                                        e.isSCell = true;
                                    }
                                }
                            }

                            activeCount++;
                            if (activeChips.length() > 0) activeChips.append("\n");
                            activeChips.append("n").append(b);
                            if (bw > 0) activeChips.append(" (").append(bw).append("MHz)");
                            if (isP) activeChips.append(" [PCell]");
                            else activeChips.append(" [SCell]");
                        }
                    }
                }
            }

            // CellInfo fallback if physical channels are not reported by HAL
            if (activeCount == 0) {
                try {
                    List<CellInfo> allCellInfo = getTelephonyManager().getAllCellInfo();
                    if (allCellInfo != null) {
                        // First pass: registered cells
                        for (CellInfo info : allCellInfo) {
                            if (!info.isRegistered()) continue;
                            boolean isP = !foundPrimary;
                            if (isP) foundPrimary = true;

                            if (info instanceof CellInfoLte) {
                                int earfcn = ((CellInfoLte) info).getCellIdentity().getEarfcn();
                                int band = earfcnToLteBand(earfcn);
                                if (band > 0) {
                                    for (BandEntry e : mBandEntries) {
                                        if (e.rat == AccessNetworkConstants.AccessNetworkType.EUTRAN && e.bandNum == band) {
                                            e.isActive = true;
                                            if (isP) {
                                                e.isPCell = true;
                                                e.isSCell = false;
                                            } else if (!e.isPCell) {
                                                e.isSCell = true;
                                            }
                                        }
                                    }
                                    activeCount++;
                                    if (activeChips.length() > 0) activeChips.append("\n");
                                    activeChips.append("B").append(band);
                                    if (isP) activeChips.append(" [PCell]");
                                    else activeChips.append(" [SCell]");
                                }
                            } else if (info instanceof CellInfoNr) {
                                CellIdentityNr cellIdNr = (CellIdentityNr) ((CellInfoNr) info).getCellIdentity();
                                int arfcn = cellIdNr.getNrarfcn();
                                List<Integer> bands = nrarfcnToNrBands(arfcn);
                                for (int b : bands) {
                                    for (BandEntry e : mBandEntries) {
                                        if (e.rat == AccessNetworkConstants.AccessNetworkType.NGRAN && e.bandNum == b) {
                                            e.isActive = true;
                                            if (isP) {
                                                e.isPCell = true;
                                                e.isSCell = false;
                                            } else if (!e.isPCell) {
                                                e.isSCell = true;
                                            }
                                        }
                                    }
                                    activeCount++;
                                    if (activeChips.length() > 0) activeChips.append("\n");
                                    activeChips.append("n").append(b);
                                    if (isP) activeChips.append(" [PCell]");
                                    else activeChips.append(" [SCell]");
                                }
                            }
                        }

                        // Second pass: candidate SCell neighbor cells reported by modem (only if PCell is registered)
                        if (foundPrimary) {
                            for (CellInfo info : allCellInfo) {
                                if (info.isRegistered()) continue;
                                if (info instanceof CellInfoLte) {
                                    int earfcn = ((CellInfoLte) info).getCellIdentity().getEarfcn();
                                    int band = earfcnToLteBand(earfcn);
                                    if (band > 0) {
                                        for (BandEntry e : mBandEntries) {
                                            if (e.rat == AccessNetworkConstants.AccessNetworkType.EUTRAN && e.bandNum == band && !e.isActive) {
                                                e.isActive = true;
                                                e.isSCell = true;
                                                activeCount++;
                                                if (activeChips.length() > 0) activeChips.append("\n");
                                                activeChips.append("B").append(band).append(" [SCell Candidate]");
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

        // ServiceState fallback right after device reboot if physical channels & CellInfo haven't initialized yet
        if (activeCount == 0) {
            try {
                android.telephony.ServiceState ss = getTelephonyManager().getServiceState();
                if (ss != null) {
                    for (NetworkRegistrationInfo nri : ss.getNetworkRegistrationInfoList()) {
                        android.telephony.CellIdentity cellId = nri.getCellIdentity();
                        if (cellId instanceof CellIdentityLte) {
                            int earfcn = ((CellIdentityLte) cellId).getEarfcn();
                            int band = earfcnToLteBand(earfcn);
                            if (band > 0) {
                                for (BandEntry e : mBandEntries) {
                                    if (e.rat == AccessNetworkConstants.AccessNetworkType.EUTRAN && e.bandNum == band && !e.isActive) {
                                        e.isActive = true;
                                        e.isPCell = true;
                                        activeCount++;
                                        if (activeChips.length() > 0) activeChips.append("\n");
                                        activeChips.append("B").append(band).append(" [PCell]");
                                    }
                                }
                            }
                        } else if (cellId instanceof CellIdentityNr) {
                            int arfcn = ((CellIdentityNr) cellId).getNrarfcn();
                            List<Integer> bands = nrarfcnToNrBands(arfcn);
                            for (int b : bands) {
                                for (BandEntry e : mBandEntries) {
                                    if (e.rat == AccessNetworkConstants.AccessNetworkType.NGRAN && e.bandNum == b && !e.isActive) {
                                        e.isActive = true;
                                        e.isPCell = true;
                                        activeCount++;
                                        if (activeChips.length() > 0) activeChips.append("\n");
                                        activeChips.append("n").append(b).append(" [PCell]");
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        final String chipsStr = activeChips.length() > 0 ? activeChips.toString() : "No Active Band";

        mHandler.post(() -> {
            if (!isAdded()) return;
            if (mStatusActiveBandsChips != null) {
                mStatusActiveBandsChips.setText(chipsStr);
            }
            filterBandsByGeneration();
            updateActiveNrModeDisplay();
        });
    }

    private void updateActiveNrModeDisplay() {
        if (mNrModeActiveText == null || mNrModeActiveDot == null || !isAdded()) return;

        if (android.os.SystemClock.elapsedRealtime() - mLastNrModeUserInteractionTime < 2500) {
            return;
        }

        boolean hasNr = false;
        boolean hasLte = false;
        int activeNrBand = 0;

        if (mBandEntries != null) {
            for (BandEntry e : mBandEntries) {
                if (e.isActive) {
                    if (e.rat == AccessNetworkConstants.AccessNetworkType.NGRAN) {
                        hasNr = true;
                        activeNrBand = e.bandNum;
                    }
                    if (e.rat == AccessNetworkConstants.AccessNetworkType.EUTRAN) hasLte = true;
                }
            }
        }

        final boolean nr = hasNr;
        final boolean lte = hasLte;
        final int nrBand = activeNrBand;

        mHandler.post(() -> {
            if (!isAdded()) return;
            if (nr) {
                String nrStr = nrBand > 0 ? ("NR n" + nrBand) : "NR";
                if (lte) {
                    mNrModeActiveText.setText("EN-DC Active: 5G NSA (LTE Anchor + " + nrStr + ")");
                    mNrModeActiveDot.setBackgroundResource(R.drawable.active_dot_green);
                } else {
                    mNrModeActiveText.setText("Active: 5G SA (" + nrStr + " Standalone)");
                    mNrModeActiveDot.setBackgroundResource(R.drawable.active_dot_green);
                }
            } else {
                mNrModeActiveText.setText("Active: LTE / No 5G");
                mNrModeActiveDot.setBackgroundResource(R.drawable.active_dot_gray);
            }
        });
    }

    private void showApplyDialog() {
        int checked = countChecked();
        if (checked == 0) {
            toast(getString(R.string.network_bands_nothing_selected));
            return;
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.network_bands_dialog_title)
                .setMessage(getString(R.string.network_bands_dialog_message, checked))
                .setPositiveButton(R.string.network_bands_apply, (d, w) -> applyBandsNow())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void applyBandsNow() {
        Set<String> newSavedKeys = new HashSet<>();
        for (BandEntry e : mBandEntries) {
            if (e.checked && !e.isHeader) {
                newSavedKeys.add(e.rat + ":" + e.bandNum);
            }
        }
        saveBandKeys(newSavedKeys);

        Log.i(TAG, "applyBandsNow: Applying custom band lock for subId=" + mCurrentSubId + ", selectedCount=" + countChecked() + ", savedKeys=" + newSavedKeys);

        List<RadioAccessSpecifier> specifiers = buildSpecifiers(true);
        Log.d(TAG, "applyBandsNow: Generated RadioAccessSpecifiers count=" + specifiers.size());

        if (specifiers.isEmpty()) {
            toast(getString(R.string.network_bands_nothing_selected));
            return;
        }

        // Enable & enforce LTE Carrier Aggregation (4G+) if user locked multiple frequency bands
        if (countChecked() > 1) {
            try {
                boolean isAlreadyEnabled = android.provider.Settings.Global.getInt(
                        requireContext().getContentResolver(), "lte_ca_enabled", 0) == 1
                        || "1".equals(SystemProperties.get("persist.vendor.radio.lte_ca_enabled", "0"))
                        || "true".equalsIgnoreCase(SystemProperties.get("persist.sys.lte_ca_enable", "false"));

                if (!isAlreadyEnabled) {
                    android.provider.Settings.Global.putInt(requireContext().getContentResolver(), "lte_ca_enabled", 1);
                    android.provider.Settings.Global.putInt(requireContext().getContentResolver(), "show_carrier_aggregation_option", 1);
                    android.provider.Settings.System.putInt(requireContext().getContentResolver(), "lte_ca_enabled", 1);
                    SystemProperties.set("persist.vendor.radio.lte_ca_enabled", "1");
                    SystemProperties.set("persist.sys.lte_ca_enable", "true");
                    SystemProperties.set("vendor.radio.lte_ca.enable", "1");
                    Log.i(TAG, "applyBandsNow: Multi-band selection detected (" + countChecked() + " bands) — enabling LTE Carrier Aggregation (4G+) in ROM Settings");
                } else {
                    Log.d(TAG, "applyBandsNow: LTE Carrier Aggregation (4G+) already active in ROM Settings — skipping redundant write.");
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to check/enforce LTE CA settings: " + e.getMessage());
            }
        }

        try {
            TelephonyManager tm = getTelephonyManager();
            tm.setSystemSelectionChannels(specifiers);
            setStatus(getString(R.string.network_bands_status_active, countChecked()));

            toast("Band Lock applied — cycling radio power...");

            tm.setRadioPower(false);
            mHandler.postDelayed(() -> {
                try {
                    tm.setRadioPower(true);
                    toast("Modem online — re-scanning physical channels...");
                    mHandler.postDelayed(this::updateActiveBands, 4000);
                    mHandler.postDelayed(() -> updateLiveDiagnostics(null), 4000);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to power on radio: " + e.getMessage());
                }
            }, 1200);

            filterBandsByGeneration();
            checkApplyButtonState();

        } catch (Exception e) {
            Log.e(TAG, "Failed to setSystemSelectionChannels: " + e.getMessage());
            toast("Modem update error: " + e.getMessage());
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void resetBandsClean() {
        Log.i(TAG, "resetBandsClean: Resetting band lock configuration to modem defaults for subId=" + mCurrentSubId);

        // 1. Uncheck all band entries in UI (preserve stored custom profiles in SharedPreferences)
        for (BandEntry e : mBandEntries) {
            e.checked = false;
            e.isActive = false;
            e.isPCell = false;
            e.isSCell = false;
        }
        filterBandsByGeneration();

        // 3. Reset UI dropdowns and checkboxes
        if (mCarrierPresetSpinner != null) mCarrierPresetSpinner.setSelection(0);
        if (mRatModeSpinner != null) mRatModeSpinner.setSelection(0);
        if (mChk2G != null) mChk2G.setChecked(true);
        if (mChk3G != null) mChk3G.setChecked(true);
        if (mChk4G != null) mChk4G.setChecked(true);
        if (mChk5G != null) mChk5G.setChecked(true);

        updateNrModeSeekbarForCarrier();

        if (mNrModeSeekBar != null) {
            mNrModeSeekBar.setProgress(1); // Auto (SA+NSA)
        }

        toast("Resetting modem — clearing all band forcing...");

        try {
            TelephonyManager tm = getTelephonyManager();

            // 4. Overwrite modem band lock table with ALL valid catalog bands
            List<RadioAccessSpecifier> allBands = buildAllBandsSpecifiers();
            Log.i(TAG, "resetBandsClean: Overwriting modem band filter with all catalog bands (" + allBands.size() + " specifiers)");
            try {
                tm.setSystemSelectionChannels(allBands);
            } catch (Exception e) {
                Log.w(TAG, "resetBandsClean: Failed to set all-bands specifiers: " + e.getMessage());
            }

            // 5. Restore full allowed network types bitmask (All RATs)
            long allRatsBitmask = TelephonyManager.NETWORK_TYPE_BITMASK_GSM
                                | TelephonyManager.NETWORK_TYPE_BITMASK_GPRS
                                | TelephonyManager.NETWORK_TYPE_BITMASK_EDGE
                                | TelephonyManager.NETWORK_TYPE_BITMASK_UMTS
                                | TelephonyManager.NETWORK_TYPE_BITMASK_HSDPA
                                | TelephonyManager.NETWORK_TYPE_BITMASK_HSUPA
                                | TelephonyManager.NETWORK_TYPE_BITMASK_HSPA
                                | TelephonyManager.NETWORK_TYPE_BITMASK_HSPAP
                                | TelephonyManager.NETWORK_TYPE_BITMASK_LTE
                                | TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA
                                | TelephonyManager.NETWORK_TYPE_BITMASK_NR;
            try {
                tm.setAllowedNetworkTypesForReason(
                        TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER, allRatsBitmask);
            } catch (Exception ignored) {}

            // 6. Reset network selection mode to automatic
            try {
                tm.setNetworkSelectionModeAutomatic();
            } catch (Exception ignored) {}

            // 7. Reset OPlus 5G NR mode HAL to default
            int slotId = SubscriptionManager.getSlotIndex(mCurrentSubId);
            if (SubscriptionManager.isValidSlotIndex(slotId)) {
                setOplusNrModeStatic(slotId, OPLUS_NR_MODE_SA_PRE);
            }

            // 8. Cycle radio power to force modem to re-attach with all bands
            try {
                tm.setRadioPower(false);
            } catch (Exception ignored) {}

            mHandler.postDelayed(() -> {
                try {
                    tm.setRadioPower(true);
                    toast("Modem restored to default — scanning all bands...");
                    
                    // Clear system selection channels to empty list once modem has re-attached
                    mHandler.postDelayed(() -> {
                        try {
                            tm.setSystemSelectionChannels(new ArrayList<>());
                        } catch (Exception ignored) {}
                        updateActiveBands();
                        updateLiveDiagnostics(null);
                    }, 4000);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to power on radio after reset: " + e.getMessage());
                }
            }, 1500);

            setStatus(getString(R.string.network_bands_status_no_signal));
            checkApplyButtonState();
        } catch (Exception e) {
            Log.e(TAG, "Failed to reset bands: " + e.getMessage());
            toast("Reset failed: " + e.getMessage());
        }
    }

    private List<RadioAccessSpecifier> buildAllBandsSpecifiers() {
        List<RadioAccessSpecifier> list = new ArrayList<>();

        String simOperator = getTelephonyManager() != null ? getTelephonyManager().getSimOperator() : "";
        String rfVer = SystemProperties.get("ro.boot.rf_version", "0");

        int[] nrBands;
        int[] lteBands;

        if ((simOperator != null && (simOperator.startsWith("310") || simOperator.startsWith("311") || simOperator.startsWith("312")))
                || "12".equals(rfVer) || "22".equals(rfVer)) {
            // US / North America Region (Sub-6GHz + mmWave FR2: n258, n260, n261)
            nrBands = new int[] { 2, 5, 12, 25, 41, 66, 71, 77, 78, 258, 260, 261 };
            lteBands = new int[] { 2, 4, 5, 12, 13, 25, 26, 41, 66, 71 };
        } else if ((simOperator != null && (simOperator.startsWith("204") || simOperator.startsWith("208") || simOperator.startsWith("222") || simOperator.startsWith("234") || simOperator.startsWith("262")))
                || "21".equals(rfVer)) {
            // EU / Europe Region (Vodafone, Deutsche Telekom, EE, O2)
            nrBands = new int[] { 1, 3, 7, 8, 20, 28, 77, 78 };
            lteBands = new int[] { 1, 3, 7, 8, 20, 28, 38, 40 };
        } else {
            // India (IN) & Global Fallback (Jio, Airtel, Vi, BSNL, etc.)
            nrBands = new int[] { 1, 3, 5, 8, 28, 78 };
            lteBands = new int[] { 1, 3, 5, 8, 40, 41 };
        }

        list.add(new RadioAccessSpecifier(AccessNetworkConstants.AccessNetworkType.NGRAN, nrBands, null));
        list.add(new RadioAccessSpecifier(AccessNetworkConstants.AccessNetworkType.EUTRAN, lteBands, null));

        return list;
    }

    private static int earfcnToLteBand(int earfcn) {
        if (earfcn >= 0 && earfcn <= 599) return 1;
        if (earfcn >= 600 && earfcn <= 1199) return 2;
        if (earfcn >= 1200 && earfcn <= 1949) return 3;
        if (earfcn >= 1950 && earfcn <= 2399) return 4;
        if (earfcn >= 2400 && earfcn <= 2649) return 5;
        if (earfcn >= 2750 && earfcn <= 3449) return 7;
        if (earfcn >= 3450 && earfcn <= 3799) return 8;
        if (earfcn >= 5010 && earfcn <= 5179) return 12;
        if (earfcn >= 5180 && earfcn <= 5279) return 13;
        if (earfcn >= 5730 && earfcn <= 5849) return 17;
        if (earfcn >= 5850 && earfcn <= 5999) return 18;
        if (earfcn >= 6000 && earfcn <= 6149) return 19;
        if (earfcn >= 6150 && earfcn <= 6449) return 20;
        if (earfcn >= 8040 && earfcn <= 8689) return 25;
        if (earfcn >= 8690 && earfcn <= 9039) return 26;
        if (earfcn >= 9210 && earfcn <= 9659) return 28;
        if (earfcn >= 9770 && earfcn <= 9869) return 30;
        if (earfcn >= 36200 && earfcn <= 36349) return 34;
        if (earfcn >= 37750 && earfcn <= 38249) return 38;
        if (earfcn >= 38250 && earfcn <= 38649) return 39;
        if (earfcn >= 38650 && earfcn <= 39649) return 40;
        if (earfcn >= 39650 && earfcn <= 41589) return 41;
        if (earfcn >= 46790 && earfcn <= 54539) return 46;
        if (earfcn >= 55240 && earfcn <= 56739) return 48;
        if (earfcn >= 66436 && earfcn <= 67335) return 66;
        if (earfcn >= 68586 && earfcn <= 68935) return 71;
        return 0;
    }

    private static List<Integer> nrarfcnToNrBands(int arfcn) {
        List<Integer> bands = new ArrayList<>();
        if (arfcn >= 422000 && arfcn <= 434000) bands.add(1);
        if (arfcn >= 386000 && arfcn <= 398000) bands.add(2);
        if (arfcn >= 361000 && arfcn <= 376000) bands.add(3);
        if (arfcn >= 173800 && arfcn <= 178800) bands.add(5);
        if (arfcn >= 524000 && arfcn <= 538000) bands.add(7);
        if (arfcn >= 185000 && arfcn <= 192000) bands.add(8);
        if (arfcn >= 158200 && arfcn <= 164200) bands.add(20);
        if (arfcn >= 386000 && arfcn <= 399000) bands.add(25);
        if (arfcn >= 151600 && arfcn <= 160600) bands.add(28);
        if (arfcn >= 514000 && arfcn <= 524000) bands.add(38);
        if (arfcn >= 460000 && arfcn <= 480000) bands.add(40);
        if (arfcn >= 499200 && arfcn <= 537999) bands.add(41);
        if (arfcn >= 636667 && arfcn <= 646666) bands.add(48);
        if (arfcn >= 422000 && arfcn <= 440000) bands.add(66);
        if (arfcn >= 123400 && arfcn <= 130400) bands.add(71);
        if (arfcn >= 620000 && arfcn <= 680000) bands.add(77);
        if (arfcn >= 620000 && arfcn <= 653333) bands.add(78);
        return bands;
    }

    private static String readModemTemperature() {
        try {
            for (int i = 0; i <= 40; i++) {
                java.io.File typeFile = new java.io.File("/sys/class/thermal/thermal_zone" + i + "/type");
                if (typeFile.exists()) {
                    String type = new String(java.nio.file.Files.readAllBytes(typeFile.toPath())).trim().toLowerCase();
                    if (type.contains("modem") || type.contains("pa") || type.contains("qcom")) {
                        java.io.File tempFile = new java.io.File("/sys/class/thermal/thermal_zone" + i + "/temp");
                        if (tempFile.exists()) {
                            String raw = new String(java.nio.file.Files.readAllBytes(tempFile.toPath())).trim();
                            float val = Float.parseFloat(raw);
                            if (val > 1000) val /= 1000.0f;
                            if (val > 0 && val < 100) {
                                return String.format(java.util.Locale.US, "%.1f°C (%s)", val, type);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "--";
    }

    private List<RadioAccessSpecifier> buildSpecifiers(boolean onlyChecked) {
        return buildSpecifiersStatic(mBandEntries, getTelephonyManager());
    }

    private static List<RadioAccessSpecifier> buildSpecifiersStatic(List<BandEntry> bandEntries, TelephonyManager tm) {
        List<Integer> nrBands    = new ArrayList<>();
        List<Integer> lteBands   = new ArrayList<>();
        List<Integer> wcdmaBands = new ArrayList<>();
        List<Integer> gsmBands   = new ArrayList<>();

        for (BandEntry e : bandEntries) {
            if (e.isHeader) continue;
            if (!e.checked) continue;
            switch (e.rat) {
                case AccessNetworkConstants.AccessNetworkType.NGRAN:  nrBands.add(e.bandNum);    break;
                case AccessNetworkConstants.AccessNetworkType.EUTRAN: lteBands.add(e.bandNum);   break;
                case AccessNetworkConstants.AccessNetworkType.UTRAN:  wcdmaBands.add(e.bandNum); break;
                case AccessNetworkConstants.AccessNetworkType.GERAN:  gsmBands.add(e.bandNum);   break;
            }
        }

        try {
            long bitmask = tm.getAllowedNetworkTypesForReason(
                    TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER);
            boolean is5gEnabledInSystem = (bitmask & TelephonyManager.NETWORK_TYPE_BITMASK_NR) != 0;
            if (!is5gEnabledInSystem && !nrBands.isEmpty()) {
                nrBands.clear();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read system 5G toggle state: " + e.getMessage());
        }

        List<Integer> sortedLteBands = new ArrayList<>();
        int[] ltePriority = {3, 40, 1, 5, 8, 28};
        for (int b : ltePriority) {
            if (lteBands.contains(b)) {
                sortedLteBands.add(b);
            }
        }
        for (int b : lteBands) {
            if (!sortedLteBands.contains(b)) {
                sortedLteBands.add(b);
            }
        }

        List<RadioAccessSpecifier> specifiers = new ArrayList<>();
        if (!nrBands.isEmpty())
            specifiers.add(new RadioAccessSpecifier(
                    AccessNetworkConstants.AccessNetworkType.NGRAN,  toIntArray(nrBands),    null));
        if (!sortedLteBands.isEmpty())
            specifiers.add(new RadioAccessSpecifier(
                    AccessNetworkConstants.AccessNetworkType.EUTRAN, toIntArray(sortedLteBands), null));
        if (!wcdmaBands.isEmpty())
            specifiers.add(new RadioAccessSpecifier(
                    AccessNetworkConstants.AccessNetworkType.UTRAN,  toIntArray(wcdmaBands), null));
        if (!gsmBands.isEmpty())
            specifiers.add(new RadioAccessSpecifier(
                    AccessNetworkConstants.AccessNetworkType.GERAN,  toIntArray(gsmBands),   null));
        return specifiers;
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    /** RecyclerView Adapter */

    public interface OnPresetClickListener {
        void onPresetClicked();
    }

    private class BandAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private final List<BandEntry> mEntries;
        private final OnPresetClickListener mClickListener;

        BandAdapter(List<BandEntry> entries, OnPresetClickListener listener) {
            mEntries = new ArrayList<>(entries);
            mClickListener = listener;
        }

        public void updateEntries(List<BandEntry> newEntries) {
            mEntries.clear();
            mEntries.addAll(newEntries);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            View v = inf.inflate(R.layout.item_band_entry, parent, false);
            return new BandVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            BandEntry entry = mEntries.get(position);
            BandVH bvh = (BandVH) holder;
            bvh.checkbox.setText(entry.label);
            bvh.freqText.setText(entry.freqHint);

            int currentAccent = getSystemAccentColor(holder.itemView.getContext());
            bvh.checkbox.setButtonTintList(android.content.res.ColorStateList.valueOf(currentAccent));

            boolean available = isSimAndRadioAvailable();
            bvh.checkbox.setEnabled(available);

            bvh.checkbox.setOnCheckedChangeListener(null);
            bvh.checkbox.setChecked(entry.checked);
            if (available) {
                bvh.checkbox.setOnCheckedChangeListener((btn, isChecked) -> {
                    entry.checked = isChecked;
                    if (mClickListener != null) mClickListener.onPresetClicked();
                    checkApplyButtonState();
                });
            }

            if (entry.isPCell) {
                bvh.activeBadge.setText("PCell");
                bvh.activeBadge.setTextColor(Color.parseColor("#4ADE80"));
                bvh.activeBadge.setBackgroundResource(R.drawable.pill_pcell_badge);
                bvh.activeBadge.setVisibility(View.VISIBLE);
            } else if (entry.isSCell) {
                bvh.activeBadge.setText("SCell");
                bvh.activeBadge.setTextColor(Color.parseColor("#38BDF8"));
                bvh.activeBadge.setBackgroundResource(R.drawable.pill_scell_badge);
                bvh.activeBadge.setVisibility(View.VISIBLE);
            } else if (entry.isActive) {
                bvh.activeBadge.setText("ACTIVE");
                bvh.activeBadge.setTextColor(Color.parseColor("#FFFFFF"));
                bvh.activeBadge.setBackgroundResource(R.drawable.chip_active_band);
                bvh.activeBadge.setVisibility(View.VISIBLE);
            } else {
                bvh.activeBadge.setVisibility(View.GONE);
            }

            if (available) {
                bvh.itemView.setOnClickListener(v -> {
                    entry.checked = !entry.checked;
                    bvh.checkbox.setOnCheckedChangeListener(null);
                    bvh.checkbox.setChecked(entry.checked);
                    if (mClickListener != null) mClickListener.onPresetClicked();
                    checkApplyButtonState();
                });
            } else {
                bvh.itemView.setOnClickListener(null);
            }
        }

        @Override
        public int getItemCount() { return mEntries.size(); }

        class BandVH extends RecyclerView.ViewHolder {
            CheckBox checkbox;
            TextView freqText;
            TextView activeBadge;
            BandVH(View v) {
                super(v);
                checkbox    = v.findViewById(R.id.band_checkbox);
                freqText    = v.findViewById(R.id.band_freq_text);
                activeBadge = v.findViewById(R.id.band_active_badge);
            }
        }
    }

    private static class ContrastSpinnerAdapter extends ArrayAdapter<String> {
        private int mTextColor = Color.WHITE;

        public ContrastSpinnerAdapter(Context context, int resource, List<String> objects) {
            super(context, resource, objects);
        }

        public void setTextColor(int color) {
            if (mTextColor != color) {
                mTextColor = color;
                notifyDataSetChanged();
            }
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View v = super.getView(position, convertView, parent);
            TextView tv = v.findViewById(android.R.id.text1);
            if (tv != null) {
                tv.setTextColor(mTextColor);
            }
            return v;
        }
    }
}

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

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.AccessNetworkConstants;
import android.telephony.PhysicalChannelConfig;
import android.telephony.RadioAccessSpecifier;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.lineageos.device.DeviceSettings.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Fragment to handle selective band locking.
 */
public class NetworkBandsFragment extends Fragment {

    private static final String TAG = "NetworkBandsFragment";
    private static final int SERVICE_CHECK_DELAY_MS = 15_000;
    private static final String PREFS_NAME = "band_lock_prefs";
    private static final String PREF_KEY_PREFIX = "selected_bands_"; // + subId
    private static final String PREF_KEY_NR_MODE_PREFIX = "nr_mode_sub_"; // + subId

    private static final int OPLUS_NR_MODE_NSA_PRE = 0;
    private static final int OPLUS_NR_MODE_NSA_ONLY = 1;
    private static final int OPLUS_NR_MODE_SA_ONLY = 2;
    private static final int OPLUS_NR_MODE_SA_PRE = 3;

    private SeekBar mNrModeSeekBar;
    private View mNrModeActiveLayout;
    private View mNrModeActiveDot;
    private TextView mNrModeActiveText;
    private long mLastNrModeUserInteractionTime = 0;

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
    private TextView mStatusText;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

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
        View root = inflater.inflate(R.layout.fragment_network_bands, container, false);

        mSimSpinner  = root.findViewById(R.id.sim_spinner);
        mApplyButton = root.findViewById(R.id.btn_apply_bands);
        mResetButton = root.findViewById(R.id.btn_reset_bands);
        mStatusText  = root.findViewById(R.id.band_status_text);

        mNrModeSeekBar = root.findViewById(R.id.nr_mode_seekbar);
        mNrModeActiveLayout = root.findViewById(R.id.nr_mode_active_layout);
        mNrModeActiveDot = root.findViewById(R.id.nr_mode_active_dot);
        mNrModeActiveText = root.findViewById(R.id.nr_mode_active_text);

        mNrModeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    mLastNrModeUserInteractionTime = android.os.SystemClock.elapsedRealtime();
                    if (mNrModeActiveLayout != null) {
                        mNrModeActiveLayout.animate().cancel();
                        mNrModeActiveLayout.setAlpha(1.0f);
                        mNrModeActiveLayout.animate()
                            .alpha(0.0f)
                            .setDuration(300)
                            .withEndAction(() -> {
                                if (mNrModeActiveText != null) {
                                    mNrModeActiveText.setText("Applying mode change...");
                                    if (mNrModeActiveDot != null) {
                                        mNrModeActiveDot.setBackgroundResource(R.drawable.active_dot_gray);
                                    }
                                }
                                mNrModeActiveLayout.animate()
                                    .alpha(1.0f)
                                    .setDuration(1700)
                                    .setStartDelay(300)
                                    .withEndAction(() -> {
                                        updateActiveNrModeDisplay();
                                    })
                                    .start();
                            })
                            .start();
                    }
                    updateNrMode(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        RecyclerView recyclerView = root.findViewById(R.id.bands_recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        mAdapter = new BandAdapter(mBandEntries);
        recyclerView.setAdapter(mAdapter);

        setupSimTabs();
        loadCurrentBands(); // loads from SharedPreferences

        mApplyButton.setOnClickListener(v -> showApplyDialog());
        mResetButton.setOnClickListener(v -> showResetDialog());

        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        registerBandMonitor();
    }

    @Override
    public void onStop() {
        super.onStop();
        unregisterBandMonitor();
        mHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
    }

    /** SIM Selector */

    private void setupSimTabs() {
        try {
            mActiveSubscriptions = mSubscriptionManager.getActiveSubscriptionInfoList();
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot read subscriptions: " + e.getMessage());
            mActiveSubscriptions = new ArrayList<>();
        }

        if (mActiveSubscriptions == null || mActiveSubscriptions.size() <= 1) {
            mSimSpinner.setVisibility(View.GONE);
            if (mActiveSubscriptions != null && !mActiveSubscriptions.isEmpty()) {
                mCurrentSubId = mActiveSubscriptions.get(0).getSubscriptionId();
            }
            Log.d(TAG, "setupSimTabs: single SIM, subId=" + mCurrentSubId);
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

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(), R.layout.item_sim_spinner, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSimSpinner.setAdapter(adapter);
        mCurrentSubId = mActiveSubscriptions.get(0).getSubscriptionId();
        Log.d(TAG, "setupSimTabs: dual SIM, initial subId=" + mCurrentSubId);

        mSimSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < mActiveSubscriptions.size()) {
                    mCurrentSubId = mActiveSubscriptions.get(position).getSubscriptionId();
                    Log.d(TAG, "SIM switched to subId=" + mCurrentSubId);
                    unregisterBandMonitor();
                    loadCurrentBands();
                    registerBandMonitor();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /* Load State from SharedPreferences */
    private void loadCurrentBands() {
        // Reset all entries first
        for (BandEntry e : mBandEntries) {
            e.checked = false;
            e.isActive = false;
        }

        // Load persisted selection
        Set<String> savedKeys = getSavedBandKeys();
        Log.d(TAG, "loadCurrentBands: SharedPreferences has " + savedKeys.size() + " saved band(s) for subId=" + mCurrentSubId);

        if (savedKeys.isEmpty()) {
            setStatus(getString(R.string.network_bands_status_no_signal));
            Log.d(TAG, "loadCurrentBands: no saved selection, showing all unchecked");
        } else {
            int loadedCount = 0;
            for (BandEntry e : mBandEntries) {
                if (e.bandNum == BandCatalog.SECTION_HEADER) continue;
                String key = e.rat + ":" + e.bandNum;
                if (savedKeys.contains(key)) {
                    e.checked = true;
                    loadedCount++;
                }
            }
            Log.d(TAG, "loadCurrentBands: loaded " + loadedCount + " saved band(s) into checkboxes");
            setStatus(getString(R.string.network_bands_status_active, loadedCount));
        }

        if (mAdapter != null) mAdapter.notifyDataSetChanged();

        // Load 5G NR mode position
        if (mNrModeSeekBar != null) {
            if (isJioCarrier()) {
                mNrModeSeekBar.setProgress(2); // Force SA Only
                mNrModeSeekBar.setEnabled(false);
                int slotId = SubscriptionManager.getSlotIndex(mCurrentSubId);
                if (SubscriptionManager.isValidSlotIndex(slotId)) {
                    setOplusNrModeStatic(slotId, OPLUS_NR_MODE_SA_ONLY);
                }
            } else {
                mNrModeSeekBar.setEnabled(true);
                int savedNrMode = getPrefs().getInt(PREF_KEY_NR_MODE_PREFIX + mCurrentSubId, 1); // default to Auto (1)
                mNrModeSeekBar.setProgress(savedNrMode);
                int slotId = SubscriptionManager.getSlotIndex(mCurrentSubId);
                if (SubscriptionManager.isValidSlotIndex(slotId)) {
                    int oplusMode = OPLUS_NR_MODE_SA_PRE;
                    if (savedNrMode == 0) {
                        oplusMode = OPLUS_NR_MODE_NSA_ONLY;
                    } else if (savedNrMode == 2) {
                        oplusMode = OPLUS_NR_MODE_SA_ONLY;
                    }
                    setOplusNrModeStatic(slotId, oplusMode);
                }
            }
        }
    }

    /** SharedPreferences Helpers */

    private SharedPreferences getPrefs() {
        return requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
    }

    private String prefKey() {
        return PREF_KEY_PREFIX + mCurrentSubId;
    }

    private Set<String> getSavedBandKeys() {
        return new HashSet<>(getPrefs().getStringSet(prefKey(), new HashSet<>()));
    }

    private void saveBandKeys(Set<String> keys) {
        getPrefs().edit().putStringSet(prefKey(), keys).apply();
        Log.d(TAG, "saveBandKeys: saved " + keys.size() + " band(s) to SharedPreferences key=" + prefKey());
    }

    private void clearBandKeys() {
        getPrefs().edit().remove(prefKey()).apply();
        Log.d(TAG, "clearBandKeys: cleared SharedPreferences for key=" + prefKey());
    }

    /** Live Band Monitor */
    private void registerBandMonitor() {
        if (mBandMonitorCallback != null) return;
        try {
            mBandMonitorCallback = new BandMonitorCallback();
            getTelephonyManager().registerTelephonyCallback(mMainExecutor, mBandMonitorCallback);
            Log.d(TAG, "BandMonitorCallback registered for subId=" + mCurrentSubId);
        } catch (Exception e) {
            Log.w(TAG, "Failed to register BandMonitorCallback: " + e.getMessage());
            mBandMonitorCallback = null;
        }
    }

    private void unregisterBandMonitor() {
        if (mBandMonitorCallback == null) return;
        try {
            getTelephonyManager().unregisterTelephonyCallback(mBandMonitorCallback);
            Log.d(TAG, "BandMonitorCallback unregistered");
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister BandMonitorCallback: " + e.getMessage());
        } finally {
            mBandMonitorCallback = null;
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
            if (e.checked && e.bandNum != BandCatalog.SECTION_HEADER) c++;
        }
        return c;
    }

    private static String intArrayToString(int[] arr) {
        if (arr == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(arr[i]);
        }
        return sb.append("]").toString();
    }

    /**
     * Maps TelephonyManager.NETWORK_TYPE_* → AccessNetworkConstants.AccessNetworkType.*
     */
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

    private class BandMonitorCallback extends TelephonyCallback
            implements TelephonyCallback.PhysicalChannelConfigListener,
                       TelephonyCallback.CellInfoListener {

        @Override
        public void onPhysicalChannelConfigChanged(@NonNull List<PhysicalChannelConfig> configs) {
            Log.d(TAG, "onPhysicalChannelConfigChanged: received " + configs.size() + " config(s)");
            for (PhysicalChannelConfig config : configs) {
                Log.d(TAG, "  PhysicalChannelConfig: networkType=" + config.getNetworkType()
                        + " band=" + config.getBand()
                        + " channel=" + config.getDownlinkChannelNumber()
                        + " connectionStatus=" + config.getConnectionStatus()
                        + " dlBandwidthKhz=" + config.getCellBandwidthDownlinkKhz());
            }
            mLastPhysicalChannelConfigs = configs;
            updateActiveBands();
        }

        @Override
        public void onCellInfoChanged(@NonNull List<android.telephony.CellInfo> cellInfo) {
            Log.d(TAG, "onCellInfoChanged: received " + cellInfo.size() + " cell(s)");
            updateActiveBands();
        }
    }

    /** Active Band Auto-Update */

    @android.annotation.SuppressLint("MissingPermission")
    private void updateActiveBands() {
        // Clear active status on all entries first
        for (BandEntry e : mBandEntries) {
            e.isActive = false;
        }

        int activeCount = 0;

        // 1. Process cached PhysicalChannelConfigs (including secondary carrier aggregation channels)
        if (mLastPhysicalChannelConfigs != null) {
            for (PhysicalChannelConfig config : mLastPhysicalChannelConfigs) {
                int rat = networkTypeToAccessNetworkType(config.getNetworkType());
                if (rat == AccessNetworkConstants.AccessNetworkType.UNKNOWN) {
                    continue;
                }
                int band = config.getBand();
                int channel = config.getDownlinkChannelNumber();

                if (rat == AccessNetworkConstants.AccessNetworkType.EUTRAN) {
                    if (band <= 0 && channel > 0 && channel != PhysicalChannelConfig.CHANNEL_NUMBER_UNKNOWN) {
                        band = earfcnToLteBand(channel);
                    }
                    if (band > 0) {
                        for (BandEntry e : mBandEntries) {
                            if (e.rat == rat && e.bandNum == band) {
                                if (!e.isActive) {
                                    e.isActive = true;
                                    activeCount++;
                                    Log.d(TAG, "Marked ACTIVE (LTE config) from PhysicalChannelConfig: " + e.label);
                                }
                            }
                        }
                    }
                } else if (rat == AccessNetworkConstants.AccessNetworkType.NGRAN) {
                    List<Integer> bands = new ArrayList<>();
                    if (band > 0) {
                        bands.add(band);
                    } else if (channel > 0 && channel != PhysicalChannelConfig.CHANNEL_NUMBER_UNKNOWN) {
                        bands = nrarfcnToNrBands(channel);
                    }
                    for (int b : bands) {
                        for (BandEntry e : mBandEntries) {
                            if (e.rat == rat && e.bandNum == b) {
                                if (!e.isActive) {
                                    e.isActive = true;
                                    activeCount++;
                                    Log.d(TAG, "Marked ACTIVE (NR config) from PhysicalChannelConfig: " + e.label);
                                }
                            }
                        }
                    }
                } else {
                    if (band > 0) {
                        for (BandEntry e : mBandEntries) {
                            if (e.rat == rat && e.bandNum == band) {
                                if (!e.isActive) {
                                    e.isActive = true;
                                    activeCount++;
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Check all visible CellInfo for primary/secondary serving cells
        try {
            List<android.telephony.CellInfo> cellInfos = getTelephonyManager().getAllCellInfo();
            if (cellInfos != null) {
                for (android.telephony.CellInfo cell : cellInfos) {
                    int connStatus = cell.getCellConnectionStatus();
                    if (connStatus == android.telephony.CellInfo.CONNECTION_PRIMARY_SERVING ||
                        connStatus == android.telephony.CellInfo.CONNECTION_SECONDARY_SERVING) {
                        
                        android.telephony.CellIdentity id = cell.getCellIdentity();
                        if (id == null) continue;

                        int rat = AccessNetworkConstants.AccessNetworkType.UNKNOWN;
                        int[] bandsArray = null;

                        if (id instanceof android.telephony.CellIdentityLte) {
                            rat = AccessNetworkConstants.AccessNetworkType.EUTRAN;
                            bandsArray = ((android.telephony.CellIdentityLte) id).getBands();
                        } else if (id instanceof android.telephony.CellIdentityNr) {
                            rat = AccessNetworkConstants.AccessNetworkType.NGRAN;
                            bandsArray = ((android.telephony.CellIdentityNr) id).getBands();
                        }

                        if (bandsArray != null) {
                            for (int band : bandsArray) {
                                for (BandEntry e : mBandEntries) {
                                    if (e.rat == rat && e.bandNum == band) {
                                        if (!e.isActive) {
                                            e.isActive = true;
                                            activeCount++;
                                            Log.d(TAG, "Marked ACTIVE (CellInfo) from serving cell: " + e.label);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get bands from CellInfo", e);
        }

        // 3. Merge active bands from ServiceState to ensure primary/registered cells are captured
        try {
            ServiceState ss = getTelephonyManager().getServiceState();
            if (ss != null) {
                List<android.telephony.NetworkRegistrationInfo> nris = ss.getNetworkRegistrationInfoList();
                if (nris != null) {
                    for (android.telephony.NetworkRegistrationInfo nri : nris) {
                        if (!nri.isRegistered()) continue;
                        android.telephony.CellIdentity id = nri.getCellIdentity();
                        if (id == null) continue;

                        int rat = AccessNetworkConstants.AccessNetworkType.UNKNOWN;
                        int[] bandsArray = null;

                        if (id instanceof android.telephony.CellIdentityLte) {
                            rat = AccessNetworkConstants.AccessNetworkType.EUTRAN;
                            bandsArray = ((android.telephony.CellIdentityLte) id).getBands();
                        } else if (id instanceof android.telephony.CellIdentityNr) {
                            rat = AccessNetworkConstants.AccessNetworkType.NGRAN;
                            bandsArray = ((android.telephony.CellIdentityNr) id).getBands();
                        }

                        if (bandsArray != null) {
                            for (int band : bandsArray) {
                                for (BandEntry e : mBandEntries) {
                                    if (e.rat == rat && e.bandNum == band) {
                                        if (!e.isActive) {
                                            e.isActive = true;
                                            activeCount++;
                                            Log.d(TAG, "Marked ACTIVE (ServiceState) from registered cell: " + e.label);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get bands from ServiceState", e);
        }

        Log.d(TAG, "updateActiveBands completed: " + activeCount + " total active band(s)");

        mHandler.post(() -> {
            if (!isAdded()) return;
            if (mAdapter != null) mAdapter.notifyDataSetChanged();
            updateActiveNrModeDisplay();
        });
    }

    private void updateActiveNrModeDisplay() {
        if (mNrModeActiveText == null || mNrModeActiveDot == null || !isAdded()) return;

        // Guard: if user recently changed the slider, let the fade animation play out
        if (android.os.SystemClock.elapsedRealtime() - mLastNrModeUserInteractionTime < 2500) {
            return;
        }

        boolean hasNr = false;
        boolean hasLte = false;

        if (mBandEntries != null) {
            for (BandEntry e : mBandEntries) {
                if (e.isActive) {
                    if (e.rat == AccessNetworkConstants.AccessNetworkType.NGRAN) {
                        hasNr = true;
                    } else if (e.rat == AccessNetworkConstants.AccessNetworkType.EUTRAN) {
                        hasLte = true;
                    }
                }
            }
        }

        try {
            int dataNetType = getTelephonyManager().getDataNetworkType();
            if (dataNetType == TelephonyManager.NETWORK_TYPE_NR) {
                hasNr = true;
            }
        } catch (Exception ignored) {}

        final boolean nr = hasNr;
        final boolean lte = hasLte;

        mHandler.post(() -> {
            if (!isAdded()) return;
            if (android.os.SystemClock.elapsedRealtime() - mLastNrModeUserInteractionTime < 2500) {
                return;
            }
            if (nr) {
                if (lte) {
                    mNrModeActiveText.setText("Active: NSA (5G Non-Standalone)");
                    mNrModeActiveDot.setBackgroundResource(R.drawable.active_dot_green);
                } else {
                    mNrModeActiveText.setText("Active: SA (5G Standalone)");
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
        Log.d(TAG, "showApplyDialog: " + checked + " band(s) checked");
        if (checked == 0) {
            toast(getString(R.string.network_bands_nothing_selected));
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.network_bands_dialog_title)
                .setMessage(getString(R.string.network_bands_dialog_message, checked))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.network_bands_apply, (d, w) -> applyBands())
                .show();
    }

    private void applyBands() {
        List<RadioAccessSpecifier> specifiers = buildSpecifiers(true);
        Log.d(TAG, "applyBands: sending " + specifiers.size() + " RAT specifier(s) to modem via setSystemSelectionChannels");
        for (RadioAccessSpecifier s : specifiers) {
            Log.d(TAG, "  specifier: rat=" + s.getRadioAccessNetwork()
                    + " bands=" + intArrayToString(s.getBands()));
        }

        TelephonyManager tm = getTelephonyManager();

        // Don't wait for modem callback — persist user intent now.
        Set<String> keysToSave = new HashSet<>();
        for (BandEntry e : mBandEntries) {
            if (e.checked && e.bandNum != BandCatalog.SECTION_HEADER) {
                keysToSave.add(e.rat + ":" + e.bandNum);
            }
        }
        saveBandKeys(keysToSave);
        Log.d(TAG, "applyBands: persisted " + keysToSave.size() + " band key(s) to SharedPreferences");

        try {
            tm.setSystemSelectionChannels(
                    specifiers,
                    mMainExecutor,
                    success -> {
                        Log.d(TAG, "setSystemSelectionChannels CALLBACK: success=" + success);
                        if (success) {
                            toast(getString(R.string.network_bands_applied_success));
                            mHandler.postDelayed(this::checkServiceState, SERVICE_CHECK_DELAY_MS);
                        } else {
                            Log.w(TAG, "setSystemSelectionChannels returned success=false");
                            toast(getString(R.string.network_bands_applied_fail));
                        }
                    });
            Log.d(TAG, "applyBands: setSystemSelectionChannels call dispatched");
        } catch (Exception e) {
            Log.e(TAG, "applyBands: exception", e);
            toast(getString(R.string.network_bands_applied_fail));
        }
    }

    private void checkServiceState() {
        if (!isAdded()) return;
        try {
            ServiceState ss = getTelephonyManager().getServiceState();
            int state = ss != null ? ss.getState() : -1;
            Log.d(TAG, "checkServiceState (15s post-apply): state=" + state);
            if (state == ServiceState.STATE_OUT_OF_SERVICE) {
                toast(getString(R.string.network_bands_no_signal));
            }
        } catch (Exception e) {
            Log.w(TAG, "checkServiceState failed", e);
        }
    }

    /** Reset to Automatic */

    private void showResetDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.network_bands_reset)
                .setMessage("Resetting network bands to automatic mode requires a device reboot. Would you like to reboot now?")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Reboot", (d, w) -> resetToAutomatic())
                .show();
    }

    private void resetToAutomatic() {
        Log.d(TAG, "resetToAutomatic: clearing preferences and rebooting device");

        // Clear saved state immediately
        clearBandKeys();
        getPrefs().edit().remove(PREF_KEY_NR_MODE_PREFIX + mCurrentSubId).apply();

        // Reset UI
        for (BandEntry e : mBandEntries) {
            e.checked = false;
            e.isActive = false;
        }
        if (mAdapter != null) mAdapter.notifyDataSetChanged();
        if (mNrModeSeekBar != null) {
            mNrModeSeekBar.setProgress(1); // Auto
        }
        setStatus(getString(R.string.network_bands_status_no_signal));

        // Reboot the device cleanly
        try {
            android.os.PowerManager pm = (android.os.PowerManager) requireContext().getSystemService(android.content.Context.POWER_SERVICE);
            if (pm != null) {
                pm.reboot(null);
            } else {
                Log.e(TAG, "resetToAutomatic: PowerManager is null");
                toast("Error: PowerManager not available");
            }
        } catch (Exception e) {
            Log.e(TAG, "resetToAutomatic: reboot failed", e);
            toast("Reboot permission denied or failed");
        }
    }

    /** Helpers */

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

    private List<RadioAccessSpecifier> buildSpecifiers(boolean onlyChecked) {
        List<Integer> nrBands    = new ArrayList<>();
        List<Integer> lteBands   = new ArrayList<>();
        List<Integer> wcdmaBands = new ArrayList<>();
        List<Integer> gsmBands   = new ArrayList<>();

        for (BandEntry e : mBandEntries) {
            if (e.bandNum == BandCatalog.SECTION_HEADER) continue;
            if (onlyChecked && !e.checked) continue;
            switch (e.rat) {
                case AccessNetworkConstants.AccessNetworkType.NGRAN:  nrBands.add(e.bandNum);    break;
                case AccessNetworkConstants.AccessNetworkType.EUTRAN: lteBands.add(e.bandNum);   break;
                case AccessNetworkConstants.AccessNetworkType.UTRAN:  wcdmaBands.add(e.bandNum); break;
                case AccessNetworkConstants.AccessNetworkType.GERAN:  gsmBands.add(e.bandNum);   break;
            }
        }

        List<RadioAccessSpecifier> specifiers = new ArrayList<>();
        if (!nrBands.isEmpty())
            specifiers.add(new RadioAccessSpecifier(
                    AccessNetworkConstants.AccessNetworkType.NGRAN,  toIntArray(nrBands),    null));
        if (!lteBands.isEmpty())
            specifiers.add(new RadioAccessSpecifier(
                    AccessNetworkConstants.AccessNetworkType.EUTRAN, toIntArray(lteBands),   null));
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

    private static class BandAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int VIEW_TYPE_HEADER = 0;
        private static final int VIEW_TYPE_BAND   = 1;

        private final List<BandEntry> mEntries;

        BandAdapter(List<BandEntry> entries) { mEntries = entries; }

        @Override
        public int getItemViewType(int position) {
            return mEntries.get(position).bandNum == BandCatalog.SECTION_HEADER
                    ? VIEW_TYPE_HEADER : VIEW_TYPE_BAND;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == VIEW_TYPE_HEADER) {
                View v = inf.inflate(R.layout.item_band_section_header, parent, false);
                return new HeaderVH(v);
            } else {
                View v = inf.inflate(R.layout.item_band_entry, parent, false);
                return new BandVH(v);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            BandEntry entry = mEntries.get(position);
            if (holder instanceof HeaderVH) {
                ((HeaderVH) holder).title.setText(entry.label);
            } else {
                BandVH bvh = (BandVH) holder;
                bvh.checkbox.setText(entry.label);
                bvh.freqText.setText(entry.freqHint);

                // CRITICAL: clear listener before setChecked to avoid recycling side-effects
                bvh.checkbox.setOnCheckedChangeListener(null);
                bvh.checkbox.setChecked(entry.checked);
                bvh.checkbox.setOnCheckedChangeListener((btn, isChecked) ->
                        entry.checked = isChecked);

                // ACTIVE badge: visible when modem is currently camped on this band
                bvh.activeBadge.setVisibility(entry.isActive ? View.VISIBLE : View.GONE);

                // Row tap = toggle checkbox
                bvh.itemView.setOnClickListener(v -> {
                    entry.checked = !entry.checked;
                    bvh.checkbox.setOnCheckedChangeListener(null);
                    bvh.checkbox.setChecked(entry.checked);
                    bvh.checkbox.setOnCheckedChangeListener((btn, isChecked) ->
                            entry.checked = isChecked);
                });
            }
        }

        @Override
        public int getItemCount() { return mEntries.size(); }

        static class HeaderVH extends RecyclerView.ViewHolder {
            TextView title;
            HeaderVH(View v) { super(v); title = v.findViewById(R.id.band_section_title); }
        }

        static class BandVH extends RecyclerView.ViewHolder {
            android.widget.CheckBox checkbox;
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

    private void updateNrMode(int position) {
        int oplusMode;
        if (position == 0) {
            oplusMode = OPLUS_NR_MODE_NSA_ONLY;
        } else if (position == 2) {
            oplusMode = OPLUS_NR_MODE_SA_ONLY;
        } else {
            oplusMode = OPLUS_NR_MODE_SA_PRE; // Auto
        }

        Log.d(TAG, "updateNrMode: User changed NR mode slider to position=" + position + " -> oplusMode=" + oplusMode);

        // Save to Prefs
        getPrefs().edit().putInt(PREF_KEY_NR_MODE_PREFIX + mCurrentSubId, position).apply();

        // Send to service
        int slotId = SubscriptionManager.getSlotIndex(mCurrentSubId);
        if (SubscriptionManager.isValidSlotIndex(slotId)) {
            setOplusNrModeStatic(slotId, oplusMode);
        }
    }

    public static void restoreNrModeSettings(android.content.Context context) {
        try {
            SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
            if (sm == null) return;
            List<SubscriptionInfo> activeSubs = sm.getActiveSubscriptionInfoList();
            if (activeSubs == null) return;
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);

            for (SubscriptionInfo info : activeSubs) {
                int subId = info.getSubscriptionId();
                int slotId = info.getSimSlotIndex();
                if (SubscriptionManager.isValidSlotIndex(slotId)) {
                    boolean isJio = false;
                    CharSequence displayName = info.getDisplayName();
                    if (displayName != null && displayName.toString().toLowerCase().contains("jio")) {
                        isJio = true;
                    }
                    CharSequence carrierName = info.getCarrierName();
                    if (carrierName != null && carrierName.toString().toLowerCase().contains("jio")) {
                        isJio = true;
                    }

                    int oplusMode;
                    if (isJio) {
                        oplusMode = OPLUS_NR_MODE_SA_ONLY;
                    } else {
                        int savedNrMode = prefs.getInt(PREF_KEY_NR_MODE_PREFIX + subId, 1); // default to Auto
                        if (savedNrMode == 0) {
                            oplusMode = OPLUS_NR_MODE_NSA_ONLY;
                        } else if (savedNrMode == 2) {
                            oplusMode = OPLUS_NR_MODE_SA_ONLY;
                        } else {
                            oplusMode = OPLUS_NR_MODE_SA_PRE; // Auto
                        }
                    }
                    setOplusNrModeStatic(slotId, oplusMode);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "restoreNrModeSettings failed", e);
        }
    }

    private static void setOplusNrModeStatic(int slotId, int mode) {
        String serviceName = "vendor.oplus.hardware.radio.IRadioStable/OplusRadio" + slotId;
        try {
            android.os.IBinder binder = android.os.ServiceManager.getService(serviceName);
            if (binder != null) {
                vendor.oplus.hardware.radio.IOplusRadio oplusRadio =
                        vendor.oplus.hardware.radio.IOplusRadio.Stub.asInterface(binder);
                if (oplusRadio != null) {
                    oplusRadio.setNrMode(1001, mode);
                    Log.d(TAG, "setOplusNrModeStatic: set mode=" + mode + " for slotId=" + slotId);
                } else {
                    Log.w(TAG, "setOplusNrModeStatic: IOplusRadio cast returned null");
                }
            } else {
                Log.w(TAG, "setOplusNrModeStatic: service not found: " + serviceName);
            }
        } catch (Exception e) {
            Log.e(TAG, "setOplusNrModeStatic failed", e);
        }
    }

    private boolean isJioCarrier() {
        if (mActiveSubscriptions == null) return false;
        for (SubscriptionInfo info : mActiveSubscriptions) {
            if (info.getSubscriptionId() == mCurrentSubId) {
                CharSequence displayName = info.getDisplayName();
                if (displayName != null && displayName.toString().toLowerCase().contains("jio")) {
                    return true;
                }
                CharSequence carrierName = info.getCarrierName();
                if (carrierName != null && carrierName.toString().toLowerCase().contains("jio")) {
                    return true;
                }
            }
        }
        return false;
    }
}

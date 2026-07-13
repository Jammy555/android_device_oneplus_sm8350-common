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

    private TelephonyManager mTelephonyManager;
    private SubscriptionManager mSubscriptionManager;
    private List<SubscriptionInfo> mActiveSubscriptions;
    private int mCurrentSubId = SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;

    private BandMonitorCallback mBandMonitorCallback;
    private Executor mMainExecutor;

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

    /** Load State from SharedPreferences */
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
            implements TelephonyCallback.PhysicalChannelConfigListener {

        @Override
        public void onPhysicalChannelConfigChanged(@NonNull List<PhysicalChannelConfig> configs) {
            Log.d(TAG, "onPhysicalChannelConfigChanged: received " + configs.size() + " config(s)");
            for (PhysicalChannelConfig config : configs) {
                Log.d(TAG, "  PhysicalChannelConfig: networkType=" + config.getNetworkType()
                        + " band=" + config.getBand()
                        + " connectionStatus=" + config.getConnectionStatus()
                        + " dlBandwidthKhz=" + config.getCellBandwidthDownlinkKhz());
            }

            // On SM8350, QCRIL often returns band=0 for PhysicalChannelConfig.
            // If we see band=0, we'll fall back to checking ServiceState.
            boolean hasValidBand = false;
            for (PhysicalChannelConfig config : configs) {
                if (config.getBand() > 0) {
                    hasValidBand = true;
                    break;
                }
            }

            if (!hasValidBand) {
                Log.d(TAG, "onPhysicalChannelConfigChanged: band=0, falling back to ServiceState for auto-update");
                updateActiveBandsFromServiceState();
                return;
            }

            // Standard PhysicalChannelConfig parsing (if HAL actually supports it properly)
            for (BandEntry e : mBandEntries) {
                e.isActive = false;
            }
            int activeCount = 0;
            for (PhysicalChannelConfig config : configs) {
                int rat = networkTypeToAccessNetworkType(config.getNetworkType());
                int band = config.getBand();
                if (rat == AccessNetworkConstants.AccessNetworkType.UNKNOWN || band <= 0) {
                    continue;
                }
                for (BandEntry e : mBandEntries) {
                    if (e.rat == rat && e.bandNum == band) {
                        e.isActive = true;
                        activeCount++;
                        Log.d(TAG, "  Marked ACTIVE auto: " + e.label);
                    }
                }
            }
            final int count = activeCount;
            mHandler.post(() -> {
                if (!isAdded()) return;
                Log.d(TAG, "onPhysicalChannelConfigChanged UI update: " + count + " active band(s)");
                if (mAdapter != null) mAdapter.notifyDataSetChanged();
            });
        }
    }

    /** Active Band Auto-Update */

    @android.annotation.SuppressLint("MissingPermission")
    private void updateActiveBandsFromServiceState() {
        try {
            ServiceState ss = getTelephonyManager().getServiceState();
            if (ss == null) return;

            List<android.telephony.NetworkRegistrationInfo> nris = ss.getNetworkRegistrationInfoList();
            if (nris == null || nris.isEmpty()) return;

            int activeCount = 0;
            
            for (BandEntry e : mBandEntries) {
                e.isActive = false; // Always clear active badges for a fresh scan
            }

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
                                e.isActive = true; // Show the ACTIVE badge
                                activeCount++;
                            }
                        }
                    }
                }
            }

            Log.d(TAG, "updateActiveBandsFromServiceState auto UI update: " + activeCount + " active band(s)");

            mHandler.post(() -> {
                if (!isAdded()) return;
                if (mAdapter != null) mAdapter.notifyDataSetChanged();
            });
            
        } catch (Exception e) {
            Log.e(TAG, "updateActiveBandsFromServiceState failed", e);
        }
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
        // If the modem command fails, the user can still see what they tried.
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
                            Log.w(TAG, "setSystemSelectionChannels returned success=false — modem rejected command");
                            toast(getString(R.string.network_bands_applied_fail));
                        }
                    });
            Log.d(TAG, "applyBands: setSystemSelectionChannels call dispatched (waiting for callback)");
        } catch (Exception e) {
            Log.e(TAG, "applyBands: setSystemSelectionChannels threw exception", e);
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
                .setMessage("Are you sure you want to reset the modem to automatic mode? This will re-enable all factory default bands.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.network_bands_reset, (d, w) -> resetToAutomatic())
                .show();
    }

    private void resetToAutomatic() {
        Log.d(TAG, "resetToAutomatic: sending all bands list to clear lock and restarting radio");

        // Clear saved state immediately
        clearBandKeys();

        // Reset UI
        for (BandEntry e : mBandEntries) {
            e.checked = false;
            e.isActive = false;
        }
        if (mAdapter != null) mAdapter.notifyDataSetChanged();
        setStatus(getString(R.string.network_bands_status_no_signal));

        try {
            List<RadioAccessSpecifier> allBands = buildSpecifiers(false);
            getTelephonyManager().setSystemSelectionChannels(
                    allBands, // Send all bands instead of empty list to force modem out of unsupported lock
                    mMainExecutor,
                    success -> {
                        Log.d(TAG, "resetToAutomatic CALLBACK: success=" + success);
                        if (success) {
                            toast(getString(R.string.network_bands_reset_success));
                            // Force a programmatic radio restart to snap QCRIL out of the dead lock
                            forceRadioRestart();
                        } else {
                            Log.w(TAG, "resetToAutomatic: modem returned success=false");
                            toast(getString(R.string.network_bands_applied_fail));
                        }
                    });
            Log.d(TAG, "resetToAutomatic: setSystemSelectionChannels(empty) dispatched");
        } catch (Exception e) {
            Log.e(TAG, "resetToAutomatic: exception", e);
            toast(getString(R.string.network_bands_applied_fail));
        }
    }

    private void forceRadioRestart() {
        Log.d(TAG, "forceRadioRestart: toggling setRadioPower to restore signal");
        new Thread(() -> {
            try {
                android.telephony.TelephonyManager tm = getTelephonyManager();
                tm.setRadioPower(false);
                Thread.sleep(2000);
                tm.setRadioPower(true);
                mHandler.post(() -> toast("Radio restarted to restore signal."));
            } catch (Exception e) {
                Log.e(TAG, "forceRadioRestart failed", e);
            }
        }).start();
    }

    /** Helpers */

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
}

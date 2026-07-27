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

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentTransaction;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

import org.lineageos.device.DeviceSettings.R;

/**
 * Activity hosting NetworkBandsFragment using standard SettingsLib collapsing toolbar frame.
 */
public final class NetworkBandsActivity extends CollapsingToolbarBaseActivity {

    private static final String FRAGMENT_TAG = "network_bands_settings";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        org.lineageos.device.DeviceSettings.Utils.applyAppTheme(this);
        super.onCreate(savedInstanceState);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }

        setTitle("Network Band Locking");

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new NetworkBandsFragment(),
                            FRAGMENT_TAG)
                    .commit();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.network_bands_menu, menu);
        MenuItem item = menu.findItem(R.id.action_advanced_settings);
        if (item != null) {
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        } else if (item.getItemId() == R.id.action_advanced_settings) {
            NetworkBandsFragment fragment = (NetworkBandsFragment) getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG);
            if (fragment != null) {
                fragment.showAdvancedSettingsDialog();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}

/*
 * Copyright (C) 2026 LunarisOS
 * Separate App Sound Routing Activity
 */

package org.lineageos.device.DeviceSettings.audioshare;

import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentTransaction;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

import org.lineageos.device.DeviceSettings.R;

public final class SeparateAppSoundActivity extends CollapsingToolbarBaseActivity {

    private static final String FRAGMENT_TAG = "separate_app_sound_settings";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        org.lineageos.device.DeviceSettings.Utils.applyAppTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_audio_share);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                    .replace(R.id.audio_share_fragment_container,
                             new SeparateAppSoundFragment(),
                             FRAGMENT_TAG)
                    .commit();
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}

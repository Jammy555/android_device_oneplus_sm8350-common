/*
 * Copyright (C) 2026 LunarisOS
 * Ergonomic Material 3 Volume Slider Preference for Dual Bluetooth Audio Sharing
 */

package org.lineageos.device.DeviceSettings.audioshare;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import org.lineageos.device.DeviceSettings.R;

public class AudioVolumePreference extends Preference implements SeekBar.OnSeekBarChangeListener {

    private static final long THROTTLE_DELAY_MS = 75;

    private int mValue = 80;
    private int mLastNonZeroValue = 80;

    private TextView mBadgeView;
    private ImageView mIconView;
    private SeekBar mSeekBar;
    private boolean mTrackingTouch = false;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long mLastDispatchTime = 0;
    private final Runnable mCommitRunnable = () -> {
        mLastDispatchTime = SystemClock.elapsedRealtime();
        callChangeListener(mValue);
    };

    public AudioVolumePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setLayoutResource(R.layout.preference_audio_volume);
    }

    public AudioVolumePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public AudioVolumePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_audio_volume);
    }

    public AudioVolumePreference(Context context) {
        this(context, null);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        mBadgeView = (TextView) holder.findViewById(R.id.volume_percentage_badge);
        mIconView = (ImageView) holder.findViewById(R.id.volume_icon);
        mSeekBar = (SeekBar) holder.findViewById(R.id.volume_seekbar);

        if (mSeekBar != null) {
            mSeekBar.setMax(100);
            mSeekBar.setMin(0);
            mSeekBar.setProgress(mValue);
            mSeekBar.setEnabled(isEnabled());
            mSeekBar.setOnSeekBarChangeListener(this);
        }

        if (mIconView != null) {
            mIconView.setEnabled(isEnabled());
            mIconView.setOnClickListener(v -> {
                if (!isEnabled()) return;
                if (mValue > 0) {
                    mLastNonZeroValue = mValue;
                    setValue(0, true);
                } else {
                    setValue(mLastNonZeroValue > 0 ? mLastNonZeroValue : 80, true);
                }
            });
        }

        updateViews();
    }

    public int getValue() {
        return mValue;
    }

    public void setValue(int value) {
        setValue(value, false);
    }

    public void setValue(int value, boolean notify) {
        int clamped = Math.max(0, Math.min(100, value));
        if (mValue != clamped) {
            mValue = clamped;
            if (clamped > 0) {
                mLastNonZeroValue = clamped;
            }
            if (notify) {
                callChangeListener(clamped);
            }
            if (mSeekBar != null && mSeekBar.getProgress() != clamped) {
                mSeekBar.setProgress(clamped);
            }
            updateViews();
        }
    }

    private void updateViews() {
        if (mBadgeView != null) {
            mBadgeView.setText(mValue == 0 ? "Mute" : (mValue + "%"));
        }
        if (mIconView != null) {
            mIconView.setImageResource(mValue == 0 ? R.drawable.ic_volume_mute : R.drawable.ic_volume_up);
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (mSeekBar != null) {
            mSeekBar.setEnabled(enabled);
        }
        if (mIconView != null) {
            mIconView.setEnabled(enabled);
            mIconView.setAlpha(enabled ? 1.0f : 0.4f);
        }
        if (mBadgeView != null) {
            mBadgeView.setAlpha(enabled ? 1.0f : 0.4f);
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (fromUser) {
            mValue = progress;
            if (progress > 0) {
                mLastNonZeroValue = progress;
            }
            updateViews();

            long now = SystemClock.elapsedRealtime();
            if (now - mLastDispatchTime >= THROTTLE_DELAY_MS) {
                mLastDispatchTime = now;
                mHandler.removeCallbacks(mCommitRunnable);
                callChangeListener(progress);
            } else {
                mHandler.removeCallbacks(mCommitRunnable);
                mHandler.postDelayed(mCommitRunnable, THROTTLE_DELAY_MS - (now - mLastDispatchTime));
            }
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        mTrackingTouch = true;
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        mTrackingTouch = false;
        mHandler.removeCallbacks(mCommitRunnable);
        mLastDispatchTime = SystemClock.elapsedRealtime();
        callChangeListener(mValue);
    }
}

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

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.widget.LinearLayout;

/**
 * Root container for the File Dock shelf overlay window.
 * Intercepts hardware and gesture Back events to dismiss the dock.
 */
public class DockShelfLayout extends LinearLayout {

    public interface OnBackPressedListener {
        void onBackPressed();
    }

    private OnBackPressedListener mBackPressedListener;

    public DockShelfLayout(Context context) {
        super(context);
    }

    public DockShelfLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DockShelfLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setOnBackPressedListener(OnBackPressedListener listener) {
        mBackPressedListener = listener;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (mBackPressedListener != null) {
                    mBackPressedListener.onBackPressed();
                    return true;
                }
            }
            return true; // Consume ACTION_DOWN to prevent forwarding
        }
        return super.dispatchKeyEvent(event);
    }
}

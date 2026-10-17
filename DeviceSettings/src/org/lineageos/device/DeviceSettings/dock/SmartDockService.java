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

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;
import android.util.Patterns;
import android.view.ContextThemeWrapper;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;
import androidx.preference.PreferenceManager;

import org.lineageos.device.DeviceSettings.DeviceSettingsActivity;
import org.lineageos.device.DeviceSettings.R;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class SmartDockService extends Service implements DockDatabase.OnDataChangedListener {

    private static final String TAG = "SmartDockService";
    private static final int NOTIFICATION_ID = 2002;
    private static final String CHANNEL_ID = "smart_dock_silent_channel";

    public static final String KEY_DOCK_ENABLE = "dock_sidebar_enable";
    public static final String KEY_HANDLE_POSITION = "dock_handle_position"; // "right" or "left"
    public static final String KEY_AUTO_FADE = "dock_auto_fade";
    private static final String KEY_HANDLE_Y = "dock_handle_y_pos";

    private WindowManager mWindowManager;
    private View mPillHandle;
    private View mShelfWindow;
    private boolean mIsShelfExpanded = false;

    private int mScreenWidth;
    private int mScreenHeight;
    private float mLastTouchX;
    private float mLastTouchY;
    private int mPillInitialY;
    private boolean mIsPillDragging = false;
    private boolean mIsRightEdge = true;
    private boolean mAutoFade = true;

    private int mPillNormalWidth;
    private int mPillNormalHeight;
    private int mPillExpandedWidth;
    private int mPillExpandedHeight;
    private int mPillCurrentY;

    private View mImageViewerWindow;
    private Bitmap mCurrentViewerBitmap;
    private boolean mIsImageViewerOpen = false;

    private final LruCache<String, Bitmap> mThumbnailCache =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 1024 / 8)) {
                @Override
                protected int sizeOf(String key, Bitmap bitmap) {
                    return bitmap.getByteCount() / 1024;
                }
            };

    private List<DockDatabase.DayPage> mDayPages = new ArrayList<>();
    private int mCurrentPageIndex = 0;

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences mPrefs;
    private DockDatabase mDatabase;
    private DockContentManager mContentManager;

    private boolean mIsActiveDragSession = false;

    private final Runnable mFadeHandleTask = () -> {
        if (mPillHandle != null && mAutoFade && !mIsShelfExpanded && !mIsActiveDragSession) {
            mPillHandle.animate().alpha(0.25f).setDuration(400).start();
        }
    };

    public static void restoreState(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean enabled = prefs.getBoolean(KEY_DOCK_ENABLE, false);
        if (enabled) {
            Intent intent = new Intent(context, SmartDockService.class);
            intent.putExtra("enable", true);
            try {
                context.startService(intent);
                Log.i(TAG, "SmartDockService restored and started on boot");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start SmartDockService on boot", e);
            }
        } else {
            Log.d(TAG, "SmartDockService disabled in settings, skipping start on boot");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mDatabase = DockDatabase.getInstance(this);
        mContentManager = new DockContentManager(this);
        mDatabase.addListener(this);

        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        mWindowManager.getDefaultDisplay().getRealMetrics(metrics);
        mScreenWidth = metrics.widthPixels;
        mScreenHeight = metrics.heightPixels;

        mIsRightEdge = "right".equals(mPrefs.getString(KEY_HANDLE_POSITION, "right"));
        mAutoFade = mPrefs.getBoolean(KEY_AUTO_FADE, true);

        dismissLegacyNotification();
        createPillHandle();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean enabled = mPrefs.getBoolean(KEY_DOCK_ENABLE, true);
        if (intent != null && intent.hasExtra("enable")) {
            enabled = intent.getBooleanExtra("enable", true);
        }
        if (!enabled) {
            stopSelf();
            return START_NOT_STICKY;
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mDatabase.removeListener(this);
        dismissImageViewer();
        removePillHandle();
        removeShelfWindow();
        mThumbnailCache.evictAll();
        dismissLegacyNotification();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDataChanged() {
        mHandler.post(() -> {
            if (mShelfWindow != null && mIsShelfExpanded) {
                populateShelfItems();
            }
        });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        DisplayMetrics metrics = new DisplayMetrics();
        mWindowManager.getDefaultDisplay().getRealMetrics(metrics);
        mScreenWidth = metrics.widthPixels;
        mScreenHeight = metrics.heightPixels;

        if (mPillHandle != null) {
            WindowManager.LayoutParams pillParams = getPillParams();
            if (pillParams != null) {
                mPillCurrentY = Math.max(100, Math.min(mScreenHeight - 300, mPillCurrentY));
                pillParams.y = mPillCurrentY;
                pillParams.gravity = (mIsRightEdge ? Gravity.END : Gravity.START) | Gravity.TOP;
                mWindowManager.updateViewLayout(mPillHandle, pillParams);
            }
        }

        if (mShelfWindow != null && mIsShelfExpanded) {
            populateShelfItems();
        }
    }

    private void dismissLegacyNotification() {
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.cancel(NOTIFICATION_ID);
                manager.deleteNotificationChannel(CHANNEL_ID);
                manager.deleteNotificationChannel("smart_dock_channel");
            }
        } catch (Throwable ignored) {
        }
    }

    private void resetHandleFadeTimer() {
        if (mPillHandle != null) {
            mPillHandle.animate().cancel();
            mPillHandle.setAlpha(1.0f);
            mHandler.removeCallbacks(mFadeHandleTask);
            if (mAutoFade && !mIsShelfExpanded && !mIsActiveDragSession) {
                mHandler.postDelayed(mFadeHandleTask, 3000);
            }
        }
    }

    // =========================================================================
    // Edge Pill Handle (Drag Target & Inward Swipe Trigger)
    // =========================================================================

    private void createPillHandle() {
        if (mPillHandle != null) return;

        LayoutInflater inflater = LayoutInflater.from(this);
        mPillHandle = inflater.inflate(R.layout.dock_pill_handle, null);

        float density = getResources().getDisplayMetrics().density;
        mPillNormalWidth = (int) (24 * density);
        mPillNormalHeight = (int) (80 * density);
        mPillExpandedWidth = (int) (84 * density);
        mPillExpandedHeight = (int) (180 * density);

        int savedY = mPrefs.getInt(KEY_HANDLE_Y, mScreenHeight / 3);
        mPillCurrentY = Math.max(100, Math.min(mScreenHeight - 300, savedY));

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                mPillNormalWidth,
                mPillNormalHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);

        params.gravity = (mIsRightEdge ? Gravity.END : Gravity.START) | Gravity.TOP;
        params.x = 0;
        params.y = mPillCurrentY;

        setupPillTouchListener();
        setupPillDragListener();

        mWindowManager.addView(mPillHandle, params);
        resetHandleFadeTimer();
    }

    private void removePillHandle() {
        if (mPillHandle != null) {
            mWindowManager.removeView(mPillHandle);
            mPillHandle = null;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupPillTouchListener() {
        mPillHandle.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mLastTouchX = event.getRawX();
                    mLastTouchY = event.getRawY();
                    mPillInitialY = getPillParams().y;
                    mIsPillDragging = false;
                    resetHandleFadeTimer();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float totalDx = event.getRawX() - mLastTouchX;
                    float totalDy = event.getRawY() - mLastTouchY;
                    if (!mIsPillDragging && (Math.abs(totalDx) > 16 || Math.abs(totalDy) > 16)) {
                        mIsPillDragging = true;
                    }
                    if (mIsPillDragging) {
                        WindowManager.LayoutParams params = getPillParams();
                        params.gravity = Gravity.START | Gravity.TOP;
                        params.x = (int) Math.max(0, Math.min(mScreenWidth - params.width, event.getRawX() - params.width / 2.0f));
                        params.y = (int) Math.max(40, Math.min(mScreenHeight - params.height - 40, event.getRawY() - params.height / 2.0f));
                        mWindowManager.updateViewLayout(mPillHandle, params);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float swipeX = event.getRawX() - mLastTouchX;
                    boolean inwardSwipe = mIsRightEdge ? (swipeX < -20) : (swipeX > 20);
                    long touchDuration = event.getEventTime() - event.getDownTime();

                    if (!mIsPillDragging || (inwardSwipe && touchDuration < 300)) {
                        snapPillToEdge(mIsRightEdge, mPillCurrentY);
                        toggleShelfWindow();
                    } else {
                        boolean newRightEdge = event.getRawX() >= (mScreenWidth / 2.0f);
                        int newY = (int) Math.max(60, Math.min(mScreenHeight - mPillNormalHeight - 60, event.getRawY() - mPillNormalHeight / 2.0f));
                        snapPillToEdge(newRightEdge, newY);
                        playHapticTick();
                    }
                    resetHandleFadeTimer();
                    return true;
            }
            return false;
        });
    }

    private void snapPillToEdge(boolean rightEdge, int y) {
        if (mPillHandle == null) return;
        mIsRightEdge = rightEdge;
        mPillCurrentY = y;

        WindowManager.LayoutParams params = getPillParams();
        params.gravity = (mIsRightEdge ? Gravity.END : Gravity.START) | Gravity.TOP;
        params.x = 0;
        params.y = mPillCurrentY;
        params.width = mPillNormalWidth;
        params.height = mPillNormalHeight;
        mWindowManager.updateViewLayout(mPillHandle, params);

        mPrefs.edit()
                .putString(KEY_HANDLE_POSITION, mIsRightEdge ? "right" : "left")
                .putInt(KEY_HANDLE_Y, mPillCurrentY)
                .apply();
    }

    private void setupPillDragListener() {
        mPillHandle.setOnDragListener((v, event) -> {
            View pillBody = mPillHandle != null ? mPillHandle.findViewById(R.id.dock_pill_body) : null;
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    mIsActiveDragSession = true;
                    mHandler.removeCallbacks(mFadeHandleTask);
                    if (mPillHandle != null) {
                        mPillHandle.animate().cancel();
                        mPillHandle.setAlpha(1.0f);
                    }
                    expandPillForDrop();
                    return true;

                case DragEvent.ACTION_DRAG_ENTERED:
                    mIsActiveDragSession = true;
                    mHandler.removeCallbacks(mFadeHandleTask);
                    if (mPillHandle != null) {
                        mPillHandle.animate().cancel();
                        mPillHandle.setAlpha(1.0f);
                    }
                    playHapticTick();
                    if (pillBody != null) {
                        pillBody.animate().scaleX(1.05f).scaleY(1.05f).setDuration(120).start();
                    }
                    return true;

                case DragEvent.ACTION_DRAG_EXITED:
                    if (pillBody != null) {
                        pillBody.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
                    }
                    return true;

                case DragEvent.ACTION_DROP:
                    mIsActiveDragSession = false;
                    collapsePillFromDrop();
                    boolean ingested = mContentManager.ingestDrop(event);
                    resetHandleFadeTimer();
                    return ingested;

                case DragEvent.ACTION_DRAG_ENDED:
                    mIsActiveDragSession = false;
                    collapsePillFromDrop();
                    resetHandleFadeTimer();
                    return true;
            }
            return false;
        });
    }

    private void expandPillForDrop() {
        if (mPillHandle == null) return;
        mPillHandle.animate().cancel();
        mPillHandle.setAlpha(1.0f);
        mHandler.removeCallbacks(mFadeHandleTask);
        WindowManager.LayoutParams params = getPillParams();
        params.gravity = (mIsRightEdge ? Gravity.END : Gravity.START) | Gravity.TOP;
        params.x = 0;
        params.width = mPillExpandedWidth;
        params.height = mPillExpandedHeight;
        int centerYOffset = (mPillExpandedHeight - mPillNormalHeight) / 2;
        params.y = Math.max(40, mPillCurrentY - centerYOffset);
        mWindowManager.updateViewLayout(mPillHandle, params);

        View pillBody = mPillHandle.findViewById(R.id.dock_pill_body);
        View grip = mPillHandle.findViewById(R.id.dock_pill_grip);
        View dropContainer = mPillHandle.findViewById(R.id.dock_pill_drop_container);

        if (pillBody != null) {
            pillBody.setBackgroundResource(R.drawable.dock_drop_zone_bg);
        }
        if (grip != null) grip.setVisibility(View.GONE);
        if (dropContainer != null) dropContainer.setVisibility(View.VISIBLE);
    }

    private void collapsePillFromDrop() {
        if (mPillHandle == null) return;
        mPillHandle.animate().cancel();
        mPillHandle.setAlpha(1.0f);
        WindowManager.LayoutParams params = getPillParams();
        params.gravity = (mIsRightEdge ? Gravity.END : Gravity.START) | Gravity.TOP;
        params.x = 0;
        params.width = mPillNormalWidth;
        params.height = mPillNormalHeight;
        params.y = mPillCurrentY;
        mWindowManager.updateViewLayout(mPillHandle, params);

        View pillBody = mPillHandle.findViewById(R.id.dock_pill_body);
        View grip = mPillHandle.findViewById(R.id.dock_pill_grip);
        View dropContainer = mPillHandle.findViewById(R.id.dock_pill_drop_container);

        if (pillBody != null) {
            pillBody.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start();
            pillBody.setBackgroundResource(R.drawable.dock_pill_handle_bg);
        }
        if (grip != null) grip.setVisibility(View.VISIBLE);
        if (dropContainer != null) dropContainer.setVisibility(View.GONE);
    }

    private void updatePillY(int rawY) {
        if (mPillHandle == null) return;
        WindowManager.LayoutParams params = getPillParams();
        int minY = 60;
        int maxY = mScreenHeight - 200;
        mPillCurrentY = Math.max(minY, Math.min(maxY, rawY));
        params.y = mPillCurrentY;
        mWindowManager.updateViewLayout(mPillHandle, params);
    }

    private WindowManager.LayoutParams getPillParams() {
        return (WindowManager.LayoutParams) mPillHandle.getLayoutParams();
    }

    // =========================================================================
    // Floating Dock Shelf Window
    // =========================================================================

    private void toggleShelfWindow() {
        if (mIsShelfExpanded) {
            collapseShelfWindow();
        } else {
            expandShelfWindow();
        }
    }

    private void expandShelfWindow() {
        if (mShelfWindow != null) return;

        mCurrentPageIndex = 0; // Always start on Today page when opening

        Context themedContext = new android.view.ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_DayNight);
        LayoutInflater inflater = LayoutInflater.from(themedContext);
        mShelfWindow = inflater.inflate(R.layout.dock_shelf_window, null);

        float density = getResources().getDisplayMetrics().density;
        int shelfWidth = (int) (180 * density);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                shelfWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);

        params.gravity = (mIsRightEdge ? Gravity.END : Gravity.START) | Gravity.TOP;
        params.x = (int) (6 * density);

        int pillY = (mPillHandle != null) ? getPillParams().y : (mScreenHeight / 3);
        int shelfY = pillY - (int) (40 * density);
        int maxShelfY = Math.max(0, mScreenHeight - (int) (420 * density));
        params.y = Math.max((int) (20 * density), Math.min(maxShelfY, shelfY));

        setupShelfViews();

        if (mShelfWindow instanceof DockShelfLayout) {
            DockShelfLayout shelf = (DockShelfLayout) mShelfWindow;
            shelf.setFocusable(true);
            shelf.setFocusableInTouchMode(true);
            shelf.requestFocus();
            shelf.setOnBackPressedListener(this::collapseShelfWindow);
        }

        mShelfWindow.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                collapseShelfWindow();
                return true;
            }
            return false;
        });

        mWindowManager.addView(mShelfWindow, params);
        mIsShelfExpanded = true;
        resetHandleFadeTimer();
    }

    private void collapseShelfWindow() {
        dismissImageViewer();
        if (mShelfWindow != null) {
            mWindowManager.removeView(mShelfWindow);
            mShelfWindow = null;
        }
        mIsShelfExpanded = false;
        resetHandleFadeTimer();
    }

    private void removeShelfWindow() {
        dismissImageViewer();
        if (mShelfWindow != null) {
            mWindowManager.removeView(mShelfWindow);
            mShelfWindow = null;
        }
        mIsShelfExpanded = false;
    }

    private void setupShelfViews() {
        if (mShelfWindow == null) return;

        View btnClose = mShelfWindow.findViewById(R.id.dock_shelf_btn_close);
        View btnClear = mShelfWindow.findViewById(R.id.dock_shelf_btn_clear);
        View header = mShelfWindow.findViewById(R.id.dock_shelf_header);
        ImageView btnPrev = mShelfWindow.findViewById(R.id.dock_day_btn_prev);
        ImageView btnNext = mShelfWindow.findViewById(R.id.dock_day_btn_next);

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> collapseShelfWindow());
        }

        if (btnClear != null) {
            btnClear.setOnClickListener(v -> showClearConfirmationDialog());
        }

        if (btnPrev != null) {
            btnPrev.setOnClickListener(v -> {
                if (mCurrentPageIndex < mDayPages.size() - 1) {
                    mCurrentPageIndex++;
                    populateShelfItems();
                    playHapticTick();
                }
            });
        }

        if (btnNext != null) {
            btnNext.setOnClickListener(v -> {
                if (mCurrentPageIndex > 0) {
                    mCurrentPageIndex--;
                    populateShelfItems();
                    playHapticTick();
                }
            });
        }

        setupShelfDragReposition(header);
        populateShelfItems();
    }

    private void showClearConfirmationDialog() {
        DockDatabase.DayPage page = (!mDayPages.isEmpty() && mCurrentPageIndex < mDayPages.size())
                ? mDayPages.get(mCurrentPageIndex) : null;
        List<DockItem> items = mDatabase.getItemsForPage(page);
        if (items == null || items.isEmpty()) {
            return;
        }

        Context themedContext = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        AlertDialog.Builder builder = new AlertDialog.Builder(themedContext);
        builder.setTitle(R.string.dock_clear_all);

        String message = (page != null)
                ? "Clear all items for " + page.label + "?"
                : "Clear all items from File Dock?";
        builder.setMessage(message);
        builder.setPositiveButton(R.string.dock_clear_all, (dialog, which) -> {
            if (page != null) {
                mDatabase.clearPage(page);
            } else {
                mDatabase.clearAll();
            }
            Toast.makeText(this, R.string.dock_clear_all, Toast.LENGTH_SHORT).show();
            playHapticTick();
        });
        builder.setNegativeButton(android.R.string.cancel, null);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
        dialog.show();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupShelfDragReposition(View header) {
        if (header == null) return;
        header.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float touchX, touchY;
            private boolean isMoving = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (mShelfWindow == null) return false;
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) mShelfWindow.getLayoutParams();
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        isMoving = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - touchX;
                        float dy = event.getRawY() - touchY;
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                            isMoving = true;
                        }
                        if (isMoving) {
                            params.x = Math.max(0, mIsRightEdge ? (int) (initialX - dx) : (int) (initialX + dx));
                            params.y = Math.max(20, (int) (initialY + dy));
                            mWindowManager.updateViewLayout(mShelfWindow, params);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        return isMoving;
                }
                return false;
            }
        });
    }

    public static boolean isWebUrl(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("ftp://")) {
            return true;
        }
        if (trimmed.startsWith("www.") && trimmed.length() > 5) {
            return true;
        }
        return Patterns.WEB_URL.matcher(trimmed).matches();
    }

    public static String extractDomain(String text) {
        if (text == null) return "Web link";
        try {
            String trimmed = text.trim();
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://") && !trimmed.startsWith("ftp://")) {
                trimmed = "https://" + trimmed;
            }
            URI uri = new URI(trimmed);
            String host = uri.getHost();
            if (host != null) {
                return host.startsWith("www.") ? host.substring(4) : host;
            }
        } catch (Exception ignored) {}
        return "Web link";
    }

    private void openWebLink(String url) {
        if (url == null || url.trim().isEmpty()) return;
        try {
            String target = url.trim();
            if (!target.startsWith("http://") && !target.startsWith("https://") && !target.startsWith("ftp://")) {
                target = "https://" + target;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            collapseShelfWindow();
        } catch (Exception e) {
            Toast.makeText(this, "Could not open link", Toast.LENGTH_SHORT).show();
        }
    }

    private void calculateAndApplyShelfDimensions(List<DockItem> items) {
        if (mShelfWindow == null) return;

        float density = getResources().getDisplayMetrics().density;
        int count = (items != null) ? items.size() : 0;

        int imageCount = 0;
        int linkCount = 0;
        int textCount = 0;

        if (items != null) {
            for (DockItem item : items) {
                if (item.type == DockItem.TYPE_IMAGE) {
                    imageCount++;
                } else if (item.type == DockItem.TYPE_TEXT && isWebUrl(item.textContent)) {
                    linkCount++;
                } else {
                    textCount++;
                }
            }
        }

        int targetWidthDp;
        int minHeightDp;
        boolean isTallEmpty = false;

        if (count == 0) {
            // OnePlus Smart Sidebar empty state: tall, slender vertical profile
            targetWidthDp = 180;
            minHeightDp = 380;
            isTallEmpty = true;
        } else if (count == 1) {
            // 1 item: slender vertical bar with comfortable height
            targetWidthDp = (imageCount > 0) ? 200 : 190;
            minHeightDp = 300;
        } else if (count == 2) {
            // 2 items: slender vertical bar
            if (imageCount == 2) {
                targetWidthDp = 205;
            } else if (imageCount == 1) {
                targetWidthDp = 200;
            } else {
                targetWidthDp = 190;
            }
            minHeightDp = 350;
        } else {
            // 3+ items
            if (imageCount >= 2) {
                // Multi-image gallery: widen to 250dp for 2-column grid layout
                targetWidthDp = 250;
                minHeightDp = 340;
            } else if (linkCount == count) {
                // All links: compact vertical list
                targetWidthDp = 195;
                minHeightDp = 320;
            } else {
                // Mixed text / notes
                targetWidthDp = 210;
                minHeightDp = 330;
            }
        }

        int targetWidthPx = (int) (targetWidthDp * density);
        int minHeightPx = (int) (minHeightDp * density);

        View root = mShelfWindow.findViewById(R.id.dock_shelf_root);
        if (root != null) {
            root.setMinimumHeight(minHeightPx);
        }

        WindowManager.LayoutParams params = (WindowManager.LayoutParams) mShelfWindow.getLayoutParams();
        if (params != null) {
            params.width = targetWidthPx;
            params.height = isTallEmpty ? minHeightPx : WindowManager.LayoutParams.WRAP_CONTENT;
            params.x = (int) (6 * density);

            int pillY = (mPillHandle != null) ? getPillParams().y : (mScreenHeight / 3);
            int shelfY = pillY - (int) (40 * density);
            int estimatedHeight = isTallEmpty ? minHeightPx : Math.min(minHeightPx + (int) (80 * density), (int) (mScreenHeight * 0.70f));
            int maxShelfY = Math.max(20, mScreenHeight - estimatedHeight - (int) (40 * density));
            params.y = Math.max((int) (24 * density), Math.min(maxShelfY, shelfY));

            if (mShelfWindow.getParent() != null) {
                mWindowManager.updateViewLayout(mShelfWindow, params);
            }
        }
    }

    private void populateShelfItems() {
        if (mShelfWindow == null) return;

        LinearLayout container = mShelfWindow.findViewById(R.id.dock_items_container);
        ScrollView scrollView = mShelfWindow.findViewById(R.id.dock_shelf_scroll);
        View emptyLayout = mShelfWindow.findViewById(R.id.dock_shelf_empty);
        TextView emptyTitle = mShelfWindow.findViewById(R.id.dock_shelf_empty_title);
        TextView emptyText = mShelfWindow.findViewById(R.id.dock_shelf_empty_text);
        View btnClear = mShelfWindow.findViewById(R.id.dock_shelf_btn_clear);
        TextView badgeView = mShelfWindow.findViewById(R.id.dock_shelf_badge);
        TextView dayLabel = mShelfWindow.findViewById(R.id.dock_day_label);
        ImageView btnPrev = mShelfWindow.findViewById(R.id.dock_day_btn_prev);
        ImageView btnNext = mShelfWindow.findViewById(R.id.dock_day_btn_next);

        if (container == null) return;
        container.removeAllViews();

        mDayPages = mDatabase.getAvailableDayPages();
        if (mCurrentPageIndex >= mDayPages.size()) {
            mCurrentPageIndex = Math.max(0, mDayPages.size() - 1);
        }

        DockDatabase.DayPage currentPage = !mDayPages.isEmpty() ? mDayPages.get(mCurrentPageIndex) : null;

        if (dayLabel != null && currentPage != null) {
            dayLabel.setText(currentPage.label);
        }

        if (btnPrev != null) {
            boolean hasPrev = mCurrentPageIndex < mDayPages.size() - 1;
            btnPrev.setEnabled(hasPrev);
            btnPrev.setAlpha(hasPrev ? 1.0f : 0.25f);
        }

        if (btnNext != null) {
            boolean hasNext = mCurrentPageIndex > 0;
            btnNext.setEnabled(hasNext);
            btnNext.setAlpha(hasNext ? 1.0f : 0.25f);
        }

        List<DockItem> items = mDatabase.getItemsForPage(currentPage);
        int count = items.size();

        if (btnClear != null) {
            btnClear.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        }

        if (badgeView != null) {
            if (count > 0) {
                badgeView.setVisibility(View.VISIBLE);
                badgeView.setText(String.valueOf(count));
            } else {
                badgeView.setVisibility(View.GONE);
            }
        }

        calculateAndApplyShelfDimensions(items);

        if (count == 0) {
            if (emptyLayout != null) {
                if (currentPage != null && "Today".equals(currentPage.label)) {
                    if (emptyTitle != null) emptyTitle.setText("File Dock Empty");
                    if (emptyText != null) emptyText.setText(R.string.dock_empty_hint);
                } else if (currentPage != null) {
                    if (emptyTitle != null) emptyTitle.setText("No items for " + currentPage.label);
                    if (emptyText != null) emptyText.setText("No content was staged on this day");
                } else {
                    if (emptyTitle != null) emptyTitle.setText("File Dock Empty");
                    if (emptyText != null) emptyText.setText(R.string.dock_empty_hint);
                }
                emptyLayout.setVisibility(View.VISIBLE);
            }
            if (scrollView != null) scrollView.setVisibility(View.GONE);
            return;
        }

        if (emptyLayout != null) emptyLayout.setVisibility(View.GONE);
        if (scrollView != null) scrollView.setVisibility(View.VISIBLE);

        Context themedContext = new android.view.ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_DayNight);
        LayoutInflater inflater = LayoutInflater.from(themedContext);
        float density = getResources().getDisplayMetrics().density;

        int imageCount = 0;
        for (DockItem item : items) {
            if (item.type == DockItem.TYPE_IMAGE) {
                imageCount++;
            }
        }
        boolean isMultiColumnImages = (count >= 3 && imageCount >= 2);

        int i = 0;
        while (i < items.size()) {
            DockItem item = items.get(i);
            if (item.type != DockItem.TYPE_IMAGE && (item.textContent == null || item.textContent.trim().isEmpty())) {
                i++;
                continue;
            }
            if (item.type == DockItem.TYPE_IMAGE) {
                if (isMultiColumnImages) {
                    DockItem nextItem = (i + 1 < items.size() && items.get(i + 1).type == DockItem.TYPE_IMAGE)
                            ? items.get(i + 1) : null;

                    LinearLayout row = new LinearLayout(themedContext);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rowParams.bottomMargin = (int) (8 * density);
                    row.setLayoutParams(rowParams);

                    View tile1 = inflater.inflate(R.layout.dock_item_image_tile, row, false);
                    LinearLayout.LayoutParams p1 = (LinearLayout.LayoutParams) tile1.getLayoutParams();
                    p1.width = 0;
                    p1.weight = 1f;
                    p1.setMarginEnd((int) (4 * density));
                    tile1.setLayoutParams(p1);
                    bindImageTile(tile1, item, false);
                    row.addView(tile1);

                    if (nextItem != null) {
                        View tile2 = inflater.inflate(R.layout.dock_item_image_tile, row, false);
                        LinearLayout.LayoutParams p2 = (LinearLayout.LayoutParams) tile2.getLayoutParams();
                        p2.width = 0;
                        p2.weight = 1f;
                        p2.setMarginStart((int) (4 * density));
                        tile2.setLayoutParams(p2);
                        bindImageTile(tile2, nextItem, false);
                        row.addView(tile2);
                        i += 2;
                    } else {
                        View spacer = new View(themedContext);
                        LinearLayout.LayoutParams pSpacer = new LinearLayout.LayoutParams(0, 0, 1f);
                        pSpacer.setMarginStart((int) (4 * density));
                        spacer.setLayoutParams(pSpacer);
                        row.addView(spacer);
                        i += 1;
                    }
                    container.addView(row);
                } else {
                    View tile = inflater.inflate(R.layout.dock_item_image_tile, container, false);
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tile.getLayoutParams();
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    lp.bottomMargin = (int) (8 * density);
                    tile.setLayoutParams(lp);
                    bindImageTile(tile, item, true);
                    container.addView(tile);
                    i++;
                }
            } else if (item.type == DockItem.TYPE_TEXT && isWebUrl(item.textContent)) {
                View linkCard = inflater.inflate(R.layout.dock_item_link, container, false);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) linkCard.getLayoutParams();
                lp.bottomMargin = (int) (8 * density);
                linkCard.setLayoutParams(lp);
                bindLinkCard(linkCard, item);
                linkCard.setOnLongClickListener(v -> {
                    startDragOut(v, item);
                    return true;
                });
                container.addView(linkCard);
                i++;
            } else {
                View card = inflater.inflate(R.layout.dock_item_text, container, false);
                LinearLayout.LayoutParams cp = (LinearLayout.LayoutParams) card.getLayoutParams();
                cp.bottomMargin = (int) (8 * density);
                card.setLayoutParams(cp);
                bindTextCard(card, item);
                card.setOnLongClickListener(v -> {
                    startDragOut(v, item);
                    return true;
                });
                container.addView(card);
                i++;
            }
        }
    }

    private void bindCommonCardHeader(View card, DockItem item) {
        ImageView appIcon = card.findViewById(R.id.dock_item_app_icon);
        TextView appName = card.findViewById(R.id.dock_item_app_name);
        TextView timeView = card.findViewById(R.id.dock_item_time);

        if (appIcon != null) {
            Drawable ic = mContentManager.getAppIcon(item.sourcePackage);
            if (ic != null) {
                appIcon.setImageDrawable(ic);
                appIcon.setVisibility(View.VISIBLE);
            } else {
                appIcon.setVisibility(View.GONE);
            }
        }

        if (appName != null) {
            appName.setText(item.sourceLabel != null ? item.sourceLabel : item.sourcePackage);
        }

        if (timeView != null) {
            timeView.setText(item.getFormattedTime());
        }
    }

    private void bindLinkCard(View card, DockItem item) {
        bindCommonCardHeader(card, item);

        TextView domainView = card.findViewById(R.id.dock_item_link_domain);
        TextView urlView = card.findViewById(R.id.dock_item_link_url);
        ImageView btnOpen = card.findViewById(R.id.dock_item_btn_open);
        ImageView btnCopy = card.findViewById(R.id.dock_item_btn_copy);
        ImageView btnShare = card.findViewById(R.id.dock_item_btn_share);
        ImageView btnDelete = card.findViewById(R.id.dock_item_btn_delete);

        String domain = extractDomain(item.textContent);
        if (domainView != null) {
            domainView.setText(domain);
        }
        if (urlView != null) {
            urlView.setText(item.textContent);
        }

        card.setOnClickListener(v -> openWebLink(item.textContent));

        if (btnOpen != null) {
            btnOpen.setOnClickListener(v -> openWebLink(item.textContent));
        }

        if (btnCopy != null) {
            btnCopy.setOnClickListener(v -> copyTextToClipboard(item.textContent));
        }

        if (btnShare != null) {
            btnShare.setOnClickListener(v -> shareText(item.textContent));
        }

        if (btnDelete != null) {
            btnDelete.setOnClickListener(v -> {
                mDatabase.deleteItem(item.id);
                Toast.makeText(this, R.string.dock_deleted_toast, Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void bindTextCard(View card, DockItem item) {
        bindCommonCardHeader(card, item);

        TextView tvContent = card.findViewById(R.id.dock_item_text_content);
        if (tvContent != null) {
            tvContent.setText(item.textContent);
        }

        ImageView btnCopy = card.findViewById(R.id.dock_item_btn_copy);
        ImageView btnShare = card.findViewById(R.id.dock_item_btn_share);
        ImageView btnDelete = card.findViewById(R.id.dock_item_btn_delete);

        // Tap card to paste directly into active app!
        card.setOnClickListener(v -> pasteTextToActiveApp(item.textContent));

        if (btnCopy != null) {
            btnCopy.setOnClickListener(v -> copyTextToClipboard(item.textContent));
        }

        if (btnShare != null) {
            btnShare.setOnClickListener(v -> shareText(item.textContent));
        }

        if (btnDelete != null) {
            btnDelete.setOnClickListener(v -> {
                mDatabase.deleteItem(item.id);
                Toast.makeText(this, R.string.dock_deleted_toast, Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void bindImageTile(View tile, DockItem item, boolean isFullWidth) {
        ImageView appIcon = tile.findViewById(R.id.dock_item_app_icon);
        TextView appName = tile.findViewById(R.id.dock_item_app_name);
        ImageView ivPreview = tile.findViewById(R.id.dock_item_image_preview);
        ImageView btnCopy = tile.findViewById(R.id.dock_item_btn_copy);
        ImageView btnDelete = tile.findViewById(R.id.dock_item_btn_delete);

        float density = getResources().getDisplayMetrics().density;
        if (ivPreview != null && isFullWidth) {
            ivPreview.getLayoutParams().height = (int) (105 * density);
        }

        if (appIcon != null) {
            Drawable ic = mContentManager.getAppIcon(item.sourcePackage);
            if (ic != null) {
                appIcon.setImageDrawable(ic);
                appIcon.setVisibility(View.VISIBLE);
            } else {
                appIcon.setVisibility(View.GONE);
            }
        }

        if (appName != null) {
            appName.setText(item.sourceLabel != null ? item.sourceLabel : item.sourcePackage);
        }

        if (ivPreview != null) {
            String thumbPath = item.thumbnailPath != null ? item.thumbnailPath : item.filePath;
            Bitmap bmp = loadThumbnailBitmap(thumbPath);
            if (bmp != null) {
                ivPreview.setImageBitmap(bmp);
            }
            ivPreview.setOnClickListener(v -> showInbuiltImageViewer(item));
        }

        tile.setOnClickListener(v -> showInbuiltImageViewer(item));

        tile.setOnLongClickListener(v -> {
            startDragOut(v, item);
            return true;
        });

        if (btnCopy != null) {
            btnCopy.setOnClickListener(v -> copyImageUriToClipboard(item));
        }

        if (btnDelete != null) {
            btnDelete.setOnClickListener(v -> {
                if (item.thumbnailPath != null) {
                    mThumbnailCache.remove(item.thumbnailPath);
                }
                mDatabase.deleteItem(item.id);
                Toast.makeText(this, R.string.dock_deleted_toast, Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void bindImageCard(View card, DockItem item) {
        bindCommonCardHeader(card, item);

        ImageView ivPreview = card.findViewById(R.id.dock_item_image_preview);
        if (ivPreview != null) {
            String thumbPath = item.thumbnailPath != null ? item.thumbnailPath : item.filePath;
            Bitmap bmp = loadThumbnailBitmap(thumbPath);
            if (bmp != null) {
                ivPreview.setImageBitmap(bmp);
            }
            ivPreview.setOnClickListener(v -> showInbuiltImageViewer(item));
        }

        ImageView btnCopy = card.findViewById(R.id.dock_item_btn_copy);
        ImageView btnShare = card.findViewById(R.id.dock_item_btn_share);
        ImageView btnDelete = card.findViewById(R.id.dock_item_btn_delete);

        card.setOnClickListener(v -> showInbuiltImageViewer(item));

        if (btnCopy != null) {
            btnCopy.setOnClickListener(v -> copyImageUriToClipboard(item));
        }

        if (btnShare != null) {
            btnShare.setOnClickListener(v -> shareImage(item));
        }

        if (btnDelete != null) {
            btnDelete.setOnClickListener(v -> {
                if (item.thumbnailPath != null) {
                    mThumbnailCache.remove(item.thumbnailPath);
                }
                mDatabase.deleteItem(item.id);
                Toast.makeText(this, R.string.dock_deleted_toast, Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void pasteTextToActiveApp(String text) {
        if (text == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("dock_paste", text));
        }
        collapseShelfWindow();
        playHapticTick();
        mHandler.postDelayed(() -> {
            boolean injected = injectPasteEvent();
            if (injected) {
                Toast.makeText(this, R.string.dock_pasted_toast, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.dock_copied_toast, Toast.LENGTH_SHORT).show();
            }
        }, 150);
    }

    private void pasteImageToActiveApp(DockItem item) {
        if (item.filePath == null) return;
        File f = new File(item.filePath);
        if (!f.exists()) return;
        Uri contentUri = FileProvider.getUriForFile(this, DockFileProvider.AUTHORITY, f);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            String mime = item.mimeType != null ? item.mimeType : getContentResolver().getType(contentUri);
            if (mime == null) mime = "image/jpeg";
            ClipDescription desc = new ClipDescription(f.getName(), new String[]{mime, "image/*"});
            ClipData.Item clipItem = new ClipData.Item(contentUri);
            cm.setPrimaryClip(new ClipData(desc, clipItem));
        }
        collapseShelfWindow();
        playHapticTick();
        Toast.makeText(this, R.string.dock_image_copied_toast, Toast.LENGTH_SHORT).show();
    }

    private boolean injectPasteEvent() {
        try {
            android.hardware.input.InputManager im = getSystemService(android.hardware.input.InputManager.class);
            if (im == null) return false;
            long now = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PASTE, 0);
            KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PASTE, 0);
            try {
                java.lang.reflect.Method m = android.hardware.input.InputManager.class.getMethod(
                        "injectInputEvent", android.view.InputEvent.class, int.class);
                m.invoke(im, down, 0);
                m.invoke(im, up, 0);
                return true;
            } catch (NoSuchMethodException e) {
                im.injectInputEvent(down, 0);
                im.injectInputEvent(up, 0);
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to inject paste event: " + t.getMessage());
            return false;
        }
    }

    // =========================================================================
    // Drag OUT Functionality (Cross-App Drop)
    // =========================================================================

    private void startDragOut(View view, DockItem item) {
        ClipData clipData;
        if (item.type == DockItem.TYPE_IMAGE && item.filePath != null) {
            File f = new File(item.filePath);
            if (!f.exists()) return;
            Uri contentUri = FileProvider.getUriForFile(this, DockFileProvider.AUTHORITY, f);
            String mime = item.mimeType != null ? item.mimeType : getContentResolver().getType(contentUri);
            if (mime == null) mime = "image/jpeg";
            ClipDescription desc = new ClipDescription(f.getName(), new String[]{mime, "image/*"});
            ClipData.Item clipItem = new ClipData.Item(contentUri);
            clipData = new ClipData(desc, clipItem);
        } else {
            if (item.textContent == null) return;
            if (isWebUrl(item.textContent)) {
                ClipDescription desc = new ClipDescription("dock_link", new String[]{
                        ClipDescription.MIMETYPE_TEXT_PLAIN,
                        ClipDescription.MIMETYPE_TEXT_URILIST
                });
                ClipData.Item clipItem = new ClipData.Item(
                        item.textContent,
                        null,
                        Uri.parse(item.textContent)
                );
                clipData = new ClipData(desc, clipItem);
            } else {
                clipData = ClipData.newPlainText("dock_text", item.textContent);
            }
        }

        View.DragShadowBuilder shadow = new View.DragShadowBuilder(view) {
            @Override
            public void onProvideShadowMetrics(Point outShadowSize, Point outShadowTouchPoint) {
                super.onProvideShadowMetrics(outShadowSize, outShadowTouchPoint);
                outShadowTouchPoint.set(outShadowSize.x / 2, outShadowSize.y / 2);
            }
        };

        int flags = View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ;
        boolean started = view.startDragAndDrop(clipData, shadow, item, flags);
        if (started) {
            // Temporarily minimize the dock shelf so user can drop cleanly into underlying app
            collapseShelfWindow();
        }
        playHapticTick();
    }

    // =========================================================================
    // Quick Helper Actions
    // =========================================================================

    private void copyTextToClipboard(String text) {
        if (text == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("dock_copy", text));
            Toast.makeText(this, R.string.dock_copied_toast, Toast.LENGTH_SHORT).show();
            playHapticTick();
        }
    }

    private void copyImageUriToClipboard(DockItem item) {
        if (item.filePath == null) return;
        File f = new File(item.filePath);
        if (!f.exists()) return;
        Uri contentUri = FileProvider.getUriForFile(this, DockFileProvider.AUTHORITY, f);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            String mime = item.mimeType != null ? item.mimeType : getContentResolver().getType(contentUri);
            if (mime == null) mime = "image/jpeg";
            ClipDescription desc = new ClipDescription(f.getName(), new String[]{mime, "image/*"});
            ClipData.Item clipItem = new ClipData.Item(contentUri);
            cm.setPrimaryClip(new ClipData(desc, clipItem));
            Toast.makeText(this, R.string.dock_copied_toast, Toast.LENGTH_SHORT).show();
            playHapticTick();
        }
    }

    private void shareText(String text) {
        if (text == null) return;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(Intent.createChooser(intent, getString(R.string.dock_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void shareImage(DockItem item) {
        if (item.filePath == null) return;
        File f = new File(item.filePath);
        if (!f.exists()) return;
        Uri contentUri = FileProvider.getUriForFile(this, DockFileProvider.AUTHORITY, f);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(item.mimeType != null ? item.mimeType : "image/*");
        intent.putExtra(Intent.EXTRA_STREAM, contentUri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(Intent.createChooser(intent, getString(R.string.dock_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void showInbuiltImageViewer(DockItem item) {
        dismissImageViewer();

        Context themedContext = new android.view.ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_DayNight);
        LayoutInflater inflater = LayoutInflater.from(themedContext);
        mImageViewerWindow = inflater.inflate(R.layout.dock_image_viewer_popup, null);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.CENTER;

        if (mImageViewerWindow instanceof DockShelfLayout) {
            DockShelfLayout viewerLayout = (DockShelfLayout) mImageViewerWindow;
            viewerLayout.setFocusable(true);
            viewerLayout.setFocusableInTouchMode(true);
            viewerLayout.requestFocus();
            viewerLayout.setOnBackPressedListener(this::dismissImageViewer);
        }

        View btnClose = mImageViewerWindow.findViewById(R.id.dock_viewer_btn_close);
        View backdrop = mImageViewerWindow.findViewById(R.id.dock_viewer_backdrop);
        if (btnClose != null) btnClose.setOnClickListener(v -> dismissImageViewer());
        if (backdrop != null) backdrop.setOnClickListener(v -> dismissImageViewer());

        ImageView appIcon = mImageViewerWindow.findViewById(R.id.dock_viewer_app_icon);
        TextView titleView = mImageViewerWindow.findViewById(R.id.dock_viewer_title);
        TextView subtitleView = mImageViewerWindow.findViewById(R.id.dock_viewer_subtitle);

        if (appIcon != null) {
            Drawable ic = mContentManager.getAppIcon(item.sourcePackage);
            if (ic != null) {
                appIcon.setImageDrawable(ic);
                appIcon.setVisibility(View.VISIBLE);
            } else {
                appIcon.setVisibility(View.GONE);
            }
        }

        if (titleView != null) {
            titleView.setText(item.sourceLabel != null ? item.sourceLabel : getString(R.string.dock_title));
        }

        if (subtitleView != null) {
            subtitleView.setText(item.getFormattedTime());
        }

        ImageView imageView = mImageViewerWindow.findViewById(R.id.dock_viewer_image);
        String imagePath = item.filePath != null ? item.filePath : item.thumbnailPath;
        if (imageView != null && imagePath != null) {
            mCurrentViewerBitmap = decodeSampledBitmap(imagePath, mScreenWidth, mScreenHeight);
            if (mCurrentViewerBitmap != null) {
                imageView.setImageBitmap(mCurrentViewerBitmap);
            }
        }

        ImageView btnCopy = mImageViewerWindow.findViewById(R.id.dock_viewer_btn_copy);
        ImageView btnShare = mImageViewerWindow.findViewById(R.id.dock_viewer_btn_share);
        View btnPaste = mImageViewerWindow.findViewById(R.id.dock_viewer_btn_paste);

        if (btnCopy != null) {
            btnCopy.setOnClickListener(v -> copyImageUriToClipboard(item));
        }
        if (btnShare != null) {
            btnShare.setOnClickListener(v -> shareImage(item));
        }
        if (btnPaste != null) {
            btnPaste.setOnClickListener(v -> {
                dismissImageViewer();
                pasteImageToActiveApp(item);
            });
        }

        mWindowManager.addView(mImageViewerWindow, params);
        mIsImageViewerOpen = true;
        playHapticTick();
    }

    private void dismissImageViewer() {
        if (mImageViewerWindow != null) {
            try {
                ImageView iv = mImageViewerWindow.findViewById(R.id.dock_viewer_image);
                if (iv != null) iv.setImageDrawable(null);
                mWindowManager.removeView(mImageViewerWindow);
            } catch (Throwable t) {
                Log.w(TAG, "Error removing viewer window", t);
            }
            mImageViewerWindow = null;
        }
        if (mCurrentViewerBitmap != null && !mCurrentViewerBitmap.isRecycled()) {
            mCurrentViewerBitmap.recycle();
            mCurrentViewerBitmap = null;
        }
        mIsImageViewerOpen = false;
    }

    private Bitmap loadThumbnailBitmap(String path) {
        if (path == null) return null;
        Bitmap cached = mThumbnailCache.get(path);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }
        Bitmap decoded = decodeSampledBitmap(path, 240, 240);
        if (decoded != null) {
            mThumbnailCache.put(path, decoded);
        }
        return decoded;
    }

    private Bitmap decodeSampledBitmap(String path, int reqWidth, int reqHeight) {
        if (path == null) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);

            int height = options.outHeight;
            int width = options.outWidth;
            int inSampleSize = 1;

            if (height > reqHeight || width > reqWidth) {
                final int halfHeight = height / 2;
                final int halfWidth = width / 2;
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2;
                }
            }

            options.inSampleSize = Math.max(1, inSampleSize);
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, options);
        } catch (Throwable t) {
            Log.e(TAG, "Error decoding sampled bitmap: " + path, t);
            return null;
        }
    }

    private void playHapticTick() {
        try {
            Vibrator vibrator = getSystemService(Vibrator.class);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
            }
        } catch (Throwable ignored) {
        }
    }
}

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

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.CombinedVibration;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.DragAndDropPermissions;
import android.view.DragEvent;
import android.widget.Toast;

import org.lineageos.device.DeviceSettings.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DockContentManager {

    private static final String TAG = "DockContentManager";
    private static final String DOCK_DIR = "dock_storage";

    private final Context mContext;
    private final DockDatabase mDatabase;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public DockContentManager(Context context) {
        mContext = context.getApplicationContext();
        mDatabase = DockDatabase.getInstance(mContext);
    }

    public boolean ingestDrop(DragEvent event) {
        ClipData clipData = event.getClipData();
        if (clipData == null || clipData.getItemCount() == 0) {
            Log.w(TAG, "Empty clipData in drop event");
            return false;
        }

        // Transient URI permissions for content URIs
        DragAndDropPermissions dropPerms = null;
        try {
            dropPerms = DragAndDropPermissions.obtain(event);
            if (dropPerms != null) {
                dropPerms.takeTransient();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not take transient drag permissions", t);
        }

        // Identify source application
        SourceAppInfo sourceApp = resolveSourceApp(clipData);

        // Process items asynchronously
        final DragAndDropPermissions finalDropPerms = dropPerms;
        mExecutor.execute(() -> {
            try {
                int itemCount = clipData.getItemCount();
                boolean anySaved = false;

                for (int i = 0; i < itemCount; i++) {
                    ClipData.Item item = clipData.getItemAt(i);
                    Uri uri = item.getUri();
                    CharSequence text = item.getText();

                    if (uri != null) {
                        anySaved |= processUriItem(uri, clipData.getDescription(), sourceApp);
                    } else if (text != null && text.length() > 0) {
                        anySaved |= processTextItem(text.toString(), sourceApp);
                    } else {
                        // Fallback: coerce to text
                        CharSequence coerced = item.coerceToText(mContext);
                        if (coerced != null && coerced.length() > 0) {
                            anySaved |= processTextItem(coerced.toString(), sourceApp);
                        }
                    }
                }

                if (anySaved) {
                    playSuccessHaptic();
                    showSavedToast(sourceApp.label);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error ingesting drop payload", e);
            } finally {
                if (finalDropPerms != null) {
                    try {
                        finalDropPerms.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });

        return true;
    }

    private boolean processTextItem(String text, SourceAppInfo sourceApp) {
        if (text == null || text.trim().isEmpty()) return false;
        DockItem dockItem = new DockItem(
                DockItem.TYPE_TEXT,
                text.trim(),
                null,
                null,
                sourceApp.packageName,
                sourceApp.label,
                ClipDescription.MIMETYPE_TEXT_PLAIN
        );
        mDatabase.insertItem(dockItem);
        return true;
    }

    private boolean processUriItem(Uri uri, ClipDescription description, SourceAppInfo sourceApp) {
        ContentResolver cr = mContext.getContentResolver();
        String mime = cr.getType(uri);
        if (mime == null && description != null && description.getMimeTypeCount() > 0) {
            mime = description.getMimeType(0);
        }
        if (mime == null) mime = "application/octet-stream";

        boolean isImage = mime.startsWith("image/");
        String ext = isImage ? ".jpg" : ".bin";
        if (mime.contains("png")) ext = ".png";
        else if (mime.contains("webp")) ext = ".webp";
        else if (mime.contains("gif")) ext = ".gif";

        File storageDir = new File(mContext.getFilesDir(), DOCK_DIR);
        if (!storageDir.exists()) storageDir.mkdirs();

        String fileName = UUID.randomUUID().toString() + ext;
        File destFile = new File(storageDir, fileName);

        try (InputStream in = cr.openInputStream(uri);
             FileOutputStream out = new FileOutputStream(destFile)) {
            if (in == null) return false;
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy URI stream to storage: " + uri, e);
            return false;
        }

        String thumbPath = null;
        if (isImage) {
            thumbPath = generateThumbnail(destFile, storageDir);
        }

        DockItem dockItem = new DockItem(
                isImage ? DockItem.TYPE_IMAGE : DockItem.TYPE_FILE,
                null,
                destFile.getAbsolutePath(),
                thumbPath != null ? thumbPath : destFile.getAbsolutePath(),
                sourceApp.packageName,
                sourceApp.label,
                mime
        );
        mDatabase.insertItem(dockItem);
        return true;
    }

    private String generateThumbnail(File sourceImage, File storageDir) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(sourceImage.getAbsolutePath(), opts);

            int maxDim = 240;
            int scale = 1;
            while (opts.outWidth / scale / 2 >= maxDim && opts.outHeight / scale / 2 >= maxDim) {
                scale *= 2;
            }

            opts.inJustDecodeBounds = false;
            opts.inSampleSize = Math.max(1, scale);
            Bitmap bmp = BitmapFactory.decodeFile(sourceImage.getAbsolutePath(), opts);
            if (bmp == null) return null;

            File thumbFile = new File(storageDir, "thumb_" + sourceImage.getName());
            try (FileOutputStream fos = new FileOutputStream(thumbFile)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            }
            bmp.recycle();
            return thumbFile.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "Thumbnail generation failed", e);
            return null;
        }
    }

    public static class SourceAppInfo {
        public String packageName;
        public String label;
        public Drawable icon;

        public SourceAppInfo(String pkg, String lbl, Drawable ic) {
            this.packageName = pkg;
            this.label = lbl;
            this.icon = ic;
        }
    }

    public SourceAppInfo resolveSourceApp(ClipData clipData) {
        String pkg = null;
        try {
            ActivityTaskManager atm = ActivityTaskManager.getInstance();
            List<ActivityManager.RunningTaskInfo> tasks = atm.getTasks(1);
            if (tasks != null && !tasks.isEmpty()) {
                ActivityManager.RunningTaskInfo task = tasks.get(0);
                if (task.topActivity != null) {
                    pkg = task.topActivity.getPackageName();
                } else if (task.baseActivity != null) {
                    pkg = task.baseActivity.getPackageName();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get top task via ActivityTaskManager", e);
        }

        if (pkg == null && clipData != null && clipData.getItemCount() > 0) {
            Uri uri = clipData.getItemAt(0).getUri();
            if (uri != null) {
                String auth = uri.getAuthority();
                if (auth != null) {
                    int dotIdx = auth.indexOf('.');
                    if (dotIdx > 0) {
                        pkg = auth;
                    }
                }
            }
        }

        if (pkg == null) {
            pkg = "unknown";
        }

        PackageManager pm = mContext.getPackageManager();
        String label = pkg;
        Drawable icon = null;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null) label = cs.toString();
            icon = pm.getApplicationIcon(ai);
        } catch (Exception ignored) {
        }

        return new SourceAppInfo(pkg, label, icon);
    }

    public Drawable getAppIcon(String packageName) {
        try {
            PackageManager pm = mContext.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationIcon(ai);
        } catch (Exception e) {
            return null;
        }
    }

    private void playSuccessHaptic() {
        try {
            Vibrator vibrator = mContext.getSystemService(Vibrator.class);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
            }
        } catch (Throwable ignored) {
        }
    }

    private void showSavedToast(String appLabel) {
        mMainHandler.post(() -> {
            String msg = mContext.getString(R.string.dock_saved_toast, appLabel);
            Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show();
        });
    }
}

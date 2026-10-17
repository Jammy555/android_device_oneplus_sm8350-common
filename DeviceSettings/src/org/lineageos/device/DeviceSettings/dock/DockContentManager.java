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
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.view.DragAndDropPermissions;
import android.view.DragEvent;
import android.widget.Toast;

import org.lineageos.device.DeviceSettings.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
                } else {
                    playErrorHaptic();
                    showUnsupportedToast();
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
        if (uri == null) return false;

        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) || "ftp".equalsIgnoreCase(scheme)) {
            return processTextItem(uri.toString(), sourceApp);
        }

        ContentResolver cr = mContext.getContentResolver();
        String mime = null;
        try {
            mime = cr.getType(uri);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to get MIME type for: " + uri, t);
        }
        if (mime == null && description != null && description.getMimeTypeCount() > 0) {
            mime = description.getMimeType(0);
        }

        String displayName = queryDisplayName(cr, uri);
        String ext = resolveImageExtension(displayName, mime);

        if (ext == null) {
            // Check if it's text content via URI
            boolean isTextMime = mime != null && (mime.startsWith("text/") || mime.equals("application/json"));
            boolean isTextExt = displayName != null && (
                    displayName.toLowerCase().endsWith(".txt") ||
                    displayName.toLowerCase().endsWith(".json") ||
                    displayName.toLowerCase().endsWith(".md") ||
                    displayName.toLowerCase().endsWith(".log") ||
                    displayName.toLowerCase().endsWith(".csv")
            );
            if (isTextMime || isTextExt) {
                String textContent = readTextFromUri(cr, uri);
                if (textContent != null && !textContent.trim().isEmpty()) {
                    return processTextItem(textContent, sourceApp);
                }
            }
            // Strictly reject all other file types (music, audio, video, archives, binaries, etc.)
            Log.w(TAG, "Rejected unsupported drop format: " + uri + " (mime=" + mime + ", name=" + displayName + ")");
            return false;
        }

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

        String normalizedMime = getMimeForExtension(ext);
        String thumbPath = generateThumbnail(destFile, storageDir);

        DockItem dockItem = new DockItem(
                DockItem.TYPE_IMAGE,
                null,
                destFile.getAbsolutePath(),
                thumbPath != null ? thumbPath : destFile.getAbsolutePath(),
                sourceApp.packageName,
                sourceApp.label,
                normalizedMime
        );
        mDatabase.insertItem(dockItem);
        return true;
    }

    private String queryDisplayName(ContentResolver cr, Uri uri) {
        if (uri == null) return null;
        try (Cursor cursor = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return cursor.getString(idx);
                }
            }
        } catch (Throwable ignored) {
        }
        return uri.getLastPathSegment();
    }

    private String resolveImageExtension(String fileName, String mime) {
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return ".jpg";
            if (lower.endsWith(".png")) return ".png";
            if (lower.endsWith(".gif")) return ".gif";
            if (lower.endsWith(".heic") || lower.endsWith(".heif")) return ".heic";
            if (lower.endsWith(".webp")) return ".webp";
        }
        if (mime != null) {
            String lowerMime = mime.toLowerCase();
            if (lowerMime.equals("image/jpeg") || lowerMime.equals("image/jpg")) return ".jpg";
            if (lowerMime.equals("image/png")) return ".png";
            if (lowerMime.equals("image/gif")) return ".gif";
            if (lowerMime.contains("heic") || lowerMime.contains("heif")) return ".heic";
            if (lowerMime.equals("image/webp")) return ".webp";
            if (lowerMime.startsWith("image/")) return ".jpg";
        }
        return null;
    }

    private String getMimeForExtension(String ext) {
        if (ext == null) return "image/jpeg";
        switch (ext) {
            case ".png": return "image/png";
            case ".gif": return "image/gif";
            case ".heic": return "image/heic";
            case ".webp": return "image/webp";
            default: return "image/jpeg";
        }
    }

    private String readTextFromUri(ContentResolver cr, Uri uri) {
        try (InputStream in = cr.openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 50000) break;
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return null;
        }
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

    private void playErrorHaptic() {
        try {
            Vibrator vibrator = mContext.getSystemService(Vibrator.class);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK));
            }
        } catch (Throwable ignored) {
        }
    }

    private void showUnsupportedToast() {
        mMainHandler.post(() -> {
            Toast.makeText(mContext, R.string.dock_unsupported_format, Toast.LENGTH_SHORT).show();
        });
    }
}

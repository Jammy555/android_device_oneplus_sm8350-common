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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public class DockDatabase extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "dock_items.db";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_ITEMS = "dock_items";
    public static final String COL_ID = "_id";
    public static final String COL_TYPE = "type";
    public static final String COL_TEXT_CONTENT = "text_content";
    public static final String COL_FILE_PATH = "file_path";
    public static final String COL_THUMBNAIL_PATH = "thumbnail_path";
    public static final String COL_SOURCE_PKG = "source_pkg";
    public static final String COL_SOURCE_LABEL = "source_label";
    public static final String COL_MIME_TYPE = "mime_type";
    public static final String COL_TIMESTAMP = "timestamp";

    public interface OnDataChangedListener {
        void onDataChanged();
    }

    private static DockDatabase sInstance;
    private final List<OnDataChangedListener> mListeners = new CopyOnWriteArrayList<>();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public static synchronized DockDatabase getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new DockDatabase(context.getApplicationContext());
        }
        return sInstance;
    }

    private DockDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_ITEMS + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_TYPE + " INTEGER, "
                + COL_TEXT_CONTENT + " TEXT, "
                + COL_FILE_PATH + " TEXT, "
                + COL_THUMBNAIL_PATH + " TEXT, "
                + COL_SOURCE_PKG + " TEXT, "
                + COL_SOURCE_LABEL + " TEXT, "
                + COL_MIME_TYPE + " TEXT, "
                + COL_TIMESTAMP + " INTEGER"
                + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_ITEMS);
        onCreate(db);
    }

    public static final int MAX_ITEMS_LIMIT = 50;

    public synchronized long insertItem(DockItem item) {
        SQLiteDatabase db = getWritableDatabase();

        // 1. Text Deduplication: if identical text already exists, refresh timestamp and source app
        if (item.type == DockItem.TYPE_TEXT && item.textContent != null) {
            Cursor dup = db.query(TABLE_ITEMS, new String[]{COL_ID},
                    COL_TYPE + "=? AND " + COL_TEXT_CONTENT + "=?",
                    new String[]{String.valueOf(DockItem.TYPE_TEXT), item.textContent},
                    null, null, null);
            if (dup != null) {
                try {
                    if (dup.moveToFirst()) {
                        long existingId = dup.getLong(0);
                        ContentValues updateCv = new ContentValues();
                        updateCv.put(COL_TIMESTAMP, item.timestamp);
                        if (item.sourcePackage != null) updateCv.put(COL_SOURCE_PKG, item.sourcePackage);
                        if (item.sourceLabel != null) updateCv.put(COL_SOURCE_LABEL, item.sourceLabel);
                        db.update(TABLE_ITEMS, updateCv, COL_ID + "=?", new String[]{String.valueOf(existingId)});
                        item.id = existingId;
                        notifyDataChanged();
                        return existingId;
                    }
                } finally {
                    dup.close();
                }
            }
        }

        // 2. FIFO Eviction: ensure item count never exceeds MAX_ITEMS_LIMIT
        enforceCapacityLimit(db, MAX_ITEMS_LIMIT - 1);

        ContentValues cv = new ContentValues();
        cv.put(COL_TYPE, item.type);
        cv.put(COL_TEXT_CONTENT, item.textContent);
        cv.put(COL_FILE_PATH, item.filePath);
        cv.put(COL_THUMBNAIL_PATH, item.thumbnailPath);
        cv.put(COL_SOURCE_PKG, item.sourcePackage);
        cv.put(COL_SOURCE_LABEL, item.sourceLabel);
        cv.put(COL_MIME_TYPE, item.mimeType);
        cv.put(COL_TIMESTAMP, item.timestamp);
        long id = db.insert(TABLE_ITEMS, null, cv);
        item.id = id;
        notifyDataChanged();
        return id;
    }

    private void enforceCapacityLimit(SQLiteDatabase db, int maxAllowed) {
        Cursor cursor = db.rawQuery(
                "SELECT " + COL_ID + ", " + COL_FILE_PATH + ", " + COL_THUMBNAIL_PATH
                        + " FROM " + TABLE_ITEMS
                        + " ORDER BY " + COL_TIMESTAMP + " ASC", null);
        if (cursor != null) {
            try {
                int totalCount = cursor.getCount();
                int toEvict = totalCount - maxAllowed;
                while (toEvict > 0 && cursor.moveToNext()) {
                    long id = cursor.getLong(0);
                    String file = cursor.getString(1);
                    String thumb = cursor.getString(2);
                    if (file != null) safeDeleteFile(file);
                    if (thumb != null && !thumb.equals(file)) safeDeleteFile(thumb);
                    db.delete(TABLE_ITEMS, COL_ID + "=?", new String[]{String.valueOf(id)});
                    toEvict--;
                }
            } finally {
                cursor.close();
            }
        }
    }

    public synchronized List<DockItem> getAllItems() {
        List<DockItem> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_ITEMS, null, null, null, null, null, COL_TIMESTAMP + " DESC");
        if (cursor != null) {
            try {
                int idIdx = cursor.getColumnIndexOrThrow(COL_ID);
                int typeIdx = cursor.getColumnIndexOrThrow(COL_TYPE);
                int textIdx = cursor.getColumnIndexOrThrow(COL_TEXT_CONTENT);
                int fileIdx = cursor.getColumnIndexOrThrow(COL_FILE_PATH);
                int thumbIdx = cursor.getColumnIndexOrThrow(COL_THUMBNAIL_PATH);
                int pkgIdx = cursor.getColumnIndexOrThrow(COL_SOURCE_PKG);
                int labelIdx = cursor.getColumnIndexOrThrow(COL_SOURCE_LABEL);
                int mimeIdx = cursor.getColumnIndexOrThrow(COL_MIME_TYPE);
                int timeIdx = cursor.getColumnIndexOrThrow(COL_TIMESTAMP);

                while (cursor.moveToNext()) {
                    DockItem item = new DockItem();
                    item.id = cursor.getLong(idIdx);
                    item.type = cursor.getInt(typeIdx);
                    item.textContent = cursor.getString(textIdx);
                    item.filePath = cursor.getString(fileIdx);
                    item.thumbnailPath = cursor.getString(thumbIdx);
                    item.sourcePackage = cursor.getString(pkgIdx);
                    item.sourceLabel = cursor.getString(labelIdx);
                    item.mimeType = cursor.getString(mimeIdx);
                    item.timestamp = cursor.getLong(timeIdx);
                    list.add(item);
                }
            } finally {
                cursor.close();
            }
        }
        return list;
    }

    public synchronized int getItemCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_ITEMS, null);
        int count = 0;
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    count = cursor.getInt(0);
                }
            } finally {
                cursor.close();
            }
        }
        return count;
    }

    public synchronized void deleteItem(long id) {
        SQLiteDatabase db = getWritableDatabase();
        // Check for attached file to remove
        Cursor cursor = db.query(TABLE_ITEMS, new String[]{COL_FILE_PATH, COL_THUMBNAIL_PATH},
                COL_ID + "=?", new String[]{String.valueOf(id)}, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    String file = cursor.getString(0);
                    String thumb = cursor.getString(1);
                    if (file != null) safeDeleteFile(file);
                    if (thumb != null && !thumb.equals(file)) safeDeleteFile(thumb);
                }
            } finally {
                cursor.close();
            }
        }
        db.delete(TABLE_ITEMS, COL_ID + "=?", new String[]{String.valueOf(id)});
        notifyDataChanged();
    }

    public synchronized void clearAll() {
        List<DockItem> all = getAllItems();
        for (DockItem item : all) {
            if (item.filePath != null) safeDeleteFile(item.filePath);
            if (item.thumbnailPath != null && !item.thumbnailPath.equals(item.filePath)) {
                safeDeleteFile(item.thumbnailPath);
            }
        }
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_ITEMS, null, null);
        notifyDataChanged();
    }

    public static class DayPage {
        public final String dateKey; // "YYYY-MM-DD"
        public final String label;   // "Today", "Yesterday", or "Oct 12"
        public final long startMillis;
        public final long endMillis;

        public DayPage(String dateKey, String label, long startMillis, long endMillis) {
            this.dateKey = dateKey;
            this.label = label;
            this.startMillis = startMillis;
            this.endMillis = endMillis;
        }
    }

    public synchronized List<DayPage> getAvailableDayPages() {
        List<DayPage> pages = new ArrayList<>();

        // Always compute Today first
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long todayStart = cal.getTimeInMillis();

        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        cal.set(Calendar.MILLISECOND, 999);
        long todayEnd = cal.getTimeInMillis();

        SimpleDateFormat keyFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat prettyFormat = new SimpleDateFormat("MMM d", Locale.US);

        String todayKey = keyFormat.format(new Date(todayStart));
        pages.add(new DayPage(todayKey, "Today", todayStart, todayEnd));

        // Compute Yesterday key for comparison
        Calendar yestCal = Calendar.getInstance();
        yestCal.add(Calendar.DAY_OF_YEAR, -1);
        String yestKey = keyFormat.format(yestCal.getTime());

        // Inspect existing items in database for older days
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT " + COL_TIMESTAMP + " FROM " + TABLE_ITEMS + " ORDER BY " + COL_TIMESTAMP + " DESC", null);
        if (cursor != null) {
            try {
                Set<String> seenDays = new HashSet<>();
                seenDays.add(todayKey);

                while (cursor.moveToNext()) {
                    long ts = cursor.getLong(0);
                    Calendar itemCal = Calendar.getInstance();
                    itemCal.setTimeInMillis(ts);
                    itemCal.set(Calendar.HOUR_OF_DAY, 0);
                    itemCal.set(Calendar.MINUTE, 0);
                    itemCal.set(Calendar.SECOND, 0);
                    itemCal.set(Calendar.MILLISECOND, 0);
                    long start = itemCal.getTimeInMillis();

                    itemCal.set(Calendar.HOUR_OF_DAY, 23);
                    itemCal.set(Calendar.MINUTE, 59);
                    itemCal.set(Calendar.SECOND, 59);
                    itemCal.set(Calendar.MILLISECOND, 999);
                    long end = itemCal.getTimeInMillis();

                    String key = keyFormat.format(new Date(start));
                    if (!seenDays.contains(key)) {
                        seenDays.add(key);
                        String label = key.equals(yestKey) ? "Yesterday" : prettyFormat.format(new Date(start));
                        pages.add(new DayPage(key, label, start, end));
                    }
                }
            } finally {
                cursor.close();
            }
        }
        return pages;
    }

    public synchronized List<DockItem> getItemsForPage(DayPage page) {
        if (page == null) return getAllItems();
        List<DockItem> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_ITEMS, null,
                COL_TIMESTAMP + " >= ? AND " + COL_TIMESTAMP + " <= ?",
                new String[]{String.valueOf(page.startMillis), String.valueOf(page.endMillis)},
                null, null, COL_TIMESTAMP + " DESC");
        if (cursor != null) {
            try {
                int idIdx = cursor.getColumnIndexOrThrow(COL_ID);
                int typeIdx = cursor.getColumnIndexOrThrow(COL_TYPE);
                int textIdx = cursor.getColumnIndexOrThrow(COL_TEXT_CONTENT);
                int fileIdx = cursor.getColumnIndexOrThrow(COL_FILE_PATH);
                int thumbIdx = cursor.getColumnIndexOrThrow(COL_THUMBNAIL_PATH);
                int pkgIdx = cursor.getColumnIndexOrThrow(COL_SOURCE_PKG);
                int labelIdx = cursor.getColumnIndexOrThrow(COL_SOURCE_LABEL);
                int mimeIdx = cursor.getColumnIndexOrThrow(COL_MIME_TYPE);
                int timeIdx = cursor.getColumnIndexOrThrow(COL_TIMESTAMP);

                while (cursor.moveToNext()) {
                    DockItem item = new DockItem();
                    item.id = cursor.getLong(idIdx);
                    item.type = cursor.getInt(typeIdx);
                    item.textContent = cursor.getString(textIdx);
                    item.filePath = cursor.getString(fileIdx);
                    item.thumbnailPath = cursor.getString(thumbIdx);
                    item.sourcePackage = cursor.getString(pkgIdx);
                    item.sourceLabel = cursor.getString(labelIdx);
                    item.mimeType = cursor.getString(mimeIdx);
                    item.timestamp = cursor.getLong(timeIdx);
                    list.add(item);
                }
            } finally {
                cursor.close();
            }
        }
        return list;
    }

    public synchronized void clearPage(DayPage page) {
        if (page == null) {
            clearAll();
            return;
        }
        List<DockItem> items = getItemsForPage(page);
        for (DockItem item : items) {
            if (item.filePath != null) safeDeleteFile(item.filePath);
            if (item.thumbnailPath != null && !item.thumbnailPath.equals(item.filePath)) {
                safeDeleteFile(item.thumbnailPath);
            }
        }
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_ITEMS, COL_TIMESTAMP + " >= ? AND " + COL_TIMESTAMP + " <= ?",
                new String[]{String.valueOf(page.startMillis), String.valueOf(page.endMillis)});
        notifyDataChanged();
    }

    private void safeDeleteFile(String path) {
        try {
            File f = new File(path);
            if (f.exists()) f.delete();
        } catch (Exception ignored) {
        }
    }

    public void addListener(OnDataChangedListener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeListener(OnDataChangedListener listener) {
        mListeners.remove(listener);
    }

    private void notifyDataChanged() {
        mMainHandler.post(() -> {
            for (OnDataChangedListener listener : mListeners) {
                listener.onDataChanged();
            }
        });
    }
}

/*
 * Copyright (C) 2026 LunarisOS
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
package org.lineageos.device.DeviceSettings.dock

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import org.lineageos.device.DeviceSettings.defaultPrefs
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CopyOnWriteArrayList

/**
 * File Dock history (credential-protected storage). Behaviour, schema and the legacy-storage
 * migration are unchanged: nothing is deleted until every row points to its copied file.
 */
class DockDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    fun interface OnDataChangedListener {
        fun onDataChanged()
    }

    private val storage = File(context.filesDir, "dock_storage")
    private val prefs = context.defaultPrefs()
    private val listeners = CopyOnWriteArrayList<OnDataChangedListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE_ITEMS (" +
                "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$COL_TYPE INTEGER, " +
                "$COL_TEXT_CONTENT TEXT, " +
                "$COL_FILE_PATH TEXT, " +
                "$COL_THUMBNAIL_PATH TEXT, " +
                "$COL_SOURCE_PKG TEXT, " +
                "$COL_SOURCE_LABEL TEXT, " +
                "$COL_MIME_TYPE TEXT, " +
                "$COL_TIMESTAMP INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("File Dock requires an explicit migration from $oldVersion to $newVersion")
    }

    @Synchronized
    fun insertItem(item: DockItem): Long {
        importItems { insert -> insert(item) }
        return item.id
    }

    private fun insertRow(item: DockItem): Long {
        val db = writableDatabase

        // Text deduplication: identical text only refreshes timestamp and source app
        val text = item.textContent
        if (item.type == DockItem.TYPE_TEXT && text != null) {
            db.query(
                TABLE_ITEMS, arrayOf(COL_ID),
                "$COL_TYPE=? AND $COL_TEXT_CONTENT=?",
                arrayOf(DockItem.TYPE_TEXT.toString(), text), null, null, null,
            ).use { dup ->
                if (dup.moveToFirst()) {
                    val existingId = dup.getLong(0)
                    val update = ContentValues()
                    update.put(COL_TIMESTAMP, item.timestamp)
                    item.sourcePackage?.let { update.put(COL_SOURCE_PKG, it) }
                    item.sourceLabel?.let { update.put(COL_SOURCE_LABEL, it) }
                    db.update(TABLE_ITEMS, update, "$COL_ID=?", arrayOf(existingId.toString()))
                    item.id = existingId
                    return existingId
                }
            }
        }

        val cv = ContentValues().apply {
            put(COL_TYPE, item.type)
            put(COL_TEXT_CONTENT, item.textContent)
            put(COL_FILE_PATH, item.filePath)
            put(COL_THUMBNAIL_PATH, item.thumbnailPath)
            put(COL_SOURCE_PKG, item.sourcePackage)
            put(COL_SOURCE_LABEL, item.sourceLabel)
            put(COL_MIME_TYPE, item.mimeType)
            put(COL_TIMESTAMP, item.timestamp)
        }
        val id = db.insertOrThrow(TABLE_ITEMS, null, cv)
        item.id = id
        return id
    }

    @Synchronized
    fun importItems(read: ((DockItem) -> Unit) -> Unit): Int {
        val db = writableDatabase
        var count = 0
        var removed: List<String> = emptyList()
        db.beginTransaction()
        try {
            read { item ->
                insertRow(item)
                count++
            }
            removed = pruneToLimit(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        for (path in removed) safeDeleteFile(path)
        notifyDataChanged()
        return count
    }

    fun trimToLimit() {
        importItems { }
    }

    private fun pruneToLimit(db: SQLiteDatabase): List<String> {
        val limit = prefs.getInt(SmartDockService.KEY_HISTORY_LIMIT, 0)
        if (limit != 50 && limit != 100) return emptyList()
        val excess = "$COL_ID NOT IN (SELECT $COL_ID FROM $TABLE_ITEMS ORDER BY $COL_TIMESTAMP DESC, $COL_ID DESC LIMIT ?)"
        val args = arrayOf(limit.toString())
        val files = ArrayList<String>()
        db.query(TABLE_ITEMS, arrayOf(COL_FILE_PATH, COL_THUMBNAIL_PATH), excess, args, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                for (column in 0..1) cursor.getString(column)?.let(files::add)
            }
        }
        db.delete(TABLE_ITEMS, excess, args)
        return files
    }

    @Synchronized
    fun visitItems(visitor: (DockItem) -> Unit) {
        readableDatabase.query(TABLE_ITEMS, null, null, null, null, null, "$COL_TIMESTAMP DESC, $COL_ID DESC").use { cursor ->
            while (cursor.moveToNext()) visitor(cursor.toItem())
        }
    }

    private fun Cursor.toItem(): DockItem = DockItem().also {
        it.id = getLong(getColumnIndexOrThrow(COL_ID))
        it.type = getInt(getColumnIndexOrThrow(COL_TYPE))
        it.textContent = getString(getColumnIndexOrThrow(COL_TEXT_CONTENT))
        it.filePath = getString(getColumnIndexOrThrow(COL_FILE_PATH))
        it.thumbnailPath = getString(getColumnIndexOrThrow(COL_THUMBNAIL_PATH))
        it.sourcePackage = getString(getColumnIndexOrThrow(COL_SOURCE_PKG))
        it.sourceLabel = getString(getColumnIndexOrThrow(COL_SOURCE_LABEL))
        it.mimeType = getString(getColumnIndexOrThrow(COL_MIME_TYPE))
        it.timestamp = getLong(getColumnIndexOrThrow(COL_TIMESTAMP))
    }

    @Synchronized
    fun getAllItems(limit: Int? = null): List<DockItem> {
        val list = ArrayList<DockItem>()
        readableDatabase.query(TABLE_ITEMS, null, null, null, null, null, "$COL_TIMESTAMP DESC, $COL_ID DESC", limit?.toString()).use { cursor ->
            while (cursor.moveToNext()) list.add(cursor.toItem())
        }
        return list
    }

    @Synchronized
    fun getLatestItem(): DockItem? =
        readableDatabase.query(TABLE_ITEMS, null, null, null, null, null, "$COL_TIMESTAMP DESC", "1").use { cursor ->
            if (cursor.moveToFirst()) cursor.toItem() else null
        }

    @Synchronized
    fun getItemCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_ITEMS", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    @Synchronized
    fun deleteItem(id: Long) {
        val db = writableDatabase
        var file: String? = null
        var thumb: String? = null
        db.query(
            TABLE_ITEMS, arrayOf(COL_FILE_PATH, COL_THUMBNAIL_PATH),
            "$COL_ID=?", arrayOf(id.toString()), null, null, null,
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                file = cursor.getString(0)
                thumb = cursor.getString(1)
            }
        }
        db.delete(TABLE_ITEMS, "$COL_ID=?", arrayOf(id.toString()))
        file?.let { safeDeleteFile(it) }
        thumb?.let { if (it != file) safeDeleteFile(it) }
        notifyDataChanged()
    }

    @Synchronized
    fun clearAll() {
        val all = getAllItems()
        writableDatabase.delete(TABLE_ITEMS, null, null)
        for (item in all) {
            item.filePath?.let { safeDeleteFile(it) }
            item.thumbnailPath?.let { if (it != item.filePath) safeDeleteFile(it) }
        }
        notifyDataChanged()
    }

    private fun safeDeleteFile(path: String) {
        try {
            val f = File(path).canonicalFile
            if (f.path.startsWith(storage.canonicalPath + File.separator)) {
                readableDatabase.query(
                    TABLE_ITEMS, arrayOf(COL_ID), "$COL_FILE_PATH=? OR $COL_THUMBNAIL_PATH=?",
                    arrayOf(path, path), null, null, null, "1",
                ).use { if (it.moveToFirst()) return }
                Files.deleteIfExists(f.toPath())
            }
        } catch (_: Exception) {
        }
    }

    fun addListener(listener: OnDataChangedListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: OnDataChangedListener) {
        listeners.remove(listener)
    }

    private fun notifyDataChanged() {
        mainHandler.post { for (listener in listeners) listener.onDataChanged() }
    }

    companion object {
        private const val DATABASE_NAME = "dock_items.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_ITEMS = "dock_items"
        const val COL_ID = "_id"
        const val COL_TYPE = "type"
        const val COL_TEXT_CONTENT = "text_content"
        const val COL_FILE_PATH = "file_path"
        const val COL_THUMBNAIL_PATH = "thumbnail_path"
        const val COL_SOURCE_PKG = "source_pkg"
        const val COL_SOURCE_LABEL = "source_label"
        const val COL_MIME_TYPE = "mime_type"
        const val COL_TIMESTAMP = "timestamp"
        @Volatile
        private var instance: DockDatabase? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): DockDatabase {
            instance?.let { return it }
            val credential = context.createCredentialProtectedStorageContext()
            if (!credential.getSystemService(UserManager::class.java).isUserUnlocked) {
                throw IllegalStateException("File Dock is unavailable before unlock")
            }
            migrateStorage(credential)
            return DockDatabase(credential).also { instance = it }
        }

        private fun migrateStorage(credential: Context) {
            val device = credential.createDeviceProtectedStorageContext()
            val oldDatabase = device.getDatabasePath(DATABASE_NAME)
            val database = credential.getDatabasePath(DATABASE_NAME)
            if (oldDatabase.exists() && database.exists()) {
                throw SQLiteException("Both File Dock databases exist; keep both for recovery")
            }
            if (oldDatabase.exists() && !database.exists() && !credential.moveDatabaseFrom(device, DATABASE_NAME)) {
                throw SQLiteException("Could not move File Dock history to credential storage")
            }
            val oldStorage = File(device.filesDir, "dock_storage")
            if (!oldStorage.exists()) return
            val storage = File(credential.filesDir, "dock_storage")
            try {
                Files.createDirectories(storage.toPath())
                val files = oldStorage.listFiles() ?: throw IOException("Could not read legacy File Dock storage")
                for (file in files) {
                    if (!file.isFile || Files.isSymbolicLink(file.toPath())) {
                        throw IOException("Unexpected entry in legacy File Dock storage")
                    }
                    val target = File(storage, file.name)
                    if (target.exists()) {
                        if (!sameFileContent(file, target)) throw IOException("Conflicting history attachment")
                    } else {
                        val partial = File(storage, file.name + ".migration")
                        Files.copy(file.toPath(), partial.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        if (!sameFileContent(file, partial)) throw IOException("Attachment copy did not verify")
                        FileChannel.open(partial.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
                        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    }
                }
                FileChannel.open(storage.toPath(), StandardOpenOption.READ).use { it.force(true) }
                if (database.exists()) {
                    SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                        db.beginTransaction()
                        try {
                            db.query(
                                TABLE_ITEMS, arrayOf(COL_ID, COL_FILE_PATH, COL_THUMBNAIL_PATH),
                                null, null, null, null, null,
                            ).use { rows ->
                                val prefix = oldStorage.canonicalPath + File.separator
                                while (rows.moveToNext()) {
                                    val values = ContentValues()
                                    for (column in 1..2) {
                                        val path = rows.getString(column)
                                        if (path != null && File(path).canonicalPath.startsWith(prefix)) {
                                            values.put(
                                                if (column == 1) COL_FILE_PATH else COL_THUMBNAIL_PATH,
                                                File(storage, File(path).name).absolutePath,
                                            )
                                        }
                                    }
                                    if (values.size() != 0) {
                                        db.update(TABLE_ITEMS, values, "$COL_ID=?", arrayOf(rows.getLong(0).toString()))
                                    }
                                }
                                db.setTransactionSuccessful()
                            }
                        } finally {
                            db.endTransaction()
                        }
                    }
                }
                // Attachments stay in the old location until every row points to its copied file.
                for (file in files) Files.delete(file.toPath())
                Files.delete(oldStorage.toPath())
            } catch (e: IOException) {
                throw SQLiteException("Could not migrate File Dock attachments", e)
            }
        }

        @JvmStatic
        fun sameFileContent(first: File, second: File): Boolean {
            if (first.length() != second.length()) return false
            Files.newInputStream(first.toPath()).use { a ->
                Files.newInputStream(second.toPath()).use { b ->
                    val left = ByteArray(8192)
                    val right = ByteArray(8192)
                    while (true) {
                        val count = a.read(left)
                        if (count == -1) break
                        var offset = 0
                        while (offset < count) {
                            val read = b.read(right, offset, count - offset)
                            if (read < 0) return false
                            offset += read
                        }
                        for (i in 0 until count) if (left[i] != right[i]) return false
                    }
                    return b.read() == -1
                }
            }
        }
    }
}

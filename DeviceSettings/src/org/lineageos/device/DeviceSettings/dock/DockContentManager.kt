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

import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.OpenableColumns
import android.util.Log
import android.view.DragAndDropPermissions
import android.view.DragEvent
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import org.lineageos.device.DeviceSettings.R

/** Imports dropped / copied content into File Dock storage, with strict size and permission checks. */
class DockContentManager(context: Context) : AutoCloseable {

    private val context: Context = context.createCredentialProtectedStorageContext()
    private val database = DockDatabase.getInstance(this.context)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var closed = false

    class SourceAppInfo(var packageName: String?, var label: String?, var icon: Drawable?)

    fun ingestDrop(event: DragEvent): Boolean {
        val incoming = event.clipData
        if (event.clipDescription?.extras?.getBoolean(DockInputService.OWN_CLIP, false) == true) return false
        val data = incoming?.let { ClipData(it) }
        if (closed || data == null || data.itemCount == 0 || data.itemCount > 10) {
            return false
        }
        val permission = DragAndDropPermissions.obtain(event)
        for (i in 0 until data.itemCount) {
            val uri = data.getItemAt(i).uri
            // Normal overlay DROP events omit dragFlags; the supplied grant is authoritative.
            if (uri != null && "content".equals(uri.scheme, ignoreCase = true) && permission == null) {
                return rejectDrop(permission)
            }
        }
        try {
            if (permission != null && !permission.takeTransient()) {
                return rejectDrop(permission)
            }
            for (i in 0 until data.itemCount) {
                val uri = data.getItemAt(i).uri ?: continue
                if ("content".equals(uri.scheme, ignoreCase = true) &&
                    context.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED
                ) return rejectDrop(permission)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not acquire dropped URI read access", e)
            return rejectDrop(permission)
        }
        try {
            executor.execute {
                var saved = 0
                try {
                    val source = resolveSourceApp(data)
                    var i = 0
                    while (i < data.itemCount && !closed) {
                        val item = data.getItemAt(i)
                        val accepted = if (item.uri != null) {
                            processUriItem(item.uri, source)
                        } else {
                            item.text != null && processTextItem(item.text.toString(), source)
                        }
                        if (accepted) saved++
                        i++
                    }
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Could not store dropped content", e)
                } finally {
                    releasePermission(permission)
                }
                val count = saved
                mainHandler.post {
                    if (!closed) {
                        Toast.makeText(
                            context, context.getString(R.string.dock_import_result, count, data.itemCount),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            return true
        } catch (e: RejectedExecutionException) {
            releasePermission(permission)
            return false
        }
    }

    private fun rejectDrop(permission: DragAndDropPermissions?): Boolean {
        releasePermission(permission)
        Log.w(TAG, "Drop rejected: sender did not grant URI read access")
        Toast.makeText(context, R.string.dock_drop_permission_required, Toast.LENGTH_LONG).show()
        return false
    }

    fun ingestClipboard(clip: ClipData?) {
        if (closed || clip == null || clip.itemCount == 0 || clip.itemCount > 10) return
        val copy = ClipData(clip)
        try {
            executor.execute {
                val source = resolveSourceApp(copy)
                for (i in 0 until copy.itemCount) {
                    if (closed) break
                    try {
                        val item = copy.getItemAt(i)
                        if (item.uri != null) processUriItem(item.uri, source)
                        else if (item.text != null) processTextItem(item.text.toString(), source)
                    } catch (e: RuntimeException) {
                        Log.w(TAG, "Could not save clipboard item", e)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun processTextItem(text: String?, source: SourceAppInfo): Boolean {
        if (closed || text.isNullOrEmpty() || text.length > MAX_TEXT) return false
        return database.insertItem(
            DockItem(DockItem.TYPE_TEXT, text, null, null, source.packageName, source.label, ClipDescription.MIMETYPE_TEXT_PLAIN)
        ) > 0
    }

    private fun processUriItem(uri: Uri, source: SourceAppInfo): Boolean {
        val scheme = uri.scheme
        if ("http".equals(scheme, true) || "https".equals(scheme, true) || "ftp".equals(scheme, true)) {
            return processTextItem(uri.toString(), source)
        }
        // Never use the system UID to import an arbitrary file:// path.
        if (!"content".equals(scheme, ignoreCase = true)) return false
        var destination: File? = null
        var thumbnail: File? = null
        var saved = false
        try {
            val resolver = context.contentResolver
            var mime = resolver.getType(uri)
            val displayName = queryDisplayName(resolver, uri)
            if (mime == null) mime = "application/octet-stream"
            var extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            if (extension == null && displayName.contains(".")) {
                val candidate = displayName.substring(displayName.lastIndexOf('.') + 1).lowercase(Locale.ROOT)
                if (candidate.matches(Regex("[a-z0-9]{1,12}"))) extension = candidate
            }
            val storage = File(context.filesDir, "dock_storage")
            Files.createDirectories(storage.toPath())
            val dest = File(storage, UUID.randomUUID().toString() + (if (extension == null) "" else ".$extension"))
            destination = dest
            resolver.openInputStream(uri).use { input ->
                FileOutputStream(dest).use { out ->
                    if (input == null) return false
                    copyBounded(input, out, MAX_FILE_BYTES) { closed }
                    out.fd.sync()
                }
            }
            if (closed) return false
            if ((mime.startsWith("text/") || mime == "application/json") && dest.length() <= MAX_TEXT) {
                val text = String(Files.readAllBytes(dest.toPath()), StandardCharsets.UTF_8)
                if (processTextItem(text, source)) return true
            }
            if (mime.startsWith("image/")) {
                try {
                    thumbnail = generateThumbnail(dest, storage)
                } catch (e: IOException) {
                    Log.w(TAG, "Preview unavailable; retaining original attachment", e)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Preview unavailable; retaining original attachment", e)
                }
            }
            val type = if (thumbnail == null) DockItem.TYPE_FILE else DockItem.TYPE_IMAGE
            saved = database.insertItem(
                DockItem(type, displayName, dest.absolutePath, thumbnail?.absolutePath, source.packageName, source.label, mime)
            ) > 0
            return saved
        } catch (e: IOException) {
            Log.w(TAG, "Could not import attachment", e)
            return false
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not import attachment", e)
            return false
        } finally {
            if (!saved) {
                destination?.delete()
                thumbnail?.delete()
            }
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String {
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null).use { rows ->
                if (rows != null && rows.moveToFirst()) {
                    val name = rows.getString(0)
                    if (!name.isNullOrEmpty()) return name
                }
            }
        } catch (_: RuntimeException) {
        }
        return context.getString(R.string.dock_attachment)
    }

    @Throws(IOException::class)
    private fun generateThumbnail(source: File, storage: File): File? {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(source.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        var scale = 1
        while (options.outWidth / scale > 480 || options.outHeight / scale > 480) scale *= 2
        options.inSampleSize = scale
        options.inJustDecodeBounds = false
        val bitmap = BitmapFactory.decodeFile(source.path, options) ?: return null
        val thumbnail = File(storage, "thumb_" + UUID.randomUUID() + ".jpg")
        var saved = false
        try {
            FileOutputStream(thumbnail).use { out ->
                saved = bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                if (!saved) throw IOException("Thumbnail could not be encoded")
                return thumbnail
            }
        } finally {
            bitmap.recycle()
            if (!saved) thumbnail.delete()
        }
    }

    fun resolveSourceApp(clipData: ClipData?): SourceAppInfo {
        var pkg: String? = null
        try {
            val tasks: List<ActivityManager.RunningTaskInfo>? = ActivityTaskManager.getInstance().getTasks(1)
            if (!tasks.isNullOrEmpty()) {
                val task = tasks[0]
                pkg = task.topActivity?.packageName ?: task.baseActivity?.packageName
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get top task via ActivityTaskManager", e)
        }

        if (pkg == null && clipData != null && clipData.itemCount > 0) {
            val auth = clipData.getItemAt(0).uri?.authority
            if (auth != null && auth.indexOf('.') > 0) pkg = auth
        }
        if (pkg == null) pkg = "unknown"

        val pm = context.packageManager
        var label: String = pkg
        var icon: Drawable? = null
        try {
            val ai = pm.getApplicationInfo(pkg, 0)
            label = pm.getApplicationLabel(ai).toString()
            icon = pm.getApplicationIcon(ai)
        } catch (_: Exception) {
        }
        return SourceAppInfo(pkg, label, icon)
    }

    fun getAppIcon(packageName: String): Drawable? = try {
        val pm: PackageManager = context.packageManager
        pm.getApplicationIcon(pm.getApplicationInfo(packageName, 0))
    } catch (e: Exception) {
        null
    }

    override fun close() {
        closed = true
        executor.shutdown()
        mainHandler.removeCallbacksAndMessages(null)
    }

    internal companion object {
        private const val TAG = "DockContentManager"
        const val MAX_TEXT = 50000
        const val MAX_FILE_BYTES = 32L * 1024 * 1024

        private fun releasePermission(permission: DragAndDropPermissions?) {
            if (permission == null) return
            try {
                permission.release()
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not release drag permission", e)
            }
        }

        @Throws(IOException::class)
        fun copyBounded(input: InputStream, out: OutputStream, limit: Long, cancelled: () -> Boolean) {
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val size = input.read(buffer)
                if (size == -1) break
                if (cancelled()) throw IOException("Import cancelled")
                total += size
                if (total > limit) throw IOException("Attachment exceeds File Dock limit")
                out.write(buffer, 0, size)
            }
        }
    }
}

/* Copyright (C) 2026 LunarisOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.device.DeviceSettings.dock

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Versioned ZIP of per-item metadata and attachments; import adds to existing history. */
object DockHistoryArchive {
    fun export(context: Context, output: OutputStream): Int {
        val storage = File(context.createCredentialProtectedStorageContext().filesDir, "dock_storage")
        var count = 0
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("file-dock-version"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
            DockDatabase.getInstance(context).visitItems { item ->
                val prefix = "items/${count++}/"
                val metadata = JSONObject().apply {
                    put("version", 1)
                    put("type", item.type)
                    put("text", item.textContent)
                    put("sourcePackage", item.sourcePackage)
                    put("sourceLabel", item.sourceLabel)
                    put("mimeType", item.mimeType)
                    put("timestamp", item.timestamp)
                    put("file", item.filePath != null)
                    put("thumbnail", item.thumbnailPath != null)
                }
                zip.putNextEntry(ZipEntry(prefix + "item.json"))
                zip.write(metadata.toString().toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
                for ((name, path) in listOf("file" to item.filePath, "thumbnail" to item.thumbnailPath)) {
                    if (path == null) continue
                    val file = File(path).canonicalFile
                    require(file.path.startsWith(storage.canonicalPath + File.separator) && file.isFile) {
                        "Missing history attachment"
                    }
                    zip.putNextEntry(ZipEntry(prefix + name))
                    file.inputStream().use { DockContentManager.copyBounded(it, zip, DockContentManager.MAX_FILE_BYTES) { false } }
                    zip.closeEntry()
                }
            }
        }
        return count
    }

    fun import(context: Context, input: InputStream): Int {
        val storage = File(context.createCredentialProtectedStorageContext().filesDir, "dock_storage")
        Files.createDirectories(storage.toPath())
        val created = ArrayList<File>()
        var count = 0
        var committed = false
        try {
            DockDatabase.getInstance(context).importItems { insert ->
                ZipInputStream(input).use { zip ->
                    val version = zip.nextEntry ?: throw IOException("Missing history version")
                    require(version.name == "file-dock-version" && !version.isDirectory &&
                        zip.read() == 1 && zip.read() == -1) { "Unsupported history archive" }
                    zip.closeEntry()
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val prefix = "items/$count/"
                        require(entry.name == prefix + "item.json" && !entry.isDirectory) { "Invalid history archive" }
                        val metadata = ByteArrayOutputStream()
                        DockContentManager.copyBounded(zip, metadata, 512_000) { false }
                        zip.closeEntry()
                        val json = JSONObject(metadata.toString(StandardCharsets.UTF_8.name()))
                        require(json.getInt("version") == 1) { "Unsupported history archive version" }
                        val item = DockItem().apply {
                            type = json.getInt("type")
                            textContent = nullableString(json, "text")
                            sourcePackage = nullableString(json, "sourcePackage")
                            sourceLabel = nullableString(json, "sourceLabel")
                            mimeType = nullableString(json, "mimeType")
                            timestamp = json.getLong("timestamp")
                        }
                        require(item.type in DockItem.TYPE_TEXT..DockItem.TYPE_FILE && item.timestamp >= 0)
                        require((item.textContent?.length ?: 0) <= DockContentManager.MAX_TEXT)
                        require(item.type != DockItem.TYPE_TEXT || !item.textContent.isNullOrEmpty())
                        val hasFile = json.getBoolean("file")
                        val hasThumbnail = json.getBoolean("thumbnail")
                        require(hasFile == (item.type != DockItem.TYPE_TEXT))
                        require(!hasThumbnail || item.type == DockItem.TYPE_IMAGE)
                        if (hasFile) item.filePath = readAttachment(zip, prefix + "file", storage, created)
                        if (hasThumbnail) item.thumbnailPath = readAttachment(zip, prefix + "thumbnail", storage, created)
                        insert(item)
                        count++
                    }
                }
            }
            committed = true
            return count
        } finally {
            // A failed import owns only these new files; existing clips are never removed.
            if (!committed) for (file in created) file.delete()
        }
    }

    private fun nullableString(json: JSONObject, key: String): String? =
        if (json.isNull(key)) null else json.getString(key)

    private fun readAttachment(zip: ZipInputStream, name: String, storage: File, created: MutableList<File>): String {
        val entry = zip.nextEntry ?: throw IOException("Missing history attachment")
        require(entry.name == name && !entry.isDirectory) { "Invalid history attachment" }
        val file = File(storage, UUID.randomUUID().toString())
        created.add(file)
        file.outputStream().use { output ->
            DockContentManager.copyBounded(zip, output, DockContentManager.MAX_FILE_BYTES) { false }
            output.fd.sync()
        }
        zip.closeEntry()
        return file.absolutePath
    }
}

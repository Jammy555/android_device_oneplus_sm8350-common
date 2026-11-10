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
package org.lineageos.device.DeviceSettings.ui.dock

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.device.DeviceSettings.R
import org.lineageos.device.DeviceSettings.dock.DockClipboardTimeout
import org.lineageos.device.DeviceSettings.dock.DockDatabase
import org.lineageos.device.DeviceSettings.dock.DockFileProvider
import org.lineageos.device.DeviceSettings.dock.DockInputService
import org.lineageos.device.DeviceSettings.dock.DockItem
import org.lineageos.device.DeviceSettings.dock.SmartDockService

enum class HistoryKind { Text, Link, Image, File }

data class HistoryEntry(
    val id: Long,
    val kind: HistoryKind,
    /** The text itself, or the file name for attachments. */
    val title: String,
    val source: String,
    val time: String,
    val thumbnailPath: String?,
)

internal fun DockItem.toHistoryEntry(ctx: android.content.Context): HistoryEntry {
    val kind = when {
        type == DockItem.TYPE_IMAGE -> HistoryKind.Image
        type == DockItem.TYPE_FILE -> HistoryKind.File
        SmartDockService.isWebUrl(textContent) -> HistoryKind.Link
        else -> HistoryKind.Text
    }
    return HistoryEntry(
        id = id,
        kind = kind,
        title = textContent ?: ctx.getString(R.string.dock_attachment),
        source = sourceLabel ?: ctx.getString(R.string.dock_clipboard),
        time = formattedTime.toString(),
        thumbnailPath = if (kind == HistoryKind.Image) thumbnailPath ?: filePath else null,
    )
}

data class DockHistoryState(
    val items: List<HistoryEntry> = emptyList(),
    /** 0 all, 1 text, 2 links, 3 images, 4 files */
    val filter: Int = 0,
    val loading: Boolean = true,
    val error: Boolean = false,
) {
    val visible: List<HistoryEntry>
        get() = when (filter) {
            1 -> items.filter { it.kind == HistoryKind.Text }
            2 -> items.filter { it.kind == HistoryKind.Link }
            3 -> items.filter { it.kind == HistoryKind.Image }
            4 -> items.filter { it.kind == HistoryKind.File }
            else -> items
        }
}

/**
 * Everything the File Dock has saved, with copy / share / open / delete. Rides on the same database
 * and FileProvider as the overlay, so attachments are handed out with temporary read grants only.
 */
class DockHistoryViewModel(app: Application) : AndroidViewModel(app), DockDatabase.OnDataChangedListener {

    private val ctx = app
    private var database: DockDatabase? = null
    private var raw: Map<Long, DockItem> = emptyMap()

    private val _state = MutableStateFlow(DockHistoryState())
    val state: StateFlow<DockHistoryState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        load()
    }

    override fun onCleared() {
        database?.removeListener(this)
        super.onCleared()
    }

    override fun onDataChanged() = load()

    fun load() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val db = DockDatabase.getInstance(ctx)
                    val items = db.getAllItems()
                    val entries = items.map { it.toHistoryEntry(ctx) }
                    Triple(db, items, entries)
                } catch (e: RuntimeException) {
                    null
                }
            }
            if (result == null) {
                _state.update { it.copy(loading = false, error = true) }
                return@launch
            }
            val (db, items, entries) = result
            if (database == null) {
                database = db
                db.addListener(this@DockHistoryViewModel)
            }
            raw = items.associateBy { it.id }
            _state.update { it.copy(items = entries, loading = false, error = false) }
        }
    }

    companion object {
        internal fun decode(path: String?): Bitmap? {
          if (path == null) return null
          return try {
              val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
              BitmapFactory.decodeFile(path, options)
              var sample = 1
              while (options.outWidth / sample > 360 || options.outHeight / sample > 360) sample *= 2
              BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
          } catch (e: RuntimeException) {
              null
          }
        }
    }

    fun setFilter(filter: Int) = _state.update { it.copy(filter = filter) }

    private fun clipFor(item: DockItem): ClipData {
        if (item.type == DockItem.TYPE_TEXT) return ClipData.newPlainText(ctx.getString(R.string.dock_title), item.textContent)
        val path = item.filePath
        if (path == null || !File(path).isFile) throw IllegalArgumentException("Missing attachment")
        val uri: Uri = FileProvider.getUriForFile(ctx.createCredentialProtectedStorageContext(), DockFileProvider.AUTHORITY, File(path))
        return ClipData(
            ClipDescription(item.textContent ?: ctx.getString(R.string.dock_attachment), arrayOf(item.mimeType ?: "application/octet-stream")),
            ClipData.Item(uri),
        )
    }

    fun copy(id: Long) {
        val item = raw[id] ?: return
        try {
            val clip = clipFor(item)
            DockInputService.markOwnClip(clip)
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            DockClipboardTimeout.schedule(ctx)
            _messages.trySend(ctx.getString(R.string.dock_copied_toast))
        } catch (e: RuntimeException) {
            _messages.trySend(ctx.getString(R.string.dock_attachment_unavailable))
        }
    }

    fun share(id: Long) {
        val item = raw[id] ?: return
        try {
            val clip = clipFor(item)
            val send = Intent(Intent.ACTION_SEND).setType(item.mimeType ?: "text/plain")
            send.clipData = clip
            if (item.type == DockItem.TYPE_TEXT) {
                send.putExtra(Intent.EXTRA_TEXT, item.textContent)
            } else {
                send.putExtra(Intent.EXTRA_STREAM, clip.getItemAt(0).uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.dock_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            _messages.trySend(ctx.getString(R.string.dock_attachment_unavailable))
        }
    }

    fun open(id: Long) {
        val item = raw[id] ?: return
        try {
            if (item.type == DockItem.TYPE_TEXT) {
                var value = item.textContent?.trim() ?: return
                if (!value.contains("://")) value = "https://$value"
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                val path = item.filePath ?: return
                val uri = FileProvider.getUriForFile(ctx.createCredentialProtectedStorageContext(), DockFileProvider.AUTHORITY, File(path))
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mimeType ?: "*/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (e: RuntimeException) {
            _messages.trySend(ctx.getString(R.string.dock_link_unavailable))
        }
    }

    fun delete(id: Long) {
        // remove at once so the row animates away, the database confirms right after
        _state.update { st -> st.copy(items = st.items.filter { it.id != id }) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                database?.deleteItem(id)
            } catch (e: RuntimeException) {
                _messages.trySend(ctx.getString(R.string.dock_storage_error))
            }
        }
        _messages.trySend(ctx.getString(R.string.dock_deleted))
    }

    fun clearAll() {
        _state.update { it.copy(items = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                database?.clearAll()
            } catch (e: RuntimeException) {
                _messages.trySend(ctx.getString(R.string.dock_storage_error))
            }
        }
    }
}

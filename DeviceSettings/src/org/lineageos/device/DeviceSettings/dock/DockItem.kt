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

import android.text.format.DateUtils

/** One staged clip: text, image or file. */
class DockItem() {

    var id: Long = 0
    var type: Int = TYPE_TEXT
    var textContent: String? = null
    var filePath: String? = null
    var thumbnailPath: String? = null
    var sourcePackage: String? = null
    var sourceLabel: String? = null
    var mimeType: String? = null
    var timestamp: Long = System.currentTimeMillis()

    constructor(
        type: Int,
        textContent: String?,
        filePath: String?,
        thumbnailPath: String?,
        sourcePackage: String?,
        sourceLabel: String?,
        mimeType: String?,
    ) : this() {
        this.type = type
        this.textContent = textContent
        this.filePath = filePath
        this.thumbnailPath = thumbnailPath
        this.sourcePackage = sourcePackage
        this.sourceLabel = sourceLabel
        this.mimeType = mimeType
        this.timestamp = System.currentTimeMillis()
    }

    val formattedTime: CharSequence
        get() = DateUtils.getRelativeTimeSpanString(timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)

    companion object {
        const val TYPE_TEXT = 0
        const val TYPE_IMAGE = 1
        const val TYPE_FILE = 2
    }
}

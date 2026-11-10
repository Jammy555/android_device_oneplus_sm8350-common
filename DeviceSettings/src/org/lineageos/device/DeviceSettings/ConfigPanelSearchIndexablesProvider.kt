/*
 * Copyright (C) 2018 The LineageOS Project
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

package org.lineageos.device.DeviceSettings

import android.database.Cursor
import android.database.MatrixCursor
import android.provider.SearchIndexableResource
import android.provider.SearchIndexablesContract.COLUMN_INDEX_XML_RES_CLASS_NAME
import android.provider.SearchIndexablesContract.COLUMN_INDEX_XML_RES_ICON_RESID
import android.provider.SearchIndexablesContract.COLUMN_INDEX_XML_RES_INTENT_ACTION
import android.provider.SearchIndexablesContract.COLUMN_INDEX_XML_RES_INTENT_TARGET_CLASS
import android.provider.SearchIndexablesContract.COLUMN_INDEX_XML_RES_INTENT_TARGET_PACKAGE
import android.provider.SearchIndexablesContract.COLUMN_INDEX_XML_RES_RANK
import android.provider.SearchIndexablesContract.COLUMN_INDEX_XML_RES_RESID
import android.provider.SearchIndexablesContract.INDEXABLES_RAW_COLUMNS
import android.provider.SearchIndexablesContract.INDEXABLES_XML_RES_COLUMNS
import android.provider.SearchIndexablesContract.NON_INDEXABLES_KEYS_COLUMNS
import android.provider.SearchIndexablesProvider

/**
 * Feeds system Settings search. `res/xml/main.xml` is no longer rendered by the app, it only
 * remains as the search-index description; keys there match the item keys in the Compose UI so a
 * search hit scrolls to and highlights the right row.
 */
class ConfigPanelSearchIndexablesProvider : SearchIndexablesProvider() {

    override fun onCreate(): Boolean = true

    override fun queryXmlResources(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(INDEXABLES_XML_RES_COLUMNS)
        INDEXABLE_RES.forEach { cursor.addRow(generateResourceRef(it)) }
        return cursor
    }

    private fun generateResourceRef(sir: SearchIndexableResource): Array<Any?> {
        val ref = arrayOfNulls<Any>(7)
        ref[COLUMN_INDEX_XML_RES_RANK] = sir.rank
        ref[COLUMN_INDEX_XML_RES_RESID] = sir.xmlResId
        ref[COLUMN_INDEX_XML_RES_CLASS_NAME] = null
        ref[COLUMN_INDEX_XML_RES_ICON_RESID] = sir.iconResId
        ref[COLUMN_INDEX_XML_RES_INTENT_ACTION] = "com.android.settings.action.EXTRA_SETTINGS"
        ref[COLUMN_INDEX_XML_RES_INTENT_TARGET_PACKAGE] = context!!.packageName
        ref[COLUMN_INDEX_XML_RES_INTENT_TARGET_CLASS] = sir.className
        return ref
    }

    override fun queryRawData(projection: Array<out String>?): Cursor = MatrixCursor(INDEXABLES_RAW_COLUMNS)

    override fun queryNonIndexableKeys(projection: Array<out String>?): Cursor =
        MatrixCursor(NON_INDEXABLES_KEYS_COLUMNS)

    private companion object {
        val INDEXABLE_RES = arrayOf(
            SearchIndexableResource(
                1, R.xml.main, DeviceSettingsActivity::class.java.name, R.drawable.ic_network_band_lock
            )
        )
    }
}

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

package org.lineageos.device.DeviceSettings.powertools

import android.os.SystemClock
import android.os.SystemProperties
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

object SysfsUtils {

    private const val TAG = "SysfsUtils"
    private const val SETTLE_TIMEOUT_MS = 2000L
    private const val POLL_INTERVAL_MS = 20L

    fun isReadable(path: String): Boolean = File(path).canRead()

    fun isWritable(path: String): Boolean = File(path).canWrite()

    fun readLine(path: String): String? = try {
        Files.readAllLines(Paths.get(path), Charsets.UTF_8).firstOrNull()?.trim()
    } catch (e: IOException) {
        null
    }

    fun readInt(path: String, defaultValue: Int): Int {
        val v = readLine(path)
        if (v.isNullOrEmpty()) return defaultValue
        return v.toIntOrNull() ?: defaultValue
    }

    fun readLong(path: String): Long? = readLine(path)?.toLongOrNull()

    fun writeValue(path: String, value: String?): Boolean {
        if (value == null) return false
        return try {
            Files.write(Paths.get(path), value.toByteArray(Charsets.UTF_8), StandardOpenOption.WRITE)
            true
        } catch (e: IOException) {
            Log.w(TAG, "writeValue failed: $path", e)
            false
        }
    }

    /**
     * Sets a persist.* property, then waits until the kernel node at [path] reports [value].
     * If the node never settles the previous value is restored.
     */
    fun setPropertyAndWait(property: String, value: String, path: String): Boolean {
        val read: () -> String? = {
            if (path.endsWith("/scheduler")) KernelOptionUtils.readBracketedValue(path) else readLine(path)
        }
        val previous = read()
        try {
            SystemProperties.set(property, value)
            if (awaitValue(read, value)) return true
            if (previous != null) {
                SystemProperties.set(property, previous)
                if (!awaitValue(read, previous)) Log.e(TAG, "Could not restore $property")
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not apply $property", e)
        }
        return false
    }

    fun awaitValue(read: () -> String?, expected: String): Boolean {
        val deadline = SystemClock.uptimeMillis() + SETTLE_TIMEOUT_MS
        do {
            if (expected == read()) return true
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }
}

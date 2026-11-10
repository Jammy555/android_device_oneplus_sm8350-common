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

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PersistableBundle
import android.os.UserHandle
import android.os.UserManager
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo

/** Receives editor sessions without reading the view tree or typed text. */
class DockInputService : AccessibilityService() {

    fun interface Listener {
        fun onEditorChanged(session: Long, eligible: Boolean)
    }

    private var eligible = false
    private var privateEditor = false

    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Intent.ACTION_USER_PRESENT == intent.action) {
                val input = inputMethod
                setEditor(if (input != null && input.currentInputStarted) input.currentInputEditorInfo else null)
            } else {
                setEditor(null)
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_USER_PRESENT)
        filter.addAction(Intent.ACTION_USER_SWITCHED)
        // These actions are protected; USER_PRESENT comes from SystemUI's separate UID.
        registerReceiver(lockReceiver, filter, Context.RECEIVER_EXPORTED)
        SmartDockService.restoreState(this)
        publish()
    }

    override fun onCreateInputMethod(): InputMethod = object : InputMethod(this) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) = setEditor(attribute)
        override fun onFinishInput() = setEditor(null)
    }

    private fun unlocked(): Boolean =
        ActivityManager.getCurrentUser() == UserHandle.myUserId() &&
            getSystemService(UserManager::class.java).isUserUnlocked &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun setEditor(info: EditorInfo?) {
        ++session
        privateEditor = info != null && !eligible(info)
        eligible = eligible(info) && unlocked()
        publish()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() = setEditor(null)

    override fun onDestroy() {
        if (instance === this) {
            unregisterReceiver(lockReceiver)
            instance = null
            ++session
            publish()
        }
        super.onDestroy()
    }

    companion object {
        const val OWN_CLIP = "org.lineageos.device.DeviceSettings.dock_clip"
        private var instance: DockInputService? = null
        private var listener: Listener? = null
        private var session = 0L

        fun eligible(info: EditorInfo?): Boolean {
            if (info == null || info.inputType == InputType.TYPE_NULL ||
                (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
            ) return false
            val type = info.inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
            return type != (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) &&
                type != (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) &&
                type != (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) &&
                type != (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        }

        private fun publish() {
            val l = listener ?: return
            val s = instance
            l.onEditorChanged(session, s != null && s.eligible && s.unlocked())
        }

        fun setListener(newListener: Listener?) {
            listener = newListener
            publish()
        }

        fun connected(): Boolean = instance != null

        fun privateEditor(): Boolean = instance?.privateEditor == true

        fun markOwnClip(clip: ClipData) {
            val extras = PersistableBundle()
            extras.putBoolean(OWN_CLIP, true)
            clip.description.extras = extras
        }

        fun paste(session: Long, clip: ClipData): Boolean {
            val service = instance
            if (service == null || session != this.session || !service.eligible || !service.unlocked()) return false
            val input = service.inputMethod ?: return false
            val connection = input.currentInputConnection ?: return false
            markOwnClip(clip)
            service.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            DockClipboardTimeout.schedule(service)
            // The connection carries Android's editor-session token. Never inject a global key.
            connection.performContextMenuAction(android.R.id.paste)
            return true // Queued; the receiving editor does not return a paste acknowledgement.
        }
    }
}

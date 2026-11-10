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

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.BatteryManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.format.DateUtils
import android.util.Log
import android.util.Patterns
import android.view.ContextThemeWrapper
import android.view.DragEvent
import android.view.Gravity
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.VelocityTracker
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.WindowManager
import com.android.internal.util.ScreenshotHelper
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.io.File
import java.net.URI
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.lineageos.device.DeviceSettings.R
import org.lineageos.device.DeviceSettings.ui.dock.HistoryCard
import org.lineageos.device.DeviceSettings.ui.dock.toHistoryEntry
import org.lineageos.device.DeviceSettings.ui.theme.DeviceSettingsTheme
import org.lineageos.device.DeviceSettings.Utils
import org.lineageos.device.DeviceSettings.defaultPrefs

internal fun screenPointInBounds(
    x: Float, y: Float, left: Int, top: Int, width: Int, height: Int,
): Boolean = width > 0 && height > 0 && x >= left && x < left + width && y >= top && y < top + height

internal data class DockRevealMotion(val durationMs: Long, val overshoot: Float)

internal fun dockRevealMotion(inwardVelocityDpPerSecond: Float): DockRevealMotion {
    val velocityFactor = inwardVelocityDpPerSecond.coerceIn(0f, 4000f) / 4000f
    return DockRevealMotion(
        durationMs = (260 - (80 * velocityFactor).roundToInt()).toLong(),
        overshoot = 1.05f + 0.45f * velocityFactor,
    )
}

/**
 * The File Dock overlay: an edge handle (drop target), a shelf of staged items and a small paste
 * popup next to the handle. Same window flags, drag-and-drop permission rules and lock handling as
 * before. Views own window and URI drag handling; shelf cards share the history screen's Compose UI.
 */
class SmartDockService : Service(), DockDatabase.OnDataChangedListener, DockInputService.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var windowManager: WindowManager
    private var pillHandle: View? = null
    private var quickControls: DockQuickControls? = null
    private var quickGesture = false
    private var pillTaps: GestureDetector? = null
    private var pillVelocityTracker: VelocityTracker? = null
    private var pendingShelfVelocityDpPerSecond = 0f
    private var suppressNextPillClick = false
    private val clearPillClickSuppression = Runnable { suppressNextPillClick = false }
    private var takingScreenshot = false
    private val screenshotHelper by lazy { ScreenshotHelper(applicationContext) }
    private val screenshotHandler = Handler(Looper.getMainLooper())
    private var batteryPercent = -1
    private var batteryCharging = false
    private val holdHandle = Runnable {
        val pill = pillHandle
        if (pill != null && !isPillDragging && canShow()) {
            quickGesture = true
            cancelPillTap()
            pill.performLongClick()
        }
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0)
            batteryPercent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL || intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            updateBatteryGrip()
        }
    }
    private var shelfWindow: View? = null
    private var shelfOwner: DockQuickControls.OverlayOwner? = null
    private var expandedItemId by mutableLongStateOf(-1L)
    private var imageViewerWindow: View? = null
    private var dragSource: View? = null
    private var pastePopup: View? = null
    private val dismissPastePopupRunnable = Runnable { dismissPastePopup() }
    private var shelfParams: WindowManager.LayoutParams? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var pillCurrentY = 0
    private var pillNormalWidth = 0
    private var pillNormalHeight = 0
    private var pillExpandedWidth = 0
    private var pillExpandedHeight = 0
    private var isPillDragging = false
    private var handlePressed = false
    private var isShelfExpanded = false
    private var isActiveDragSession = false
    private var isRightEdge = true
    private var autoFade = false
    private var contextual = false
    private var outgoing = false
    private var editorEligible = false
    private var openAfterCapture = false

    @Volatile
    private var destroyed = false
    private var editorSession = 0L
    private var traySession = 0L
    private var dismissedSession = 0L
    private var clipboardStamp = -1L
    private var loadGeneration = 0
    private var currentViewerBitmap: Bitmap? = null
    private lateinit var prefs: SharedPreferences
    private var database: DockDatabase? = null
    private var contentManager: DockContentManager? = null
    private lateinit var clipboard: ClipboardManager
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val fadeHandleTask = Runnable {
        val pill = pillHandle
        if (pill != null && autoFade && !isActiveDragSession && !handlePressed && !quickGesture && !takingScreenshot) {
            val transparency = prefs.getInt(KEY_FADE_TRANSPARENCY, 50).coerceIn(25, 75)
            pill.animate().alpha(1f - transparency / 100f).setDuration(250).start()
        }
    }
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboard() }
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (canShow()) {
                if (database != null) createPillHandle()
            } else {
                removeShelfWindow()
                removePillHandle()
            }
        }
    }

    private fun dockAvailable(): Boolean =
        !destroyed && prefs.getBoolean(KEY_DOCK_ENABLE, false) &&
            ActivityManager.getCurrentUser() == UserHandle.myUserId() &&
            getSystemService(UserManager::class.java).isUserUnlocked &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun canShow(): Boolean = dockAvailable() &&
        !(prefs.getBoolean(KEY_HIDE_LANDSCAPE, false) &&
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)

    override fun onCreate() {
        super.onCreate()
        prefs = defaultPrefs()
        prefs.registerOnSharedPreferenceChangeListener(this)
        windowManager = getSystemService(WindowManager::class.java)
        updateScreenSize()
        isRightEdge = "right" == prefs.getString(KEY_HANDLE_POSITION, "right")
        autoFade = prefs.getBoolean(KEY_AUTO_FADE, false)
        clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.addPrimaryClipChangedListener(clipboardListener)
        val lock = IntentFilter(Intent.ACTION_SCREEN_OFF)
        lock.addAction(Intent.ACTION_USER_PRESENT)
        lock.addAction(Intent.ACTION_USER_SWITCHED)
        // These actions are protected; USER_PRESENT comes from SystemUI's separate UID.
        registerReceiver(lockReceiver, lock, Context.RECEIVER_EXPORTED)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        DockInputService.setListener(this)
        io.execute {
            try {
                val db = DockDatabase.getInstance(this)
                val content = DockContentManager(this)
                handler.post {
                    if (destroyed) {
                        content.close()
                        return@post
                    }
                    database = db
                    contentManager = content
                    db.addListener(this)
                    if (canShow()) createPillHandle()
                    captureClipboard()
                    showRecentPastePopup()
                }
            } catch (e: RuntimeException) {
                Log.e(TAG, "File Dock history could not be opened", e)
                handler.post {
                    if (!destroyed) {
                        Toast.makeText(this, R.string.dock_storage_error, Toast.LENGTH_LONG).show()
                        stopSelf()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!prefs.getBoolean(KEY_DOCK_ENABLE, false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        DockInputService.setListener(null)
        clipboard.removePrimaryClipChangedListener(clipboardListener)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        unregisterReceiver(lockReceiver)
        unregisterReceiver(batteryReceiver)
        database?.removeListener(this)
        contentManager?.close()
        removeShelfWindow()
        removePillHandle()
        io.shutdown()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun updateScreenSize() {
        val bounds = windowManager.currentWindowMetrics.bounds
        screenWidth = bounds.width()
        screenHeight = bounds.height()
    }

    override fun onConfigurationChanged(configuration: Configuration) {
        super.onConfigurationChanged(configuration)
        updateScreenSize()
        removeShelfWindow()
        removePillHandle()
        if (canShow() && database != null) {
            createPillHandle()
            showRecentPastePopup()
        }
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        if (KEY_DOCK_ENABLE == key && !prefs.getBoolean(key, false)) {
            stopSelf()
            return
        }
        autoFade = prefs.getBoolean(KEY_AUTO_FADE, false)
        if (KEY_AUTO_FADE == key || KEY_FADE_TRANSPARENCY == key) resetHandleFadeTimer()
        if (KEY_HIDE_LANDSCAPE == key || KEY_HANDLE_POSITION == key) {
            isRightEdge = "right" == prefs.getString(KEY_HANDLE_POSITION, "right")
            removeShelfWindow()
            removePillHandle()
            if (canShow() && database != null) createPillHandle()
        }
        if (KEY_CAPTURE == key) captureClipboard()
        if (KEY_CLIPBOARD_TIMEOUT == key) DockClipboardTimeout.schedule(this)
        if (KEY_BATTERY_RAIL == key) updateBatteryGrip()
        if (KEY_BLUR == key) {
            quickControls?.dismiss()
            quickControls = null
            val reopen = isShelfExpanded
            removeShelfWindow()
            if (reopen) openShelf(false)
        }
        if (KEY_WIDTH == key || KEY_HEIGHT == key) updatePillDimensions()
        if (KEY_AUTO_SHOW == key) {
            if (prefs.getBoolean(key, true)) showRecentPastePopup() else dismissPastePopup()
        }
    }

    override fun onEditorChanged(session: Long, eligible: Boolean) {
        val changed = session != editorSession
        editorSession = session
        editorEligible = eligible
        if (outgoing) return
        if (changed || !eligible) {
            removeShelfWindow()
            dismissPastePopup()
        }
        if (changed) dismissedSession = -1
        captureClipboard()
        if (eligible) showRecentPastePopup()
    }

    private fun showRecentPastePopup() {
        if (takingScreenshot) return
        val db = database
        if (!editorEligible || db == null || !canShow() || !prefs.getBoolean(KEY_AUTO_SHOW, true)) {
            dismissPastePopup()
            return
        }
        io.execute {
            val latest = db.getLatestItem()
            val now = System.currentTimeMillis()
            if (latest != null && now - latest.timestamp <= 120_000L) {
                handler.post {
                    if (editorEligible && !destroyed && !outgoing) displayPastePopup(latest)
                }
            } else {
                handler.post { dismissPastePopup() }
            }
        }
    }

    private fun displayPastePopup(item: DockItem) {
        if (!editorEligible || pillHandle == null) return
        dismissPastePopup()

        val themedContext = ContextThemeWrapper(this, R.style.Theme_DeviceSettings)
        val popup = LayoutInflater.from(themedContext).inflate(R.layout.dock_paste_popup, null)
        pastePopup = popup

        popup.findViewById<TextView>(R.id.dock_paste_popup_text)?.let { tv ->
            val text = item.textContent?.trim()
            if (!text.isNullOrEmpty()) {
                val preview = if (text.length > 18) text.substring(0, 18) + "…" else text
                tv.text = getString(R.string.dock_paste_with_preview, preview)
            } else {
                tv.setText(R.string.dock_paste_action)
            }
        }

        popup.setOnClickListener {
            pasteItem(item, editorSession)
            dismissPastePopup()
            playHapticTick()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = (if (isRightEdge) Gravity.END else Gravity.START) or Gravity.TOP
        params.x = pillNormalWidth + dp(8)
        params.y = pillCurrentY + max(0, (pillNormalHeight - dp(40)) / 2)

        try {
            windowManager.addView(popup, params)
            popup.alpha = 0f
            popup.translationX = (if (isRightEdge) dp(20) else -dp(20)).toFloat()
            popup.animate().alpha(1f).translationX(0f).setDuration(240)
                .setInterpolator(OvershootInterpolator(1.1f)).start()
            expandPillSlightly()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach paste popup", e)
            pastePopup = null
        }

        handler.removeCallbacks(dismissPastePopupRunnable)
        handler.postDelayed(dismissPastePopupRunnable, 7000L)
    }

    private fun dismissPastePopup() {
        handler.removeCallbacks(dismissPastePopupRunnable)
        val view = pastePopup
        if (view != null) {
            pastePopup = null
            view.animate().alpha(0f).translationX((if (isRightEdge) dp(16) else -dp(16)).toFloat())
                .setDuration(150)
                .withEndAction {
                    try {
                        windowManager.removeView(view)
                    } catch (_: Exception) {
                    }
                }.start()
        }
        restorePillSize()
    }

    private fun expandPillSlightly() {
        val pill = pillHandle ?: return
        pill.animate().cancel()
        pill.alpha = 1.0f
        pill.findViewById<View>(R.id.dock_pill_grip)?.animate()?.scaleY(1.3f)?.setDuration(150)?.start()
    }

    private fun restorePillSize() {
        val pill = pillHandle ?: return
        pill.findViewById<View>(R.id.dock_pill_grip)?.animate()?.scaleY(1.0f)?.setDuration(150)?.start()
        resetHandleFadeTimer()
    }

    private fun computePillDimensions() {
        val density = resources.displayMetrics.density
        val customWidth = prefs.getInt(KEY_WIDTH, 48)
        val customHeight = prefs.getInt(KEY_HEIGHT, 80)
        pillNormalWidth = (customWidth * density).toInt()
        pillNormalHeight = (customHeight * density).toInt()
        pillExpandedWidth = min(screenWidth, dp(116))
        pillExpandedHeight = min(screenHeight,
            dp(120) + (dp(48) * max(0f, resources.configuration.fontScale - 1f)).roundToInt())
    }

    private fun updatePillDimensions() {
        computePillDimensions()
        val pill = pillHandle
        if (pill != null && !isShelfExpanded && !isActiveDragSession) {
            val params = pillParams()
            params.width = pillNormalWidth
            params.height = pillNormalHeight
            windowManager.updateViewLayout(pill, params)
            updateBatteryGrip()
        }
    }

    private fun captureClipboard() {
        DockClipboardTimeout.schedule(this)
        val content = contentManager
        if (content == null || !dockAvailable() || !DockInputService.connected() || !prefs.getBoolean(KEY_CAPTURE, true)) return
        try {
            val description = clipboard.primaryClipDescription
            if (description == null ||
                description.timestamp == clipboardStamp ||
                (description.extras != null && description.extras.getBoolean(DockInputService.OWN_CLIP, false))
            ) return
            clipboardStamp = description.timestamp
            val clip = clipboard.primaryClip ?: return
            openAfterCapture = true
            dismissedSession = -1
            content.ingestClipboard(clip)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Clipboard is unavailable", e)
        }
    }

    override fun onDataChanged() {
        if (destroyed || outgoing) return
        if (openAfterCapture) {
            openAfterCapture = false
            if (editorEligible) showRecentPastePopup()
        }
        populateShelfItems()
    }

    private fun resetHandleFadeTimer() {
        val pill = pillHandle ?: return
        pill.animate().cancel()
        pill.alpha = 1.0f
        handler.removeCallbacks(fadeHandleTask)
        if (autoFade && !isActiveDragSession && !handlePressed && !quickGesture && !takingScreenshot) {
            handler.postDelayed(fadeHandleTask, 3000)
        }
    }

    // =========================================================================
    // Edge pill handle (drag target and inward swipe trigger)
    // =========================================================================

    private fun createPillHandle() {
        if (pillHandle != null) return
        val pill = LayoutInflater.from(ContextThemeWrapper(this, R.style.Theme_DeviceSettings))
            .inflate(R.layout.dock_pill_handle, null)
        pillHandle = pill
        computePillDimensions()

        val savedY = prefs.getInt(KEY_HANDLE_Y, screenHeight / 3)
        pillCurrentY = max(100, min(screenHeight - 300, savedY))

        val params = WindowManager.LayoutParams(
            pillNormalWidth, pillNormalHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = (if (isRightEdge) Gravity.END else Gravity.START) or Gravity.TOP
        params.x = 0
        params.y = pillCurrentY

        setupPillTouchListener(pill)
        setupPillDragListener(pill)
        pill.setOnClickListener {
            val inwardVelocityDpPerSecond = pendingShelfVelocityDpPerSecond
            pendingShelfVelocityDpPerSecond = 0f
            if (suppressNextPillClick) {
                suppressNextPillClick = false
                handler.removeCallbacks(clearPillClickSuppression)
            } else {
                toggleShelfWindow(inwardVelocityDpPerSecond)
            }
        }
        pill.setOnLongClickListener {
            if (!canShow()) return@setOnLongClickListener false
            if (isShelfExpanded) collapseShelfWindow()
            quickControls?.dismiss()
            quickControls = DockQuickControls(this, windowManager)
            try {
                quickControls!!.show(isRightEdge, if (quickGesture) lastTouchY.toInt() else pillCurrentY)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Quick controls unavailable", e)
                quickControls?.dismiss()
                quickControls = null
                Toast.makeText(this, R.string.dock_control_unavailable, Toast.LENGTH_SHORT).show()
                return@setOnLongClickListener false
            }
            handler.removeCallbacks(fadeHandleTask)
            playHapticTick()
            true
        }
        updateBatteryGrip()

        if (takingScreenshot) pill.visibility = View.INVISIBLE
        windowManager.addView(pill, params)
        resetHandleFadeTimer()
    }

    private fun removePillHandle() {
        handler.removeCallbacks(holdHandle)
        handler.removeCallbacks(fadeHandleTask)
        handler.removeCallbacks(clearPillClickSuppression)
        suppressNextPillClick = false
        isActiveDragSession = false
        isPillDragging = false
        handlePressed = false
        cancelPillTap()
        pillTaps = null
        pillVelocityTracker?.recycle()
        pillVelocityTracker = null
        pendingShelfVelocityDpPerSecond = 0f
        quickControls?.dismiss()
        quickControls = null
        quickGesture = false
        dismissPastePopup()
        pillHandle?.let {
            windowManager.removeView(it)
            pillHandle = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPillTouchListener(pill: View) {
        var cancelled = false
        pillTaps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapUp(event: MotionEvent): Boolean {
                if (canShow() && !quickGesture) pill.performClick()
                return true
            }
            override fun onDoubleTap(event: MotionEvent) = true
            override fun onDoubleTapEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_UP && !isPillDragging && !quickGesture) {
                    if (prefs.getBoolean(KEY_DOUBLE_TAP_SCREENSHOT, true)) takeDockScreenshot() else pill.performClick()
                }
                return true
            }
        }).apply { setIsLongpressEnabled(false) }
        pill.setOnTouchListener { _, event ->
            if (cancelled && event.actionMasked != MotionEvent.ACTION_DOWN) {
                return@setOnTouchListener true
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                pillVelocityTracker?.recycle()
                pillVelocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                pendingShelfVelocityDpPerSecond = 0f
            } else {
                pillVelocityTracker?.addMovement(event)
            }
            pillTaps?.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelled = false
                    quickControls?.dismiss()
                    quickControls = null
                    quickGesture = false
                    dismissPastePopup()
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    isPillDragging = false
                    resetHandleFadeTimer()
                    handler.removeCallbacks(fadeHandleTask)
                    animateHandlePress(true)
                    if (prefs.getBoolean(KEY_HOLD_CONTROLS, true)) {
                        handler.postDelayed(holdHandle, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (quickGesture) {
                        quickControls?.drag(event.rawX, event.rawY)
                        return@setOnTouchListener true
                    }
                    val totalDx = event.rawX - lastTouchX
                    val totalDy = event.rawY - lastTouchY
                    val slop = ViewConfiguration.get(this).scaledTouchSlop
                    if (!isPillDragging && (abs(totalDx) > slop || abs(totalDy) > slop)) {
                        isPillDragging = true
                        handler.removeCallbacks(holdHandle)
                    }
                    if (isPillDragging) {
                        val params = pillParams()
                        params.gravity = Gravity.START or Gravity.TOP
                        params.x = max(0f, min((screenWidth - params.width).toFloat(), event.rawX - params.width / 2.0f)).toInt()
                        params.y = max(40f, min((screenHeight - params.height - 40).toFloat(), event.rawY - params.height / 2.0f)).toInt()
                        windowManager.updateViewLayout(pill, params)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    animateHandlePress(false)
                    cancelled = true
                    pillVelocityTracker?.recycle()
                    pillVelocityTracker = null
                    handler.removeCallbacks(holdHandle)
                    quickControls?.dismiss()
                    quickControls = null
                    quickGesture = false
                    snapPillToEdge(isRightEdge, pillCurrentY)
                    resetHandleFadeTimer()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    pillVelocityTracker?.computeCurrentVelocity(1000)
                    val xVelocity = pillVelocityTracker?.xVelocity ?: 0f
                    val inwardVelocityDpPerSecond = max(
                        0f,
                        (if (isRightEdge) -xVelocity else xVelocity) / resources.displayMetrics.density,
                    )
                    pillVelocityTracker?.recycle()
                    pillVelocityTracker = null
                    animateHandlePress(false)
                    handler.removeCallbacks(holdHandle)
                    if (quickGesture) {
                        quickControls?.dismiss()
                        quickControls = null
                        quickGesture = false
                        resetHandleFadeTimer()
                        return@setOnTouchListener true
                    }
                    val totalDx = event.rawX - lastTouchX
                    val totalDy = event.rawY - lastTouchY
                    val crossedCenter = if (isRightEdge) event.rawX < screenWidth / 2.0f else event.rawX > screenWidth / 2.0f
                    val inwardSwipe = if (isRightEdge) totalDx < -24 else totalDx > 24
                    val mostlyHorizontal = Math.abs(totalDx) > Math.abs(totalDy) * 1.4f
                    val shortFlick = Math.abs(totalDx) < (screenWidth * 0.35f)

                    if (!isPillDragging || (inwardSwipe && mostlyHorizontal && !crossedCenter && shortFlick)) {
                        snapPillToEdge(isRightEdge, pillCurrentY)
                        if (isPillDragging) {
                            pendingShelfVelocityDpPerSecond = inwardVelocityDpPerSecond
                            pill.performClick()
                        }
                    } else {
                        val newRightEdge = event.rawX >= screenWidth / 2.0f
                        snapPillToEdge(newRightEdge, pillParams().y)
                        playHapticTick()
                    }
                    resetHandleFadeTimer()
                    true
                }
                else -> false
            }
        }
    }

    private fun cancelPillTap() {
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        pillTaps?.onTouchEvent(cancel)
        cancel.recycle()
    }

    private fun takeDockScreenshot() {
        if (takingScreenshot || !canShow()) return
        val pill = pillHandle ?: return
        takingScreenshot = true
        cancelPillTap()
        handler.removeCallbacks(holdHandle)
        quickControls?.dismiss()
        quickControls = null
        removeShelfWindow()
        dismissPastePopup()
        pill.visibility = View.INVISIBLE
        // Let the hidden overlay reach SurfaceFlinger before SystemUI captures the display.
        handler.postDelayed({
            if (destroyed || !canShow()) {
                restoreScreenshotHandle()
                return@postDelayed
            }
            try {
                screenshotHelper.takeScreenshot(WindowManager.ScreenshotSource.SCREENSHOT_OTHER, screenshotHandler) { uri ->
                    restoreScreenshotHandle()
                    if (uri == null && !destroyed) Toast.makeText(this,
                        R.string.dock_screenshot_failed, Toast.LENGTH_SHORT).show()
                }
            } catch (e: RuntimeException) {
                restoreScreenshotHandle()
                Log.w(TAG, "Screenshot request refused", e)
                Toast.makeText(this, R.string.dock_screenshot_failed, Toast.LENGTH_SHORT).show()
            }
        }, 120)
    }

    private fun restoreScreenshotHandle() {
        takingScreenshot = false
        if (!destroyed && canShow()) {
            pillHandle?.visibility = View.VISIBLE
            resetHandleFadeTimer()
        }
    }

    private fun snapPillToEdge(rightEdge: Boolean, y: Int) {
        val pill = pillHandle ?: return
        isRightEdge = rightEdge
        pillCurrentY = y
        val params = pillParams()
        params.gravity = (if (isRightEdge) Gravity.END else Gravity.START) or Gravity.TOP
        params.x = 0
        params.y = pillCurrentY
        params.width = pillNormalWidth
        params.height = pillNormalHeight
        windowManager.updateViewLayout(pill, params)
        prefs.edit()
            .putString(KEY_HANDLE_POSITION, if (isRightEdge) "right" else "left")
            .putInt(KEY_HANDLE_Y, pillCurrentY)
            .apply()
    }

    private fun updateBatteryGrip() {
        val pill = pillHandle ?: return
        val grip = pill.findViewById<DockBatteryRailView>(R.id.dock_pill_grip)
        grip.layoutParams = grip.layoutParams.apply {
            width = minOf(dp(8), pillNormalWidth / 3)
            height = (pillNormalHeight * 0.72f).toInt()
        }
        // Fill follows the charge with a spring, the colour cross-fades between bands, and while
        // charging a bright wave keeps travelling from the bottom to the top of the fill.
        grip.setRailEnabled(batteryPercent >= 0 && prefs.getBoolean(KEY_BATTERY_RAIL, true))
        grip.setBattery(if (batteryPercent < 0) 100 else batteryPercent, batteryCharging && batteryPercent >= 0)
        pill.contentDescription = if (batteryPercent < 0) getString(R.string.dock_handle_controls)
            else getString(R.string.dock_handle_battery, batteryPercent)
    }

    /** ColorOS-style squish while the handle is pressed, springing back when released. */
    private fun animateHandlePress(down: Boolean) {
        handlePressed = down
        val body = pillHandle?.findViewById<View>(R.id.dock_pill_body) ?: return
        if (!ValueAnimator.areAnimatorsEnabled()) return
        body.pivotX = if (isRightEdge) body.width.toFloat() else 0f
        body.pivotY = body.height / 2f
        body.animate().cancel()
        if (down) {
            body.animate().scaleX(0.82f).scaleY(0.93f).setDuration(110)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            body.animate().scaleX(1f).scaleY(1f).setDuration(460)
                .setInterpolator(OvershootInterpolator(3f)).start()
        }
    }

    private fun setupPillDragListener(pill: View) {
        pill.setOnDragListener { _, event ->
            if (outgoing || event.localState is DockItem ||
                event.clipDescription?.extras?.getBoolean(DockInputService.OWN_CLIP, false) == true
            ) return@setOnDragListener false
            val pillBody = pillHandle?.findViewById<View>(R.id.dock_pill_body)
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    isActiveDragSession = true
                    handler.removeCallbacks(fadeHandleTask)
                    pillHandle?.let {
                        it.animate().cancel()
                        it.alpha = 1.0f
                    }
                    expandPillForDrop()
                    true
                }
                DragEvent.ACTION_DRAG_ENTERED -> {
                    isActiveDragSession = true
                    handler.removeCallbacks(fadeHandleTask)
                    pillHandle?.let {
                        it.animate().cancel()
                        it.alpha = 1.0f
                    }
                    playHapticTick()
                    pill.findViewById<TextView>(R.id.dock_pill_drop_text).setText(R.string.dock_drop_release)
                    pillBody?.animate()?.scaleX(1.05f)?.scaleY(1.05f)?.setDuration(120)?.start()
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    pill.findViewById<TextView>(R.id.dock_pill_drop_text).setText(R.string.dock_drop_save)
                    pillBody?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(120)?.start()
                    true
                }
                DragEvent.ACTION_DROP -> {
                    isActiveDragSession = false
                    collapsePillFromDrop()
                    val ingested = contentManager?.ingestDrop(event) ?: false
                    openAfterCapture = ingested
                    resetHandleFadeTimer()
                    ingested
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    isActiveDragSession = false
                    collapsePillFromDrop()
                    resetHandleFadeTimer()
                    true
                }
                else -> false
            }
        }
    }

    private fun expandPillForDrop() {
        val pill = pillHandle ?: return
        pill.animate().cancel()
        pill.alpha = 1.0f
        handler.removeCallbacks(fadeHandleTask)
        val params = pillParams()
        params.gravity = (if (isRightEdge) Gravity.END else Gravity.START) or Gravity.TOP
        params.x = 0
        params.width = pillExpandedWidth
        params.height = pillExpandedHeight
        val centerYOffset = (pillExpandedHeight - pillNormalHeight) / 2
        val edge = dockInsets()
        params.y = (pillCurrentY - centerYOffset).coerceIn(edge.top + dp(8),
            max(edge.top + dp(8), screenHeight - edge.bottom - params.height - dp(8)))
        windowManager.updateViewLayout(pill, params)

        pill.findViewById<View>(R.id.dock_pill_body)?.setBackgroundResource(R.drawable.dock_drop_zone_bg)
        pill.findViewById<View>(R.id.dock_pill_grip)?.visibility = View.GONE
        pill.findViewById<View>(R.id.dock_pill_drop_container)?.visibility = View.VISIBLE
        pill.findViewById<TextView>(R.id.dock_pill_drop_text).setText(R.string.dock_drop_save)
        if (ValueAnimator.areAnimatorsEnabled()) {
            pill.findViewById<View>(R.id.dock_pill_drop_container)?.let {
                it.animate().cancel()
                it.alpha = 0f
                it.scaleX = 0.86f
                it.scaleY = 0.86f
                it.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
                    .setInterpolator(OvershootInterpolator(1.2f)).start()
            }
        }
    }

    private fun collapsePillFromDrop() {
        val pill = pillHandle ?: return
        pill.animate().cancel()
        pill.alpha = 1.0f
        val params = pillParams()
        params.gravity = (if (isRightEdge) Gravity.END else Gravity.START) or Gravity.TOP
        params.x = 0
        params.width = pillNormalWidth
        params.height = pillNormalHeight
        params.y = pillCurrentY
        windowManager.updateViewLayout(pill, params)

        pill.findViewById<View>(R.id.dock_pill_body)?.let {
            it.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
            it.setBackgroundResource(R.drawable.dock_pill_handle_bg)
        }
        pill.findViewById<View>(R.id.dock_pill_grip)?.visibility = View.VISIBLE
        pill.findViewById<View>(R.id.dock_pill_drop_container)?.visibility = View.GONE
        pill.findViewById<View>(R.id.dock_pill_drop_container)?.animate()?.cancel()
    }

    private fun pillParams(): WindowManager.LayoutParams = pillHandle!!.layoutParams as WindowManager.LayoutParams

    // =========================================================================
    // Floating dock shelf window
    // =========================================================================

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun toggleShelfWindow(inwardVelocityDpPerSecond: Float = 0f) {
        if (isShelfExpanded) collapseShelfWindow() else openShelf(false, inwardVelocityDpPerSecond)
    }

    private fun openShelf(contextual: Boolean, inwardVelocityDpPerSecond: Float = 0f) {
        if (takingScreenshot) return
        if (!canShow() || database == null || shelfWindow != null) return
        dismissPastePopup()
        this.contextual = contextual
        traySession = editorSession
        val theme = ContextThemeWrapper(this, R.style.Theme_DeviceSettings)
        val window = LayoutInflater.from(theme).inflate(R.layout.dock_shelf_window, null)
        val owner = DockQuickControls.OverlayOwner()
        shelfOwner = owner
        window.setViewTreeLifecycleOwner(owner)
        window.setViewTreeSavedStateRegistryOwner(owner)
        shelfWindow = window
        val initialW = dp(prefs.getInt(KEY_SHELF_WIDTH, DEFAULT_SHELF_WIDTH))
        val initialH = dp(prefs.getInt(KEY_SHELF_HEIGHT, DEFAULT_SHELF_HEIGHT))
        val params = WindowManager.LayoutParams(
            initialW, initialH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        )
        shelfParams = params
        params.setFitInsetsTypes(0)
        params.gravity = Gravity.TOP or (if (isRightEdge) Gravity.RIGHT else Gravity.LEFT)
        params.y = pillCurrentY
        restoreShelfPosition(params)
        val motion = dockRevealMotion(inwardVelocityDpPerSecond)
        DockPanelBackground.install(
            window, windowManager, prefs.getBoolean(KEY_BLUR, false), 26,
            revealDurationMs = motion.durationMs,
        )
        setupShelfDragReposition(window.findViewById(R.id.dock_shelf_header))
        setupShelfResize(window.findViewById(R.id.dock_shelf_resize_bar))
        // Clip the viewport itself; tall child cards can otherwise draw over the fixed header.
        window.findViewById<View>(R.id.dock_shelf_scroll).addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.clipBounds = Rect(0, 0, v.width, v.height)
        }
        window.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE && !outgoing && !this.contextual) {
                pillHandle?.let { pill ->
                    val location = IntArray(2)
                    pill.getLocationOnScreen(location)
                    if (screenPointInBounds(
                            event.rawX, event.rawY, location[0], location[1], pill.width, pill.height,
                        )) {
                        suppressNextPillClick = true
                        handler.removeCallbacks(clearPillClickSuppression)
                        handler.postDelayed(
                            clearPillClickSuppression,
                            ViewConfiguration.getDoubleTapTimeout().toLong() + 100L,
                        )
                    }
                }
                collapseShelfWindow()
            }
            false
        }
        window.setOnApplyWindowInsetsListener { _, insets ->
            applyShelfBounds(insets)
            insets
        }
        window.setWindowInsetsAnimationCallback(object :
            WindowInsetsAnimation.Callback(WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onProgress(insets: WindowInsets, runningAnimations: MutableList<WindowInsetsAnimation>): WindowInsets {
                applyShelfBounds(insets)
                return insets
            }
        })
        window.setOnDragListener { view, event ->
            val own = outgoing || event.localState is DockItem ||
                event.clipDescription?.extras?.getBoolean(DockInputService.OWN_CLIP, false) == true
            if (!own) {
                return@setOnDragListener when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> true
                    DragEvent.ACTION_DROP -> contentManager?.ingestDrop(event) ?: false
                    else -> true
                }
            }
            if (event.action == DragEvent.ACTION_DRAG_ENDED && shelfWindow === view && outgoing) finishDrag(event.result)
            event.action != DragEvent.ACTION_DROP
        }
        applyShelfBounds(null)
        windowManager.addView(window, params)
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        isShelfExpanded = true
        if (ValueAnimator.areAnimatorsEnabled()) {
            // Grows out of the handle: scale from the edge side with a soft overshoot.
            window.pivotX = if (isRightEdge) params.width.toFloat() else 0f
            window.pivotY = dp(40).toFloat()
            window.alpha = 0f
            window.scaleX = 0.8f
            window.scaleY = 0.8f
            window.translationX = dp(if (isRightEdge) 28 else -28).toFloat()
            window.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f)
                .setDuration(motion.durationMs).setInterpolator(OvershootInterpolator(motion.overshoot)).start()
        }
        populateShelfItems()
        resetHandleFadeTimer()
    }

    private var isShelfMoving = false
    private var isShelfResizing = false

    @SuppressLint("ClickableViewAccessibility")
    private fun setupShelfDragReposition(header: View?) {
        if (header == null) return
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        header.setOnTouchListener { v, event ->
            val window = shelfWindow ?: return@setOnTouchListener false
            val params = shelfParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    isShelfMoving = false
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!isShelfMoving && abs(dx) < touchSlop && abs(dy) < touchSlop) {
                        return@setOnTouchListener true
                    }
                    isShelfMoving = true
                    val edge = dockInsets()
                    val minShelfX = (if (isRightEdge) edge.right else edge.left) + dp(8)
                    val maxShelfX = max(minShelfX,
                        screenWidth - (if (isRightEdge) edge.left else edge.right) - params.width - dp(8))
                    val targetX = if (isRightEdge) (initialX - dx).toInt() else (initialX + dx).toInt()
                    val clampedX = targetX.coerceIn(minShelfX, maxShelfX)
                    val clampedY = (initialY + dy).toInt().coerceIn(edge.top + dp(8),
                        max(edge.top + dp(8), screenHeight - edge.bottom - params.height - dp(8)))
                    if (params.x != clampedX || params.y != clampedY) {
                        params.x = clampedX
                        params.y = clampedY
                        windowManager.updateViewLayout(window, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val moved = isShelfMoving
                    isShelfMoving = false
                    if (moved && event.action == MotionEvent.ACTION_UP) saveShelfPosition(params)
                    if (!moved && event.action == MotionEvent.ACTION_UP) v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupShelfResize(resizeBar: View?) {
        if (resizeBar == null) return
        var initialWidth = 0
        var initialHeight = 0
        var touchX = 0f
        var touchY = 0f
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        resizeBar.setOnTouchListener { v, event ->
            val window = shelfWindow ?: return@setOnTouchListener false
            val params = shelfParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    isShelfResizing = false
                    initialWidth = params.width
                    initialHeight = params.height
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!isShelfResizing && abs(dx) < touchSlop && abs(dy) < touchSlop) {
                        return@setOnTouchListener true
                    }
                    isShelfResizing = true
                    val newWidth = if (isRightEdge) (initialWidth - dx).toInt() else (initialWidth + dx).toInt()
                    val newHeight = (initialHeight + dy).toInt()

                    val edge = dockInsets()
                    val maxWidth = max(1, screenWidth - params.x -
                        (if (isRightEdge) edge.left else edge.right) - dp(8))
                    val minWidth = min(dp(MIN_SHELF_WIDTH), maxWidth)
                    val maxHeight = max(1, screenHeight - edge.bottom - params.y - dp(8))
                    val minHeight = min(dp(MIN_SHELF_HEIGHT), maxHeight)

                    val clampedW = max(minWidth, min(maxWidth, newWidth))
                    val clampedH = max(minHeight, min(maxHeight, newHeight))

                    if (params.width != clampedW || params.height != clampedH) {
                        params.width = clampedW
                        params.height = clampedH
                        windowManager.updateViewLayout(window, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val resized = isShelfResizing
                    isShelfResizing = false
                    if (resized) {
                        val density = resources.displayMetrics.density
                        prefs.edit()
                            .putInt(KEY_SHELF_WIDTH, Math.round(params.width / density))
                            .putInt(KEY_SHELF_HEIGHT, Math.round(params.height / density))
                            .apply()
                        applyShelfBounds(null)
                        saveShelfPosition(params)
                        playHapticTick()
                    } else {
                        v.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    isShelfResizing = false
                    params.width = initialWidth
                    params.height = initialHeight
                    applyShelfBounds(null)
                    true
                }
                else -> false
            }
        }
    }

    private fun applyShelfBounds(supplied: WindowInsets?) {
        val window = shelfWindow
        val params = shelfParams
        if (window == null || params == null || outgoing || isShelfResizing || isShelfMoving) return
        val insets = supplied ?: windowManager.currentWindowMetrics.windowInsets
        val edge = insets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout()
        )
        // Temporary keyboard/cutout limits must not replace the user's chosen size.
        val targetW = dp(prefs.getInt(KEY_SHELF_WIDTH, DEFAULT_SHELF_WIDTH))
        val targetH = dp(prefs.getInt(KEY_SHELF_HEIGHT, DEFAULT_SHELF_HEIGHT))
        val maxAllowedWidth = max(1, screenWidth - edge.left - edge.right - dp(16))
        val usableHeight = max(1, screenHeight - edge.top - edge.bottom - dp(16))
        val width = targetW.coerceIn(min(dp(MIN_SHELF_WIDTH), maxAllowedWidth), maxAllowedWidth)
        val height = targetH.coerceIn(min(dp(MIN_SHELF_HEIGHT), usableHeight), usableHeight)
        val y = if (contextual) screenHeight - edge.bottom - height - dp(8)
        else max(edge.top + dp(8), min(params.y, screenHeight - edge.bottom - height - dp(8)))
        val minX = (if (isRightEdge) edge.right else edge.left) + dp(8)
        val maxX = max(minX, screenWidth - (if (isRightEdge) edge.left else edge.right) - width - dp(8))
        val x = params.x.coerceIn(minX, maxX)
        val changed = params.width != width || params.height != height || params.y != y || params.x != x
        params.width = width
        params.height = height
        params.x = x
        params.y = y
        if (changed && window.isAttachedToWindow) windowManager.updateViewLayout(window, params)
    }

    private fun dockInsets() = windowManager.currentWindowMetrics.windowInsets.getInsets(
        WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())

    private fun restoreShelfPosition(params: WindowManager.LayoutParams) {
        val edge = dockInsets()
        val savedEdge = prefs.getBoolean(KEY_SHELF_RIGHT, !isRightEdge)
        val x = dp(prefs.getInt(KEY_SHELF_X, -1))
        val y = dp(prefs.getInt(KEY_SHELF_Y, -1))
        val minX = (if (isRightEdge) edge.right else edge.left) + dp(8)
        val maxX = screenWidth - (if (isRightEdge) edge.left else edge.right) - params.width - dp(8)
        val tolerance = max(1, dp(1)) // Position is saved in whole dp; allow its rounding error.
        if (savedEdge == isRightEdge && x >= minX - tolerance && x <= maxX + tolerance &&
            y >= edge.top + dp(8) - tolerance &&
            y + params.height <= screenHeight - edge.bottom - dp(8) + tolerance) {
            params.x = x
            params.y = y
        }
    }

    private fun saveShelfPosition(params: WindowManager.LayoutParams) {
        val density = resources.displayMetrics.density
        prefs.edit().putInt(KEY_SHELF_X, (params.x / density).roundToInt())
            .putInt(KEY_SHELF_Y, (params.y / density).roundToInt())
            .putBoolean(KEY_SHELF_RIGHT, isRightEdge).apply()
    }

    private fun collapseShelfWindow() {
        dismissPastePopup()
        dismissedSession = editorSession
        removeShelfWindow()
        resetHandleFadeTimer()
    }

    private fun removeShelfWindow() {
        ++loadGeneration
        outgoing = false
        dragSource?.let {
            try {
                it.cancelDragAndDrop()
            } catch (e: RuntimeException) {
                Log.w(TAG, "Drag already ended during window removal", e)
            }
            dragSource = null
        }
        dismissImageViewer()
        shelfOwner?.lifecycle?.currentState = Lifecycle.State.DESTROYED
        shelfOwner = null
        expandedItemId = -1L
        shelfWindow?.let {
            it.animate().cancel()
            windowManager.removeView(it)
            shelfWindow = null
            shelfParams = null
        }
        isShelfExpanded = false
        isShelfMoving = false
        isShelfResizing = false
        resetHandleFadeTimer()
    }

    private fun runStorageAction(action: () -> Unit) {
        if (destroyed) return
        io.execute {
            try {
                action()
            } catch (e: RuntimeException) {
                Log.w(TAG, "File Dock storage operation failed", e)
                handler.post {
                    if (!destroyed) Toast.makeText(this, R.string.dock_storage_error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun populateShelfItems() {
        val window = shelfWindow
        val db = database
        if (window == null || db == null || outgoing || destroyed) return
        val generation = ++loadGeneration
        val contextual = this.contextual
        val session = traySession
        runStorageAction {
            val count = db.getItemCount()
            val items = db.getAllItems(if (contextual) 5 else 30)
            handler.post {
                if (destroyed || generation != loadGeneration || shelfWindow !== window || outgoing) return@post
                val container = window.findViewById<LinearLayout>(R.id.dock_items_container)
                val scroll = window.findViewById<ScrollView>(R.id.dock_shelf_scroll)
                val scrollY = scroll.scrollY
                container.removeAllViews()
                window.findViewById<View>(R.id.dock_shelf_empty).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                scroll.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                window.findViewById<TextView>(R.id.dock_shelf_badge).apply {
                    text = count.toString()
                    contentDescription = getString(R.string.dock_history_count, count)
                }
                var lastDay: String? = null
                for (item in items) {
                    val day = DateUtils.formatDateTime(
                        this, item.timestamp, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
                    )
                    if (day != lastDay) {
                        val label = TextView(window.context)
                        label.text = day
                        label.setPadding(dp(8), dp(12), dp(8), dp(8))
                        label.isAccessibilityHeading = true
                        container.addView(label)
                        lastDay = day
                    }
                    val card = ComposeView(window.context)
                    card.id = View.generateViewId()
                    card.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(8) }
                    val entry = item.toHistoryEntry(this)
                    card.setContent {
                        DeviceSettingsTheme(dynamicColor = Utils.useDynamicColors(this)) {
                            HistoryCard(
                                entry = entry,
                                expanded = expandedItemId == item.id,
                                onToggle = { expandedItemId = if (expandedItemId == item.id) -1L else item.id },
                                onCopy = { copyItem(item) },
                                onShare = { shareItem(item) },
                                onOpen = {
                                    when (item.type) {
                                        DockItem.TYPE_IMAGE -> showInbuiltImageViewer(item)
                                        DockItem.TYPE_TEXT -> openWebLink(item.textContent)
                                        else -> shareItem(item)
                                    }
                                },
                                onDelete = { runStorageAction { database?.deleteItem(item.id) } },
                                onLongClick = { startDragOut(card, item) },
                                onPaste = if (editorEligible) ({ pasteItem(item, session) }) else null,
                                compact = true,
                            )
                        }
                    }
                    container.addView(card)
                }
                scroll.post { if (shelfWindow === window) scroll.scrollTo(0, scrollY) }
            }
        }
    }

    private fun clipFor(item: DockItem): ClipData {
        if (item.type == DockItem.TYPE_TEXT) return ClipData.newPlainText(getString(R.string.dock_title), item.textContent)
        val path = item.filePath
        if (path == null || !File(path).isFile) throw IllegalArgumentException("Missing attachment")
        val uri = FileProvider.getUriForFile(createCredentialProtectedStorageContext(), DockFileProvider.AUTHORITY, File(path))
        return ClipData(
            ClipDescription(
                item.textContent ?: getString(R.string.dock_attachment),
                arrayOf(item.mimeType ?: "application/octet-stream"),
            ),
            ClipData.Item(uri),
        )
    }

    private fun pasteItem(item: DockItem, session: Long) {
        try {
            val clip = clipFor(item)
            if (DockInputService.paste(session, clip)) {
                collapseShelfWindow()
                playHapticTick()
            } else {
                DockInputService.markOwnClip(clip)
                clipboard.setPrimaryClip(clip)
                DockClipboardTimeout.schedule(this)
                Toast.makeText(this, R.string.dock_paste_unavailable, Toast.LENGTH_LONG).show()
            }
        } catch (e: RuntimeException) {
            Toast.makeText(this, R.string.dock_attachment_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyItem(item: DockItem) {
        try {
            val clip = clipFor(item)
            DockInputService.markOwnClip(clip)
            clipboard.setPrimaryClip(clip)
            DockClipboardTimeout.schedule(this)
            Toast.makeText(this, R.string.dock_copied_toast, Toast.LENGTH_SHORT).show()
        } catch (e: RuntimeException) {
            Toast.makeText(this, R.string.dock_attachment_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareItem(item: DockItem) {
        try {
            val clip = clipFor(item)
            val share = Intent(Intent.ACTION_SEND).setType(item.mimeType ?: "text/plain")
            share.clipData = clip
            if (item.type == DockItem.TYPE_TEXT) {
                share.putExtra(Intent.EXTRA_TEXT, item.textContent)
            } else {
                share.putExtra(Intent.EXTRA_STREAM, clip.getItemAt(0).uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, getString(R.string.dock_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            collapseShelfWindow()
        } catch (e: RuntimeException) {
            Toast.makeText(this, R.string.dock_attachment_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startDragOut(view: View, item: DockItem) {
        if (outgoing) return
        try {
            val clip = clipFor(item)
            DockInputService.markOwnClip(clip)
            outgoing = true
            var flags = View.DRAG_FLAG_GLOBAL
            if (item.type != DockItem.TYPE_TEXT) flags = flags or View.DRAG_FLAG_GLOBAL_URI_READ
            if (!view.startDragAndDrop(clip, DockDragShadow(view, dp(160), dp(112)), item, flags)) {
                outgoing = false
                return
            }
            dragSource = view
            val window = shelfWindow
            val params = shelfParams
            if (window != null && params != null) {
                window.animate().cancel()
                window.alpha = 0f
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                params.alpha = 0f
                windowManager.updateViewLayout(window, params)
            }
            playHapticTick()
        } catch (e: RuntimeException) {
            removeShelfWindow()
            Log.w(TAG, "Drag could not start", e)
        }
    }

    private fun finishDrag(accepted: Boolean) {
        outgoing = false
        dragSource = null
        val window = shelfWindow
        val params = shelfParams
        if (accepted || !canShow() || traySession != editorSession) {
            collapseShelfWindow()
        } else if (window != null && params != null) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            params.alpha = 1f
            windowManager.updateViewLayout(window, params)
            window.alpha = 1f
            populateShelfItems()
            Toast.makeText(this, R.string.dock_drop_not_accepted, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWebLink(text: String?) {
        if (text == null || !isWebUrl(text)) return
        var value = text.trim()
        if (!value.contains("://")) value = "https://$value"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            collapseShelfWindow()
        } catch (e: RuntimeException) {
            Toast.makeText(this, R.string.dock_link_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showInbuiltImageViewer(item: DockItem) {
        dismissImageViewer()

        val themedContext = ContextThemeWrapper(this, R.style.Theme_DeviceSettings)
        val viewer = LayoutInflater.from(themedContext).inflate(R.layout.dock_image_viewer_popup, null)
        imageViewerWindow = viewer

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.CENTER

        viewer.findViewById<View>(R.id.dock_viewer_btn_close)?.setOnClickListener { dismissImageViewer() }
        viewer.findViewById<View>(R.id.dock_viewer_backdrop)?.setOnClickListener { dismissImageViewer() }

        viewer.findViewById<ImageView>(R.id.dock_viewer_app_icon)?.let { appIcon ->
            val ic = item.sourcePackage?.let { contentManager?.getAppIcon(it) }
            if (ic != null) {
                appIcon.setImageDrawable(ic)
                appIcon.visibility = View.VISIBLE
            } else {
                appIcon.visibility = View.GONE
            }
        }
        viewer.findViewById<TextView>(R.id.dock_viewer_title)?.text = item.sourceLabel ?: getString(R.string.dock_title)
        viewer.findViewById<TextView>(R.id.dock_viewer_subtitle)?.text = item.formattedTime

        val imageView = viewer.findViewById<ImageView>(R.id.dock_viewer_image)
        val imagePath = item.filePath ?: item.thumbnailPath
        if (imageView != null && imagePath != null) {
            io.execute {
                val bitmap = decodeSampledBitmap(imagePath, screenWidth, screenHeight)
                handler.post {
                    if (destroyed || imageViewerWindow !== viewer) {
                        bitmap?.recycle()
                        return@post
                    }
                    currentViewerBitmap = bitmap
                    imageView.setImageBitmap(bitmap)
                }
            }
        }

        viewer.findViewById<View>(R.id.dock_viewer_btn_paste)?.visibility = View.GONE
        viewer.findViewById<ImageView>(R.id.dock_viewer_btn_copy)?.setOnClickListener { copyItem(item) }
        viewer.findViewById<ImageView>(R.id.dock_viewer_btn_share)?.setOnClickListener { shareItem(item) }

        windowManager.addView(viewer, params)
        playHapticTick()
    }

    private fun dismissImageViewer() {
        imageViewerWindow?.let {
            try {
                it.findViewById<ImageView>(R.id.dock_viewer_image)?.setImageDrawable(null)
                windowManager.removeView(it)
            } catch (t: Throwable) {
                Log.w(TAG, "Error removing viewer window", t)
            }
            imageViewerWindow = null
        }
        currentViewerBitmap?.let {
            if (!it.isRecycled) it.recycle()
            currentViewerBitmap = null
        }
    }

    private fun decodeSampledBitmap(path: String?, reqWidth: Int, reqHeight: Int): Bitmap? {
        if (path == null) return null
        return try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, options)
            val height = options.outHeight
            val width = options.outWidth
            var inSampleSize = 1
            if (height > reqHeight || width > reqWidth) {
                val halfHeight = height / 2
                val halfWidth = width / 2
                while (halfHeight / inSampleSize >= reqHeight || halfWidth / inSampleSize >= reqWidth) inSampleSize *= 2
            }
            options.inSampleSize = max(1, inSampleSize)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565
            BitmapFactory.decodeFile(path, options)
        } catch (t: Throwable) {
            Log.e(TAG, "Error decoding sampled bitmap: $path", t)
            null
        }
    }

    private fun playHapticTick() {
        try {
            val vibrator = getSystemService(Vibrator::class.java)
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            }
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "SmartDockService"
        const val KEY_DOCK_ENABLE = "dock_sidebar_enable"
        const val KEY_HANDLE_POSITION = "dock_handle_position"
        const val KEY_AUTO_FADE = "dock_auto_fade"
        const val KEY_FADE_TRANSPARENCY = "dock_fade_transparency"
        const val KEY_HIDE_LANDSCAPE = "dock_hide_landscape"
        const val KEY_AUTO_SHOW = "dock_auto_show"
        const val KEY_CAPTURE = "dock_capture_clipboard"
        const val KEY_WIDTH = "dock_width"
        const val KEY_HEIGHT = "dock_height"
        const val KEY_HOLD_CONTROLS = "dock_hold_controls"
        const val KEY_DOUBLE_TAP_SCREENSHOT = "dock_double_tap_screenshot"
        const val KEY_BATTERY_RAIL = "dock_battery_rail"
        const val KEY_BLUR = "dock_expanded_blur"
        const val KEY_CLIPBOARD_TIMEOUT = "dock_clipboard_timeout_minutes"
        // Do not reactivate the obsolete dock_history_limit cap after unlimited history.
        const val KEY_HISTORY_LIMIT = "dock_history_retention_limit"
        const val DEFAULT_SHELF_WIDTH = 300
        const val DEFAULT_SHELF_HEIGHT = 330
        private const val MIN_SHELF_WIDTH = 200
        private const val MIN_SHELF_HEIGHT = 180
        const val KEY_SHELF_WIDTH = "dock_shelf_width"
        const val KEY_SHELF_HEIGHT = "dock_shelf_height"
        const val KEY_SHELF_X = "dock_shelf_x"
        const val KEY_SHELF_Y = "dock_shelf_y"
        const val KEY_SHELF_RIGHT = "dock_shelf_right"
        private const val KEY_HANDLE_Y = "dock_handle_y_pos"

        @JvmStatic
        fun restoreState(context: Context) {
            if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
            if (context.defaultPrefs().getBoolean(KEY_DOCK_ENABLE, false)) {
                context.startService(Intent(context, SmartDockService::class.java))
            }
        }

        @JvmStatic
        fun isWebUrl(text: String?): Boolean {
            if (text == null || text.trim().matches(Regex(".*\\s+.*"))) return false
            val value = text.trim()
            return Patterns.WEB_URL.matcher(value).matches() ||
                value.startsWith("https://") || value.startsWith("http://") || value.startsWith("ftp://")
        }

        @JvmStatic
        fun extractDomain(text: String?): String {
            if (text == null) return ""
            return try {
                val uri = URI(if (text.contains("://")) text.trim() else "https://" + text.trim())
                uri.host ?: text
            } catch (e: Exception) {
                text
            }
        }
    }
}

/** Keep a held clip compact without changing the platform drag payload or URI grants. */
internal class DockDragShadow(private val source: View, maxWidth: Int, maxHeight: Int) : View.DragShadowBuilder(source) {
    private val width = source.width.coerceAtLeast(1)
    private val height = source.height.coerceAtLeast(1)
    private val scale = minOf(1f, maxWidth.toFloat() / width, maxHeight.toFloat() / height)

    override fun onProvideShadowMetrics(size: Point, touch: Point) {
        size.set((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
        touch.set(size.x / 2, size.y / 2)
    }

    override fun onDrawShadow(canvas: Canvas) {
        val saved = canvas.save()
        canvas.scale(scale, scale)
        source.draw(canvas)
        canvas.restoreToCount(saved)
    }
}

package com.screenreader.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Bitmap.Config.ARGB_8888
import android.graphics.ColorSpace
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.screenreader.app.runtime.ScreenReaderController

class ReaderAccessibilityService : AccessibilityService() {

    @Volatile
    private var lastScrollEventTimeMs: Long = 0L
    @Volatile
    private var connected = false
    private var createdAtMs = 0L
    private var loggedPreConnectionEvent = false

    override fun onCreate() {
        createdAtMs = SystemClock.uptimeMillis()
        Log.i(TAG, "onCreate: SDK=${Build.VERSION.SDK_INT}, process service instance created")
        try {
            super.onCreate()
            Log.i(TAG, "onCreate: completed")
        } catch (error: Throwable) {
            Log.e(TAG, "onCreate: failed", error)
            throw error
        }
    }

    override fun onServiceConnected() {
        val elapsedMs = SystemClock.uptimeMillis() - createdAtMs
        Log.i(TAG, "onServiceConnected: begin after ${elapsedMs}ms")
        try {
            super.onServiceConnected()

            // Flags are declared in accessibility_service_config.xml. Avoid rewriting
            // serviceInfo during the first OEM binding, which is redundant and fragile.
            val info = serviceInfo
            Log.i(
                TAG,
                "onServiceConnected: config flags=${info?.flags}, " +
                    "eventTypes=${info?.eventTypes}, capabilities=${info?.capabilities}"
            )

            instance = this
            connected = true
            ScreenReaderController.onAccessibilityAvailabilityChanged(true)
            Log.i(TAG, "onServiceConnected: ready")
        } catch (error: Throwable) {
            connected = false
            if (instance === this) {
                instance = null
            }
            Log.e(TAG, "onServiceConnected: failed", error)
            throw error
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!connected && !loggedPreConnectionEvent) {
            loggedPreConnectionEvent = true
            Log.w(
                TAG,
                "onAccessibilityEvent: received before onServiceConnected completed; " +
                    "type=${event?.eventType}"
            )
        }
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            lastScrollEventTimeMs = SystemClock.uptimeMillis()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
    }

    override fun onDestroy() {
        Log.w(TAG, "onDestroy: connected=$connected")
        connected = false
        if (instance === this) {
            instance = null
        }
        ScreenReaderController.onAccessibilityAvailabilityChanged(false)
        super.onDestroy()
        Log.i(TAG, "onDestroy: completed")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "onUnbind: action=${intent?.action}, connected=$connected")
        connected = false
        if (instance === this) {
            instance = null
        }
        ScreenReaderController.onAccessibilityAvailabilityChanged(false)
        return super.onUnbind(intent)
    }

    fun captureScreen(onResult: (Result<Bitmap>) -> Unit) {
        Log.d(TAG, "captureScreen: requested, connected=$connected")
        if (!connected) {
            onResult(Result.failure(IllegalStateException("Accessibility service is not connected.")))
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(Result.failure(UnsupportedOperationException("Screenshot API requires Android 11+ for this MVP build.")))
            return
        }

        takeScreenshotCompat(onResult)
    }

    fun swipeUpForMoreContent(onResult: (Boolean) -> Unit) {
        if (!connected) {
            Log.w(TAG, "swipeUpForMoreContent: rejected because service is not connected")
            onResult(false)
            return
        }
        val metrics = resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * SCROLL_START_Y_RATIO
        val endY = metrics.heightPixels * SCROLL_END_Y_RATIO
        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, SCROLL_GESTURE_DURATION_MS))
            .build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "swipeUpForMoreContent: gesture completed")
                    onResult(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "swipeUpForMoreContent: gesture cancelled")
                    onResult(false)
                }
            },
            null
        )
        Log.d(TAG, "swipeUpForMoreContent: dispatchGesture returned $dispatched")
        if (!dispatched) {
            onResult(false)
        }
    }

    fun millisSinceLastScrollEvent(): Long {
        val lastEventTime = lastScrollEventTimeMs
        if (lastEventTime == 0L) return Long.MAX_VALUE
        return SystemClock.uptimeMillis() - lastEventTime
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun takeScreenshotCompat(onResult: (Result<Bitmap>) -> Unit) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    try {
                        val bitmap = screenshotResult.toBitmap()
                        Log.d(TAG, "takeScreenshot: success ${bitmap.width}x${bitmap.height}")
                        onResult(Result.success(bitmap))
                    } catch (error: Throwable) {
                        Log.e(TAG, "takeScreenshot: bitmap conversion failed", error)
                        onResult(Result.failure(error))
                    }
                }

                override fun onFailure(errorCode: Int) {
                    val errorName = screenshotErrorName(errorCode)
                    Log.e(TAG, "takeScreenshot: failed code=$errorCode ($errorName)")
                    onResult(Result.failure(IllegalStateException("Screenshot failed with code $errorCode")))
                }
            }
        )
    }

    private fun screenshotErrorName(errorCode: Int): String {
        return when (errorCode) {
            1 -> "ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR"
            2 -> "ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS"
            3 -> "ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT"
            4 -> "ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY"
            5 -> "ERROR_TAKE_SCREENSHOT_INVALID_WINDOW"
            6 -> "ERROR_TAKE_SCREENSHOT_SECURE_WINDOW"
            else -> "UNKNOWN"
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun ScreenshotResult.toBitmap(): Bitmap {
        val screenshotBuffer: HardwareBuffer = hardwareBuffer
        val screenshotColorSpace: ColorSpace = colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
        val source = Bitmap.wrapHardwareBuffer(screenshotBuffer, screenshotColorSpace)
            ?: throw IllegalStateException("Could not wrap screenshot buffer.")
        val mutableBitmap = source.copy(ARGB_8888, false)
        source.recycle()
        screenshotBuffer.close()
        return mutableBitmap
    }

    companion object {
        private const val TAG = "ReaderAccessibility"
        private const val SCROLL_GESTURE_DURATION_MS = 420L
        private const val SCROLL_START_Y_RATIO = 0.70f
        private const val SCROLL_END_Y_RATIO = 0.45f

        @Volatile
        private var instance: ReaderAccessibilityService? = null

        fun current(): ReaderAccessibilityService? = instance
    }
}

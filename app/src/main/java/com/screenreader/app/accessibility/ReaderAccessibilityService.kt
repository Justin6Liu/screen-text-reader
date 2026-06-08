package com.screenreader.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
import android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
import android.graphics.Bitmap
import android.graphics.Bitmap.Config.ARGB_8888
import android.graphics.ColorSpace
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.screenreader.app.runtime.ScreenReaderController

class ReaderAccessibilityService : AccessibilityService() {

    @Volatile
    private var lastScrollEventTimeMs: Long = 0L

    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or
                FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                FLAG_REPORT_VIEW_IDS
        }
        ScreenReaderController.onAccessibilityAvailabilityChanged(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            lastScrollEventTimeMs = SystemClock.uptimeMillis()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        ScreenReaderController.onAccessibilityAvailabilityChanged(false)
    }

    fun captureScreen(onResult: (Result<Bitmap>) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(Result.failure(UnsupportedOperationException("Screenshot API requires Android 11+ for this MVP build.")))
            return
        }

        takeScreenshotCompat(onResult)
    }

    fun swipeUpForMoreContent(onResult: (Boolean) -> Unit) {
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
                    onResult(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onResult(false)
                }
            },
            null
        )
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
                        onResult(Result.success(bitmap))
                    } catch (error: Throwable) {
                        onResult(Result.failure(error))
                    }
                }

                override fun onFailure(errorCode: Int) {
                    onResult(Result.failure(IllegalStateException("Screenshot failed with code $errorCode")))
                }
            }
        )
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
        private const val SCROLL_GESTURE_DURATION_MS = 420L
        private const val SCROLL_START_Y_RATIO = 0.76f
        private const val SCROLL_END_Y_RATIO = 0.36f

        @Volatile
        private var instance: ReaderAccessibilityService? = null

        fun current(): ReaderAccessibilityService? = instance
    }
}

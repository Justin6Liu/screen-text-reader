package com.screenreader.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.provider.Settings
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.screenreader.app.MainActivity
import com.screenreader.app.R
import com.screenreader.app.ocr.OcrDebugSnapshot
import com.screenreader.app.runtime.ScreenReaderController
import com.screenreader.app.runtime.ScreenReaderController.ReaderState

class OverlayService : Service(),
    ScreenReaderController.StateListener,
    ScreenReaderController.DebugListener,
    ScreenReaderController.CaptureOverlayListener {

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var overlayButton: ImageButton? = null
    private var overlayLabel: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayHiddenForCapture = false
    private var debugOverlayView: OcrDebugOverlayView? = null

    private val clearDebugOverlay = Runnable {
        hideDebugOverlay()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        ScreenReaderController.initialize(applicationContext)
        ScreenReaderController.addStateListener(this)
        ScreenReaderController.addDebugListener(this)
        ScreenReaderController.addCaptureOverlayListener(this)
        createNotificationChannel()
        startAsForegroundService()
        if (!Settings.canDrawOverlays(this)) {
            val message = "Overlay permission is missing. Grant it first, then start the floating button again."
            ScreenReaderController.reportStatus(message)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ScreenReaderController.removeStateListener(this)
        ScreenReaderController.removeDebugListener(this)
        ScreenReaderController.removeCaptureOverlayListener(this)
        hideOverlay()
        hideDebugOverlay()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay() {
        if (overlayView != null) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.view_overlay_button, null)
        val button = view.findViewById<ImageButton>(R.id.overlayButton)
        val label = view.findViewById<TextView>(R.id.overlayLabel)

        val params = overlayParams ?: createOverlayLayoutParams()

        installButtonActionHandler(button, view, params)
        installDragHandler(view, params)

        try {
            windowManager.addView(view, params)
            overlayView = view
            overlayButton = button
            overlayLabel = label
            overlayParams = params
            updateOverlayUi(
                if (ScreenReaderController.isSpeaking()) ReaderState.SPEAKING
                else if (ScreenReaderController.isPaused()) ReaderState.PAUSED
                else if (ScreenReaderController.isProcessing()) ReaderState.PROCESSING
                else ReaderState.IDLE
            )
            ScreenReaderController.reportStatus("Floating button started.")
        } catch (error: Throwable) {
            val message = error.message ?: "Could not show floating button."
            ScreenReaderController.reportStatus(message)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun installDragHandler(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        moved = false
                        return false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                            moved = true
                            params.x = initialX + dx
                            params.y = initialY + dy
                            windowManager.updateViewLayout(view, params)
                            return true
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        if (moved) return true
                    }
                }
                return false
            }
        })
    }

    private fun installButtonActionHandler(button: ImageButton, view: View, params: WindowManager.LayoutParams) {
        button.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var moved = false
            private var longPressTriggered = false

            private val redoRead = Runnable {
                longPressTriggered = true
                ScreenReaderController.haltReading()
                Toast.makeText(this@OverlayService, "Reading halted.", Toast.LENGTH_SHORT).show()
            }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        moved = false
                        longPressTriggered = false
                        mainHandler.postDelayed(redoRead, REDO_OCR_LONG_PRESS_MS)
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                            moved = true
                            mainHandler.removeCallbacks(redoRead)
                            params.x = initialX + dx
                            params.y = initialY + dy
                            windowManager.updateViewLayout(view, params)
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        mainHandler.removeCallbacks(redoRead)
                        if (!moved && !longPressTriggered) {
                            ScreenReaderController.captureAndReadAloud()
                        }
                        return true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        mainHandler.removeCallbacks(redoRead)
                        return true
                    }
                }
                return true
            }
        })
    }

    private fun createOverlayLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 240
        }
    }

    private fun hideOverlay() {
        overlayView?.let { view ->
            windowManager.removeView(view)
        }
        overlayView = null
        overlayButton = null
        overlayLabel = null
    }

    private fun hideOverlayForCapture() {
        if (overlayView == null) return
        overlayHiddenForCapture = true
        hideOverlay()
    }

    private fun restoreOverlayAfterCapture() {
        if (!overlayHiddenForCapture) return
        overlayHiddenForCapture = false
        showOverlay()
    }

    private fun showDebugOverlay(snapshot: OcrDebugSnapshot) {
        if (debugOverlayView == null) {
            debugOverlayView = OcrDebugOverlayView(this)
            val displayBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager.currentWindowMetrics.bounds
            } else {
                null
            }
            val params = WindowManager.LayoutParams(
                displayBounds?.width() ?: WindowManager.LayoutParams.MATCH_PARENT,
                displayBounds?.height() ?: WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fitInsetsTypes = 0
                    fitInsetsSides = 0
                }
            }
            windowManager.addView(debugOverlayView, params)
        }
        debugOverlayView?.updateSnapshot(snapshot)
        mainHandler.removeCallbacks(clearDebugOverlay)
        if (snapshot.activeReadBounds == null) {
            mainHandler.postDelayed(clearDebugOverlay, 8000L)
        }
    }

    private fun hideDebugOverlay() {
        mainHandler.removeCallbacks(clearDebugOverlay)
        debugOverlayView?.let { view ->
            windowManager.removeView(view)
        }
        debugOverlayView = null
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getBroadcast(
            this,
            2,
            Intent(this, OverlayActionReceiver::class.java).setAction(ACTION_STOP_SERVICE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopSpeechIntent = PendingIntent.getBroadcast(
            this,
            3,
            Intent(this, OverlayActionReceiver::class.java).setAction(ACTION_STOP_SPEECH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.stop_speech), stopSpeechIntent)
            .addAction(0, getString(R.string.stop_overlay), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun startAsForegroundService() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStateChanged(state: ReaderState) {
        overlayView?.post {
            updateOverlayUi(state)
        }
    }

    override fun onDebugSnapshot(snapshot: OcrDebugSnapshot?) {
        mainHandler.post {
            if (snapshot == null) {
                hideDebugOverlay()
            } else {
                showDebugOverlay(snapshot)
            }
        }
    }

    override fun onBeforeScreenCapture() {
        mainHandler.post {
            hideOverlayForCapture()
        }
    }

    override fun onAfterScreenCapture() {
        mainHandler.post {
            restoreOverlayAfterCapture()
        }
    }

    private fun updateOverlayUi(state: ReaderState) {
        when (state) {
            ReaderState.IDLE -> {
                overlayButton?.setImageResource(R.drawable.ic_overlay_play)
                overlayButton?.contentDescription = getString(R.string.overlay_button_read_label)
                overlayLabel?.text = getString(R.string.overlay_button_read_text)
                overlayButton?.alpha = 1.0f
            }

            ReaderState.PROCESSING -> {
                overlayButton?.setImageResource(R.drawable.ic_overlay_busy)
                overlayButton?.contentDescription = getString(R.string.overlay_button_processing_label)
                overlayLabel?.text = getString(R.string.overlay_button_processing_text)
                overlayButton?.alpha = 0.85f
            }

            ReaderState.SPEAKING -> {
                overlayButton?.setImageResource(R.drawable.ic_overlay_pause)
                overlayButton?.contentDescription = getString(R.string.overlay_button_pause_label)
                overlayLabel?.text = getString(R.string.overlay_button_pause_text)
                overlayButton?.alpha = 1.0f
            }

            ReaderState.PAUSED -> {
                overlayButton?.setImageResource(R.drawable.ic_overlay_play)
                overlayButton?.contentDescription = getString(R.string.overlay_button_resume_label)
                overlayLabel?.text = getString(R.string.overlay_button_resume_text)
                overlayButton?.alpha = 1.0f
            }
        }
    }

    companion object {
        const val ACTION_STOP_SERVICE = "com.screenreader.app.action.STOP_SERVICE"
        const val ACTION_STOP_SPEECH = "com.screenreader.app.action.STOP_SPEECH"

        private const val CHANNEL_ID = "screen_reader_overlay"
        private const val NOTIFICATION_ID = 1001
        private const val REDO_OCR_LONG_PRESS_MS = 1000L

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}

package com.screenreader.app.runtime

import android.content.Context
import com.screenreader.app.ocr.OcrDebugSnapshot
import com.screenreader.app.accessibility.ReaderAccessibilityService
import com.screenreader.app.ocr.OcrOutput
import com.screenreader.app.ocr.OcrEngine
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

object ScreenReaderController {

    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArraySet<StateListener>()
    private val debugListeners = CopyOnWriteArraySet<DebugListener>()

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var accessibilityReady = false

    @Volatile
    private var state: ReaderState = ReaderState.IDLE

    @Volatile
    private var lastStatus: String = "Ready. Grant permissions, start the overlay, then tap the floating button."

    private var speechManager: SpeechManager? = null
    private var ocrEngine: OcrEngine? = null

    fun initialize(context: Context) {
        val applicationContext = context.applicationContext
        appContext = applicationContext
        if (speechManager == null) {
            speechManager = SpeechManager(
                applicationContext,
                onStatusChanged = { updateStatus(it) },
                onSpeechStarted = { updateState(ReaderState.SPEAKING) },
                onSpeechFinished = { onSpeechFinished() }
            )
        }
        if (ocrEngine == null) {
            ocrEngine = OcrEngine(applicationContext)
        }
    }

    fun onAccessibilityAvailabilityChanged(available: Boolean) {
        accessibilityReady = available
        updateStatus(
            if (available) {
                "Accessibility connected. You can test reading now."
            } else {
                "Accessibility not connected. Enable the Screen Reader service in Accessibility settings."
            }
        )
    }

    fun isAccessibilityReady(): Boolean = accessibilityReady && ReaderAccessibilityService.current() != null

    fun captureAndReadAloud() {
        if (state == ReaderState.PROCESSING) {
            updateStatus("Already processing. Please wait.")
            return
        }
        if (state == ReaderState.SPEAKING) {
            stopSpeaking()
            return
        }

        val service = ReaderAccessibilityService.current()
        if (service == null) {
            updateStatus("Accessibility service is not connected.")
            return
        }

        updateState(ReaderState.PROCESSING)
        emitDebugSnapshot(null)
        updateStatus("Capturing screen...")
        service.captureScreen { captureResult ->
            captureResult.onSuccess { bitmap ->
                worker.execute {
                    updateStatus("Recognizing text...")
                    val result = ocrEngine?.recognize(bitmap)
                    bitmap.recycle()
                    result
                        ?.onSuccess { output ->
                            maybeEmitDebugSnapshot(output.debugSnapshot)
                            if (output.text.isBlank()) {
                                finishWithStatus("No text found on screen.")
                            } else {
                                updateStatus("Reading aloud...")
                                val started = speechManager?.speak(output.text) == true
                                if (!started) {
                                    finishWithStatus("Speech is not ready yet.")
                                }
                            }
                        }
                        ?.onFailure { error ->
                            finishWithStatus(error.message ?: "OCR failed.")
                        } ?: finishWithStatus("OCR engine unavailable.")
                }
            }.onFailure { error ->
                finishWithStatus(error.message ?: "Screen capture failed.")
            }
        }
    }

    fun stopSpeaking() {
        speechManager?.stop()
        updateStatus("Speech stopped.")
    }

    fun readDemoText() {
        val started = speechManager?.speak("屏幕朗读测试。请先确认中文语音可以正常播放。") == true
        if (started) {
            updateStatus("Playing demo speech.")
        }
    }

    fun getUiStatus(): String = lastStatus

    fun reportStatus(message: String) {
        updateStatus(message)
    }

    fun setOcrDebugModeEnabled(enabled: Boolean) {
        if (!enabled) {
            emitDebugSnapshot(null)
        }
    }

    fun isSpeaking(): Boolean = state == ReaderState.SPEAKING

    fun isProcessing(): Boolean = state == ReaderState.PROCESSING

    fun addStateListener(listener: StateListener) {
        listeners.add(listener)
        listener.onStateChanged(state)
    }

    fun removeStateListener(listener: StateListener) {
        listeners.remove(listener)
    }

    fun addDebugListener(listener: DebugListener) {
        debugListeners.add(listener)
    }

    fun removeDebugListener(listener: DebugListener) {
        debugListeners.remove(listener)
    }

    private fun finishWithStatus(message: String) {
        updateState(ReaderState.IDLE)
        updateStatus(message)
    }

    private fun updateStatus(message: String) {
        lastStatus = message
    }

    private fun updateState(newState: ReaderState) {
        state = newState
        listeners.forEach { it.onStateChanged(newState) }
    }

    private fun onSpeechFinished() {
        if (state == ReaderState.SPEAKING) {
            updateState(ReaderState.IDLE)
            updateStatus("Ready for the next read.")
        }
    }

    private fun maybeEmitDebugSnapshot(snapshot: OcrDebugSnapshot?) {
        val context = appContext ?: return
        if (AppPreferences.isOcrDebugModeEnabled(context)) {
            emitDebugSnapshot(snapshot)
        } else {
            emitDebugSnapshot(null)
        }
    }

    private fun emitDebugSnapshot(snapshot: OcrDebugSnapshot?) {
        debugListeners.forEach { it.onDebugSnapshot(snapshot) }
    }

    interface StateListener {
        fun onStateChanged(state: ReaderState)
    }

    interface DebugListener {
        fun onDebugSnapshot(snapshot: OcrDebugSnapshot?)
    }

    enum class ReaderState {
        IDLE,
        PROCESSING,
        SPEAKING
    }
}

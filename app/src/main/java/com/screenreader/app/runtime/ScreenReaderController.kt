package com.screenreader.app.runtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import com.screenreader.app.ocr.OcrDebugSnapshot
import com.screenreader.app.accessibility.ReaderAccessibilityService
import com.screenreader.app.ocr.OcrOutput
import com.screenreader.app.ocr.OcrEngine
import com.screenreader.app.ocr.OcrReadSegment
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

object ScreenReaderController {

    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArraySet<StateListener>()
    private val debugListeners = CopyOnWriteArraySet<DebugListener>()
    private val captureOverlayListeners = CopyOnWriteArraySet<CaptureOverlayListener>()

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var accessibilityReady = false

    @Volatile
    private var state: ReaderState = ReaderState.IDLE

    @Volatile
    private var lastStatus: String = "Ready. Grant permissions, start the overlay, then tap the floating button."
    @Volatile
    private var lastRecognizedText: String = "No recognized text yet."
    @Volatile
    private var currentPlayback: PlaybackSession? = null
    @Volatile
    private var pausedSegmentIndex: Int = 0

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
            val context = appContext
            if (context != null && AppPreferences.isPauseResumeReadingEnabled(context)) {
                pauseSpeaking()
            } else {
                haltReading()
            }
            return
        }
        if (state == ReaderState.PAUSED) {
            resumeSpeaking()
            return
        }

        captureFreshAndReadAloud()
    }

    fun haltReading() {
        if (state == ReaderState.PROCESSING) {
            updateStatus("Already processing. Please wait.")
            return
        }
        currentPlayback = null
        pausedSegmentIndex = 0
        speechManager?.stop()
        emitDebugSnapshot(null)
        updateState(ReaderState.IDLE)
        updateStatus("Reading halted.")
    }

    private fun captureFreshAndReadAloud() {
        val service = ReaderAccessibilityService.current()
        if (service == null) {
            updateStatus("Accessibility service is not connected.")
            return
        }

        updateState(ReaderState.PROCESSING)
        emitDebugSnapshot(null)
        updateStatus(
            if (appContext?.let { AppPreferences.isAutoScrollCaptureEnabled(it) } == true) {
                "Capturing long image..."
            } else {
                "Capturing screen..."
            }
        )
        prepareOverlaysForCapture {
            val context = appContext
            if (context != null && AppPreferences.isAutoScrollCaptureEnabled(context)) {
                captureScrollableAndRead(service)
            } else {
                captureSingleAndRead(service)
            }
        }
    }

    private fun captureSingleAndRead(service: ReaderAccessibilityService) {
        service.captureScreen { captureResult ->
            restoreOverlaysAfterCapture()
            captureResult
                .onSuccess { bitmap -> recognizeAndRead(bitmap) }
                .onFailure { error -> finishWithStatus(error.message ?: "Screen capture failed.") }
        }
    }

    private fun captureScrollableAndRead(service: ReaderAccessibilityService) {
        val captures = mutableListOf<Bitmap>()
        val maxCaptures = appContext
            ?.let { AppPreferences.getAutoScrollMaxCaptures(it) }
            ?: AppPreferences.DEFAULT_AUTO_SCROLL_MAX_CAPTURES

        fun finishWithCaptures() {
            restoreOverlaysAfterCapture()
            if (captures.isEmpty()) {
                finishWithStatus("Screen capture failed.")
                return
            }
            worker.execute {
                recognizeScrollableCapturesAndRead(captures)
            }
        }

        fun captureStep(step: Int) {
            updateStatus("Capturing long image... ${step + 1}/$maxCaptures")
            service.captureScreen { captureResult ->
                captureResult
                    .onSuccess { bitmap ->
                        val previous = captures.lastOrNull()
                        if (previous != null && bitmap.isVisuallySimilarTo(previous)) {
                            bitmap.recycle()
                            finishWithCaptures()
                            return@onSuccess
                        }
                        captures += bitmap
                        if (captures.size >= maxCaptures) {
                            finishWithCaptures()
                            return@onSuccess
                        }
                        service.swipeUpForMoreContent { scrolled ->
                            if (!scrolled) {
                                finishWithCaptures()
                            } else {
                                waitForScrollToSettle(service) {
                                    captureStep(step + 1)
                                }
                            }
                        }
                    }
                    .onFailure {
                        finishWithCaptures()
                    }
            }
        }

        captureStep(0)
    }

    private fun recognizeScrollableCapturesAndRead(captures: List<Bitmap>) {
        val ocr = ocrEngine
        if (ocr == null) {
            captures.recycleAll()
            finishWithStatus("OCR engine unavailable.")
            return
        }

        val outputs = mutableListOf<ScrollableOcrOutput>()
        captures.forEachIndexed { index, capture ->
            updateStatus("Recognizing long image... ${index + 1}/${captures.size}")
            maybeSaveDebugScreenshot(capture)
            val result = ocr.recognize(capture)
            if (!capture.isRecycled) {
                capture.recycle()
            }
            result
                .onSuccess { output ->
                    if (output.text.isNotBlank()) {
                        outputs += ScrollableOcrOutput(
                            output = output,
                            captureIndex = index,
                            captureCount = captures.size
                        )
                    }
                }
                .onFailure { error ->
                    if (outputs.isEmpty()) {
                        captures.recycleAll()
                        finishWithStatus(error.message ?: "OCR failed.")
                        return
                    }
                }
        }

        val output = combineScrollableOutputs(outputs)
        updateRecognizedText(output.text)
        if (output.text.isBlank()) {
            finishWithStatus("No text found on screen.")
            return
        }

        emitDebugSnapshot(null)
        updateStatus("Reading aloud...")
        val started = speakOcrOutput(output)
        if (!started) {
            finishWithStatus("Speech is not ready yet.")
        }
    }

    private fun List<Bitmap>.recycleAll() {
        forEach { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun combineScrollableOutputs(outputs: List<ScrollableOcrOutput>): OcrOutput {
        if (outputs.isEmpty()) return OcrOutput("", null)

        val acceptedSegments = mutableListOf<String>()
        outputs.forEach { item ->
            val incomingSegments = item.output.toScrollableSegmentTexts(
                captureIndex = item.captureIndex,
                captureCount = item.captureCount
            )
            if (incomingSegments.isEmpty()) return@forEach

            val overlapCount = findOverlappingSegmentCount(acceptedSegments, incomingSegments)
            val candidateSegments = incomingSegments
                .drop(overlapCount)
                .map { it.trim() }
                .filter { it.isNotBlank() }

            if (acceptedSegments.hasRecentCaptureDuplicate(candidateSegments)) {
                return@forEach
            }

            val segmentsToAppend = if (item.isNearScrollableEnd()) {
                candidateSegments.filterNovelAgainst(acceptedSegments)
            } else {
                candidateSegments
            }

            segmentsToAppend.forEach { segment ->
                if (!acceptedSegments.hasRecentDuplicate(segment)) {
                    acceptedSegments += segment
                }
            }
        }

        val builder = StringBuilder()
        val readSegments = mutableListOf<OcrReadSegment>()
        acceptedSegments.forEach { segment ->
            if (builder.isNotEmpty()) {
                builder.append("\n\n")
            }
            val start = builder.length
            builder.append(segment)
            val end = builder.length
            readSegments += OcrReadSegment(
                text = segment,
                bounds = Rect(0, 0, 0, 0),
                startIndex = start,
                endIndex = end
            )
        }

        return OcrOutput(
            text = builder.toString().trim(),
            debugSnapshot = null,
            readSegments = readSegments
        )
    }

    private fun OcrOutput.toScrollableSegmentTexts(
        captureIndex: Int,
        captureCount: Int
    ): List<String> {
        val safeBounds = scrollableSafeVerticalBounds(captureIndex, captureCount)
        val segments = readSegments
            .filter { segment -> segment.isInsideScrollableSafeZone(safeBounds) }
            .map { it.text.trim() }
            .filter { it.isNotBlank() }
        if (segments.isNotEmpty()) return segments
        return if (safeBounds == null) text.splitIntoScrollableSegments() else emptyList()
    }

    private fun OcrOutput.scrollableSafeVerticalBounds(
        captureIndex: Int,
        captureCount: Int
    ): IntRange? {
        val imageHeight = debugSnapshot?.imageHeight ?: return null
        if (imageHeight <= 0 || captureCount <= 1) return null

        val edgeInset = (imageHeight * SCROLLABLE_SAFE_EDGE_RATIO).toInt()
            .coerceAtLeast(SCROLLABLE_SAFE_EDGE_MIN_PX)
        val top = if (captureIndex == 0) 0 else edgeInset
        val bottom = if (captureIndex == captureCount - 1) imageHeight else imageHeight - edgeInset
        return top..bottom.coerceAtLeast(top)
    }

    private fun OcrReadSegment.isInsideScrollableSafeZone(safeBounds: IntRange?): Boolean {
        if (safeBounds == null) return true
        if (bounds.isEmpty()) return false
        val centerY = bounds.centerY()
        return centerY in safeBounds
    }

    private fun String.splitIntoScrollableSegments(): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (char in this) {
            current.append(char)
            val shouldBreak =
                char in SCROLLABLE_TEXT_BREAK_CHARS ||
                    current.length >= SCROLLABLE_FALLBACK_SEGMENT_CHARS
            if (shouldBreak) {
                current.toString().trim().takeIf { it.isNotBlank() }?.let { result += it }
                current.clear()
            }
        }
        current.toString().trim().takeIf { it.isNotBlank() }?.let { result += it }
        return result
    }

    private fun findOverlappingSegmentCount(
        existing: List<String>,
        incoming: List<String>
    ): Int {
        if (existing.isEmpty() || incoming.isEmpty()) return 0

        val maxOverlap = minOf(
            SCROLLABLE_MAX_OVERLAP_SEGMENTS,
            existing.size,
            incoming.size
        )
        for (count in maxOverlap downTo 1) {
            val existingText = existing.takeLast(count).joinToString(separator = "")
            val incomingText = incoming.take(count).joinToString(separator = "")
            if (existingText.scrollableSimilarity(incomingText) >= SCROLLABLE_OVERLAP_SIMILARITY) {
                return count
            }
        }
        return 0
    }

    private fun List<String>.hasRecentDuplicate(segment: String): Boolean {
        return takeLast(SCROLLABLE_RECENT_DUPLICATE_LOOKBACK).any { recent ->
            recent.scrollableSimilarity(segment) >= SCROLLABLE_DUPLICATE_SIMILARITY
        }
    }

    private fun List<String>.filterNovelAgainst(existingSegments: List<String>): List<String> {
        if (isEmpty() || existingSegments.isEmpty()) return this

        val recentSegments = existingSegments.takeLast(SCROLLABLE_END_NOVELTY_LOOKBACK)
        val recentText = recentSegments.joinToString(separator = "").normalizedForScrollableMerge()
        if (recentText.length < SCROLLABLE_CAPTURE_DUPLICATE_MIN_CHARS) return this

        val novelSegments = filter { segment ->
            segment.isNovelComparedWith(recentSegments, recentText)
        }

        val incomingText = joinToString(separator = "").normalizedForScrollableMerge()
        val novelText = novelSegments.joinToString(separator = "").normalizedForScrollableMerge()
        val noveltyRatio = if (incomingText.isEmpty()) {
            0.0
        } else {
            novelText.length.toDouble() / incomingText.length.toDouble()
        }

        return if (noveltyRatio <= SCROLLABLE_END_LOW_NOVELTY_RATIO) {
            novelSegments
        } else {
            this
        }
    }

    private fun String.isNovelComparedWith(
        recentSegments: List<String>,
        recentText: String
    ): Boolean {
        val normalized = normalizedForScrollableMerge()
        if (normalized.length < SCROLLABLE_END_NOVELTY_MIN_CHARS) return true
        if (recentText.contains(normalized)) return false

        return recentSegments.none { recent ->
            val recentNormalized = recent.normalizedForScrollableMerge()
            recentNormalized.contains(normalized) ||
                normalized.scrollableSimilarity(recentNormalized) >= SCROLLABLE_END_SEGMENT_DUPLICATE_SIMILARITY
        }
    }

    private fun List<String>.hasRecentCaptureDuplicate(incomingSegments: List<String>): Boolean {
        if (isEmpty() || incomingSegments.isEmpty()) return false

        val incomingText = incomingSegments.joinToString(separator = "").normalizedForScrollableMerge()
        if (incomingText.length < SCROLLABLE_CAPTURE_DUPLICATE_MIN_CHARS) return false

        val tailSegmentCount = (incomingSegments.size + SCROLLABLE_CAPTURE_DUPLICATE_EXTRA_LOOKBACK)
            .coerceAtMost(size)
        val recentText = takeLast(tailSegmentCount).joinToString(separator = "").normalizedForScrollableMerge()
        if (recentText.length < SCROLLABLE_CAPTURE_DUPLICATE_MIN_CHARS) return false

        return recentText.contains(incomingText) ||
            recentText.scrollableSimilarity(incomingText) >= SCROLLABLE_CAPTURE_DUPLICATE_SIMILARITY
    }

    private fun ScrollableOcrOutput.isNearScrollableEnd(): Boolean {
        return captureIndex >= captureCount - SCROLLABLE_END_NOVELTY_CAPTURE_COUNT
    }

    private fun String.scrollableSimilarity(other: String): Double {
        val first = normalizedForScrollableMerge()
        val second = other.normalizedForScrollableMerge()
        if (first.isEmpty() || second.isEmpty()) return 0.0
        if (first == second) return 1.0

        val shorter = minOf(first.length, second.length)
        val longer = maxOf(first.length, second.length)
        if (shorter < SCROLLABLE_SHORT_TEXT_LENGTH) {
            return if (first.contains(second) || second.contains(first)) {
                shorter.toDouble() / longer.toDouble()
            } else {
                0.0
            }
        }

        val firstBigrams = first.charBigrams()
        val secondBigrams = second.charBigrams()
        var overlap = 0
        firstBigrams.forEach { (bigram, count) ->
            val secondCount = secondBigrams[bigram] ?: 0
            overlap += minOf(count, secondCount)
        }

        val total = firstBigrams.values.sum() + secondBigrams.values.sum()
        return if (total == 0) 0.0 else (2.0 * overlap.toDouble()) / total.toDouble()
    }

    private fun String.normalizedForScrollableMerge(): String {
        return buildString {
            this@normalizedForScrollableMerge.forEach { char ->
                if (char.isLetterOrDigit() || char.code in CJK_UNIFIED_IDEOGRAPHS) {
                    append(char.lowercaseChar())
                }
            }
        }
    }

    private fun String.charBigrams(): Map<String, Int> {
        if (length < 2) return mapOf(this to 1)
        val counts = mutableMapOf<String, Int>()
        for (index in 0 until length - 1) {
            val bigram = substring(index, index + 2)
            counts[bigram] = (counts[bigram] ?: 0) + 1
        }
        return counts
    }

    private fun waitForScrollToSettle(
        service: ReaderAccessibilityService,
        onSettled: () -> Unit
    ) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val startTimeMs = android.os.SystemClock.uptimeMillis()

        fun check() {
            val elapsedMs = android.os.SystemClock.uptimeMillis() - startTimeMs
            val quietLongEnough = service.millisSinceLastScrollEvent() >= AUTO_SCROLL_QUIET_WINDOW_MS
            val waitedMinimum = elapsedMs >= AUTO_SCROLL_MIN_SETTLE_DELAY_MS
            if ((quietLongEnough && waitedMinimum) || elapsedMs >= AUTO_SCROLL_MAX_SETTLE_DELAY_MS) {
                onSettled()
            } else {
                handler.postDelayed({ check() }, AUTO_SCROLL_SETTLE_POLL_INTERVAL_MS)
            }
        }

        handler.postDelayed({ check() }, AUTO_SCROLL_SETTLE_POLL_INTERVAL_MS)
    }

    private fun recognizeAndRead(bitmap: Bitmap) {
        worker.execute {
            maybeSaveDebugScreenshot(bitmap)
            updateStatus("Recognizing text...")
            val result = ocrEngine?.recognize(bitmap)
            bitmap.recycle()
            result
                ?.onSuccess { output ->
                    maybeEmitDebugSnapshot(output.debugSnapshot)
                    updateRecognizedText(output.text)
                    if (output.text.isBlank()) {
                        finishWithStatus("No text found on screen.")
                    } else {
                        updateStatus("Reading aloud...")
                        val started = speakOcrOutput(output)
                        if (!started) {
                            finishWithStatus("Speech is not ready yet.")
                        }
                    }
                }
                ?.onFailure { error ->
                    finishWithStatus(error.message ?: "OCR failed.")
                } ?: finishWithStatus("OCR engine unavailable.")
        }
    }

    private fun stitchVerticalCaptures(captures: List<Bitmap>): Bitmap {
        if (captures.size == 1) return captures.first()

        val targetWidth = captures.minOf { it.width }
        val overlaps = captures.zipWithNext { previous, next ->
            findVerticalOverlap(previous, next, targetWidth)
        }
        val totalHeight = captures.withIndex().sumOf { (index, bitmap) ->
            if (index == 0) bitmap.height else (bitmap.height - overlaps[index - 1]).coerceAtLeast(1)
        }

        val stitched = Bitmap.createBitmap(targetWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(stitched)
        var top = 0
        captures.forEachIndexed { index, bitmap ->
            val sourceTop = if (index == 0) 0 else overlaps[index - 1]
            val source = Rect(0, sourceTop, targetWidth.coerceAtMost(bitmap.width), bitmap.height)
            val height = source.height()
            val destination = Rect(0, top, targetWidth, top + height)
            canvas.drawBitmap(bitmap, source, destination, null)
            top += height
        }
        return stitched
    }

    private fun findVerticalOverlap(previous: Bitmap, next: Bitmap, targetWidth: Int): Int {
        val comparableHeight = minOf(previous.height, next.height)
        val minOverlap = (comparableHeight * MIN_AUTO_SCROLL_OVERLAP_RATIO).toInt().coerceAtLeast(1)
        val maxOverlap = (comparableHeight * MAX_AUTO_SCROLL_OVERLAP_RATIO).toInt()
            .coerceAtLeast(minOverlap)
            .coerceAtMost(comparableHeight - 1)
        val fallbackOverlap = (comparableHeight * FALLBACK_AUTO_SCROLL_OVERLAP_RATIO).toInt()
            .coerceIn(minOverlap, maxOverlap)

        var bestOverlap = fallbackOverlap
        var bestScore = Double.MAX_VALUE
        var overlap = minOverlap
        while (overlap <= maxOverlap) {
            val score = overlapInkDifferenceScore(previous, next, targetWidth, overlap)
            if (score.isUsable && score.difference < bestScore) {
                bestScore = score.difference
                bestOverlap = overlap
            }
            overlap += OVERLAP_SEARCH_STEP_PX
        }

        if (bestScore == Double.MAX_VALUE) return fallbackOverlap

        val refinedMin = (bestOverlap - OVERLAP_SEARCH_STEP_PX).coerceAtLeast(minOverlap)
        val refinedMax = (bestOverlap + OVERLAP_SEARCH_STEP_PX).coerceAtMost(maxOverlap)
        var refinedOverlap = refinedMin
        while (refinedOverlap <= refinedMax) {
            val score = overlapInkDifferenceScore(previous, next, targetWidth, refinedOverlap)
            if (score.isUsable && score.difference < bestScore) {
                bestScore = score.difference
                bestOverlap = refinedOverlap
            }
            refinedOverlap += OVERLAP_REFINE_STEP_PX
        }

        return if (bestScore <= OVERLAP_MATCH_SCORE_THRESHOLD) bestOverlap else fallbackOverlap
    }

    private fun overlapInkDifferenceScore(
        previous: Bitmap,
        next: Bitmap,
        targetWidth: Int,
        overlap: Int
    ): OverlapScore {
        val width = targetWidth.coerceAtMost(previous.width).coerceAtMost(next.width)
        if (width <= 0 || overlap <= 0) return OverlapScore.unusable()

        val left = (width * OVERLAP_HORIZONTAL_MARGIN_RATIO).toInt().coerceIn(0, width - 1)
        val right = (width * (1f - OVERLAP_HORIZONTAL_MARGIN_RATIO)).toInt().coerceIn(left + 1, width)
        val sampleWidth = right - left

        var weightedDifference = 0.0
        var totalInformation = 0.0
        for (yIndex in 0 until OVERLAP_SAMPLE_ROWS) {
            val relativeY = ((yIndex + 0.5f) * overlap / OVERLAP_SAMPLE_ROWS).toInt()
                .coerceIn(0, overlap - 1)
            val previousY = (previous.height - overlap + relativeY).coerceIn(0, previous.height - 1)
            val nextY = relativeY.coerceIn(0, next.height - 1)
            for (xIndex in 0 until OVERLAP_SAMPLE_COLUMNS) {
                val x = (left + ((xIndex + 0.5f) * sampleWidth / OVERLAP_SAMPLE_COLUMNS)).toInt()
                    .coerceIn(left, right - 1)
                val first = previous.getPixel(x, previousY)
                val second = next.getPixel(x, nextY)
                val previousInk = first.inkAmount()
                val nextInk = second.inkAmount()
                val information = maxOf(previousInk, nextInk)
                weightedDifference += kotlin.math.abs(previousInk - nextInk) * (1.0 + information / 255.0)
                totalInformation += information
            }
        }

        if (totalInformation < MIN_OVERLAP_INK_INFORMATION) {
            return OverlapScore.unusable()
        }
        return OverlapScore(weightedDifference / totalInformation, isUsable = true)
    }

    private fun Int.inkAmount(): Double {
        val luminance = (0.299 * Color.red(this)) + (0.587 * Color.green(this)) + (0.114 * Color.blue(this))
        return (INK_BACKGROUND_LUMINANCE - luminance).coerceAtLeast(0.0)
    }

    private fun Bitmap.isVisuallySimilarTo(other: Bitmap): Boolean {
        val width = minOf(width, other.width)
        val height = minOf(height, other.height)
        if (width <= 0 || height <= 0) return false

        var totalDifference = 0L
        var samples = 0
        for (yIndex in 0 until SCREEN_COMPARE_GRID) {
            val y = ((yIndex + 0.5f) * height / SCREEN_COMPARE_GRID).toInt().coerceIn(0, height - 1)
            for (xIndex in 0 until SCREEN_COMPARE_GRID) {
                val x = ((xIndex + 0.5f) * width / SCREEN_COMPARE_GRID).toInt().coerceIn(0, width - 1)
                val first = getPixel(x, y)
                val second = other.getPixel(x, y)
                totalDifference += kotlin.math.abs(Color.red(first) - Color.red(second))
                totalDifference += kotlin.math.abs(Color.green(first) - Color.green(second))
                totalDifference += kotlin.math.abs(Color.blue(first) - Color.blue(second))
                samples += 3
            }
        }

        val averageDifference = totalDifference.toDouble() / samples.toDouble()
        return averageDifference < SCREEN_SIMILARITY_THRESHOLD
    }

    fun stopSpeaking() {
        currentPlayback = null
        pausedSegmentIndex = 0
        speechManager?.stop()
        emitDebugSnapshot(null)
        updateState(ReaderState.IDLE)
        updateStatus("Speech stopped.")
    }

    fun pauseSpeaking() {
        val playback = currentPlayback
        if (state != ReaderState.SPEAKING || playback == null) {
            updateStatus("Nothing is currently reading.")
            return
        }
        pausedSegmentIndex = playback.currentSegmentIndex
        updateState(ReaderState.PAUSED)
        speechManager?.pausePlayback()
        updateStatus("Reading paused.")
    }

    fun resumeSpeaking() {
        val playback = currentPlayback
        if (playback == null) {
            finishWithStatus("No paused reading to resume.")
            return
        }
        val started = speakPlaybackFrom(playback, pausedSegmentIndex)
        if (started) {
            updateStatus("Reading resumed.")
            playback.output.readSegments.getOrNull(pausedSegmentIndex)?.let { segment ->
                emitReadingHighlight(playback.output, segment)
            }
        } else {
            finishWithStatus("Could not resume speech.")
        }
    }

    fun readDemoText() {
        val started = speechManager?.speak("屏幕朗读测试。请先确认中文语音可以正常播放。") == true
        if (started) {
            updateStatus("Playing demo speech.")
        }
    }

    fun getUiStatus(): String = lastStatus

    fun getLastRecognizedText(): String = lastRecognizedText

    fun reportStatus(message: String) {
        updateStatus(message)
    }

    fun setOcrDebugModeEnabled(enabled: Boolean) {
        if (!enabled) {
            emitDebugSnapshot(null)
        }
    }

    fun setHighlightReadingLineEnabled(enabled: Boolean) {
        if (!enabled) {
            emitDebugSnapshot(null)
        }
    }

    fun isSpeaking(): Boolean = state == ReaderState.SPEAKING

    fun isPaused(): Boolean = state == ReaderState.PAUSED

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

    fun addCaptureOverlayListener(listener: CaptureOverlayListener) {
        captureOverlayListeners.add(listener)
    }

    fun removeCaptureOverlayListener(listener: CaptureOverlayListener) {
        captureOverlayListeners.remove(listener)
    }

    private fun finishWithStatus(message: String) {
        currentPlayback = null
        pausedSegmentIndex = 0
        updateState(ReaderState.IDLE)
        updateStatus(message)
    }

    private fun updateStatus(message: String) {
        lastStatus = message
    }

    private fun updateRecognizedText(text: String) {
        lastRecognizedText = text.ifBlank { "No text found on screen." }
    }

    private fun updateState(newState: ReaderState) {
        state = newState
        listeners.forEach { it.onStateChanged(newState) }
    }

    private fun onSpeechFinished() {
        if (state == ReaderState.SPEAKING) {
            emitDebugSnapshot(null)
            currentPlayback = null
            pausedSegmentIndex = 0
            updateState(ReaderState.IDLE)
            updateStatus("Ready for the next read.")
        }
    }

    private fun speakOcrOutput(output: OcrOutput): Boolean {
        if (output.readSegments.isEmpty()) {
            return speechManager?.speak(output.text) == true
        }

        val playback = PlaybackSession(output = output, segmentTexts = output.readSegments.map { it.text })
        currentPlayback = playback
        pausedSegmentIndex = 0
        val started = speakPlaybackFrom(playback, 0)
        if (started) {
            output.readSegments.firstOrNull()?.let { segment ->
                emitReadingHighlight(output, segment)
            }
        }
        return started
    }

    private fun speakPlaybackFrom(playback: PlaybackSession, startIndex: Int): Boolean {
        val safeStartIndex = startIndex.coerceIn(0, playback.segmentTexts.lastIndex.coerceAtLeast(0))
        return speechManager?.speakSegmentsFrom(playback.segmentTexts, safeStartIndex) { index ->
            playback.currentSegmentIndex = index
            pausedSegmentIndex = index
            playback.output.readSegments.getOrNull(index)?.let { segment ->
                emitReadingHighlight(playback.output, segment)
            }
        } == true
    }

    private fun emitReadingHighlight(output: OcrOutput, start: Int, end: Int) {
        val context = appContext ?: return
        if (!AppPreferences.isHighlightReadingLineEnabled(context)) return
        if (AppPreferences.isAutoScrollCaptureEnabled(context)) return

        val baseSnapshot = output.debugSnapshot ?: return
        val activeSegment = output.readSegments.findBestSegment(start, end) ?: return
        val includeDebugBoxes = AppPreferences.isOcrDebugModeEnabled(context)
        emitDebugSnapshot(
            OcrDebugSnapshot(
                imageWidth = baseSnapshot.imageWidth,
                imageHeight = baseSnapshot.imageHeight,
                lineBounds = if (includeDebugBoxes) baseSnapshot.lineBounds else emptyList(),
                paragraphBounds = if (includeDebugBoxes) baseSnapshot.paragraphBounds else emptyList(),
                sourceLabel = "reading highlight",
                referenceParagraphBounds = if (includeDebugBoxes) baseSnapshot.referenceParagraphBounds else emptyList(),
                activeReadBounds = activeSegment.bounds
            )
        )
    }

    private fun emitReadingHighlight(output: OcrOutput, activeSegment: OcrReadSegment) {
        val context = appContext ?: return
        if (!AppPreferences.isHighlightReadingLineEnabled(context)) return
        if (AppPreferences.isAutoScrollCaptureEnabled(context)) return

        val baseSnapshot = output.debugSnapshot ?: return
        val includeDebugBoxes = AppPreferences.isOcrDebugModeEnabled(context)
        emitDebugSnapshot(
            OcrDebugSnapshot(
                imageWidth = baseSnapshot.imageWidth,
                imageHeight = baseSnapshot.imageHeight,
                lineBounds = if (includeDebugBoxes) baseSnapshot.lineBounds else emptyList(),
                paragraphBounds = if (includeDebugBoxes) baseSnapshot.paragraphBounds else emptyList(),
                sourceLabel = "reading highlight",
                referenceParagraphBounds = if (includeDebugBoxes) baseSnapshot.referenceParagraphBounds else emptyList(),
                activeReadBounds = activeSegment.bounds
            )
        )
    }

    private fun maybeEmitDebugSnapshot(snapshot: OcrDebugSnapshot?) {
        val context = appContext ?: return
        if (AppPreferences.isOcrDebugModeEnabled(context)) {
            emitDebugSnapshot(snapshot)
        } else {
            emitDebugSnapshot(null)
        }
    }

    private fun maybeSaveDebugScreenshot(bitmap: android.graphics.Bitmap) {
        val context = appContext ?: return
        if (!AppPreferences.isSaveDebugScreenshotsEnabled(context)) return
        DebugImageStore.saveCapturedScreenshot(context, bitmap)
            .onSuccess { file ->
                updateStatus("Saved debug screenshot: ${file.absolutePath}")
            }
            .onFailure { error ->
                updateStatus(error.message ?: "Failed to save debug screenshot.")
            }
    }

    private fun emitDebugSnapshot(snapshot: OcrDebugSnapshot?) {
        debugListeners.forEach { it.onDebugSnapshot(snapshot) }
    }

    private fun prepareOverlaysForCapture(onReady: () -> Unit) {
        captureOverlayListeners.forEach { it.onBeforeScreenCapture() }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed(onReady, OVERLAY_HIDE_BEFORE_CAPTURE_DELAY_MS)
    }

    private fun restoreOverlaysAfterCapture() {
        captureOverlayListeners.forEach { it.onAfterScreenCapture() }
    }

    private fun List<OcrReadSegment>.findBestSegment(start: Int, end: Int): OcrReadSegment? {
        return firstOrNull { segment ->
            start < segment.endIndex && end > segment.startIndex
        } ?: minByOrNull { segment ->
            kotlin.math.abs(segment.startIndex - start)
        }
    }

    interface StateListener {
        fun onStateChanged(state: ReaderState)
    }

    interface DebugListener {
        fun onDebugSnapshot(snapshot: OcrDebugSnapshot?)
    }

    interface CaptureOverlayListener {
        fun onBeforeScreenCapture()
        fun onAfterScreenCapture()
    }

    enum class ReaderState {
        IDLE,
        PROCESSING,
        SPEAKING,
        PAUSED
    }

    private data class PlaybackSession(
        val output: OcrOutput,
        val segmentTexts: List<String>,
        @Volatile var currentSegmentIndex: Int = 0
    )

    private data class ScrollableOcrOutput(
        val output: OcrOutput,
        val captureIndex: Int,
        val captureCount: Int
    )

    private data class OverlapScore(
        val difference: Double,
        val isUsable: Boolean
    ) {
        companion object {
            fun unusable(): OverlapScore = OverlapScore(Double.MAX_VALUE, isUsable = false)
        }
    }

    private val SCROLLABLE_TEXT_BREAK_CHARS = setOf('。', '！', '？', '.', '!', '?', '；', ';')
    private val CJK_UNIFIED_IDEOGRAPHS = 0x4E00..0x9FFF

    private const val OVERLAY_HIDE_BEFORE_CAPTURE_DELAY_MS = 120L
    private const val AUTO_SCROLL_MIN_SETTLE_DELAY_MS = 500L
    private const val AUTO_SCROLL_QUIET_WINDOW_MS = 500L
    private const val AUTO_SCROLL_MAX_SETTLE_DELAY_MS = 2200L
    private const val AUTO_SCROLL_SETTLE_POLL_INTERVAL_MS = 100L
    private const val FALLBACK_AUTO_SCROLL_OVERLAP_RATIO = 0.35f
    private const val MIN_AUTO_SCROLL_OVERLAP_RATIO = 0.18f
    private const val MAX_AUTO_SCROLL_OVERLAP_RATIO = 0.82f
    private const val OVERLAP_SEARCH_STEP_PX = 32
    private const val OVERLAP_REFINE_STEP_PX = 4
    private const val OVERLAP_SAMPLE_ROWS = 28
    private const val OVERLAP_SAMPLE_COLUMNS = 32
    private const val OVERLAP_HORIZONTAL_MARGIN_RATIO = 0.06f
    private const val INK_BACKGROUND_LUMINANCE = 245.0
    private const val MIN_OVERLAP_INK_INFORMATION = 160.0
    private const val OVERLAP_MATCH_SCORE_THRESHOLD = 0.42
    private const val SCREEN_COMPARE_GRID = 8
    private const val SCREEN_SIMILARITY_THRESHOLD = 2.5
    private const val SCROLLABLE_FALLBACK_SEGMENT_CHARS = 70
    private const val SCROLLABLE_MAX_OVERLAP_SEGMENTS = 8
    private const val SCROLLABLE_RECENT_DUPLICATE_LOOKBACK = 12
    private const val SCROLLABLE_OVERLAP_SIMILARITY = 0.74
    private const val SCROLLABLE_DUPLICATE_SIMILARITY = 0.88
    private const val SCROLLABLE_CAPTURE_DUPLICATE_SIMILARITY = 0.82
    private const val SCROLLABLE_CAPTURE_DUPLICATE_MIN_CHARS = 24
    private const val SCROLLABLE_CAPTURE_DUPLICATE_EXTRA_LOOKBACK = 8
    private const val SCROLLABLE_END_NOVELTY_CAPTURE_COUNT = 3
    private const val SCROLLABLE_END_NOVELTY_LOOKBACK = 18
    private const val SCROLLABLE_END_LOW_NOVELTY_RATIO = 0.45
    private const val SCROLLABLE_END_NOVELTY_MIN_CHARS = 8
    private const val SCROLLABLE_END_SEGMENT_DUPLICATE_SIMILARITY = 0.80
    private const val SCROLLABLE_SHORT_TEXT_LENGTH = 8
    private const val SCROLLABLE_SAFE_EDGE_RATIO = 0.12f
    private const val SCROLLABLE_SAFE_EDGE_MIN_PX = 120
}

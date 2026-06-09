package com.screenreader.app.runtime

import android.media.AudioAttributes
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class SpeechManager(
    private val context: Context,
    private val onStatusChanged: (String) -> Unit,
    private val onSpeechStarted: () -> Unit,
    private val onSpeechFinished: () -> Unit
) : TextToSpeech.OnInitListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var textToSpeech: TextToSpeech? = null
    private val candidateLocales = listOf(
        Locale.SIMPLIFIED_CHINESE,
        Locale.CHINA,
        Locale.CHINESE,
        Locale("zh", "CN"),
        Locale.getDefault()
    )

    @Volatile
    private var ready = false
    @Volatile
    private var initialized = false
    @Volatile
    private var initAttempted = false
    @Volatile
    private var engineLabel: String = "Unknown"
    @Volatile
    private var selectedLocale: Locale? = null
    @Volatile
    private var currentRangeListener: ((Int, Int) -> Unit)? = null
    @Volatile
    private var currentSegmentStartListener: ((Int) -> Unit)? = null
    @Volatile
    private var finalUtteranceId: String? = null
    @Volatile
    private var pendingRetry: (() -> Unit)? = null

    private val sequenceCounter = AtomicInteger(0)

    init {
        createTextToSpeech()
    }

    override fun onInit(status: Int) {
        initAttempted = true
        val tts = textToSpeech ?: run {
            initialized = false
            onStatusChanged("Text-to-speech failed to initialize. Engine: Unknown")
            return
        }
        if (status == TextToSpeech.SUCCESS) {
            initialized = true
            engineLabel = tts.defaultEngine ?: "Unknown"
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    utteranceId?.parseSegmentIndex()?.let { index ->
                        currentSegmentStartListener?.invoke(index)
                    }
                    onSpeechStarted()
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == finalUtteranceId) {
                        clearSpeechCallbacks()
                        onSpeechFinished()
                    }
                }

                override fun onError(utteranceId: String?) {
                    clearSpeechCallbacks()
                    onSpeechFinished()
                    onStatusChanged("Speech playback failed.")
                }

                override fun onRangeStart(
                    utteranceId: String?,
                    start: Int,
                    end: Int,
                    frame: Int
                ) {
                    currentRangeListener?.invoke(start, end)
                }
            })
            ready = configureBestChineseLocale()
            onStatusChanged(
                if (ready) {
                    "Chinese speech ready. Engine: $engineLabel. Locale: ${selectedLocale?.toLanguageTag() ?: "unknown"}"
                } else {
                    "Chinese speech fallback failed. Engine: $engineLabel. Default locale: ${Locale.getDefault().toLanguageTag()}"
                }
            )
            if (ready) {
                val retry = pendingRetry
                pendingRetry = null
                if (retry != null) {
                    mainHandler.postDelayed(retry, TTS_RETRY_AFTER_INIT_DELAY_MS)
                }
            } else if (pendingRetry != null) {
                pendingRetry = null
                notifySpeechCouldNotStart()
            }
        } else {
            initialized = false
            val hadPendingRetry = pendingRetry != null
            pendingRetry = null
            onStatusChanged("Text-to-speech failed to initialize. Engine: ${tts.defaultEngine ?: "Unknown"} Error: $status")
            if (hadPendingRetry) {
                notifySpeechCouldNotStart()
            }
        }
    }

    fun speak(text: String): Boolean {
        return speak(text, onRangeStart = null)
    }

    fun speak(text: String, onRangeStart: ((Int, Int) -> Unit)?): Boolean {
        return speakInternal(text, onRangeStart, allowEngineReset = true)
    }

    private fun speakInternal(
        text: String,
        onRangeStart: ((Int, Int) -> Unit)?,
        allowEngineReset: Boolean
    ): Boolean {
        if (!ready) {
            onStatusChanged(
                if (!initAttempted) {
                    "Speech engine is still initializing. Wait a moment and try again."
                } else if (!initialized) {
                    "Speech engine initialization failed. Engine: $engineLabel"
                } else {
                    "Speech is not ready. Engine: $engineLabel. Check Xiaomi TTS settings and Chinese voice."
                }
            )
            return false
        }
        val tts = textToSpeech ?: return resetSpeechEngineAndRetry(
            message = "Speech engine is unavailable. Engine: $engineLabel.",
            retry = { speakInternal(text, onRangeStart, allowEngineReset = false) }
        )

        tts.stop()
        currentRangeListener = onRangeStart
        currentSegmentStartListener = null
        finalUtteranceId = SINGLE_UTTERANCE_ID
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, SINGLE_UTTERANCE_ID)
        if (result == TextToSpeech.ERROR) {
            clearSpeechCallbacks()
            if (allowEngineReset) {
                return resetSpeechEngineAndRetry(
                    message = "Speech engine rejected playback. Engine: $engineLabel.",
                    retry = { speakInternal(text, onRangeStart, allowEngineReset = false) }
                )
            }
            onStatusChanged("Speech engine rejected playback after reset. Engine: $engineLabel")
            notifySpeechCouldNotStart()
            return false
        }
        return result == TextToSpeech.SUCCESS
    }

    fun speakSegments(segments: List<String>, onSegmentStart: (Int) -> Unit): Boolean {
        return speakSegmentsInternal(segments, onSegmentStart, allowEngineReset = true)
    }

    private fun speakSegmentsInternal(
        segments: List<String>,
        onSegmentStart: (Int) -> Unit,
        allowEngineReset: Boolean
    ): Boolean {
        if (!ready) {
            onStatusChanged(
                if (!initAttempted) {
                    "Speech engine is still initializing. Wait a moment and try again."
                } else if (!initialized) {
                    "Speech engine initialization failed. Engine: $engineLabel"
                } else {
                    "Speech is not ready. Engine: $engineLabel. Check Xiaomi TTS settings and Chinese voice."
                }
            )
            return false
        }

        val speakableSegments = segments.map { it.trim() }.filter { it.isNotEmpty() }
        if (speakableSegments.isEmpty()) return false

        val tts = textToSpeech ?: return resetSpeechEngineAndRetry(
            message = "Speech engine is unavailable. Engine: $engineLabel.",
            retry = { speakSegmentsInternal(segments, onSegmentStart, allowEngineReset = false) }
        )

        tts.stop()
        currentRangeListener = null
        currentSegmentStartListener = onSegmentStart

        val sequence = sequenceCounter.incrementAndGet()
        finalUtteranceId = segmentUtteranceId(sequence, speakableSegments.lastIndex)

        var accepted = true
        speakableSegments.forEachIndexed { index, segment ->
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = tts.speak(segment, queueMode, null, segmentUtteranceId(sequence, index))
            if (result == TextToSpeech.ERROR) {
                accepted = false
            }
        }

        if (!accepted) {
            clearSpeechCallbacks()
            if (allowEngineReset) {
                return resetSpeechEngineAndRetry(
                    message = "Speech engine rejected segmented playback. Engine: $engineLabel.",
                    retry = { speakSegmentsInternal(segments, onSegmentStart, allowEngineReset = false) }
                )
            }
            onStatusChanged("Speech engine rejected segmented playback after reset. Engine: $engineLabel")
            notifySpeechCouldNotStart()
        }
        return accepted
    }

    fun speakSegmentsFrom(
        segments: List<String>,
        startIndex: Int,
        onSegmentStart: (Int) -> Unit
    ): Boolean {
        return speakSegmentsFromInternal(segments, startIndex, onSegmentStart, allowEngineReset = true)
    }

    private fun speakSegmentsFromInternal(
        segments: List<String>,
        startIndex: Int,
        onSegmentStart: (Int) -> Unit,
        allowEngineReset: Boolean
    ): Boolean {
        if (!ready) {
            onStatusChanged(
                if (!initAttempted) {
                    "Speech engine is still initializing. Wait a moment and try again."
                } else if (!initialized) {
                    "Speech engine initialization failed. Engine: $engineLabel"
                } else {
                    "Speech is not ready. Engine: $engineLabel. Check Xiaomi TTS settings and Chinese voice."
                }
            )
            return false
        }

        val indexedSegments = segments
            .mapIndexed { index, text -> index to text.trim() }
            .filter { (index, text) -> index >= startIndex && text.isNotEmpty() }
        if (indexedSegments.isEmpty()) return false

        val tts = textToSpeech ?: return resetSpeechEngineAndRetry(
            message = "Speech engine is unavailable. Engine: $engineLabel.",
            retry = { speakSegmentsFromInternal(segments, startIndex, onSegmentStart, allowEngineReset = false) }
        )

        tts.stop()
        currentRangeListener = null
        currentSegmentStartListener = onSegmentStart

        val sequence = sequenceCounter.incrementAndGet()
        finalUtteranceId = segmentUtteranceId(sequence, indexedSegments.last().first)

        var accepted = true
        indexedSegments.forEachIndexed { queueIndex, (originalIndex, segment) ->
            val queueMode = if (queueIndex == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = tts.speak(segment, queueMode, null, segmentUtteranceId(sequence, originalIndex))
            if (result == TextToSpeech.ERROR) {
                accepted = false
            }
        }

        if (!accepted) {
            clearSpeechCallbacks()
            if (allowEngineReset) {
                return resetSpeechEngineAndRetry(
                    message = "Speech engine rejected segmented playback. Engine: $engineLabel.",
                    retry = { speakSegmentsFromInternal(segments, startIndex, onSegmentStart, allowEngineReset = false) }
                )
            }
            onStatusChanged("Speech engine rejected segmented playback after reset. Engine: $engineLabel")
            notifySpeechCouldNotStart()
        }
        return accepted
    }

    fun stop() {
        clearSpeechCallbacks()
        textToSpeech?.stop()
        onSpeechFinished()
    }

    fun pausePlayback() {
        clearSpeechCallbacks()
        textToSpeech?.stop()
    }

    private fun clearSpeechCallbacks() {
        currentRangeListener = null
        currentSegmentStartListener = null
        finalUtteranceId = null
    }

    private fun notifySpeechCouldNotStart() {
        onSpeechStarted()
        onSpeechFinished()
    }

    private fun createTextToSpeech() {
        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            mainHandler.post {
                onInit(status)
            }
        }
    }

    private fun resetSpeechEngineAndRetry(
        message: String,
        retry: () -> Unit
    ): Boolean {
        clearSpeechCallbacks()
        ready = false
        initialized = false
        initAttempted = false
        selectedLocale = null
        pendingRetry = retry
        onStatusChanged("$message Resetting speech engine...")
        runCatching {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        }
        textToSpeech = null
        createTextToSpeech()
        return true
    }

    private fun segmentUtteranceId(sequence: Int, index: Int): String {
        return "$SEGMENT_UTTERANCE_PREFIX$sequence-$index"
    }

    private fun String.parseSegmentIndex(): Int? {
        if (!startsWith(SEGMENT_UTTERANCE_PREFIX)) return null
        return substringAfterLast('-', missingDelimiterValue = "").toIntOrNull()
    }

    private fun configureBestChineseLocale(): Boolean {
        val tts = textToSpeech ?: return false
        for (locale in candidateLocales.distinctBy { it.toLanguageTag() }) {
            if (!locale.language.equals("zh", ignoreCase = true)) {
                continue
            }
            val availability = tts.isLanguageAvailable(locale)
            if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
                continue
            }

            val setResult = tts.setLanguage(locale)
            if (setResult != TextToSpeech.LANG_MISSING_DATA && setResult != TextToSpeech.LANG_NOT_SUPPORTED) {
                selectedLocale = locale
                return true
            }
        }

        val voiceLocale = tts.voice?.locale
        if (voiceLocale != null && voiceLocale.language.equals("zh", ignoreCase = true)) {
            selectedLocale = voiceLocale
            return true
        }

        val defaultLocale = Locale.getDefault()
        if (defaultLocale.language.equals("zh", ignoreCase = true)) {
            val setResult = tts.setLanguage(defaultLocale)
            if (setResult != TextToSpeech.LANG_MISSING_DATA && setResult != TextToSpeech.LANG_NOT_SUPPORTED) {
                selectedLocale = defaultLocale
                return true
            }
        }

        val availableVoice = tts.voices
            ?.firstOrNull { voice ->
                !voice.isNetworkConnectionRequired &&
                    voice.locale?.language.equals("zh", ignoreCase = true)
            }

        if (availableVoice != null) {
            val voiceSet = runCatching { tts.voice = availableVoice }.isSuccess
            if (voiceSet) {
                selectedLocale = availableVoice.locale
                return true
            }
        }

        return false
    }

    companion object {
        private const val SINGLE_UTTERANCE_ID = "screen-reader-tts"
        private const val SEGMENT_UTTERANCE_PREFIX = "screen-reader-segment-"
        private const val TTS_RETRY_AFTER_INIT_DELAY_MS = 250L
    }
}

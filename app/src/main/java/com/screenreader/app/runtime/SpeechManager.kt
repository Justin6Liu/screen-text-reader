package com.screenreader.app.runtime

import android.media.AudioAttributes
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class SpeechManager(
    context: Context,
    private val onStatusChanged: (String) -> Unit,
    private val onSpeechStarted: () -> Unit,
    private val onSpeechFinished: () -> Unit
) : TextToSpeech.OnInitListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val textToSpeech = TextToSpeech(context.applicationContext) { status ->
        mainHandler.post {
            onInit(status)
        }
    }
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

    override fun onInit(status: Int) {
        initAttempted = true
        val tts = textToSpeech
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
                    onSpeechStarted()
                }

                override fun onDone(utteranceId: String?) {
                    onSpeechFinished()
                }

                override fun onError(utteranceId: String?) {
                    onSpeechFinished()
                    onStatusChanged("Speech playback failed.")
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
        } else {
            initialized = false
            onStatusChanged("Text-to-speech failed to initialize. Engine: ${tts.defaultEngine ?: "Unknown"} Error: $status")
        }
    }

    fun speak(text: String): Boolean {
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
        textToSpeech.stop()
        val result = textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "screen-reader-tts")
        if (result == TextToSpeech.ERROR) {
            onStatusChanged("Speech engine rejected playback. Engine: $engineLabel")
            return false
        }
        return result == TextToSpeech.SUCCESS
    }

    fun stop() {
        textToSpeech.stop()
        onSpeechFinished()
    }

    private fun configureBestChineseLocale(): Boolean {
        for (locale in candidateLocales.distinctBy { it.toLanguageTag() }) {
            if (!locale.language.equals("zh", ignoreCase = true)) {
                continue
            }
            val availability = textToSpeech.isLanguageAvailable(locale)
            if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
                continue
            }

            val setResult = textToSpeech.setLanguage(locale)
            if (setResult != TextToSpeech.LANG_MISSING_DATA && setResult != TextToSpeech.LANG_NOT_SUPPORTED) {
                selectedLocale = locale
                return true
            }
        }

        val voiceLocale = textToSpeech.voice?.locale
        if (voiceLocale != null && voiceLocale.language.equals("zh", ignoreCase = true)) {
            selectedLocale = voiceLocale
            return true
        }

        val defaultLocale = Locale.getDefault()
        if (defaultLocale.language.equals("zh", ignoreCase = true)) {
            val setResult = textToSpeech.setLanguage(defaultLocale)
            if (setResult != TextToSpeech.LANG_MISSING_DATA && setResult != TextToSpeech.LANG_NOT_SUPPORTED) {
                selectedLocale = defaultLocale
                return true
            }
        }

        val availableVoice = textToSpeech.voices
            ?.firstOrNull { voice ->
                !voice.isNetworkConnectionRequired &&
                    voice.locale?.language.equals("zh", ignoreCase = true)
            }

        if (availableVoice != null) {
            val voiceSet = runCatching { textToSpeech.voice = availableVoice }.isSuccess
            if (voiceSet) {
                selectedLocale = availableVoice.locale
                return true
            }
        }

        return false
    }
}

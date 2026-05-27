package com.screenreader.app.runtime

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class SpeechManager(
    context: Context,
    private val onStatusChanged: (String) -> Unit,
    private val onSpeechStarted: () -> Unit,
    private val onSpeechFinished: () -> Unit
) : TextToSpeech.OnInitListener {

    private val textToSpeech = TextToSpeech(context.applicationContext, this)

    @Volatile
    private var ready = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech.setLanguage(Locale.SIMPLIFIED_CHINESE)
            textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
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
            ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            onStatusChanged(
                if (ready) {
                    "Chinese speech ready."
                } else {
                    "Chinese speech engine missing or unsupported on this device."
                }
            )
        } else {
            onStatusChanged("Text-to-speech failed to initialize.")
        }
    }

    fun speak(text: String): Boolean {
        if (!ready) {
            onStatusChanged("Speech is not ready yet.")
            return false
        }
        textToSpeech.stop()
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "screen-reader-tts")
        return true
    }

    fun stop() {
        textToSpeech.stop()
        onSpeechFinished()
    }
}

package com.screenreader.app.runtime

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class SpeechManager(
    context: Context,
    private val onStatusChanged: (String) -> Unit
) : TextToSpeech.OnInitListener {

    private val textToSpeech = TextToSpeech(context.applicationContext, this)

    @Volatile
    private var ready = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech.setLanguage(Locale.SIMPLIFIED_CHINESE)
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

    fun speak(text: String) {
        if (!ready) {
            onStatusChanged("Speech is not ready yet.")
            return
        }
        textToSpeech.stop()
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "screen-reader-tts")
    }

    fun stop() {
        textToSpeech.stop()
    }
}

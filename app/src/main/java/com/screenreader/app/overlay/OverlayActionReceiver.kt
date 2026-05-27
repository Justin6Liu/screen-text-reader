package com.screenreader.app.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.screenreader.app.runtime.ScreenReaderController

class OverlayActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            OverlayService.ACTION_STOP_SERVICE -> OverlayService.stop(context)
            OverlayService.ACTION_STOP_SPEECH -> ScreenReaderController.stopSpeaking()
        }
    }
}

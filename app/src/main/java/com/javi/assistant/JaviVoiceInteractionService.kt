package com.javi.assistant

import android.service.voice.VoiceInteractionService

class JaviVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        if (JaviConfig.wakeEnabled(this)) {
            try {
                JaviWakeWordService.start(this)
            } catch (_: Exception) {
            }
        }
    }

    override fun onShutdown() {
        super.onShutdown()
    }
}

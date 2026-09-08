package com.javi.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class JaviVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = JaviVoiceInteractionSession(this)
}

/**
 * Sesión deliberadamente ligera. Invocar al asistente del sistema no debe
 * lanzar MainActivity: JAVI ya escucha y ejecuta las órdenes desde su servicio.
 * Esto evita que el usuario tenga que volver a la APK o tocar la J.
 */
class JaviVoiceInteractionSession(context: android.content.Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            JaviConfig.setWakeEnabled(context, true)
            JaviWakeWordService.start(context)
        } catch (_: Exception) {
        }
        hide()
    }
}

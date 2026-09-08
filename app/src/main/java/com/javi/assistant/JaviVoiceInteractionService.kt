package com.javi.assistant

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.service.voice.VoiceInteractionService

/**
 * Servicio global del asistente J.A.V.I.
 *
 * Cuando el usuario selecciona J.A.V.I. como asistente predeterminado, Android
 * mantiene este servicio enlazado incluso aunque MainActivity no esté abierta.
 * Desde aquí arrancamos el detector/recognizer de "Javi" para que no dependa
 * del ciclo de vida de la pantalla principal.
 */
class JaviVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()

        // Al ser el asistente activo, JAVI debe quedar disponible sin abrir la APK.
        JaviConfig.setWakeEnabled(this, true)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            try {
                JaviWakeWordService.start(this)
            } catch (_: Exception) {
                // MainActivity mostrará la configuración si Android/OEM requiere
                // una intervención del usuario para conceder permisos.
            }
        }
    }

    override fun onShutdown() {
        // Si Android deja de considerar JAVI como asistente activo, detenemos la
        // escucha persistente. El usuario podrá volver a activarlo eligiendo JAVI
        // otra vez como asistente predeterminado.
        try {
            JaviWakeWordService.stop(this)
        } catch (_: Exception) {
        }
        super.onShutdown()
    }

    companion object {
        fun isSelected(context: android.content.Context): Boolean {
            return try {
                isActiveService(
                    context,
                    ComponentName(context, JaviVoiceInteractionService::class.java)
                )
            } catch (_: Exception) {
                false
            }
        }
    }
}

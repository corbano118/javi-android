package com.javi.assistant

sealed class JaviCommand {
    data object WakePc : JaviCommand()
    data class OpenApp(val appName: String) : JaviCommand()
    data class SetAlarm(val hour: Int, val minute: Int) : JaviCommand()
    data class AskCore(val text: String) : JaviCommand()
}

object CommandRouter {
    private val wakeVariants = listOf("javi", "javy", "yavi", "llavi")

    fun removeWakeWord(text: String): String {
        val raw = text.trim()
        val lower = raw.lowercase()
        val wake = wakeVariants.firstOrNull { lower.startsWith(it) } ?: return raw
        return raw.drop(wake.length).trim(' ', ',', '.', ':', ';', '-')
    }

    fun hasWakeWord(text: String): Boolean {
        val lower = text.trim().lowercase()
        return wakeVariants.any { lower.startsWith(it) }
    }

    fun route(text: String): JaviCommand {
        val cmd = removeWakeWord(text).trim()
        val lower = cmd.lowercase()

        if (Regex("\\b(activa|despierta|prende|enciende)\\b.*\\b(computador|computadora|pc)\\b").containsMatchIn(lower)) {
            return JaviCommand.WakePc
        }

        parseAlarm(lower)?.let { return it }
        parseOpenApp(cmd)?.let { return it }

        return JaviCommand.AskCore(cmd.ifBlank { text.trim() })
    }

    private fun parseOpenApp(text: String): JaviCommand.OpenApp? {
        val match = Regex(
            pattern = "^(?:abre|abrir|ábreme|abreme|inicia|iniciar|lanza|lanzar|ejecuta|ejecutar|entra\\s+a|ve\\s+a)\\s+(?:(?:la\\s+)?(?:app|aplicación|aplicacion)\\s+)?(?:(?:el|la)\\s+)?(.+)$",
            option = RegexOption.IGNORE_CASE
        ).find(text.trim()) ?: return null

        val appName = match.groupValues[1]
            .trim()
            .trim('.', ',', ';', ':', '!', '?')

        if (appName.isBlank()) return null
        return JaviCommand.OpenApp(appName)
    }

    private fun parseAlarm(text: String): JaviCommand.SetAlarm? {
        if (!(text.contains("alarma") || text.contains("despiértame") || text.contains("despiertame"))) return null
        val m = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b").find(text) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        val suffix = m.groupValues[3]
        if (suffix == "pm" && hour in 1..11) hour += 12
        if (suffix == "am" && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return JaviCommand.SetAlarm(hour, minute)
    }
}

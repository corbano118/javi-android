package com.javi.assistant

import java.text.Normalizer

sealed class JaviCommand {
    data object WakePc : JaviCommand()
    data class OpenApp(val appName: String) : JaviCommand()
    data class SetAlarm(val hour: Int, val minute: Int) : JaviCommand()
    data class AskCore(val text: String) : JaviCommand()
}

object CommandRouter {
    private val wakeVariants = listOf("javi", "javy", "yavi", "llavi", "xavi", "havi")

    fun removeWakeWord(text: String): String {
        val raw = text.trim()
        val normalized = normalize(raw)
        val wake = wakeVariants.firstOrNull { variant ->
            Regex("(?:^|\\b)${Regex.escape(variant)}(?:\\b|$)").containsMatchIn(normalized)
        } ?: return raw

        val match = Regex("(?i)(?:^|\\b)${Regex.escape(wake)}(?:\\b|$)").find(normalized) ?: return raw
        val originalStart = mapNormalizedIndexToOriginal(raw, match.range.first)
        val originalEnd = mapNormalizedIndexToOriginal(raw, match.range.last + 1)
        return (raw.take(originalStart) + " " + raw.drop(originalEnd))
            .trim(' ', ',', '.', ':', ';', '-', '!', '?')
            .replace(Regex("\\s+"), " ")
    }

    fun hasWakeWord(text: String): Boolean {
        val normalized = normalize(text)
        return wakeVariants.any { variant ->
            Regex("(?:^|\\b)${Regex.escape(variant)}(?:\\b|$)").containsMatchIn(normalized)
        }
    }

    fun route(text: String): JaviCommand {
        val cmd = removeWakeWord(text).trim()
        val lower = normalize(cmd)

        if (Regex("\\b(activa|despierta|prende|enciende)\\b.*\\b(computador|computadora|pc)\\b").containsMatchIn(lower)) {
            return JaviCommand.WakePc
        }

        parseAlarm(lower)?.let { return it }
        parseOpenApp(cmd)?.let { return it }

        return JaviCommand.AskCore(cmd.ifBlank { text.trim() })
    }

    private fun parseOpenApp(text: String): JaviCommand.OpenApp? {
        val cleaned = text.trim()
        val match = Regex(
            pattern = "^(?:abre|abrir|ábreme|abreme|inicia|iniciar|lanza|lanzar|ejecuta|ejecutar|entra\\s+a|ve\\s+a|pon|muéstrame|muestrame)\\s+(?:(?:la\\s+)?(?:app|aplicación|aplicacion)\\s+)?(?:(?:el|la)\\s+)?(.+)$",
            option = RegexOption.IGNORE_CASE
        ).find(cleaned) ?: return null

        val appName = match.groupValues[1]
            .trim()
            .trim('.', ',', ';', ':', '!', '?')

        if (appName.isBlank()) return null
        return JaviCommand.OpenApp(appName)
    }

    private fun parseAlarm(text: String): JaviCommand.SetAlarm? {
        if (!(text.contains("alarma") || text.contains("despiertame"))) return null
        val m = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b").find(text) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        val suffix = m.groupValues[3]
        if (suffix == "pm" && hour in 1..11) hour += 12
        if (suffix == "am" && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return JaviCommand.SetAlarm(hour, minute)
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun mapNormalizedIndexToOriginal(original: String, normalizedIndex: Int): Int {
        if (normalizedIndex <= 0) return 0
        var count = 0
        original.forEachIndexed { index, ch ->
            val norm = Normalizer.normalize(ch.toString().lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
            if (norm.isNotEmpty()) count += norm.length
            if (count >= normalizedIndex) return index + 1
        }
        return original.length
    }
}

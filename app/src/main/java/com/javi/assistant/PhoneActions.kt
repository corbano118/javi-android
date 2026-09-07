package com.javi.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.AlarmClock
import java.text.Normalizer

object PhoneActions {

    data class AppMatch(val label: String, val packageName: String)

    fun openAppByName(context: Context, spokenName: String): AppMatch? {
        val match = findInstalledApp(context, spokenName) ?: return null
        val launch = context.packageManager.getLaunchIntentForPackage(match.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(launch)
        return match
    }

    private fun findInstalledApp(context: Context, spokenName: String): AppMatch? {
        val pm = context.packageManager
        val wanted = normalize(spokenName)
        if (wanted.isBlank()) return null

        val apps = installedLaunchableApps(pm)
        val exact = apps.firstOrNull { normalize(it.label) == wanted }
        if (exact != null) return exact

        val aliases = mapOf(
            "whats app" to "whatsapp",
            "you tube" to "youtube",
            "tik tok" to "tiktok",
            "face book" to "facebook",
            "insta" to "instagram",
            "google maps" to "maps",
            "mapa" to "maps",
            "mapas" to "maps",
            "play store" to "google play store"
        )
        val aliased = aliases[wanted] ?: wanted
        apps.firstOrNull { normalize(it.label) == aliased }?.let { return it }

        val starts = apps.filter {
            val label = normalize(it.label)
            label.startsWith(aliased) || aliased.startsWith(label)
        }.minByOrNull { kotlin.math.abs(normalize(it.label).length - aliased.length) }
        if (starts != null) return starts

        return apps.filter {
            val label = normalize(it.label)
            label.contains(aliased) || aliased.contains(label)
        }.minByOrNull { kotlin.math.abs(normalize(it.label).length - aliased.length) }
    }

    private fun installedLaunchableApps(pm: PackageManager): List<AppMatch> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                val appInfo: ApplicationInfo = try {
                    pm.getApplicationInfo(packageName, 0)
                } catch (_: Exception) {
                    return@mapNotNull null
                }
                val label = pm.getApplicationLabel(appInfo).toString().trim()
                if (label.isBlank()) null else AppMatch(label, packageName)
            }
            .distinctBy { it.packageName }
    }

    private fun normalize(value: String): String {
        val noAccents = Normalizer.normalize(value.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return noAccents
            .replace('&', ' ')
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun setAlarm(context: Context, hour: Int, minute: Int, label: String = "J.A.V.I.") {
        val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(i)
    }
}

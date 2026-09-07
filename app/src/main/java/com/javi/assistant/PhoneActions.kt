package com.javi.assistant

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

object PhoneActions {
    fun openApp(context: Context, packageName: String): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(launch); return true
    }
    fun setAlarm(context: Context, hour: Int, minute: Int, label: String = "J.A.V.I.") {
        val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour); putExtra(AlarmClock.EXTRA_MINUTES, minute); putExtra(AlarmClock.EXTRA_MESSAGE, label); putExtra(AlarmClock.EXTRA_SKIP_UI, false); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(i)
    }
}

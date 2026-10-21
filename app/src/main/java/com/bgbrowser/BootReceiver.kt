package com.bgbrowser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.bool("bootLaunch", true)) return
        val launch = Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("noRedirect", true)
        if (Settings.canDrawOverlays(c)) {
            c.startActivity(launch)
        } else {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("boot", "Startup", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(c, 2, launch, PendingIntent.FLAG_IMMUTABLE)
            nm.notify(
                43,
                NotificationCompat.Builder(c, "boot")
                    .setSmallIcon(R.drawable.ic_wave)
                    .setContentTitle("BG Browser")
                    .setContentText("Tap to resume background browsing")
                    .setContentIntent(pi).setAutoCancel(true).build()
            )
        }
    }
}

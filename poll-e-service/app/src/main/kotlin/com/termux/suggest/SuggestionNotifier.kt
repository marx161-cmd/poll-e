package com.termux.suggest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object SuggestionNotifier {

    private const val CHANNEL_ID = "poll_e_suggestion"
    const val NOTIF_ID = 0x0E11E0

    fun init(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Poll-E Suggestion", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                }
        )
    }

    fun post(context: Context, suggestion: String) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val acceptPi = PendingIntent.getBroadcast(
            context, 0,
            Intent(PollEAccessibilityService.ACTION_ACCEPT).apply {
                setPackage(context.packageName)
                putExtra(PollEAccessibilityService.EXTRA_TEXT, suggestion)
            },
            flags
        )
        val dismissPi = PendingIntent.getBroadcast(
            context, 1,
            Intent(PollEAccessibilityService.ACTION_DISMISS).apply {
                setPackage(context.packageName)
            },
            flags
        )

        val notif = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(suggestion)
            .setShortCriticalText(suggestion)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .addAction(Notification.Action.Builder(null, "Accept", acceptPi).build())
            .addAction(Notification.Action.Builder(null, "Dismiss", dismissPi).build())
            .build()

        context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }
}

package com.termux.suggest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

object SuggestionNotifier {

    private const val CHANNEL_ID = "poll_e_answer"
    const val NOTIF_ID = 0x0E11E0

    fun init(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Poll-E Answer", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                    setSound(null, null)
                }
        )
    }

    fun postAnswer(context: Context, answer: String) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Poll-E", answer))

        val notif = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Poll-E")
            .setContentText(answer)
            .setStyle(Notification.BigTextStyle().bigText(answer))
            .setAutoCancel(true)
            .setLocalOnly(true)
            .build()

        context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }
}

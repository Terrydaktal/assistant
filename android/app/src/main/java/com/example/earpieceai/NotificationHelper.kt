package com.example.earpieceai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {
    private const val CHANNEL_ID = "earpieceai_overlay_channel"
    private const val CHANNEL_NAME = "Assistant Overlay"

    fun createNotificationId(): Int = 1001

    fun createForegroundNotification(
        ctx: Context,
        status: String = "Say kilo vesta begin, kilo vesta end, or kilo vesta stop."
    ): Notification {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("Assistant voice control")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
        return builder.build()
    }
}

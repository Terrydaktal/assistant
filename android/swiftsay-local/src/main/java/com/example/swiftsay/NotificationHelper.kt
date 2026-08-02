package com.example.swiftsay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {
    private const val CHANNEL_ID = "swiftsay_overlay_channel"
    private const val CHANNEL_NAME = "Swiftsay Overlay"

    fun createNotificationId(): Int = 1001

    fun createForegroundNotification(ctx: Context): Notification {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("Floating Recorder")
            .setContentText("Tap to record. Accessibility needed to paste.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
        return builder.build()
    }
}

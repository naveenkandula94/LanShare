
package com.example.lanshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

class ProjectionForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                getSystemService(NotificationManager::class.java)

            val channel = NotificationChannel(
                CHANNEL_ID,
                "LAN Share screen and voice",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps screen and microphone sharing active."
                setShowBadge(false)
            }

            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        return builder
            .setContentTitle("LAN Share is active")
            .setContentText("Your screen and voice sharing session is running.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        try {
            val notification = buildNotification()

            if (Build.VERSION.SDK_INT >= 30) {
                val types =
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    types
                )
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    notification
                )
            }

            return START_NOT_STICKY

        } catch (e: Exception) {
            Log.e(TAG, "Foreground service startup failed", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        super.onDestroy()
    }

    companion object {
        private const val TAG = "LanShare"
        private const val CHANNEL_ID = "lan_share_projection"
        private const val NOTIFICATION_ID = 41
    }
}

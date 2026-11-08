
package com.example.lanshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

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
                "LAN Share screen sharing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps screen sharing active."
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
            .setContentText("Your screen is being shared.")
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

            if (Build.VERSION.SDK_INT >= 34) {

                val serviceTypes =
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    serviceTypes
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

        } catch (e: Exception) {

            stopSelf()
            throw e
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {

        stopForeground(STOP_FOREGROUND_REMOVE)

        super.onDestroy()
    }

    companion object {

        private const val CHANNEL_ID =
            "lan_share_projection"

        private const val NOTIFICATION_ID = 41
    }
}

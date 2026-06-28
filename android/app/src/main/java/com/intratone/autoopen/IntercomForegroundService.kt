package com.intratone.autoopen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Foreground service that keeps the call-watching process alive when the app is in the background.
 * Without this, Android may kill the process after a few minutes, breaking call detection.
 *
 * On Android 14+ (API 34+), foreground services require a type declaration in the manifest.
 * We use `phoneCall` type (declared as FOREGROUND_SERVICE_PHONE_CALL permission)
 * since this service supports phone call interception.
 */
class IntercomForegroundService : Service() {

    companion object {
        private const val TAG = "IntercomFGService"
        private const val CHANNEL_ID = "intratone_watch_channel"
        private const val CHANNEL_NAME = "Call Watching"
        private const val NOTIFICATION_ID = 1

        /**
         * Start the foreground service. Safe to call multiple times —
         * if already running, the notification text is just updated.
         */
        fun start(context: Context) {
            val intent = Intent(context, IntercomForegroundService::class.java)
            intent.action = "START"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the foreground service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, IntercomForegroundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (BuildConfig.DEBUG) Log.d(TAG, "Foreground service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        // Create notification and start as foreground service
        val notification = buildNotification("Watching for intercom calls…")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+: startForeground with service type
                startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            // On Android 14+ this can fail if the app doesn't have the required
            // permission or isn't in a valid state. Fall back to regular start.
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "Foreground service started — call watching active")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Not a bound service
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (BuildConfig.DEBUG) Log.d(TAG, "Foreground service destroyed")
    }

    /**
     * Create the notification channel (required on Android 8.0+).
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when the app is watching for intercom calls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Build the persistent notification shown while watching.
     */
    private fun buildNotification(message: String): Notification {
        // Intent to open the app when notification is tapped
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setPriority(Notification.PRIORITY_LOW)
        }

        return builder
            .setContentTitle("Intratone Auto-Open")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)  // Cannot be swiped away
            .build()
    }

    // Foreground service type constant for Android 14+ (API 34)
    private val FOREGROUND_SERVICE_TYPE_PHONE_CALL: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL = 8
            8
        } else {
            0
        }
}

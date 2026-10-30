package com.boxweb.browser

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the process alive (foreground) while downloads are running, so they continue when the
 * user leaves the app. It holds no download logic itself: it follows [DownloadStore] and stops
 * when nothing is active any more.
 */
class DownloadService : Service(), DownloadStore.Listener {

    override fun onCreate() {
        super.onCreate()
        startForeground(
            DownloadNotifications.ID_FOREGROUND,
            DownloadNotifications.foreground(this, maxOf(1, DownloadStore.activeCount()))
        )
        DownloadStore.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DownloadStore.activeCount() == 0) stop()
        return START_NOT_STICKY
    }

    override fun onDownloadsChanged() {
        val active = DownloadStore.activeCount()
        if (active == 0) {
            stop()
        } else {
            NotificationManagerCompat.from(this).apply {
                try {
                    notify(DownloadNotifications.ID_FOREGROUND, DownloadNotifications.foreground(this@DownloadService, active))
                } catch (e: SecurityException) {
                }
            }
        }
    }

    private fun stop() {
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        DownloadStore.removeListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (e: Exception) {
                // e.g. started while the app is in the background on Android 12+; downloads still run.
                Log.w("DownloadService", "Could not start foreground service", e)
            }
        }
    }
}

/** Handles the Cancel button of a progress notification. */
class DownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_CANCEL) {
            DownloadEngine.cancel(intent.getLongExtra(EXTRA_ID, -1))
        }
    }

    companion object {
        const val ACTION_CANCEL = "com.boxweb.browser.action.CANCEL_DOWNLOAD"
        const val EXTRA_ID = "download_id"
    }
}

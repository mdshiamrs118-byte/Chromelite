package com.chromelite.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Download progress / result notifications. */
object DownloadNotifications {

    private const val CHANNEL_PROGRESS = "downloads_progress"
    private const val CHANNEL_DONE = "downloads_done"
    private const val ID_BASE = 1000
    const val ID_FOREGROUND = 999

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.dl_channel_progress), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, context.getString(R.string.dl_channel_done), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun notificationId(downloadId: Long): Int = ID_BASE + (downloadId % 100_000).toInt()

    /** Ongoing notification with a progress bar and a Cancel action. */
    fun progress(context: Context, item: DownloadItem) {
        val indeterminate = item.totalBytes <= 0
        val text = when {
            item.status == DownloadStatus.QUEUED -> context.getString(R.string.dl_starting)
            indeterminate -> Formatter.formatShortFileSize(context, item.bytesDone)
            else -> "${item.percent}%  ·  ${Formatter.formatShortFileSize(context, item.bytesDone)} / " +
                Formatter.formatShortFileSize(context, item.totalBytes)
        }
        val cancel = PendingIntent.getBroadcast(
            context, notificationId(item.id),
            Intent(context, DownloadCancelReceiver::class.java)
                .setAction(DownloadCancelReceiver.ACTION_CANCEL)
                .putExtra(DownloadCancelReceiver.EXTRA_ID, item.id),
            flags()
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(item.name)
            .setContentText(text)
            .setProgress(100, item.percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openDownloads(context))
            .addAction(0, context.getString(android.R.string.cancel), cancel)
            .build()
        notify(context, notificationId(item.id), notification)
    }

    /** Final notification: complete, or failed. A canceled download just removes its notification. */
    fun finished(context: Context, item: DownloadItem) {
        val id = notificationId(item.id)
        when (item.status) {
            DownloadStatus.DONE -> notify(
                context, id,
                NotificationCompat.Builder(context, CHANNEL_DONE)
                    .setSmallIcon(R.drawable.ic_download_done)
                    .setContentTitle(item.name)
                    .setContentText(
                        context.getString(R.string.dl_complete, Formatter.formatShortFileSize(context, item.bytesDone))
                    )
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setContentIntent(openDownloads(context))
                    .build()
            )
            DownloadStatus.FAILED -> notify(
                context, id,
                NotificationCompat.Builder(context, CHANNEL_DONE)
                    .setSmallIcon(R.drawable.ic_download)
                    .setContentTitle(item.name)
                    .setContentText(context.getString(R.string.dl_failed, item.error ?: "?"))
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .setContentIntent(openDownloads(context))
                    .build()
            )
            else -> NotificationManagerCompat.from(context).cancel(id)
        }
    }

    /** The notification required while the download service runs in the foreground. */
    fun foreground(context: Context, activeCount: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.resources.getQuantityString(R.plurals.dl_active, activeCount, activeCount))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openDownloads(context))
            .build()

    private fun notify(context: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Notifications blocked by the user: downloads still work, just silently.
        }
    }

    private fun openDownloads(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, DownloadsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags()
        )

    private fun flags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
}

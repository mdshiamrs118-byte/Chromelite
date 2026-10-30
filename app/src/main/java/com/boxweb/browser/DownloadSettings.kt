package com.boxweb.browser

import android.content.Context
import android.os.Environment
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** Where finished downloads are stored. */
object DownloadSettings {

    enum class Destination {
        /** The system's public Downloads folder (Environment.DIRECTORY_DOWNLOADS). */
        PUBLIC,

        /** A folder named after the app, in the app's own storage. */
        APP
    }

    private const val PREFS = "download_settings"
    private const val KEY_DESTINATION = "destination"

    fun destination(context: Context): Destination =
        runCatching {
            Destination.valueOf(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_DESTINATION, Destination.PUBLIC.name)!!
            )
        }.getOrDefault(Destination.PUBLIC)

    fun setDestination(context: Context, destination: Destination) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DESTINATION, destination.name).apply()
    }

    /** `<app storage>/<app name>/` (falls back to internal storage if external is unavailable). */
    fun appFolder(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, context.getString(R.string.app_name))
    }

    @Suppress("DEPRECATION")
    fun publicFolder(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    fun describe(context: Context, destination: Destination = destination(context)): String =
        when (destination) {
            Destination.PUBLIC -> publicFolder().path
            Destination.APP -> appFolder(context).path
        }

    /** Settings panel: choose the download destination. */
    fun showDialog(activity: AppCompatActivity) {
        val options = Destination.values()
        val labels = options.map {
            val title = when (it) {
                Destination.PUBLIC -> activity.getString(R.string.dl_dest_public)
                Destination.APP -> activity.getString(R.string.dl_dest_app, activity.getString(R.string.app_name))
            }
            "$title\n${describe(activity, it)}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.dl_settings_title)
            .setSingleChoiceItems(labels, options.indexOf(destination(activity))) { dialog, which ->
                setDestination(activity, options[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

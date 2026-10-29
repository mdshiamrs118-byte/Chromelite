package com.chromelite.browser

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** Where bytes of one download are written, and how it is finalized. */
interface DownloadSink {
    val output: OutputStream

    /** Closes the file and makes it visible. */
    fun commit(): Committed

    /** Closes and deletes the partial file. */
    fun abort()

    class Committed(val uri: String, val displayName: String)
}

object DownloadStorage {

    /**
     * Opens a destination for a new file according to the user's setting:
     *  - public Downloads on Android 10+ -> MediaStore.Downloads (no permission needed)
     *  - public Downloads before Android 10 -> plain file in /Download (needs WRITE_EXTERNAL_STORAGE)
     *  - app folder -> plain file in the app's own storage
     */
    fun open(context: Context, fileName: String, mimeType: String): DownloadSink {
        val name = sanitize(fileName)
        val mime = mimeType.ifBlank { guessMime(name) }
        return when (DownloadSettings.destination(context)) {
            DownloadSettings.Destination.PUBLIC ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStoreSink(context, name, mime)
                } else {
                    FileSink(context, DownloadSettings.publicFolder(), name, mime, scanAfter = true)
                }
            DownloadSettings.Destination.APP ->
                FileSink(context, DownloadSettings.appFolder(context), name, mime, scanAfter = false)
        }
    }

    /** True when saving needs the (pre-Android 10) storage permission and it is not granted yet. */
    fun needsLegacyPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            DownloadSettings.destination(context) == DownloadSettings.Destination.PUBLIC &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- names / types

    fun sanitize(raw: String): String {
        var name = raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
        if (name.isEmpty()) name = "download"
        if (name.length > MAX_NAME) {
            val dot = name.lastIndexOf('.')
            name = if (dot > 0 && name.length - dot <= 10) {
                name.take(MAX_NAME - (name.length - dot)) + name.substring(dot)
            } else {
                name.take(MAX_NAME)
            }
        }
        return name
    }

    fun guessMime(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    // ---------------------------------------------------------------- finished files

    /** A URI other apps can open (content:// as is, plain paths through the FileProvider). */
    fun shareableUri(context: Context, item: DownloadItem): Uri? {
        val stored = item.uri ?: return null
        return if (stored.startsWith("content://")) {
            Uri.parse(stored)
        } else {
            runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(stored))
            }.getOrNull()
        }
    }

    fun exists(context: Context, item: DownloadItem): Boolean {
        val stored = item.uri ?: return false
        return if (stored.startsWith("content://")) {
            runCatching {
                context.contentResolver.openFileDescriptor(Uri.parse(stored), "r")?.use { true } ?: false
            }.getOrDefault(false)
        } else {
            File(stored).exists()
        }
    }

    fun deleteFile(context: Context, item: DownloadItem): Boolean {
        val stored = item.uri ?: return false
        return runCatching {
            if (stored.startsWith("content://")) {
                context.contentResolver.delete(Uri.parse(stored), null, null) > 0
            } else {
                File(stored).delete()
            }
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- sinks

    @RequiresApi(Build.VERSION_CODES.Q)
    private class MediaStoreSink(context: Context, name: String, mime: String) : DownloadSink {
        private val resolver = context.applicationContext.contentResolver
        private val uri: Uri
        override val output: OutputStream

        init {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create the file in Downloads")
            output = resolver.openOutputStream(uri) ?: run {
                resolver.delete(uri, null, null)
                throw IOException("Could not open the file for writing")
            }
        }

        override fun commit(): DownloadSink.Committed {
            output.close()
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            // The system may have renamed the file to avoid a clash ("name (1).ext").
            val finalName = runCatching {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull()
            return DownloadSink.Committed(uri.toString(), finalName ?: "")
        }

        override fun abort() {
            runCatching { output.close() }
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    private class FileSink(
        private val context: Context,
        dir: File,
        name: String,
        private val mime: String,
        private val scanAfter: Boolean
    ) : DownloadSink {

        private val target: File
        private val temp: File
        override val output: OutputStream

        init {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create folder ${dir.path}")
            target = uniqueFile(dir, name)
            temp = File(dir, target.name + ".part")
            output = FileOutputStream(temp)
        }

        override fun commit(): DownloadSink.Committed {
            output.close()
            if (!temp.renameTo(target)) throw IOException("Could not finish the file")
            if (scanAfter) {
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
            }
            return DownloadSink.Committed(target.absolutePath, target.name)
        }

        override fun abort() {
            runCatching { output.close() }
            temp.delete()
        }

        private fun uniqueFile(dir: File, name: String): File {
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            var candidate = File(dir, name)
            var n = 1
            while (candidate.exists() || File(dir, candidate.name + ".part").exists()) {
                candidate = File(dir, "$base ($n)$ext")
                n++
            }
            return candidate
        }
    }

    private const val MAX_NAME = 120
}

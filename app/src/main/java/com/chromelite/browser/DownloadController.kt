package com.chromelite.browser

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

/**
 * Activity-side glue for downloads: decides how a request is handled (http / data / blob),
 * asks for the legacy storage permission when needed, and shows the long-press menu.
 */
class DownloadController(
    private val activity: AppCompatActivity,
    private val profiles: ProfileManager,
    private val snackbarAnchor: View
) {

    private var pending: (() -> Unit)? = null

    // Must be registered while the activity is being created.
    private val permissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pending
            pending = null
            if (granted) {
                action?.invoke()
            } else {
                Snackbar.make(snackbarAnchor, R.string.dl_permission_denied, Snackbar.LENGTH_LONG)
                    .setAction(R.string.dl_settings_action) { DownloadSettings.showDialog(activity) }
                    .show()
            }
        }

    // ---------------------------------------------------------------- entry points

    /** WebView's DownloadListener: a link the page cannot display (or marked as a download). */
    fun onDownloadRequested(
        pane: BrowserPane,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        whenStorageReady { start(pane, url, userAgent, contentDisposition, mimeType, contentLength) }
    }

    /** Long-press menu for images and links. */
    fun onLongPress(pane: BrowserPane, hitType: Int, target: String) {
        val isImage = hitType == WebView.HitTestResult.IMAGE_TYPE ||
            hitType == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        val isLink = hitType == WebView.HitTestResult.SRC_ANCHOR_TYPE

        val entries = ArrayList<Pair<String, () -> Unit>>()
        if (isImage) {
            entries += activity.getString(R.string.dl_save_image) to {
                whenStorageReady { start(pane, target, null, null, null, -1) }
            }
            if (UrlUtils.isWebUrl(target)) {
                entries += activity.getString(R.string.dl_copy_image_address) to { copy(target) }
            }
        }
        if (isLink) {
            entries += activity.getString(R.string.dl_open_link) to { pane.load(target) }
            entries += activity.getString(R.string.dl_download_link) to {
                whenStorageReady { start(pane, target, null, null, null, -1) }
            }
            entries += activity.getString(R.string.dl_copy_link) to { copy(target) }
        }
        if (entries.isEmpty()) return

        MaterialAlertDialogBuilder(activity)
            .setTitle(target.take(80))
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
            .show()
    }

    // ---------------------------------------------------------------- routing

    private fun start(
        pane: BrowserPane,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        val message: String? = when {
            url.startsWith("blob:", ignoreCase = true) -> startBlob(pane, url, mimeType, contentDisposition)

            url.startsWith("data:", ignoreCase = true) -> DownloadEngine.enqueueData(url).name

            UrlUtils.isWebUrl(url) -> {
                val cookies = profiles.cookieManager(pane.profileId)
                DownloadEngine.enqueueHttp(
                    HttpRequest(
                        url = url,
                        userAgent = userAgent?.takeIf { it.isNotBlank() } ?: pane.webView.settings.userAgentString,
                        referer = pane.url.takeIf { UrlUtils.isWebUrl(it) },
                        contentDisposition = contentDisposition,
                        mimeType = mimeType,
                        contentLength = contentLength,
                        cookieSource = { target -> cookies.getCookie(target) }
                    )
                ).name
            }

            else -> null
        }

        if (message == null) {
            Toast.makeText(activity, R.string.dl_unsupported, Toast.LENGTH_SHORT).show()
        } else {
            Snackbar.make(snackbarAnchor, activity.getString(R.string.dl_started, message), Snackbar.LENGTH_SHORT)
                .setAction(R.string.dl_view) { openDownloads() }
                .show()
        }
    }

    private fun startBlob(pane: BrowserPane, url: String, mimeType: String?, contentDisposition: String?): String {
        val mime = mimeType?.substringBefore(';')?.trim().orEmpty()
        // Use the page's file name only when it really supplied one; otherwise the engine
        // names the file after the type and the time.
        val suggested = contentDisposition?.takeIf { it.contains("filename", ignoreCase = true) }
            ?.let { URLUtil.guessFileName(url, it, mime.ifBlank { null }) }
        val (id, token) = DownloadEngine.startBlob(mime, suggested)
        pane.webView.evaluateJavascript(BlobScripts.fetch(url, id, token), null)
        return suggested ?: activity.getString(R.string.dl_source_data)
    }

    // ---------------------------------------------------------------- helpers

    private fun whenStorageReady(action: () -> Unit) {
        if (DownloadStorage.needsLegacyPermission(activity)) {
            pending = action
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }

    fun openDownloads() {
        activity.startActivity(Intent(activity, DownloadsActivity::class.java))
    }

    private fun copy(text: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("link", text))
        Toast.makeText(activity, R.string.dl_copied, Toast.LENGTH_SHORT).show()
    }
}

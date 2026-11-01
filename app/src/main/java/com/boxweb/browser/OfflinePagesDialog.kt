package com.boxweb.browser

import android.text.format.Formatter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** "Saved pages": tap one to open it, long-press to delete it. */
class OfflinePagesDialog(
    private val activity: AppCompatActivity,
    private val onOpen: (OfflinePages.Saved) -> Unit
) {

    fun show() {
        val items = OfflinePages.all(activity)
        if (items.isEmpty()) {
            Toast.makeText(activity, R.string.offline_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = items.map {
            val name = it.title.ifBlank { UrlUtils.host(it.url) }
            val size = Formatter.formatShortFileSize(activity, it.size)
            "$name\n${UrlUtils.host(it.url)}  ·  $size"
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.offline_title)
            .setItems(labels.toTypedArray(), null)
            .setNegativeButton(R.string.dialog_close, null)
            .show()

        dialog.listView.setOnItemClickListener { _, _, position, _ ->
            val item = items.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            if (OfflinePages.fileOf(activity, item).isFile) {
                onOpen(item)
            } else {
                OfflinePages.remove(activity, item)
                Toast.makeText(activity, R.string.offline_missing, Toast.LENGTH_LONG).show()
            }
        }
        dialog.listView.setOnItemLongClickListener { _, _, position, _ ->
            items.getOrNull(position)?.let { confirmDelete(it) }
            true
        }
    }

    private fun confirmDelete(item: OfflinePages.Saved) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.offline_delete_title)
            .setMessage(item.title.ifBlank { item.url })
            .setPositiveButton(R.string.offline_delete) { _, _ ->
                OfflinePages.remove(activity, item)
                show()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> show() }
            .show()
    }
}

package com.boxweb.browser

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.LinearLayoutManager
import com.boxweb.browser.databinding.ActivityDownloadsBinding

/** The download manager screen: live progress, open / share / delete. */
class DownloadsActivity : AppCompatActivity(), DownloadStore.Listener {

    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var adapter: DownloadsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_downloads)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_dl_settings -> DownloadSettings.showDialog(this)
                R.id.action_dl_clear -> DownloadStore.clearFinished()
            }
            true
        }

        adapter = DownloadsAdapter(
            onOpen = ::open,
            onCancel = { DownloadEngine.cancel(it.id) },
            onMore = ::showItemMenu
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.list.itemAnimator = null // progress ticks should not flicker the rows
    }

    override fun onStart() {
        super.onStart()
        DownloadStore.addListener(this)
        refresh()
    }

    override fun onStop() {
        DownloadStore.removeListener(this)
        super.onStop()
    }

    override fun onDownloadsChanged() = refresh()

    private fun refresh() {
        val items = DownloadStore.snapshot()
        adapter.submitList(items)
        binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- item actions

    private fun showItemMenu(item: DownloadItem, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, ID_OPEN, 0, R.string.dl_open).isEnabled = item.status == DownloadStatus.DONE
        popup.menu.add(0, ID_SHARE, 1, R.string.menu_share).isEnabled = item.status == DownloadStatus.DONE
        popup.menu.add(0, ID_REMOVE, 2, R.string.dl_remove_from_list)
        popup.menu.add(0, ID_DELETE, 3, R.string.dl_delete_file).isEnabled = item.uri != null
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                ID_OPEN -> open(item)
                ID_SHARE -> share(item)
                ID_REMOVE -> DownloadStore.remove(item.id)
                ID_DELETE -> {
                    DownloadStorage.deleteFile(this, item)
                    DownloadStore.remove(item.id)
                }
            }
            true
        }
        popup.show()
    }

    private fun open(item: DownloadItem) {
        val uri = DownloadStorage.shareableUri(this, item)
        if (uri == null || !DownloadStorage.exists(this, item)) {
            Toast.makeText(this, R.string.dl_file_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, item.mimeType.ifBlank { DownloadStorage.guessMime(item.name) })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.dl_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(item: DownloadItem) {
        val uri = DownloadStorage.shareableUri(this, item)
        if (uri == null || !DownloadStorage.exists(this, item)) {
            Toast.makeText(this, R.string.dl_file_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType(item.mimeType.ifBlank { DownloadStorage.guessMime(item.name) })
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.menu_share)))
    }

    private companion object {
        const val ID_OPEN = 1
        const val ID_SHARE = 2
        const val ID_REMOVE = 3
        const val ID_DELETE = 4
    }
}

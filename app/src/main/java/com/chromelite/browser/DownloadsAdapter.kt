package com.chromelite.browser

import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.chromelite.browser.databinding.ItemDownloadBinding

class DownloadsAdapter(
    private val onOpen: (DownloadItem) -> Unit,
    private val onCancel: (DownloadItem) -> Unit,
    private val onMore: (DownloadItem, View) -> Unit
) : ListAdapter<DownloadItem, DownloadsAdapter.Holder>(Diff) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val b: ItemDownloadBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(item: DownloadItem) {
            val context = b.root.context
            b.tvName.text = item.name

            b.tvStatus.text = when (item.status) {
                DownloadStatus.QUEUED -> context.getString(R.string.dl_starting)
                DownloadStatus.RUNNING ->
                    if (item.totalBytes > 0) {
                        "${item.percent}%  ·  ${Formatter.formatShortFileSize(context, item.bytesDone)} / " +
                            Formatter.formatShortFileSize(context, item.totalBytes)
                    } else {
                        Formatter.formatShortFileSize(context, item.bytesDone)
                    }
                DownloadStatus.DONE ->
                    "${Formatter.formatShortFileSize(context, item.bytesDone)}  ·  ${item.source}  ·  " +
                        DateUtils.getRelativeTimeSpanString(item.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                DownloadStatus.FAILED -> context.getString(R.string.dl_failed, item.error ?: "?")
                DownloadStatus.CANCELED -> context.getString(R.string.dl_canceled)
            }

            val running = item.isActive
            b.progress.visibility = if (running) View.VISIBLE else View.GONE
            if (running) {
                b.progress.isIndeterminate = item.totalBytes <= 0
                if (item.totalBytes > 0) b.progress.progress = item.percent
            }

            b.btnAction.setImageResource(if (running) R.drawable.ic_close else R.drawable.ic_more_vert)
            b.btnAction.contentDescription =
                context.getString(if (running) R.string.dl_cancel else R.string.cd_more)
            b.btnAction.setOnClickListener { if (running) onCancel(item) else onMore(item, it) }

            b.root.setOnClickListener { if (item.status == DownloadStatus.DONE) onOpen(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<DownloadItem>() {
        override fun areItemsTheSame(a: DownloadItem, b: DownloadItem) = a.id == b.id
        override fun areContentsTheSame(a: DownloadItem, b: DownloadItem) = a == b
    }
}

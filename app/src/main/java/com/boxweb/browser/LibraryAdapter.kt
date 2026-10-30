package com.boxweb.browser

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.boxweb.browser.databinding.ItemLibraryBinding

/** One row of the history / bookmarks list. [time] is the visit time or the time it was saved. */
data class LibraryRow(val id: Long, val title: String, val url: String, val time: Long)

class LibraryAdapter(
    private val onOpen: (LibraryRow) -> Unit,
    private val onDelete: (LibraryRow) -> Unit
) : ListAdapter<LibraryRow, LibraryAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemLibraryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val b: ItemLibraryBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: LibraryRow) {
            b.tvTitle.text = row.title.ifBlank { UrlUtils.host(row.url) }
            val whenText = DateUtils.getRelativeTimeSpanString(
                row.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            b.tvUrl.text = "${UrlUtils.host(row.url)}  ·  $whenText"
            b.root.setOnClickListener { onOpen(row) }
            b.btnDelete.setOnClickListener { onDelete(row) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<LibraryRow>() {
        override fun areItemsTheSame(a: LibraryRow, b: LibraryRow) = a.id == b.id
        override fun areContentsTheSame(a: LibraryRow, b: LibraryRow) = a == b
    }
}

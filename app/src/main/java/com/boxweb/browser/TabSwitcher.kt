package com.boxweb.browser

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.boxweb.browser.databinding.ItemTabBinding
import com.boxweb.browser.databinding.ItemTabGroupBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The tab list opened from the toolbar counter: every tab with the profile it belongs to,
 * which ones are on screen, plus close buttons, "New tab" and the split-view switch.
 *
 * Tabs without a group come first; each group follows as a header (tap to fold or unfold it)
 * with its tabs underneath. Long-press a tab to put it in a group, long-press a header to close
 * or dissolve the group.
 */
class TabSwitcher(
    private val activity: AppCompatActivity,
    private val profiles: ProfileManager,
    private val host: Host
) {

    interface Host {
        fun tabs(): List<BrowserPane>

        /** 1 or 2 when the tab is on screen in that pane, 0 when it is in the background. */
        fun paneNumberOf(tab: BrowserPane): Int

        fun activeTab(): BrowserPane
        fun splitEnabled(): Boolean

        /** Shows [tab] in the pane the user is working in. */
        fun showTab(tab: BrowserPane)

        fun closeTab(tab: BrowserPane)

        /** New tab in the profile of the active tab. */
        fun newTab()

        fun toggleSplit()
    }

    /** One line of the list: a group header or a tab. */
    private sealed class Row {
        class Header(val name: String, val count: Int, val collapsed: Boolean) : Row()
        class Item(val tab: BrowserPane, val grouped: Boolean) : Row()
    }

    private var dialog: AlertDialog? = null
    private val adapter = TabAdapter()

    /** Groups folded in the list (kept while the app runs). */
    private val collapsed = HashSet<String>()

    fun show() {
        adapter.rebuild()
        val shown = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.tabs_title, host.tabs().size))
            .setAdapter(adapter, null)
            .setPositiveButton(R.string.tab_new) { _, _ -> host.newTab() }
            .setNeutralButton(if (host.splitEnabled()) R.string.split_toggle_off else R.string.split_toggle_on) { _, _ ->
                host.toggleSplit()
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
        dialog = shown

        // The builder would close the dialog on every tap; headers must keep it open.
        shown.listView.setOnItemClickListener { _, _, position, _ ->
            when (val row = adapter.rows.getOrNull(position)) {
                is Row.Header -> {
                    if (!collapsed.remove(row.name)) collapsed.add(row.name)
                    refresh()
                }
                is Row.Item -> {
                    shown.dismiss()
                    host.showTab(row.tab)
                }
                null -> Unit
            }
        }
        shown.listView.setOnItemLongClickListener { _, _, position, _ ->
            when (val row = adapter.rows.getOrNull(position)) {
                is Row.Header -> groupMenu(row.name)
                is Row.Item -> tabMenu(row.tab)
                null -> Unit
            }
            true
        }
    }

    private fun refresh() {
        adapter.rebuild()
        adapter.notifyDataSetChanged()
        dialog?.setTitle(activity.getString(R.string.tabs_title, host.tabs().size))
    }

    // ---------------------------------------------------------------- groups

    private fun tabMenu(tab: BrowserPane) {
        val entries = ArrayList<Pair<String, () -> Unit>>()
        entries += activity.getString(R.string.tab_group_add) to { pickGroup(tab) }
        if (tab.group.isNotEmpty()) {
            entries += activity.getString(R.string.tab_group_remove) to {
                tab.group = ""
                refresh()
            }
        }
        entries += activity.getString(R.string.tab_close) to {
            host.closeTab(tab)
            refresh()
        }
        MaterialAlertDialogBuilder(activity)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
            .show()
    }

    private fun groupMenu(name: String) {
        val entries = listOf<Pair<String, () -> Unit>>(
            activity.getString(R.string.tab_group_ungroup) to {
                host.tabs().filter { it.group == name }.forEach { it.group = "" }
                collapsed.remove(name)
                refresh()
            },
            activity.getString(R.string.tab_group_close) to {
                host.tabs().filter { it.group == name }.forEach { host.closeTab(it) }
                collapsed.remove(name)
                refresh()
            }
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(name)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
            .show()
    }

    /** Choose an existing group for [tab], or start a new one. */
    private fun pickGroup(tab: BrowserPane) {
        val existing = host.tabs().map { it.group }.filter { it.isNotEmpty() && it != tab.group }.distinct()
        val labels = existing + activity.getString(R.string.tab_group_new)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.tab_group_pick_title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < existing.size) {
                    tab.group = existing[which]
                    refresh()
                } else {
                    askNewGroup(tab)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askNewGroup(tab: BrowserPane) {
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.tab_group_name_hint)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val box = FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.tab_group_new_title)
            .setView(box)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = input.text.toString().trim().take(MAX_GROUP_NAME)
                if (name.isNotEmpty()) {
                    tab.group = name
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun colorOf(group: String): Int =
        ProfileManager.PALETTE[(group.hashCode() and 0x7fffffff) % ProfileManager.PALETTE.size]

    // ---------------------------------------------------------------- list

    private inner class TabAdapter : BaseAdapter() {

        var rows: List<Row> = emptyList()
            private set

        fun rebuild() {
            val all = host.tabs()
            val built = ArrayList<Row>()
            all.filter { it.group.isEmpty() }.forEach { built += Row.Item(it, false) }
            all.map { it.group }.filter { it.isNotEmpty() }.distinct().forEach { name ->
                val members = all.filter { it.group == name }
                val isCollapsed = name in collapsed
                built += Row.Header(name, members.size, isCollapsed)
                if (!isCollapsed) members.forEach { built += Row.Item(it, true) }
            }
            rows = built
        }

        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): Row = rows[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewTypeCount(): Int = 2

        override fun getItemViewType(position: Int): Int = if (rows[position] is Row.Header) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val row = rows[position]) {
                is Row.Header -> headerView(row, convertView, parent)
                is Row.Item -> tabView(row, convertView, parent)
            }

        private fun headerView(row: Row.Header, convertView: View?, parent: ViewGroup): View {
            val b = if (convertView != null) {
                ItemTabGroupBinding.bind(convertView)
            } else {
                ItemTabGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            }
            b.tvGroupName.text = activity.getString(R.string.tab_group_header, row.name, row.count)
            ViewCompat.setBackgroundTintList(b.vGroupDot, ColorStateList.valueOf(colorOf(row.name)))
            b.ivGroupChevron.setImageResource(if (row.collapsed) R.drawable.ic_arrow_down else R.drawable.ic_arrow_up)
            return b.root
        }

        private fun tabView(row: Row.Item, convertView: View?, parent: ViewGroup): View {
            val b = if (convertView != null) {
                ItemTabBinding.bind(convertView)
            } else {
                ItemTabBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            }
            val tab = row.tab
            val density = activity.resources.displayMetrics.density
            // Tabs of a group are indented under their header.
            b.root.setPaddingRelative(
                ((if (row.grouped) 36 else 20) * density).toInt(),
                b.root.paddingTop,
                b.root.paddingEnd,
                b.root.paddingBottom
            )

            val profile = profiles.resolve(tab.profileId)
            val profileName = if (tab.incognito) activity.getString(R.string.incognito_name) else profile.name
            val profileColor = if (tab.incognito) ProfileManager.INCOGNITO_COLOR else profile.color
            val isActive = tab === host.activeTab()

            b.ivTabThumb.setImageBitmap(if (tab.incognito) null else tab.thumbnail)
            b.tvTabTitle.text = tab.title.ifBlank { UrlUtils.host(tab.url) }
                .ifBlank { activity.getString(R.string.pane_untitled) }
            b.tvTabTitle.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)

            val pane = host.paneNumberOf(tab)
            b.tvTabSub.text = buildString {
                append(profileName)
                val site = UrlUtils.host(tab.url)
                if (site.isNotBlank()) append("  ·  ").append(site)
                if (host.splitEnabled() && pane > 0) {
                    append("  ·  ").append(activity.getString(R.string.tab_pane_n, pane))
                }
            }
            ViewCompat.setBackgroundTintList(b.vDot, ColorStateList.valueOf(profileColor))

            b.btnTabClose.setOnClickListener {
                host.closeTab(tab)
                refresh()
            }
            return b.root
        }
    }

    private companion object {
        const val MAX_GROUP_NAME = 24
    }
}

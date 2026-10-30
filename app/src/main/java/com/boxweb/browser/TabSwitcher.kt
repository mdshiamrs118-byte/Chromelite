package com.boxweb.browser

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.boxweb.browser.databinding.ItemTabBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The tab list opened from the toolbar counter: every tab with the profile it belongs to,
 * which ones are on screen, plus close buttons, "New tab" and the split-view switch.
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

    private var dialog: AlertDialog? = null
    private val adapter = TabAdapter()

    fun show() {
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.tabs_title, host.tabs().size))
            .setAdapter(adapter) { _, which -> host.tabs().getOrNull(which)?.let(host::showTab) }
            .setPositiveButton(R.string.tab_new) { _, _ -> host.newTab() }
            .setNeutralButton(if (host.splitEnabled()) R.string.split_toggle_off else R.string.split_toggle_on) { _, _ ->
                host.toggleSplit()
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private inner class TabAdapter : BaseAdapter() {

        override fun getCount(): Int = host.tabs().size

        override fun getItem(position: Int): BrowserPane = host.tabs()[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val b = if (convertView != null) {
                ItemTabBinding.bind(convertView)
            } else {
                ItemTabBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            }
            val tab = getItem(position)
            val profile = profiles.resolve(tab.profileId)
            val isActive = tab === host.activeTab()

            b.tvTabTitle.text = tab.title.ifBlank { UrlUtils.host(tab.url) }
                .ifBlank { activity.getString(R.string.pane_untitled) }
            b.tvTabTitle.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)

            val pane = host.paneNumberOf(tab)
            b.tvTabSub.text = buildString {
                append(profile.name)
                val site = UrlUtils.host(tab.url)
                if (site.isNotBlank()) append("  ·  ").append(site)
                if (host.splitEnabled() && pane > 0) {
                    append("  ·  ").append(activity.getString(R.string.tab_pane_n, pane))
                }
            }
            ViewCompat.setBackgroundTintList(b.vDot, ColorStateList.valueOf(profile.color))

            b.btnTabClose.setOnClickListener {
                host.closeTab(tab)
                notifyDataSetChanged()
                dialog?.setTitle(activity.getString(R.string.tabs_title, host.tabs().size))
            }
            return b.root
        }
    }
}

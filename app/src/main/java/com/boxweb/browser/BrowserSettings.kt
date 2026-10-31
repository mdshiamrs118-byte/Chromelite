package com.boxweb.browser

import android.content.Context
import android.webkit.WebSettings
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Small persisted browser preferences. */
object BrowserSettings {

    private const val PREFS = "browser_settings"
    private const val KEY_DESKTOP = "desktop_site"
    private const val KEY_PULL = "pull_to_refresh"
    private const val KEY_ENGINE = "search_engine"
    private const val KEY_DARK = "dark_pages"
    private const val KEY_BOTTOM = "toolbar_bottom"

    /** Search engines the omnibox can use: display name and the address a query is appended to. */
    val ENGINES: List<Pair<String, String>> = listOf(
        "Google" to "https://www.google.com/search?q=",
        "DuckDuckGo" to "https://duckduckgo.com/?q=",
        "Bing" to "https://www.bing.com/search?q=",
        "Brave" to "https://search.brave.com/search?q=",
        "Ecosia" to "https://www.ecosia.org/search?q=",
        "Startpage" to "https://www.startpage.com/do/search?q="
    )

    /** What the settings screen needs from the activity. */
    interface Host {
        /** A setting changed that open pages should pick up. */
        fun onSettingsChanged()
        fun exportBookmarks()
        fun importBookmarks()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Request the desktop version of sites (desktop Chrome user agent + wide layout). */
    fun desktopSite(context: Context): Boolean = prefs(context).getBoolean(KEY_DESKTOP, false)

    fun setDesktopSite(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_DESKTOP, value).apply()
    }

    fun pullToRefresh(context: Context): Boolean = prefs(context).getBoolean(KEY_PULL, true)

    fun setPullToRefresh(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_PULL, value).apply()
    }

    private fun engineIndex(context: Context): Int =
        prefs(context).getInt(KEY_ENGINE, 0).coerceIn(0, ENGINES.size - 1)

    fun searchEngineName(context: Context): String = ENGINES[engineIndex(context)].first

    /** Address the omnibox appends a search query to. */
    fun searchUrl(context: Context): String = ENGINES[engineIndex(context)].second

    /** Render light web pages dark. */
    fun darkPages(context: Context): Boolean = prefs(context).getBoolean(KEY_DARK, false)

    /** Toolbar at the bottom of the screen (easier to reach with one hand). */
    fun toolbarAtBottom(context: Context): Boolean = prefs(context).getBoolean(KEY_BOTTOM, false)

    /** Settings list: toggles, pickers and links to the other settings panels. */
    fun showDialog(activity: AppCompatActivity, host: Host) {
        val pull = pullToRefresh(activity)
        val dark = darkPages(activity)
        val bottom = toolbarAtBottom(activity)
        val on = activity.getString(R.string.state_on)
        val off = activity.getString(R.string.state_off)

        val items = arrayOf(
            activity.getString(if (pull) R.string.settings_pull_on else R.string.settings_pull_off),
            activity.getString(R.string.settings_search_engine, searchEngineName(activity)),
            activity.getString(R.string.settings_dark_pages, if (dark) on else off),
            activity.getString(R.string.settings_toolbar_bottom, if (bottom) on else off),
            activity.getString(R.string.settings_export_bookmarks),
            activity.getString(R.string.settings_import_bookmarks),
            activity.getString(R.string.dl_settings_title)
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.menu_settings)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        prefs(activity).edit().putBoolean(KEY_PULL, !pull).apply()
                        host.onSettingsChanged()
                        showDialog(activity, host)
                    }
                    1 -> pickSearchEngine(activity, host)
                    2 -> {
                        prefs(activity).edit().putBoolean(KEY_DARK, !dark).apply()
                        host.onSettingsChanged()
                        showDialog(activity, host)
                    }
                    3 -> {
                        prefs(activity).edit().putBoolean(KEY_BOTTOM, !bottom).apply()
                        host.onSettingsChanged()
                        showDialog(activity, host)
                    }
                    4 -> host.exportBookmarks()
                    5 -> host.importBookmarks()
                    6 -> DownloadSettings.showDialog(activity)
                }
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun pickSearchEngine(activity: AppCompatActivity, host: Host) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.settings_search_engine_title)
            .setSingleChoiceItems(ENGINES.map { it.first }.toTypedArray(), engineIndex(activity)) { dialog, which ->
                prefs(activity).edit().putInt(KEY_ENGINE, which).apply()
                dialog.dismiss()
                host.onSettingsChanged()
                showDialog(activity, host)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> showDialog(activity, host) }
            .show()
    }
}

/** The two user agents behind the "Desktop site" switch. Both look like regular Chrome, not an embedded WebView. */
object UserAgents {

    private var mobileUa: String? = null
    private var desktopUa: String? = null

    /** Android Chrome: the WebView default minus the "; wv" and "Version/4.0" WebView markers. */
    fun mobile(context: Context): String =
        mobileUa ?: WebSettings.getDefaultUserAgent(context)
            .replace("; wv", "")
            .replace(" Version/4.0", "")
            .also { mobileUa = it }

    /** Chrome on Linux desktop, using the Chrome version of the installed WebView. */
    fun desktop(context: Context): String =
        desktopUa ?: run {
            val version = Regex("Chrome/([\\d.]+)").find(WebSettings.getDefaultUserAgent(context))
                ?.groupValues?.get(1) ?: "120.0.0.0"
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Safari/537.36"
        }.also { desktopUa = it }
}

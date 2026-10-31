package com.boxweb.browser

import android.app.KeyguardManager
import android.content.Context
import android.text.InputType
import android.webkit.WebSettings
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
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
    private const val KEY_POPUPS = "block_popups"
    private const val KEY_TEXT = "text_zoom"
    private const val KEY_LOCK = "app_lock"
    private const val KEY_HOME = "homepage"

    /** Page text sizes (percent) the "Text size" setting steps through. */
    private val TEXT_SIZES = listOf(85, 100, 115, 130, 150)

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

    /** Where new tabs and the home action go. */
    fun homepage(context: Context): String =
        prefs(context).getString(KEY_HOME, null)?.takeIf { it.isNotBlank() } ?: UrlUtils.DEFAULT_HOME

    /** Pop-ups opened by a page without a tap are blocked. */
    fun blockPopups(context: Context): Boolean = prefs(context).getBoolean(KEY_POPUPS, true)

    /** Text zoom in percent for every page. */
    fun textZoom(context: Context): Int = prefs(context).getInt(KEY_TEXT, 100)

    /** Ask for the phone's screen lock when coming back to the app. */
    fun appLock(context: Context): Boolean = prefs(context).getBoolean(KEY_LOCK, false)

    /** Settings list: toggles, pickers and links to the other settings panels. */
    fun showDialog(activity: AppCompatActivity, host: Host) {
        val pull = pullToRefresh(activity)
        val dark = darkPages(activity)
        val bottom = toolbarAtBottom(activity)
        val popups = blockPopups(activity)
        val lock = appLock(activity)
        val zoom = textZoom(activity)
        val on = activity.getString(R.string.state_on)
        val off = activity.getString(R.string.state_off)
        fun state(value: Boolean) = if (value) on else off

        fun toggle(key: String, value: Boolean) {
            prefs(activity).edit().putBoolean(key, value).apply()
            host.onSettingsChanged()
            showDialog(activity, host)
        }

        val entries = listOf<Pair<String, () -> Unit>>(
            activity.getString(if (pull) R.string.settings_pull_on else R.string.settings_pull_off) to
                { toggle(KEY_PULL, !pull) },
            activity.getString(R.string.settings_search_engine, searchEngineName(activity)) to
                { pickSearchEngine(activity, host) },
            activity.getString(R.string.settings_homepage, UrlUtils.host(homepage(activity)).ifBlank { homepage(activity) }) to
                { editHomepage(activity, host) },
            activity.getString(R.string.settings_text_size, zoom) to {
                val next = TEXT_SIZES[(TEXT_SIZES.indexOf(zoom) + 1) % TEXT_SIZES.size]
                prefs(activity).edit().putInt(KEY_TEXT, next).apply()
                host.onSettingsChanged()
                showDialog(activity, host)
            },
            activity.getString(R.string.settings_dark_pages, state(dark)) to { toggle(KEY_DARK, !dark) },
            activity.getString(R.string.settings_popups, state(popups)) to { toggle(KEY_POPUPS, !popups) },
            activity.getString(R.string.settings_toolbar_bottom, state(bottom)) to { toggle(KEY_BOTTOM, !bottom) },
            activity.getString(R.string.settings_app_lock, state(lock)) to {
                val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (!lock && !keyguard.isKeyguardSecure) {
                    Toast.makeText(activity, R.string.settings_lock_needs_secure, Toast.LENGTH_LONG).show()
                    showDialog(activity, host)
                } else {
                    toggle(KEY_LOCK, !lock)
                }
            },
            activity.getString(R.string.settings_user_scripts, UserScripts.all(activity).size) to
                { UserScriptsDialog(activity) { host.onSettingsChanged() }.show() },
            activity.getString(R.string.settings_export_bookmarks) to { host.exportBookmarks() },
            activity.getString(R.string.settings_import_bookmarks) to { host.importBookmarks() },
            activity.getString(R.string.dl_settings_title) to { DownloadSettings.showDialog(activity) }
        )

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.menu_settings)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun editHomepage(activity: AppCompatActivity, host: Host) {
        val input = EditText(activity).apply {
            setText(homepage(activity))
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val box = FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.settings_homepage_title)
            .setView(box)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val typed = input.text.toString().trim()
                val value = if (typed.isEmpty()) UrlUtils.DEFAULT_HOME else UrlUtils.resolve(typed)
                prefs(activity).edit().putString(KEY_HOME, value).apply()
                UrlUtils.HOME = value
                host.onSettingsChanged()
                showDialog(activity, host)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> showDialog(activity, host) }
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

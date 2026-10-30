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

    /** Settings list: simple toggles and links to the other settings panels. */
    fun showDialog(activity: AppCompatActivity, onChanged: () -> Unit) {
        val pull = pullToRefresh(activity)
        val items = arrayOf(
            activity.getString(if (pull) R.string.settings_pull_on else R.string.settings_pull_off),
            activity.getString(R.string.dl_settings_title)
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.menu_settings)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        setPullToRefresh(activity, !pull)
                        onChanged()
                    }
                    1 -> DownloadSettings.showDialog(activity)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
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

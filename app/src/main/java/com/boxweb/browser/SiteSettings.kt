package com.boxweb.browser

import android.content.Context
import org.json.JSONObject

/**
 * Settings that can differ per site: desktop mode and text size. A site without an entry follows the
 * global choice in [BrowserSettings]. Sites are keyed by host name without a leading "www.".
 */
object SiteSettings {

    private const val PREFS = "site_settings"
    private const val KEY_SITES = "sites"
    private const val DESKTOP = "desktop"
    private const val ZOOM = "zoom"

    private var cache: JSONObject? = null

    fun key(host: String): String = host.trim().lowercase().removePrefix("www.")

    @Synchronized
    private fun root(context: Context): JSONObject =
        cache ?: runCatching {
            JSONObject(prefs(context).getString(KEY_SITES, null) ?: "{}")
        }.getOrDefault(JSONObject()).also { cache = it }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun save(context: Context, sites: JSONObject) {
        prefs(context).edit().putString(KEY_SITES, sites.toString()).apply()
    }

    /** null = this site follows the global setting. */
    @Synchronized
    fun desktopOverride(context: Context, host: String): Boolean? {
        val entry = root(context).optJSONObject(key(host)) ?: return null
        return if (entry.has(DESKTOP)) entry.optBoolean(DESKTOP) else null
    }

    /** null = this site follows the global setting. */
    @Synchronized
    fun zoomOverride(context: Context, host: String): Int? {
        val entry = root(context).optJSONObject(key(host)) ?: return null
        return if (entry.has(ZOOM)) entry.optInt(ZOOM, 100) else null
    }

    /** Desktop mode in effect for [host] (an empty host gives the global setting). */
    fun desktop(context: Context, host: String): Boolean =
        desktopOverride(context, host) ?: BrowserSettings.desktopSite(context)

    /** Text size in percent in effect for [host]. */
    fun textZoom(context: Context, host: String): Int =
        zoomOverride(context, host) ?: BrowserSettings.textZoom(context)

    fun hasOverride(context: Context, host: String): Boolean =
        desktopOverride(context, host) != null || zoomOverride(context, host) != null

    fun setDesktop(context: Context, host: String, value: Boolean?) = update(context, host, DESKTOP, value)

    fun setTextZoom(context: Context, host: String, value: Int?) = update(context, host, ZOOM, value)

    @Synchronized
    fun clear(context: Context, host: String) {
        val k = key(host)
        if (k.isEmpty()) return
        val sites = root(context)
        if (sites.has(k)) {
            sites.remove(k)
            save(context, sites)
        }
    }

    @Synchronized
    private fun update(context: Context, host: String, name: String, value: Any?) {
        val k = key(host)
        if (k.isEmpty()) return
        val sites = root(context)
        val entry = sites.optJSONObject(k) ?: JSONObject()
        if (value == null) entry.remove(name) else entry.put(name, value)
        if (entry.length() == 0) sites.remove(k) else sites.put(k, entry)
        save(context, sites)
    }
}

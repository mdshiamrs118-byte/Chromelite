package com.boxweb.browser

import android.content.res.Configuration
import android.webkit.WebView
import com.boxweb.browser.data.BrowserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The built-in new tab page: a search box and a grid of shortcuts (bookmarks first, then the sites
 * visited most in the tab's profile). It is plain HTML without scripts, loaded into the WebView
 * with a boxweb://newtab base address, so local file access stays switched off.
 */
object NewTabPage {

    private const val MAX_TILES = 12 // 4 columns x 3 rows

    private val scope = MainScope()

    private class Tile(val url: String, val label: String)

    /** Builds the page for [profileId] and shows it in [webView], unless [alive] says the tab is gone by then. */
    fun load(webView: WebView, profileId: String, alive: () -> Boolean) {
        val res = webView.resources
        val dark = (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val appName = res.getString(R.string.app_name)
        val title = res.getString(R.string.tab_new)
        val searchLabel = res.getString(R.string.newtab_search)
        val emptyText = res.getString(R.string.newtab_empty)
        val searchUrl = BrowserSettings.searchUrl(webView.context)

        scope.launch {
            val tiles = withContext(Dispatchers.IO) { runCatching { tiles(profileId) }.getOrDefault(emptyList()) }
            if (!alive()) return@launch
            val html = build(dark, appName, title, searchLabel, emptyText, searchUrl, tiles)
            webView.loadDataWithBaseURL(UrlUtils.NEW_TAB, html, "text/html", "utf-8", UrlUtils.NEW_TAB)
        }
    }

    private suspend fun tiles(profileId: String): List<Tile> {
        val result = ArrayList<Tile>()
        val seenSites = HashSet<String>()

        BrowserRepository.allBookmarks().forEach { bookmark ->
            if (result.size < MAX_TILES && UrlUtils.isWebUrl(bookmark.url)) {
                val site = UrlUtils.host(bookmark.url).lowercase().removePrefix("www.")
                seenSites += site
                result += Tile(bookmark.url, site)
            }
        }
        if (result.size < MAX_TILES) {
            BrowserRepository.topOrigins(profileId, MAX_TILES).forEach { origin ->
                val site = UrlUtils.host(origin).lowercase().removePrefix("www.")
                if (result.size < MAX_TILES && seenSites.add(site)) result += Tile(origin, site)
            }
        }
        return result
    }

    private fun build(
        dark: Boolean,
        appName: String,
        title: String,
        searchLabel: String,
        emptyText: String,
        searchUrl: String,
        tiles: List<Tile>
    ): String {
        val bg = if (dark) "#202124" else "#FFFFFF"
        val fg = if (dark) "#E8EAED" else "#202124"
        val sub = if (dark) "#9AA0A6" else "#5F6368"
        val field = if (dark) "#35363A" else "#F1F3F4"

        // "https://host/search?q=" -> form action "https://host/search", field name "q"
        val action = esc(searchUrl.substringBefore('?'))
        val param = esc(searchUrl.substringAfter('?', "q=").substringBefore('=').ifBlank { "q" })

        val palette = ProfileManager.PALETTE
        val cells = tiles.joinToString("") { tile ->
            val letter = tile.label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
            val color = palette[(tile.label.hashCode() and 0x7fffffff) % palette.size]
            val hex = String.format(Locale.US, "#%06X", color and 0xFFFFFF)
            "<a class=\"tile\" href=\"${esc(tile.url)}\">" +
                "<div class=\"ico\" style=\"background:$hex\">${esc(letter)}</div>" +
                "<span class=\"lbl\">${esc(tile.label)}</span></a>"
        }
        val content = if (tiles.isEmpty()) {
            "<p class=\"empty\">${esc(emptyText)}</p>"
        } else {
            "<div class=\"grid\">$cells</div>"
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>${esc(title)}</title>
            <style>
            body { margin: 0; padding: 24px 16px 32px; background: $bg; color: $fg;
                   font-family: sans-serif; display: flex; flex-direction: column; align-items: center; }
            h1 { font-size: 28px; font-weight: 600; margin: 32px 0 20px; letter-spacing: 0.5px; }
            form { width: 100%; max-width: 560px; margin: 0 0 28px; }
            input { width: 100%; box-sizing: border-box; border: 0; border-radius: 24px; background: $field;
                    color: $fg; padding: 13px 18px; font-size: 16px; outline: none; }
            .grid { width: 100%; max-width: 560px; display: grid; grid-template-columns: repeat(4, 1fr); gap: 18px 8px; }
            a.tile { display: flex; flex-direction: column; align-items: center; text-decoration: none; color: $fg; min-width: 0; }
            .ico { width: 52px; height: 52px; border-radius: 50%; display: flex; align-items: center;
                   justify-content: center; color: #fff; font-size: 22px; font-weight: 600; }
            .lbl { margin-top: 8px; font-size: 12px; max-width: 100%; overflow: hidden;
                   text-overflow: ellipsis; white-space: nowrap; color: $sub; }
            .empty { color: $sub; font-size: 14px; text-align: center; margin-top: 12px; }
            </style>
            </head>
            <body>
            <h1>${esc(appName)}</h1>
            <form action="$action" method="get">
            <input type="search" name="$param" placeholder="${esc(searchLabel)}" autocomplete="off">
            </form>
            $content
            </body>
            </html>
        """.trimIndent()
    }

    private fun esc(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

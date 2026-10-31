package com.boxweb.browser

import com.boxweb.browser.data.Bookmark

/**
 * Bookmarks as the standard "Netscape bookmark" HTML file that Chrome, Firefox, Edge and
 * most other browsers can import and export.
 */
object BookmarkFile {

    private const val MAX_IMPORT = 5000

    private val LINK = Regex(
        "<a\\s[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val TAGS = Regex("<[^>]*>")

    fun build(bookmarks: List<Bookmark>): String = buildString {
        append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")
        bookmarks.forEach {
            append("    <DT><A HREF=\"").append(escape(it.url))
                .append("\" ADD_DATE=\"").append(it.createdAt / 1000)
                .append("\">").append(escape(it.title)).append("</A>\n")
        }
        append("</DL><p>\n")
    }

    /** Returns (title, url) pairs for every http(s) link in [html]. */
    fun parse(html: String): List<Pair<String, String>> =
        LINK.findAll(html)
            .mapNotNull { match ->
                val url = unescape(match.groupValues[1]).trim()
                if (!UrlUtils.isWebUrl(url)) return@mapNotNull null
                val title = unescape(match.groupValues[2].replace(TAGS, "")).trim()
                title.ifBlank { url } to url
            }
            .distinctBy { it.second }
            .take(MAX_IMPORT)
            .toList()

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun unescape(text: String): String =
        text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&amp;", "&")
}

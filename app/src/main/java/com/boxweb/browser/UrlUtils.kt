package com.boxweb.browser

import android.net.Uri
import android.util.Patterns

/** Turns omnibox input into a loadable URL (or a search query) and formats URLs for display. */
object UrlUtils {

    const val HOME = "https://www.google.com"
    private const val SEARCH_URL = "https://www.google.com/search?q="

    private val EXPLICIT_PREFIXES =
        listOf("http://", "https://", "file://", "about:", "data:", "blob:")

    // localhost and raw IPv4 addresses (optionally with port/path) are served over plain http
    private val LOCAL_HOST = Regex(
        "^(localhost|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/.*)?$",
        RegexOption.IGNORE_CASE
    )

    fun resolve(input: String, searchUrl: String = SEARCH_URL): String {
        val text = input.trim()
        if (text.isEmpty()) return HOME

        if (EXPLICIT_PREFIXES.any { text.startsWith(it, ignoreCase = true) }) return text
        if (text.any { it.isWhitespace() }) return searchUrl + Uri.encode(text)
        if (LOCAL_HOST.matches(text)) return "http://$text"
        if (Patterns.WEB_URL.matcher(text).matches()) return "https://$text"

        return searchUrl + Uri.encode(text)
    }

    /** "https://www.example.com/path" -> "www.example.com" (falls back to the raw string). */
    fun host(url: String): String {
        if (url.isBlank()) return ""
        return Uri.parse(url).host ?: url
    }

    fun isWebUrl(url: String?): Boolean =
        url != null && (url.startsWith("http://", true) || url.startsWith("https://", true))
}

package com.boxweb.browser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Pages saved for offline reading. Each one is a single-file web archive (.mht) in the app's private
 * storage; the list of them is kept as JSON in the preferences. Only files inside [dir] are ever
 * opened as local pages, because normal pages are not allowed to touch local files.
 */
object OfflinePages {

    class Saved(
        val id: Long,
        val title: String,
        val url: String,
        val fileName: String,
        val size: Long
    )

    private const val PREFS = "offline_pages"
    private const val KEY_LIST = "list"

    fun dir(context: Context): File = File(context.applicationContext.filesDir, "offline")

    @Synchronized
    fun all(context: Context): List<Saved> = runCatching {
        val array = JSONArray(prefs(context).getString(KEY_LIST, null) ?: "[]")
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Saved(o.getLong("id"), o.optString("title"), o.optString("url"), o.getString("file"), o.optLong("size"))
        }
    }.getOrDefault(emptyList()).sortedByDescending { it.id }

    @Synchronized
    fun add(context: Context, item: Saved) {
        write(context, all(context) + item)
    }

    /** Forgets the page and deletes its file. */
    @Synchronized
    fun remove(context: Context, item: Saved) {
        runCatching { File(dir(context), item.fileName).delete() }
        write(context, all(context).filter { it.id != item.id })
    }

    fun fileOf(context: Context, item: Saved): File = File(dir(context), item.fileName)

    /**
     * The saved file behind a file:// address, or null when the address is anything else
     * (a path outside the offline folder, ".." tricks, a missing file).
     */
    fun fileFor(context: Context, address: String): File? {
        if (!address.startsWith("file://", ignoreCase = true)) return null
        val path = Uri.parse(address).path ?: return null
        return runCatching {
            val root = dir(context).canonicalFile
            val file = File(path).canonicalFile
            if (file.parentFile == root && file.isFile) file else null
        }.getOrNull()
    }

    private fun write(context: Context, items: List<Saved>) {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject().put("id", it.id).put("title", it.title).put("url", it.url)
                    .put("file", it.fileName).put("size", it.size)
            )
        }
        prefs(context).edit().putString(KEY_LIST, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

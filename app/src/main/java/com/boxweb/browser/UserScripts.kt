package com.boxweb.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A piece of JavaScript that runs on the pages of one site (or every site) after they load. */
data class UserScript(
    val id: String,
    val name: String,
    /** "example.com" (also matches its subdomains) or "*" for every site. */
    val match: String,
    val code: String,
    val enabled: Boolean
)

/** Saved user scripts. */
object UserScripts {

    private const val PREFS = "user_scripts"
    private const val KEY_LIST = "list"
    private const val MAX_SCRIPTS = 50

    @Volatile
    private var cache: List<UserScript>? = null

    fun all(context: Context): List<UserScript> =
        cache ?: load(context).also { cache = it }

    fun newId(): String = UUID.randomUUID().toString()

    /** Adds the script, or replaces the one with the same id. */
    fun put(context: Context, script: UserScript) {
        val list = all(context).toMutableList()
        val index = list.indexOfFirst { it.id == script.id }
        if (index >= 0) list[index] = script else if (list.size < MAX_SCRIPTS) list.add(script)
        save(context, list)
    }

    fun delete(context: Context, id: String) {
        save(context, all(context).filter { it.id != id })
    }

    /** Enabled scripts that apply to [pageUrl]. */
    fun scriptsFor(context: Context, pageUrl: String): List<UserScript> {
        val host = UrlUtils.host(pageUrl).lowercase().removePrefix("www.")
        if (host.isEmpty()) return emptyList()
        return all(context).filter { it.enabled && matches(it.match, host) }
    }

    /** The code wrapped so an error in a script cannot affect the page or other scripts. */
    fun wrap(script: UserScript): String =
        "(function(){try{\n" + script.code + "\n}catch(e){console.error('BoxWeb user script: ' + e);}})();"

    private fun matches(pattern: String, host: String): Boolean {
        val p = pattern.trim().lowercase().removePrefix("*.").removePrefix("www.")
        if (p.isEmpty() || p == "*") return true
        return host == p || host.endsWith(".$p")
    }

    private fun load(context: Context): List<UserScript> = runCatching {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LIST, "[]")
        val array = JSONArray(raw)
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            UserScript(
                id = o.getString("id"),
                name = o.optString("name"),
                match = o.optString("match"),
                code = o.optString("code"),
                enabled = o.optBoolean("enabled", true)
            )
        }
    }.getOrDefault(emptyList())

    private fun save(context: Context, list: List<UserScript>) {
        cache = list
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("match", it.match)
                    .put("code", it.code)
                    .put("enabled", it.enabled)
            )
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LIST, array.toString()).apply()
    }
}

package com.chromelite.browser

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

/** A named browsing identity. [id] is what the WebView engine knows it by; [name] is for humans. */
data class BrowserProfile(val id: String, val name: String, val color: Int) {
    val initial: String get() = name.trim().firstOrNull()?.uppercase() ?: "?"
}

/**
 * Isolated browsing sessions.
 *
 * Every profile is a separate engine-level profile (androidx.webkit Profile API), so it has its own
 * cookie jar, Web Storage (localStorage / IndexedDB), geolocation permissions, service workers
 * and HTTP cache. Two WebViews bound to different profiles can therefore sit in the same
 * window and be logged into different accounts of the same site, with nothing shared.
 *
 * The Profile API ships inside the updatable "Android System WebView", not in Android itself, so
 * it works on Android 10 as long as WebView is reasonably recent. When it is missing
 * ([isSupported] == false) only the default profile is offered: sessions are never
 * *pretended* to be isolated.
 *
 * A profile must be attached to a WebView before that WebView loads anything and cannot be
 * changed afterwards, so switching a pane's profile means building a fresh WebView
 * (MainActivity.openInProfile opens the page in a new tab instead).
 */
class ProfileManager private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val profiles = mutableListOf<BrowserProfile>()

    /** True when the installed WebView can run several profiles side by side. */
    val isSupported: Boolean by lazy {
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)
    }

    init {
        load()
    }

    // ---------------------------------------------------------------- queries

    /** The profile every install starts with. It can be cleared and renamed, not deleted. */
    val default: BrowserProfile get() = profiles.first()

    /** Profiles the user may pick from. Only the default one when isolation is unavailable. */
    fun all(): List<BrowserProfile> = if (isSupported) profiles.toList() else listOf(default)

    /** Looks a profile up, falling back to the default if it no longer exists. */
    fun resolve(id: String?): BrowserProfile = all().firstOrNull { it.id == id } ?: default

    fun exists(id: String?): Boolean = all().any { it.id == id }

    // ---------------------------------------------------------------- create / rename / delete

    fun create(name: String): BrowserProfile? {
        if (!isSupported) return null
        val number = prefs.getInt(KEY_COUNTER, 1) + 1
        val profile = BrowserProfile(
            id = "profile_$number",
            name = name.trim().ifEmpty { "Profile $number" }.take(24),
            color = PALETTE[profiles.size % PALETTE.size]
        )
        // Create the engine-side profile right away so a failure shows up now, not at first use.
        val created = runCatching { ProfileStore.getInstance().getOrCreateProfile(profile.id) }
        if (created.isFailure) {
            Log.w(TAG, "Could not create profile ${profile.id}", created.exceptionOrNull())
            return null
        }
        prefs.edit().putInt(KEY_COUNTER, number).apply()
        profiles += profile
        save()
        return profile
    }

    fun rename(id: String, newName: String) {
        val index = profiles.indexOfFirst { it.id == id }
        val name = newName.trim().take(24)
        if (index < 0 || name.isEmpty()) return
        profiles[index] = profiles[index].copy(name = name)
        save()
    }

    /**
     * Wipes the profile's data and removes it. The caller must make sure no WebView still uses it
     * (the engine refuses to delete a profile that is in use). Returns false if it was not deleted.
     */
    fun delete(id: String): Boolean {
        if (id == default.id || !isSupported) return false
        clearData(id)
        val deleted = runCatching { ProfileStore.getInstance().deleteProfile(id) }
        if (deleted.isFailure) {
            Log.w(TAG, "Could not delete profile $id", deleted.exceptionOrNull())
            return false
        }
        profiles.removeAll { it.id == id }
        save()
        return true
    }

    // ---------------------------------------------------------------- binding to WebViews

    /**
     * Binds [webView] to profile [id]. Call it right after constructing the WebView, before any
     * settings or loading. Returns false when the profile could not be applied, in which case the
     * WebView is on the default profile and the caller must treat it as such.
     */
    fun attach(webView: WebView, id: String): Boolean {
        if (id == default.id) return true // new WebViews already use the default profile
        if (!isSupported) return false
        return try {
            ProfileStore.getInstance().getOrCreateProfile(id)
            WebViewCompat.setProfile(webView, id)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not attach profile $id", e)
            false
        }
    }

    /** This profile's own cookie jar (the global one when profiles are unavailable). */
    fun cookieManager(id: String): CookieManager =
        if (isSupported) {
            runCatching { ProfileStore.getInstance().getOrCreateProfile(id).cookieManager }
                .getOrElse { CookieManager.getInstance() }
        } else {
            CookieManager.getInstance()
        }

    /** Writes cookies to disk. Call when the app goes to the background. */
    fun flush(id: String) = cookieManager(id).flush()

    /**
     * Removes cookies, Web Storage and geolocation permissions of one profile only.
     * Pass a WebView that uses the profile to also empty its HTTP cache.
     */
    fun clearData(id: String, webView: WebView? = null) {
        clearSiteData(id)
        webView?.apply {
            clearCache(true)
            clearFormData()
        }
    }

    /** Cookies, Web Storage (localStorage, IndexedDB...) and geolocation permissions of one profile. */
    fun clearSiteData(id: String) {
        val cookies = cookieManager(id)
        cookies.removeAllCookies(null)
        cookies.flush()

        if (isSupported) {
            runCatching {
                val profile = ProfileStore.getInstance().getOrCreateProfile(id)
                profile.webStorage.deleteAllData()
                profile.geolocationPermissions.clearAll()
            }
        } else {
            WebStorage.getInstance().deleteAllData()
            GeolocationPermissions.getInstance().clearAll()
        }
    }

    // ---------------------------------------------------------------- persistence

    private fun load() {
        val loaded = runCatching {
            val array = JSONArray(prefs.getString(KEY_LIST, "[]"))
            List(array.length()) { i ->
                array.getJSONObject(i).let {
                    BrowserProfile(it.getString("id"), it.getString("name"), it.getInt("color"))
                }
            }
        }.getOrDefault(emptyList())

        profiles.clear()
        profiles += loaded.filter { it.id != DEFAULT_ID }
        profiles.add(0, loaded.firstOrNull { it.id == DEFAULT_ID } ?: BrowserProfile(DEFAULT_ID, "Personal", PALETTE[0]))
    }

    private fun save() {
        val array = JSONArray()
        profiles.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color))
        }
        prefs.edit().putString(KEY_LIST, array.toString()).apply()
    }

    companion object {
        private const val TAG = "ProfileManager"
        private const val PREFS = "profiles"
        private const val KEY_LIST = "list"
        private const val KEY_COUNTER = "counter"

        /** Same string as androidx.webkit.Profile.DEFAULT_PROFILE_NAME. */
        const val DEFAULT_ID = "Default"

        val PALETTE = intArrayOf(
            Color.parseColor("#1A73E8"), // blue
            Color.parseColor("#D93025"), // red
            Color.parseColor("#188038"), // green
            Color.parseColor("#E37400"), // orange
            Color.parseColor("#9334E6"), // purple
            Color.parseColor("#129EAF"), // teal
            Color.parseColor("#C2185B"), // pink
            Color.parseColor("#5F6368")  // gray
        )

        @Volatile
        private var instance: ProfileManager? = null

        fun get(context: Context): ProfileManager =
            instance ?: synchronized(this) {
                instance ?: ProfileManager(context.applicationContext).also { instance = it }
            }
    }
}

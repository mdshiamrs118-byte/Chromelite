package com.boxweb.browser.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** History and bookmarks. Writes run on a background scope; callers never block the UI. */
object BrowserRepository {

    private const val MAX_HISTORY = 5000
    /** How many of the newest history rows the "most visited" tiles are counted from. */
    private const val TOP_SITES_SAMPLE = 1000
    /** Reloading the same address within this window updates the row instead of adding a new one. */
    private const val SAME_VISIT_WINDOW_MS = 30 * 60 * 1000L

    private lateinit var db: AppDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        db = AppDatabase.get(context)
    }

    // ---------------------------------------------------------------- history

    fun recordVisit(profileId: String, url: String, title: String) {
        scope.launch {
            val now = System.currentTimeMillis()
            val dao = db.history()
            val last = dao.latest(profileId)
            if (last != null && last.url == url && now - last.visitedAt < SAME_VISIT_WINDOW_MS) {
                dao.update(last.copy(title = title.ifBlank { last.title }, visitedAt = now))
            } else {
                dao.insert(HistoryEntry(profileId = profileId, url = url, title = title, visitedAt = now))
                dao.trim(MAX_HISTORY)
            }
        }
    }

    fun history(profileId: String): Flow<List<HistoryEntry>> = db.history().observe(profileId)

    fun deleteHistoryEntry(id: Long) {
        scope.launch { db.history().delete(id) }
    }

    fun clearHistory(profileId: String) {
        scope.launch { db.history().clearProfile(profileId) }
    }

    fun clearAllHistory() {
        scope.launch { db.history().clearAll() }
    }

    // ---------------------------------------------------------------- bookmarks

    fun bookmarks(): Flow<List<Bookmark>> = db.bookmarks().observe()

    suspend fun isBookmarked(url: String): Boolean = db.bookmarks().count(url) > 0

    fun addBookmark(title: String, url: String) {
        scope.launch {
            db.bookmarks().insert(Bookmark(title = title.ifBlank { url }, url = url, createdAt = System.currentTimeMillis()))
        }
    }

    suspend fun allBookmarks(): List<Bookmark> = withContext(Dispatchers.IO) { db.bookmarks().all() }

    /** Adds (title, url) pairs that are not saved yet. Returns how many were new. */
    suspend fun importBookmarks(items: List<Pair<String, String>>): Int = withContext(Dispatchers.IO) {
        var added = 0
        val now = System.currentTimeMillis()
        items.forEachIndexed { index, (title, url) ->
            val row = Bookmark(title = title.ifBlank { url }, url = url, createdAt = now - index)
            if (db.bookmarks().insert(row) > 0) added++
        }
        added
    }

    fun removeBookmark(url: String) {
        scope.launch { db.bookmarks().deleteByUrl(url) }
    }

    fun deleteBookmark(id: Long) {
        scope.launch { db.bookmarks().delete(id) }
    }

    // ---------------------------------------------------------------- reading list

    fun readingList(): Flow<List<ReadingItem>> = db.reading().observe()

    /** Saves a page to read later; a page that is already saved is left as it is. */
    fun addToReadingList(title: String, url: String) {
        scope.launch {
            db.reading().insert(ReadingItem(title = title.ifBlank { url }, url = url, createdAt = System.currentTimeMillis()))
        }
    }

    fun deleteReadingItem(id: Long) {
        scope.launch { db.reading().delete(id) }
    }

    // ---------------------------------------------------------------- most visited

    /** Origins ("https://example.com") of the sites this profile visited most, most visited first. */
    suspend fun topOrigins(profileId: String, limit: Int): List<String> = withContext(Dispatchers.IO) {
        db.history().recent(profileId, TOP_SITES_SAMPLE)
            .mapNotNull { entry ->
                val uri = Uri.parse(entry.url)
                val host = uri.host
                if (host == null || uri.scheme == null) null else "${uri.scheme}://$host"
            }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }
}

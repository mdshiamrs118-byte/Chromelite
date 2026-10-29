package com.chromelite.browser.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** History and bookmarks. Writes run on a background scope; callers never block the UI. */
object BrowserRepository {

    private const val MAX_HISTORY = 5000
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

    fun removeBookmark(url: String) {
        scope.launch { db.bookmarks().deleteByUrl(url) }
    }

    fun deleteBookmark(id: Long) {
        scope.launch { db.bookmarks().delete(id) }
    }
}

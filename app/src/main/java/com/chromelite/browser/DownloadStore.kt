package com.chromelite.browser

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

enum class DownloadStatus { QUEUED, RUNNING, DONE, FAILED, CANCELED }

data class DownloadItem(
    val id: Long,
    val name: String,
    val mimeType: String,
    /** Where it came from, for display (host name, or "Page data"). */
    val source: String,
    val status: DownloadStatus,
    val bytesDone: Long,
    /** -1 when the size is not known. */
    val totalBytes: Long,
    /** content:// URI (MediaStore) or absolute file path once the download is DONE. */
    val uri: String?,
    val error: String?,
    val createdAt: Long
) {
    val isActive: Boolean get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING
    val percent: Int get() = if (totalBytes > 0) ((bytesDone * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
}

/**
 * Process-wide list of downloads. The engine writes to it from worker threads, the UI reads it
 * and is told about changes on the main thread (coalesced, so fast progress does not flood it).
 */
object DownloadStore {

    interface Listener {
        fun onDownloadsChanged()
    }

    private const val PREFS = "downloads"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 200

    private val items = ArrayList<DownloadItem>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val dispatchPending = AtomicBoolean(false)
    private var prefs: SharedPreferences? = null
    private var nextId = 1L

    @Synchronized
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        items.clear()
        runCatching {
            val array = JSONArray(prefs?.getString(KEY_ITEMS, "[]"))
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                var status = DownloadStatus.valueOf(o.getString("status"))
                var error = o.optString("error").ifEmpty { null }
                // A download that was running when the process died cannot continue.
                if (status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING) {
                    status = DownloadStatus.FAILED
                    error = "Interrupted"
                }
                items += DownloadItem(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    mimeType = o.optString("mime"),
                    source = o.optString("source"),
                    status = status,
                    bytesDone = o.optLong("done"),
                    totalBytes = o.optLong("total", -1),
                    uri = o.optString("uri").ifEmpty { null },
                    error = error,
                    createdAt = o.optLong("created")
                )
            }
        }
        nextId = (items.maxOfOrNull { it.id } ?: 0L) + 1
    }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Newest first. */
    @Synchronized
    fun snapshot(): List<DownloadItem> = items.asReversed().toList()

    @Synchronized
    fun get(id: Long): DownloadItem? = items.firstOrNull { it.id == id }

    @Synchronized
    fun activeCount(): Int = items.count { it.isActive }

    @Synchronized
    fun add(build: (id: Long) -> DownloadItem): DownloadItem {
        val item = build(nextId++)
        items += item
        while (items.size > MAX_ITEMS) {
            val oldestFinished = items.indexOfFirst { !it.isActive }
            if (oldestFinished < 0) break
            items.removeAt(oldestFinished)
        }
        persist()
        dispatch()
        return item
    }

    @Synchronized
    fun update(id: Long, transform: (DownloadItem) -> DownloadItem): DownloadItem? {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return null
        val old = items[index]
        val new = transform(old)
        items[index] = new
        if (old.status != new.status) persist() // progress ticks are not worth a disk write
        dispatch()
        return new
    }

    @Synchronized
    fun remove(id: Long) {
        if (items.removeAll { it.id == id }) {
            persist()
            dispatch()
        }
    }

    /** Drops finished, failed and canceled entries from the list (files are kept). */
    @Synchronized
    fun clearFinished() {
        if (items.removeAll { !it.isActive }) {
            persist()
            dispatch()
        }
    }

    private fun dispatch() {
        if (dispatchPending.compareAndSet(false, true)) {
            main.post {
                dispatchPending.set(false)
                listeners.forEach { it.onDownloadsChanged() }
            }
        }
    }

    private fun persist() {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id).put("name", it.name).put("mime", it.mimeType)
                    .put("source", it.source).put("status", it.status.name)
                    .put("done", it.bytesDone).put("total", it.totalBytes)
                    .put("uri", it.uri ?: "").put("error", it.error ?: "")
                    .put("created", it.createdAt)
            )
        }
        prefs?.edit()?.putString(KEY_ITEMS, array.toString())?.apply()
    }
}

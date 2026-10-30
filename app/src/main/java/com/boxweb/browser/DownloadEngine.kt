package com.boxweb.browser

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Everything needed to fetch an http(s) file the way the page would have. */
class HttpRequest(
    val url: String,
    val userAgent: String?,
    val referer: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    val contentLength: Long,
    /** Cookie header for a URL, taken from the cookie jar of the profile that started the download. */
    val cookieSource: (String) -> String?
)

/**
 * Runs downloads: http(s) files, data: URIs and blob: URLs (streamed from the page in chunks).
 * Progress goes to [DownloadStore] and to notifications; [DownloadService] keeps the process
 * alive while anything is active.
 */
object DownloadEngine {

    private lateinit var app: Context

    private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r, "download-worker") }
    private val canceled: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val blobs = ConcurrentHashMap<Long, BlobTransfer>()
    private val random = SecureRandom()

    private val sweeper by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "download-sweeper").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay({ sweepBlobs() }, 15, 15, TimeUnit.SECONDS) }
    }

    fun init(context: Context) {
        app = context.applicationContext
    }

    // ================================================================ http(s)

    fun enqueueHttp(req: HttpRequest): DownloadItem {
        val mime = req.mimeType?.substringBefore(';')?.trim().orEmpty()
        val name = DownloadStorage.sanitize(URLUtil.guessFileName(req.url, req.contentDisposition, mime))
        val item = DownloadStore.add {
            newItem(it, name, mime, hostOf(req.url), DownloadStatus.QUEUED, req.contentLength.takeIf { l -> l > 0 } ?: -1)
        }
        DownloadService.start(app)
        DownloadNotifications.progress(app, item)
        pool.execute { runHttp(item.id, req) }
        return item
    }

    private fun runHttp(id: Long, req: HttpRequest) {
        var sink: DownloadSink? = null
        var connection: HttpURLConnection? = null
        try {
            if (isCanceled(id)) throw CanceledException()
            publish(id) { it.copy(status = DownloadStatus.RUNNING) }

            val (conn, finalUrl) = connectFollowingRedirects(req)
            connection = conn
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Server returned HTTP $code")

            val mime = conn.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                .ifBlank { req.mimeType?.substringBefore(';')?.trim().orEmpty() }
            val disposition = conn.getHeaderField("Content-Disposition") ?: req.contentDisposition
            val name = DownloadStorage.sanitize(URLUtil.guessFileName(finalUrl, disposition, mime.ifBlank { null }))
            val total = conn.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
                ?: req.contentLength.takeIf { it > 0 } ?: -1L
            publish(id) { it.copy(name = name, mimeType = mime, totalBytes = total) }

            sink = DownloadStorage.open(app, name, mime)
            val out = sink.output
            val buffer = ByteArray(BUFFER_SIZE)
            var done = 0L
            var lastTick = 0L

            conn.inputStream.use { input ->
                while (true) {
                    if (isCanceled(id)) throw CanceledException()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastTick >= PROGRESS_INTERVAL_MS) {
                        lastTick = now
                        publish(id, notify = true) { it.copy(bytesDone = done) }
                    }
                }
            }
            if (total > 0 && done < total) throw IOException("Connection closed early")

            val committed = sink.commit()
            sink = null
            complete(id, committed, done)
        } catch (e: CanceledException) {
            sink?.abort()
            markCanceled(id)
        } catch (e: Exception) {
            sink?.abort()
            fail(id, e.message ?: e.javaClass.simpleName)
        } finally {
            canceled.remove(id)
            connection?.disconnect()
        }
    }

    /** Follows redirects by hand so the right cookies are sent to every host on the way. */
    private fun connectFollowingRedirects(req: HttpRequest): Pair<HttpURLConnection, String> {
        var url = req.url
        for (hop in 0..MAX_REDIRECTS) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Encoding", "identity") // keeps Content-Length truthful
            req.userAgent?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("User-Agent", it) }
            req.referer?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Referer", it) }
            req.cookieSource(url)?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }

            val code = conn.responseCode
            if (code in 300..399 && code != 304) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("Redirect without a location")
                url = URL(URL(url), location).toString()
                if (!UrlUtils.isWebUrl(url)) throw IOException("Redirect to an unsupported address")
                continue
            }
            return conn to url
        }
        throw IOException("Too many redirects")
    }

    // ================================================================ data: URIs

    fun enqueueData(dataUri: String, suggestedName: String? = null): DownloadItem {
        val comma = dataUri.indexOf(',')
        val meta = if (comma > 5) dataUri.substring(5, comma) else ""
        val parts = meta.split(';')
        val mime = parts.firstOrNull()?.trim()?.lowercase()?.takeIf { it.contains('/') } ?: "text/plain"
        val base64 = parts.any { it.trim().equals("base64", ignoreCase = true) }
        val charsetName = parts.firstOrNull { it.trim().startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')?.trim()
        val name = DownloadStorage.sanitize(suggestedName ?: timestampName(mime))

        val item = DownloadStore.add { newItem(it, name, mime, app.getString(R.string.dl_source_data), DownloadStatus.QUEUED, -1) }
        DownloadService.start(app)
        DownloadNotifications.progress(app, item)

        pool.execute {
            var sink: DownloadSink? = null
            try {
                if (comma < 0) throw IOException("Malformed data address")
                publish(item.id) { it.copy(status = DownloadStatus.RUNNING) }
                val payload = dataUri.substring(comma + 1)
                val bytes = if (base64) {
                    val clean = if (payload.contains('%')) Uri.decode(payload) else payload
                    Base64.decode(clean, Base64.DEFAULT)
                } else {
                    Uri.decode(payload).toByteArray(runCatching { charset(charsetName ?: "UTF-8") }.getOrDefault(Charsets.UTF_8))
                }
                publish(item.id) { it.copy(totalBytes = bytes.size.toLong()) }

                sink = DownloadStorage.open(app, name, mime)
                sink.output.write(bytes)
                val committed = sink.commit()
                sink = null
                complete(item.id, committed, bytes.size.toLong())
            } catch (e: Exception) {
                sink?.abort()
                fail(item.id, e.message ?: e.javaClass.simpleName)
            }
        }
        return item
    }

    // ================================================================ blob: URLs

    private class BlobTransfer(
        val id: Long,
        val token: String,
        val mimeHint: String,
        val nameHint: String?
    ) {
        var sink: DownloadSink? = null
        var written = 0L
        var lastTick = 0L
        @Volatile var lastActive = SystemClock.elapsedRealtime()
    }

    /**
     * Registers a blob download and returns (id, secret). The page-side script gets both and has
     * to present them on every call, so web pages cannot write files through the JS bridge.
     */
    fun startBlob(mimeHint: String, nameHint: String?): Pair<Long, String> {
        val token = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val provisional = DownloadStorage.sanitize(nameHint ?: timestampName(mimeHint))
        val item = DownloadStore.add {
            newItem(it, provisional, mimeHint, app.getString(R.string.dl_source_data), DownloadStatus.RUNNING, -1)
        }
        blobs[item.id] = BlobTransfer(item.id, token, mimeHint, nameHint)
        sweeper // starts the stale-transfer watchdog
        DownloadService.start(app)
        DownloadNotifications.progress(app, item)
        return item.id to token
    }

    /** Called by the page script once it has the blob. Returns false to tell it to stop. */
    fun blobBegin(id: Long, token: String, size: Long, mime: String): Boolean {
        val t = transfer(id, token) ?: return false
        synchronized(t) {
            if (isCanceled(id)) return false
            return try {
                val type = mime.substringBefore(';').trim().ifBlank { t.mimeHint }.ifBlank { "application/octet-stream" }
                val name = DownloadStorage.sanitize(t.nameHint ?: timestampName(type))
                t.sink = DownloadStorage.open(app, name, type)
                t.lastActive = SystemClock.elapsedRealtime()
                publish(id, notify = true) { it.copy(name = name, mimeType = type, totalBytes = size.takeIf { s -> s > 0 } ?: -1) }
                true
            } catch (e: Exception) {
                failBlob(t, e.message ?: e.javaClass.simpleName)
                false
            }
        }
    }

    /** One base64 slice of the blob. Returns false when the transfer was canceled or failed. */
    fun blobChunk(id: Long, token: String, base64: String): Boolean {
        val t = transfer(id, token) ?: return false
        synchronized(t) {
            val sink = t.sink ?: return false
            if (isCanceled(id)) {
                cancelBlob(t)
                return false
            }
            return try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                sink.output.write(bytes)
                t.written += bytes.size
                t.lastActive = SystemClock.elapsedRealtime()
                val now = SystemClock.elapsedRealtime()
                if (now - t.lastTick >= PROGRESS_INTERVAL_MS) {
                    t.lastTick = now
                    publish(id, notify = true) { it.copy(bytesDone = t.written) }
                }
                true
            } catch (e: Exception) {
                failBlob(t, e.message ?: e.javaClass.simpleName)
                false
            }
        }
    }

    fun blobFinish(id: Long, token: String) {
        val t = transfer(id, token) ?: return
        synchronized(t) {
            val sink = t.sink ?: return failBlob(t, "No data received")
            try {
                val committed = sink.commit()
                t.sink = null
                blobs.remove(id)
                complete(id, committed, t.written)
            } catch (e: Exception) {
                failBlob(t, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun blobFail(id: Long, token: String, message: String) {
        val t = transfer(id, token) ?: return
        synchronized(t) { failBlob(t, message.take(120)) }
    }

    private fun transfer(id: Long, token: String): BlobTransfer? =
        blobs[id]?.takeIf { java.security.MessageDigest.isEqual(it.token.toByteArray(), token.toByteArray()) }

    private fun failBlob(t: BlobTransfer, message: String) {
        t.sink?.abort()
        t.sink = null
        blobs.remove(t.id)
        canceled.remove(t.id)
        fail(t.id, message)
    }

    private fun cancelBlob(t: BlobTransfer) {
        t.sink?.abort()
        t.sink = null
        blobs.remove(t.id)
        canceled.remove(t.id)
        markCanceled(t.id)
    }

    /** Fails transfers whose page went away (navigated, closed) before sending everything. */
    private fun sweepBlobs() {
        val now = SystemClock.elapsedRealtime()
        blobs.values.toList().forEach { t ->
            if (now - t.lastActive > BLOB_IDLE_TIMEOUT_MS) {
                synchronized(t) { if (blobs.containsKey(t.id)) failBlob(t, "Transfer interrupted") }
            }
        }
    }

    // ================================================================ cancel

    fun cancel(id: Long) {
        val item = DownloadStore.get(id) ?: return
        if (!item.isActive) return
        canceled += id
        blobs[id]?.let { t -> synchronized(t) { if (blobs.containsKey(id)) cancelBlob(t) } }
    }

    private fun isCanceled(id: Long) = canceled.contains(id)

    private class CanceledException : Exception()

    // ================================================================ state helpers

    private fun publish(id: Long, notify: Boolean = false, transform: (DownloadItem) -> DownloadItem) {
        val updated = DownloadStore.update(id, transform) ?: return
        if (notify) DownloadNotifications.progress(app, updated)
    }

    private fun complete(id: Long, committed: DownloadSink.Committed, size: Long) {
        val updated = DownloadStore.update(id) {
            it.copy(
                status = DownloadStatus.DONE,
                name = committed.displayName.ifBlank { it.name },
                bytesDone = size,
                totalBytes = size,
                uri = committed.uri,
                error = null
            )
        } ?: return
        DownloadNotifications.finished(app, updated)
    }

    private fun fail(id: Long, message: String) {
        val updated = DownloadStore.update(id) { it.copy(status = DownloadStatus.FAILED, error = message) } ?: return
        DownloadNotifications.finished(app, updated)
    }

    private fun markCanceled(id: Long) {
        val updated = DownloadStore.update(id) { it.copy(status = DownloadStatus.CANCELED) } ?: return
        DownloadNotifications.finished(app, updated)
    }

    private fun newItem(
        id: Long, name: String, mime: String, source: String, status: DownloadStatus, total: Long
    ) = DownloadItem(
        id = id, name = name, mimeType = mime, source = source, status = status,
        bytesDone = 0, totalBytes = total, uri = null, error = null, createdAt = System.currentTimeMillis()
    )

    private fun hostOf(url: String): String = Uri.parse(url).host ?: url

    /** "download_20261029_001500.png" - used when the page gives no file name. */
    fun timestampName(mime: String): String {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime.substringBefore(';').trim()) ?: "bin"
        return "download_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.$ext"
    }

    private const val BUFFER_SIZE = 64 * 1024
    private const val PROGRESS_INTERVAL_MS = 400L
    private const val MAX_REDIRECTS = 8
    private const val BLOB_IDLE_TIMEOUT_MS = 45_000L
}

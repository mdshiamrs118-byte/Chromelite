package com.boxweb.browser

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * JavaScript side of blob: downloads.
 *
 * A WebView cannot read a blob: URL from native code, so the page itself reads the blob and
 * hands it over in base64 slices. Every call must carry the id + secret that [DownloadEngine.startBlob]
 * issued for that one download; calls without a valid pair are ignored, so a web page cannot use
 * this object to write files on its own.
 */
class BlobBridge {

    @JavascriptInterface
    fun begin(id: String, token: String, size: Double, mime: String): Boolean =
        id.toLongOrNull()?.let { DownloadEngine.blobBegin(it, token, size.toLong(), mime) } ?: false

    @JavascriptInterface
    fun chunk(id: String, token: String, base64: String): Boolean =
        id.toLongOrNull()?.let { DownloadEngine.blobChunk(it, token, base64) } ?: false

    @JavascriptInterface
    fun finish(id: String, token: String) {
        id.toLongOrNull()?.let { DownloadEngine.blobFinish(it, token) }
    }

    @JavascriptInterface
    fun fail(id: String, token: String, message: String) {
        id.toLongOrNull()?.let { DownloadEngine.blobFail(it, token, message) }
    }
}

object BlobScripts {

    const val BRIDGE_NAME = "BoxWebDl"

    /**
     * Runs at document start in every frame. Remembers each Blob handed to URL.createObjectURL and
     * delays URL.revokeObjectURL, because many pages revoke the URL right after clicking the
     * download link, which would otherwise make the blob unreadable by the time we get to it.
     */
    val HOOK = """
        (function () {
          if (window.__clHooked) return;
          window.__clHooked = true;
          var cache = {};
          window.__clBlobs = cache;
          var create = URL.createObjectURL, revoke = URL.revokeObjectURL;
          URL.createObjectURL = function (obj) {
            var u = create.call(URL, obj);
            try { if (obj instanceof Blob) cache[u] = obj; } catch (e) {}
            return u;
          };
          URL.revokeObjectURL = function (u) {
            setTimeout(function () {
              try { revoke.call(URL, u); } catch (e) {}
              delete cache[u];
            }, 120000);
          };
        })();
    """.trimIndent()

    /** Reads [blobUrl] inside the page and streams it to [BlobBridge] in 384 KB slices. */
    fun fetch(blobUrl: String, id: Long, token: String): String = """
        (function () {
          var B = window.$BRIDGE_NAME;
          var id = ${JSONObject.quote(id.toString())};
          var t = ${JSONObject.quote(token)};
          var url = ${JSONObject.quote(blobUrl)};
          var CH = 393216;
          if (!B) return;
          function fail(m) { try { B.fail(id, t, String(m)); } catch (e) {} }
          function send(blob) {
            if (!B.begin(id, t, blob.size, blob.type || "")) return;
            var off = 0;
            (function next() {
              if (off >= blob.size) { B.finish(id, t); return; }
              var fr = new FileReader();
              fr.onload = function () {
                var s = fr.result;
                var ok = B.chunk(id, t, s.substring(s.indexOf(',') + 1));
                off += CH;
                if (ok) next();
              };
              fr.onerror = function () { fail('read error'); };
              fr.readAsDataURL(blob.slice(off, off + CH));
            })();
          }
          var cached = window.__clBlobs && window.__clBlobs[url];
          if (cached) { send(cached); return; }
          var x = new XMLHttpRequest();
          x.open('GET', url);
          x.responseType = 'blob';
          x.onload = function () { send(x.response); };
          x.onerror = function () { fail('could not read the file'); };
          x.send();
        })();
    """.trimIndent()
}

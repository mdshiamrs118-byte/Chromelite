package com.chromelite.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * One browsing surface: a WebView plus the state the toolbar needs (url, title, progress, security).
 * The activity owns one pane normally and two when split view is on.
 *
 * Step 3 will replace how [webView] is created so each pane can run in an isolated profile.
 */
class BrowserPane(
    val webView: WebView,
    val indicator: View,
    private val listener: Listener
) {

    interface Listener {
        /** The user touched this pane (used to make it the active pane). */
        fun onPaneTouched(pane: BrowserPane)

        /** url / title / progress / loading / security changed. */
        fun onPaneChanged(pane: BrowserPane)
    }

    enum class Security { SECURE, INSECURE, NEUTRAL }

    var url: String = ""
        private set
    var title: String = ""
        private set
    var progress: Int = 0
        private set
    var isLoading: Boolean = false
        private set

    private var sslError = false

    val security: Security
        get() = when {
            sslError -> Security.INSECURE
            url.startsWith("https://", ignoreCase = true) -> Security.SECURE
            url.startsWith("http://", ignoreCase = true) -> Security.INSECURE
            else -> Security.NEUTRAL
        }

    private val client = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase()
            if (scheme in INTERNAL_SCHEMES) return false
            openExternally(request.url.toString())
            return true
        }

        override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
            url = pageUrl.orEmpty()
            title = ""
            progress = 0
            isLoading = true
            sslError = false
            changed()
        }

        override fun onPageFinished(view: WebView?, pageUrl: String?) {
            url = pageUrl ?: url
            title = view?.title ?: title
            progress = 100
            isLoading = false
            changed()
        }

        // Also fires for in-page (single-page-app) navigation, so the omnibox stays accurate.
        override fun doUpdateVisitedHistory(view: WebView?, visitedUrl: String?, isReload: Boolean) {
            if (!visitedUrl.isNullOrEmpty()) {
                url = visitedUrl
                changed()
            }
        }

        // Invalid certificates are refused (default behaviour) and flagged in the omnibox.
        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
            sslError = true
            handler.cancel()
            changed()
        }
    }

    private val chrome = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            progress = newProgress
            changed()
        }

        override fun onReceivedTitle(view: WebView?, newTitle: String?) {
            title = newTitle.orEmpty()
            changed()
        }

        // target="_blank" / window.open(): load the result in this pane instead of ignoring it.
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            val msg = resultMsg ?: return false
            val transport = msg.obj as? WebView.WebViewTransport ?: return false

            val popup = WebView(webView.context)
            popup.webViewClient = object : WebViewClient() {
                private var handled = false

                private fun redirect(v: WebView, target: String?) {
                    if (handled || target.isNullOrBlank() || target == "about:blank") return
                    handled = true
                    if (UrlUtils.isWebUrl(target)) webView.loadUrl(target) else openExternally(target)
                    v.post { v.destroy() }
                }

                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    redirect(v, request.url.toString())
                    return true
                }

                override fun onPageStarted(v: WebView?, pageUrl: String?, favicon: Bitmap?) {
                    if (v != null) redirect(v, pageUrl)
                }
            }
            transport.webView = popup
            msg.sendToTarget()
            return true
        }
    }

    init {
        configure()
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun configure() {
        // Minimal settings so pages work now; Step 5 does the full WebSettings tuning.
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        webView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) listener.onPaneTouched(this)
            false // never consume: the WebView still handles the touch
        }
        webView.webViewClient = client
        webView.webChromeClient = chrome
    }

    fun load(target: String) = webView.loadUrl(target)

    fun reload() {
        if (url.isBlank()) load(UrlUtils.HOME) else webView.reload()
    }

    fun stop() {
        webView.stopLoading()
        isLoading = false
        changed()
    }

    fun onResume() = webView.onResume()

    fun onPause() = webView.onPause()

    fun destroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.webChromeClient = null
        webView.destroy()
    }

    private fun changed() = listener.onPaneChanged(this)

    /** Hands mailto:, tel:, intent: ... links to other apps. */
    private fun openExternally(link: String) {
        val context = webView.context
        try {
            val intent = if (link.startsWith("intent:", ignoreCase = true)) {
                Intent.parseUri(link, Intent.URI_INTENT_SCHEME).apply {
                    // Sanitize: a web page must not target a specific component or selector.
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(link))
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            if (link.startsWith("intent:", ignoreCase = true)) {
                val fallback = runCatching {
                    Intent.parseUri(link, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
                }.getOrNull()
                if (UrlUtils.isWebUrl(fallback)) webView.loadUrl(fallback!!)
            }
        }
    }

    private companion object {
        val INTERNAL_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript", "file")
    }
}

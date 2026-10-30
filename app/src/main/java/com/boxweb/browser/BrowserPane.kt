package com.boxweb.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.os.Build
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * One browsing surface: a WebView plus the state the toolbar needs (url, title, progress, security).
 * The activity owns one pane normally and two when split view is on.
 *
 * The WebView is bound to an isolated profile ([profileId]) by MainActivity before it gets here.
 */
class BrowserPane(
    val webView: WebView,
    /** Wraps [webView]; provides pull-to-refresh. */
    val swipe: SwipeRefreshLayout,
    /** Profile this WebView was bound to (fixed for the life of the WebView). */
    val profileId: String,
    private val listener: Listener
) {

    interface Listener {
        /** The user touched this pane (used to make it the active pane). */
        fun onPaneTouched(pane: BrowserPane)

        /** url / title / progress / loading / security changed. */
        fun onPaneChanged(pane: BrowserPane)

        /** The page started a download (file link, blob:, data:, attachment...). */
        fun onDownloadRequested(
            pane: BrowserPane,
            url: String,
            userAgent: String?,
            contentDisposition: String?,
            mimeType: String?,
            contentLength: Long
        )

        /** Long-press on an image or link. [hitType] is a WebView.HitTestResult type. */
        fun onLongPress(pane: BrowserPane, hitType: Int, target: String)

        /** A page finished loading (used for history). */
        fun onPageVisited(pane: BrowserPane, url: String, title: String)

        /** Find-in-page result for this pane ([active] is zero-based). */
        fun onFindResult(pane: BrowserPane, active: Int, total: Int)

        /** The page wants the user to pick files to upload. Return true if a picker was launched. */
        fun onFileChooser(
            pane: BrowserPane,
            callback: ValueCallback<Array<Uri>>,
            params: WebChromeClient.FileChooserParams
        ): Boolean
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

    /** True when the blob helper script runs before page scripts (otherwise it is injected late). */
    private var blobHookAtDocumentStart = false

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
            if (!blobHookAtDocumentStart) view?.evaluateJavascript(BlobScripts.HOOK, null)
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
            if (UrlUtils.isWebUrl(url)) listener.onPageVisited(this@BrowserPane, url, title)
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

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean = listener.onFileChooser(this@BrowserPane, filePathCallback, fileChooserParams)

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
    @Suppress("DEPRECATION") // legacy WebSettings still worth setting on older WebViews
    private fun configure() {
        webView.settings.apply {
            // --- features pages rely on ---
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT // honour HTTP caching headers: fastest correct behaviour

            // --- zoom / viewport ---
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false // pinch zoom without the on-screen +/- buttons
            useWideViewPort = true

            // --- hardening: pages must not read local files or app content ---
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            saveFormData = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        webView.overScrollMode = View.OVER_SCROLL_NEVER // the pull-to-refresh spinner replaces the glow

        applyBrowserSettings()

        swipe.setColorSchemeColors(ContextCompat.getColor(webView.context, R.color.chrome_accent))
        swipe.setOnRefreshListener { reload() }
        // Only start a refresh when the page itself is scrolled to the very top.
        swipe.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
        webView.setFindListener { active, total, _ -> listener.onFindResult(this, active, total) }
        webView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) listener.onPaneTouched(this)
            false // never consume: the WebView still handles the touch
        }
        webView.webViewClient = client
        webView.webChromeClient = chrome

        // ---- downloads ----
        webView.setDownloadListener { downloadUrl, userAgent, disposition, mime, length ->
            listener.onDownloadRequested(this, downloadUrl, userAgent, disposition, mime, length)
        }
        // Bridge used to pull blob: data out of the page (calls need a per-download secret).
        webView.addJavascriptInterface(BlobBridge(), BlobScripts.BRIDGE_NAME)
        blobHookAtDocumentStart = runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, BlobScripts.HOOK, setOf("*"))
                true
            } else {
                false
            }
        }.getOrDefault(false)

        webView.setOnLongClickListener {
            val hit = webView.hitTestResult
            val target = hit.extra
            val handled = hit.type in LONG_PRESS_TYPES && !target.isNullOrBlank()
            if (handled) listener.onLongPress(this, hit.type, target!!)
            handled // false keeps the normal long-press behaviour (text selection etc.)
        }
    }

    /** Address a restored (background) tab will load the first time it is shown. */
    private var pendingUrl: String? = null

    /** Registers a tab without loading it yet; [loadIfPending] does the loading once it is on screen. */
    fun restore(address: String, savedTitle: String) {
        pendingUrl = address
        url = address
        title = savedTitle
    }

    fun loadIfPending() {
        pendingUrl?.let {
            pendingUrl = null
            webView.loadUrl(it)
        }
    }

    fun load(target: String) {
        pendingUrl = null
        webView.loadUrl(target)
    }

    fun reload() {
        when {
            pendingUrl != null -> loadIfPending()
            url.isBlank() -> load(UrlUtils.HOME)
            else -> webView.reload()
        }
    }

    fun stop() {
        webView.stopLoading()
        isLoading = false
        changed()
    }

    fun onResume() = webView.onResume()

    fun onPause() = webView.onPause()

    /**
     * Applies the settings the user can change at runtime: desktop / mobile user agent and
     * pull-to-refresh. Call again after they change; it does not reload the page.
     */
    fun applyBrowserSettings() {
        val context = webView.context
        val desktop = BrowserSettings.desktopSite(context)
        webView.settings.apply {
            userAgentString = if (desktop) UserAgents.desktop(context) else UserAgents.mobile(context)
            loadWithOverviewMode = desktop // fit the desktop layout to the screen width
            layoutAlgorithm = if (desktop) {
                WebSettings.LayoutAlgorithm.NORMAL
            } else {
                WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            }
        }
        swipe.isEnabled = BrowserSettings.pullToRefresh(context)
    }

    fun destroy() {
        (swipe.parent as? ViewGroup)?.removeView(swipe)
        swipe.removeAllViews()
        webView.stopLoading()
        webView.webChromeClient = null
        webView.removeJavascriptInterface(BlobScripts.BRIDGE_NAME)
        webView.destroy()
    }

    private fun changed() {
        if (!isLoading && swipe.isRefreshing) swipe.isRefreshing = false
        listener.onPaneChanged(this)
    }

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

        val LONG_PRESS_TYPES = setOf(
            WebView.HitTestResult.IMAGE_TYPE,
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
            WebView.HitTestResult.SRC_ANCHOR_TYPE
        )
    }
}

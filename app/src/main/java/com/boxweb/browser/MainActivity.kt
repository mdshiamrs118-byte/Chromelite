package com.boxweb.browser

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.print.PrintAttributes
import android.print.PrintManager
import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.boxweb.browser.data.BrowserRepository
import com.boxweb.browser.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), BrowserPane.Listener, ProfileDialogs.Host, TabSwitcher.Host,
    BrowserSettings.Host {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var profiles: ProfileManager
    private lateinit var profileDialogs: ProfileDialogs
    private lateinit var downloads: DownloadController
    private lateinit var clearDataDialog: ClearDataDialog
    private lateinit var tabSwitcher: TabSwitcher
    private val mainMenu by lazy { MainMenu(this) }

    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            fileCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
            fileCallback = null
        }

    private val libraryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.getStringExtra(LibraryActivity.EXTRA_URL)?.let { activePane.load(it) }
            }
        }

    private val exportBookmarksLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) result.data?.data?.let { writeBookmarks(it) }
        }

    private val importBookmarksLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) result.data?.data?.let { readBookmarks(it) }
        }

    /** Every open tab (each one a WebView bound to exactly one profile). */
    private val tabs = ArrayList<BrowserPane>()

    /** The tabs currently on screen: [paneA] always, [paneB] only while split view is on. */
    private lateinit var paneA: BrowserPane
    private var paneB: BrowserPane? = null

    /** The one of the two (or the only) visible tabs the toolbar and menus act on. */
    private lateinit var activePane: BrowserPane

    private var splitEnabled = false
    private var fullscreen = false

    /** Recently closed tabs, newest last, for "Reopen closed tab". */
    private class ClosedTab(val url: String, val title: String, val profileId: String)
    private val closedTabs = ArrayList<ClosedTab>()
    private var reader: PageReader? = null

    // App lock: the phone's own screen lock is asked for after the app has been away for a while.
    private var locked = false
    private var awaitingCredential = false
    private var stoppedAt = 0L
    private var lockCover: View? = null

    private val unlockLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            awaitingCredential = false
            if (result.resultCode == RESULT_OK) unlockNow()
        }
    private var splitStacked = false // false = side by side, true = top & bottom
    private var splitRatio = 0.5f // share of the split area given to pane A

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        profiles = ProfileManager.get(this)
        profiles.wipeIncognito() // anything a previous run left behind
        profileDialogs = ProfileDialogs(this, profiles, this)
        downloads = DownloadController(this, profiles, binding.root)
        clearDataDialog = ClearDataDialog(
            this, profiles,
            currentProfile = { profiles.resolve(if (activePane.incognito) null else activePane.profileId) },
            livePanes = { tabs.toList() }
        )
        tabSwitcher = TabSwitcher(this, profiles, this)

        splitStacked = prefs.getBoolean(KEY_STACKED, false)
        splitRatio = prefs.getFloat(KEY_RATIO, 0.5f).coerceIn(MIN_RATIO, 1f - MIN_RATIO)
        restoreTabs()
        locked = BrowserSettings.appLock(this)

        setupOmnibox()
        setupToolbar()
        applyToolbarPosition()
        setupFindBar()
        setupSplitDivider()
        setupBackNavigation()
        applySplitLayout()
        render()

        // Only for a fresh launch: after the system restarts the app it would re-deliver the same link.
        if (savedInstanceState == null) webUrlFrom(intent)?.let { openLink(it) }
    }

    override fun onStart() {
        super.onStart()
        checkLock()
    }

    override fun onStop() {
        if (!awaitingCredential) stoppedAt = SystemClock.elapsedRealtime()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        webUrlFrom(intent)?.let { openLink(it) }
    }

    /** A link from another app opens in its own tab, in the profile of the tab being used. */
    private fun openLink(url: String) {
        tryNewTab(normalProfileId(), url)?.let { showTab(it) }
    }

    override fun onResume() {
        super.onResume()
        paneA.onResume()
        if (splitEnabled) paneB?.onResume()
    }

    override fun onPause() {
        saveState()
        // Persist every profile's cookies so a killed process does not lose the logins.
        tabs.map { it.profileId }.distinct().forEach { profiles.flush(it) }
        tabs.forEach { it.onPause() }
        super.onPause()
    }

    override fun onDestroy() {
        reader?.shutdown()
        val hadIncognito = tabs.any { it.incognito }
        tabs.forEach { it.destroy() }
        tabs.clear()
        if (hadIncognito) profiles.wipeIncognito()
        super.onDestroy()
    }

    /** Remembers all tabs (address, title, profile) and which ones are on screen. */
    private fun saveState() {
        // Incognito tabs are never written down.
        val saved = tabs.filter { !it.incognito }
        val array = JSONArray()
        saved.forEach {
            array.put(
                JSONObject().put("url", it.url).put("title", it.title).put("profile", it.profileId)
            )
        }
        prefs.edit()
            .putString(KEY_TABS, array.toString())
            .putInt(KEY_SLOT_A, saved.indexOf(paneA))
            .putInt(KEY_SLOT_B, paneB?.let { saved.indexOf(it) } ?: -1)
            .putString(KEY_ACTIVE, if (activePane === paneB) "B" else "A")
            .putBoolean(KEY_SPLIT, splitEnabled)
            .apply()
    }

    private fun restoreTabs() {
        runCatching {
            val array = JSONArray(prefs.getString(KEY_TABS, null) ?: "[]")
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                createTab(
                    profiles.resolve(o.optString("profile")).id,
                    o.optString("url").ifBlank { UrlUtils.HOME },
                    o.optString("title")
                )
            }
        }
        if (tabs.isEmpty()) {
            // First start, or upgrading from a version that stored a single address per pane.
            createTab(
                profiles.resolve(prefs.getString(KEY_PROFILE_A, null)).id,
                prefs.getString(KEY_URL_A, null) ?: UrlUtils.HOME,
                ""
            )
        }

        val a = prefs.getInt(KEY_SLOT_A, 0).coerceIn(0, tabs.size - 1)
        val b = prefs.getInt(KEY_SLOT_B, -1)
        splitEnabled = prefs.getBoolean(KEY_SPLIT, false) && b in tabs.indices && b != a

        place(true, tabs[a])
        if (splitEnabled) place(false, tabs[b])
        activePane = if (splitEnabled && prefs.getString(KEY_ACTIVE, "A") == "B") paneB!! else paneA
    }

    private fun webUrlFrom(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_VIEW) intent.dataString?.takeIf { UrlUtils.isWebUrl(it) } else null

    // ---------------------------------------------------------------- tabs

    /**
     * Builds a tab: a WebView bound to [profileId] (attached before the WebView is configured or
     * loads anything; if the profile cannot be applied the tab honestly reports the default one).
     * The page loads the first time the tab is shown.
     */
    private fun createTab(profileId: String, address: String, savedTitle: String): BrowserPane {
        val webView = WebView(this)
        val boundTo = if (profiles.attach(webView, profileId)) profileId else profiles.default.id
        return buildTab(webView, boundTo, address, savedTitle, incognito = false)
    }

    /** A private tab on the hidden incognito profile; null when the WebView cannot isolate it. */
    private fun createIncognitoTab(address: String): BrowserPane? {
        val webView = WebView(this)
        if (!profiles.attach(webView, ProfileManager.INCOGNITO_ID)) {
            webView.destroy()
            return null
        }
        return buildTab(webView, ProfileManager.INCOGNITO_ID, address, "", incognito = true)
    }

    private fun buildTab(
        webView: WebView,
        boundTo: String,
        address: String,
        savedTitle: String,
        incognito: Boolean
    ): BrowserPane {
        val swipe = SwipeRefreshLayout(this)
        swipe.addView(
            webView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        // Third-party cookies are needed by many sign-in flows (embedded OAuth frames).
        profiles.cookieManager(boundTo).setAcceptThirdPartyCookies(webView, true)

        val tab = BrowserPane(webView, swipe, boundTo, this, incognito)
        tab.restore(address, savedTitle)
        tab.onPause() // background until it is placed on screen
        tabs += tab
        return tab
    }

    private fun tryNewTab(profileId: String, address: String): BrowserPane? {
        if (tabs.size >= MAX_TABS) {
            Toast.makeText(this, getString(R.string.tabs_limit, MAX_TABS), Toast.LENGTH_SHORT).show()
            return null
        }
        return createTab(profileId, address, "")
    }

    private fun clearContainer(container: FrameLayout) {
        for (i in container.childCount - 1 downTo 0) {
            if (container.getChildAt(i) is SwipeRefreshLayout) container.removeViewAt(i)
        }
    }

    /** Puts [tab] on screen in pane A or B, replacing whatever that pane showed. */
    private fun place(inA: Boolean, tab: BrowserPane) {
        val container = if (inA) binding.paneAContainer else binding.paneBContainer
        clearContainer(container)
        (tab.swipe.parent as? ViewGroup)?.removeView(tab.swipe)
        container.addView(
            tab.swipe, 0,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        if (inA) paneA = tab else paneB = tab
        tab.onResume()
        tab.loadIfPending()
    }

    /**
     * Shows [tab] in the pane the user is working in. If it is already on screen in the other
     * pane, the two panes swap places. The tab that was replaced stays open in the background.
     */
    override fun showTab(tab: BrowserPane) {
        val old = activePane
        if (tab === old || tab !in tabs) return
        closeFind()

        old.captureThumbnail()
        val inA = old === paneA
        val other = if (inA) paneB else paneA
        if (tab === other) {
            place(inA, tab)
            place(!inA, old)
        } else {
            place(inA, tab)
            old.onPause()
        }
        activePane = tab
        render()
    }

    override fun closeTab(tab: BrowserPane) {
        if (tab !in tabs) return
        val inA = tab === paneA
        val inB = tab === paneB
        val wasActive = tab === activePane
        if (wasActive) closeFind()
        tabs.remove(tab)

        if (inA || inB) {
            val free = tabs.firstOrNull { it !== paneA && it !== paneB }
            when {
                free != null -> place(inA, free)
                inB -> collapseSplitTo(paneA)
                splitEnabled -> collapseSplitTo(paneB!!)
                else -> place(true, createTab(profiles.resolve(tab.profileId).id, UrlUtils.HOME, ""))
            }
        }
        if (!tab.incognito && UrlUtils.isWebUrl(tab.url)) {
            closedTabs += ClosedTab(tab.url, tab.title, tab.profileId)
            if (closedTabs.size > MAX_CLOSED) closedTabs.removeAt(0)
        }
        if (tab.incognito) tab.webView.clearCache(true)
        tab.destroy()
        if (tab.incognito && tabs.none { it.incognito }) profiles.wipeIncognito()

        if (wasActive) activePane = if (inA) paneA else (paneB ?: paneA)
        applySplitLayout()
        render()
    }

    override fun newTab() {
        tryNewTab(normalProfileId(), UrlUtils.HOME)?.let {
            showTab(it)
            binding.etOmnibox.requestFocus()
        }
    }

    /** New tab that leaves no history and erases its cookies and storage when it is closed. */
    private fun newIncognitoTab(address: String = UrlUtils.HOME) {
        if (!profiles.incognitoSupported) {
            Snackbar.make(binding.root, R.string.incognito_unsupported, Snackbar.LENGTH_LONG).show()
            return
        }
        if (tabs.size >= MAX_TABS) {
            Toast.makeText(this, getString(R.string.tabs_limit, MAX_TABS), Toast.LENGTH_SHORT).show()
            return
        }
        val tab = createIncognitoTab(address)
        if (tab == null) {
            Snackbar.make(binding.root, R.string.incognito_unsupported, Snackbar.LENGTH_LONG).show()
            return
        }
        showTab(tab)
        if (address == UrlUtils.HOME) binding.etOmnibox.requestFocus()
        Snackbar.make(binding.root, R.string.incognito_opened, Snackbar.LENGTH_LONG).show()
    }

    /** Profile for tabs opened from outside the current tab: never the hidden incognito one. */
    private fun normalProfileId(): String = if (activePane.incognito) profiles.default.id else activePane.profileId

    private fun duplicateTab() {
        val source = activePane
        val address = source.url.takeIf { UrlUtils.isWebUrl(it) } ?: UrlUtils.HOME
        if (source.incognito) newIncognitoTab(address) else tryNewTab(source.profileId, address)?.let { showTab(it) }
    }

    private fun reopenClosedTab() {
        val last = closedTabs.removeLastOrNull() ?: return
        tryNewTab(profiles.resolve(last.profileId).id, last.url)?.let { showTab(it) }
    }

    /** Closes every tab except the one in use. They can be brought back with "Reopen closed tab". */
    private fun closeOtherTabs() {
        val keep = activePane
        tabs.toList().filter { it !== keep }.forEach { closeTab(it) }
    }

    private fun addToHomeScreen() {
        val url = activePane.url
        if (!UrlUtils.isWebUrl(url)) return
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            Toast.makeText(this, R.string.shortcut_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        val label = activePane.title.ifBlank { UrlUtils.host(url) }.take(24)
        val open = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url))
        val info = ShortcutInfoCompat.Builder(this, "site-" + url.hashCode())
            .setShortLabel(label)
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(open)
            .build()
        ShortcutManagerCompat.requestPinShortcut(this, info, null)
    }

    private fun colorOf(tab: BrowserPane): Int =
        if (tab.incognito) ProfileManager.INCOGNITO_COLOR else profiles.resolve(tab.profileId).color

    private fun profileNameOf(tab: BrowserPane): String =
        if (tab.incognito) getString(R.string.incognito_name) else profiles.resolve(tab.profileId).name

    override fun tabs(): List<BrowserPane> = tabs

    override fun paneNumberOf(tab: BrowserPane): Int = when {
        tab === paneA -> 1
        tab === paneB -> 2
        else -> 0
    }

    override fun activeTab(): BrowserPane = activePane

    override fun splitEnabled(): Boolean = splitEnabled

    // ---------------------------------------------------------------- split view

    override fun toggleSplit() {
        if (splitEnabled) collapseSplitTo(activePane) else showSplitPicker()
    }

    /** Turning split view on asks which tab goes into the second pane: an open one, or a new one. */
    private fun showSplitPicker() {
        val others = tabs.filter { it !== paneA }
        val labels = others.map { tabLabel(it) } + getString(R.string.split_new_tab)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.split_pick_title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < others.size) {
                    enableSplit(others[which])
                } else {
                    profileDialogs.pickProfile(R.string.open_new_tab_title) { profileId ->
                        tryNewTab(profileId, UrlUtils.HOME)?.let { enableSplit(it) }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** "Page title · Profile name" - how a tab is listed when choosing one. */
    private fun tabLabel(tab: BrowserPane): String {
        val name = tab.title.ifBlank { UrlUtils.host(tab.url) }.ifBlank { getString(R.string.pane_untitled) }
        return "$name  \u00B7  ${profileNameOf(tab)}"
    }

    private fun enableSplit(tab: BrowserPane) {
        if (tab === paneA || tab !in tabs) return
        splitEnabled = true
        place(false, tab)
        activePane = tab
        closeFind()
        applySplitLayout()
        render()
        if (!profiles.isSupported && tab.profileId == paneA.profileId) {
            Snackbar.make(binding.root, R.string.profiles_split_shared_notice, Snackbar.LENGTH_LONG).show()
        }
    }

    /** Ends split view, keeping [keep] on screen. The other tab stays open in the background. */
    private fun collapseSplitTo(keep: BrowserPane) {
        val leaving = listOfNotNull(paneA, paneB).filter { it !== keep && it in tabs }
        if (keep !== paneA) place(true, keep)
        clearContainer(binding.paneBContainer)
        paneB = null
        splitEnabled = false
        leaving.forEach { it.onPause() }
        activePane = paneA
        applySplitLayout()
        render()
    }

    private fun toggleSplitOrientation() {
        splitStacked = !splitStacked
        prefs.edit().putBoolean(KEY_STACKED, splitStacked).apply()
        applySplitLayout()
        render()
    }

    /** Touching a pane makes it the one the toolbar (and its profile badge) describes. */
    private fun setActive(pane: BrowserPane) {
        if (pane === activePane) return
        closeFind()
        activePane = pane
        render()
    }

    /** Applies orientation, pane weights and divider direction for the current split state. */
    private fun applySplitLayout() = with(binding) {
        val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
        val bar = dp(DIVIDER_DP)

        splitContainer.orientation = if (splitStacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        val paneParams = { weight: Float ->
            LinearLayout.LayoutParams(
                if (splitStacked) matchParent else 0,
                if (splitStacked) 0 else matchParent,
                weight
            )
        }
        paneAContainer.layoutParams = paneParams(splitRatio)
        paneBContainer.layoutParams = paneParams(1f - splitRatio)
        splitDivider.layoutParams = LinearLayout.LayoutParams(
            if (splitStacked) matchParent else bar,
            if (splitStacked) bar else matchParent
        )
        // The grip is a short pill across the narrow direction of the divider.
        splitHandle.layoutParams = FrameLayout.LayoutParams(
            if (splitStacked) dp(36) else dp(4),
            if (splitStacked) dp(4) else dp(36),
            Gravity.CENTER
        )

        val visibility = if (splitEnabled) View.VISIBLE else View.GONE
        paneBContainer.visibility = visibility
        splitDivider.visibility = visibility
    }

    /** Drag the divider to resize the panes; double-tap it to go back to an even split. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupSplitDivider() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                setSplitRatio(0.5f)
                saveSplitRatio()
                return true
            }
        })
        binding.splitDivider.setOnTouchListener { view, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_MOVE -> dragSplitTo(event.rawX, event.rawY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> saveSplitRatio()
            }
            true
        }
    }

    private fun dragSplitTo(rawX: Float, rawY: Float) {
        val container = binding.splitContainer
        val divider = binding.splitDivider
        val origin = IntArray(2).also { container.getLocationOnScreen(it) }
        val barSize = if (splitStacked) divider.height else divider.width
        val usable = (if (splitStacked) container.height else container.width) - barSize
        if (usable <= 0) return
        val position = (if (splitStacked) rawY - origin[1] else rawX - origin[0]) - barSize / 2f
        setSplitRatio(position / usable)
    }

    private fun setSplitRatio(value: Float) {
        splitRatio = value.coerceIn(MIN_RATIO, 1f - MIN_RATIO)
        (binding.paneAContainer.layoutParams as? LinearLayout.LayoutParams)?.weight = splitRatio
        (binding.paneBContainer.layoutParams as? LinearLayout.LayoutParams)?.weight = 1f - splitRatio
        binding.splitContainer.requestLayout()
    }

    private fun saveSplitRatio() {
        prefs.edit().putFloat(KEY_RATIO, splitRatio).apply()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    // ---------------------------------------------------------------- BrowserPane.Listener

    override fun onPaneTouched(pane: BrowserPane) = setActive(pane)

    override fun onPaneChanged(pane: BrowserPane) {
        if (pane === activePane) render()
    }

    override fun onDownloadRequested(
        pane: BrowserPane,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) = downloads.onDownloadRequested(pane, url, userAgent, contentDisposition, mimeType, contentLength)

    override fun onLongPress(pane: BrowserPane, hitType: Int, target: String) {
        val extras = ArrayList<Pair<String, () -> Unit>>()
        if (hitType == WebView.HitTestResult.SRC_ANCHOR_TYPE && UrlUtils.isWebUrl(target)) {
            val profileId = if (pane.incognito) profiles.default.id else pane.profileId
            extras += getString(R.string.link_open_new_tab) to { tryNewTab(profileId, target)?.let { showTab(it) } }
            extras += getString(R.string.link_open_incognito) to { newIncognitoTab(target) }
        }
        downloads.onLongPress(pane, hitType, target, extras)
    }

    override fun onPopupBlocked(pane: BrowserPane) {
        if (pane === activePane) Snackbar.make(binding.root, R.string.popup_blocked, Snackbar.LENGTH_SHORT).show()
    }

    override fun onPageVisited(pane: BrowserPane, url: String, title: String) {
        if (!pane.incognito) BrowserRepository.recordVisit(pane.profileId, url, title)
    }

    override fun onFindResult(pane: BrowserPane, active: Int, total: Int) {
        if (pane === activePane && binding.findBar.visibility == View.VISIBLE) {
            binding.tvFindCount.text = if (total == 0) "0/0" else "${active + 1}/$total"
        }
    }

    override fun onFileChooser(
        pane: BrowserPane,
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean {
        fileCallback?.onReceiveValue(null) // a previous picker was abandoned
        fileCallback = callback
        try {
            fileChooserLauncher.launch(params.createIntent())
        } catch (e: Exception) {
            fileCallback = null
            callback.onReceiveValue(null)
            Toast.makeText(this, R.string.file_chooser_failed, Toast.LENGTH_SHORT).show()
        }
        return true
    }

    // ---------------------------------------------------------------- ProfileDialogs.Host

    override fun currentProfileId(): String = activePane.profileId

    override fun profileIdsInUse(): Set<String> = tabs.map { it.profileId }.toSet()

    override fun webViewFor(profileId: String): WebView? =
        tabs.firstOrNull { it.profileId == profileId }?.webView

    /**
     * Opens the page of the active tab in a NEW tab of [profileId] and shows it in the active pane.
     * The original tab keeps its profile and page, one tap away in the tab list.
     */
    override fun openInProfile(profileId: String) {
        val previous = activePane
        if (profileId == previous.profileId) return
        val address = previous.url.takeIf { UrlUtils.isWebUrl(it) } ?: UrlUtils.HOME
        val tab = tryNewTab(profileId, address) ?: return
        showTab(tab)
        Snackbar.make(
            binding.root,
            getString(R.string.tab_opened_in_profile, profiles.resolve(profileId).name),
            Snackbar.LENGTH_LONG
        ).setAction(R.string.tab_previous) { if (previous in tabs) showTab(previous) }.show()
    }

    override fun profilesChanged() = render()

    // ---------------------------------------------------------------- toolbar / omnibox

    private fun setupOmnibox() = with(binding) {
        etOmnibox.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                etOmnibox.setText(activePane.url)
                etOmnibox.selectAll()
            } else {
                hideKeyboard(view)
            }
            render()
        }

        etOmnibox.setOnEditorActionListener { view, actionId, event ->
            val enterKey = event != null &&
                event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enterKey) {
                navigate(view.text.toString())
                true
            } else {
                false
            }
        }

        etOmnibox.doAfterTextChanged { renderActionButton() }

        btnOmniboxAction.setOnClickListener {
            when {
                etOmnibox.hasFocus() -> etOmnibox.text.clear()
                activePane.isLoading -> activePane.stop()
                else -> activePane.reload()
            }
        }
    }

    private fun setupToolbar() {
        binding.tvProfile.setOnClickListener { profileDialogs.showSwitcher() }
        binding.btnTabs.setOnClickListener {
            paneA.captureThumbnail()
            if (splitEnabled) paneB?.captureThumbnail()
            tabSwitcher.show()
        }
        binding.btnMenu.setOnClickListener { showOverflowMenu(it) }
        binding.btnToolbarToggle.setOnClickListener { toggleToolbarVisibility() }
    }

    private fun navigate(input: String) {
        activePane.load(UrlUtils.resolve(input, BrowserSettings.searchUrl(this)))
        binding.etOmnibox.clearFocus()
        activePane.webView.requestFocus()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this) {
            val web = activePane.webView
            when {
                binding.findBar.visibility == View.VISIBLE -> closeFind()
                binding.etOmnibox.hasFocus() -> binding.etOmnibox.clearFocus()
                web.canGoBack() -> web.goBack()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    /** Re-draws the toolbar from the active pane's state. */
    private fun render() = with(binding) {
        val pane = activePane
        val focused = etOmnibox.hasFocus()

        // Text: URL while editing, page title (or host) otherwise
        if (!focused) {
            val label = pane.title.ifBlank { UrlUtils.host(pane.url) }
            if (etOmnibox.text.toString() != label) etOmnibox.setText(label)
        }

        // Padlock
        val (icon, color, description) = when {
            focused -> Triple(R.drawable.ic_search, R.color.chrome_text_secondary, R.string.cd_search)
            else -> when (pane.security) {
                BrowserPane.Security.SECURE ->
                    Triple(R.drawable.ic_lock, R.color.chrome_secure, R.string.cd_security_secure)
                BrowserPane.Security.INSECURE ->
                    Triple(R.drawable.ic_warning, R.color.chrome_insecure, R.string.cd_security_insecure)
                BrowserPane.Security.NEUTRAL ->
                    Triple(R.drawable.ic_search, R.color.chrome_text_secondary, R.string.cd_search)
            }
        }
        ivSecurity.setImageResource(icon)
        ImageViewCompat.setImageTintList(ivSecurity, ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, color)))
        ivSecurity.contentDescription = getString(description)

        // Progress bar
        progress.visibility = if (pane.isLoading && pane.progress < 100) View.VISIBLE else View.INVISIBLE
        progress.setProgressCompat(pane.progress, true)

        renderActionButton()
        renderProfile()

        tvTabCount.text = tabs.size.toString()

        // In split view each pane shows a line in its own profile color; the active one is solid.
        indicatorA.visibility = if (splitEnabled) View.VISIBLE else View.INVISIBLE
        indicatorB.visibility = if (splitEnabled) View.VISIBLE else View.INVISIBLE
        indicatorA.alpha = if (pane === paneA) 1f else 0.3f
        indicatorB.alpha = if (pane === paneB) 1f else 0.3f
    }

    /** Profile badge in the toolbar and the colored active-pane line, both in the profile's color. */
    private fun renderProfile() = with(binding) {
        val pane = activePane
        tvProfile.text = if (pane.incognito) getString(R.string.incognito_name).take(1) else profiles.resolve(pane.profileId).initial
        ViewCompat.setBackgroundTintList(tvProfile, ColorStateList.valueOf(colorOf(pane)))

        indicatorA.setBackgroundColor(colorOf(paneA))
        paneB?.let { indicatorB.setBackgroundColor(colorOf(it)) }
    }

    /** Clear (while typing) / stop (while loading) / reload. */
    private fun renderActionButton() = with(binding) {
        val (icon, description) = when {
            etOmnibox.hasFocus() -> R.drawable.ic_close to R.string.cd_clear
            activePane.isLoading -> R.drawable.ic_close to R.string.cd_stop
            else -> R.drawable.ic_refresh to R.string.cd_reload
        }
        btnOmniboxAction.setImageResource(icon)
        btnOmniboxAction.contentDescription = getString(description)
        btnOmniboxAction.visibility =
            if (etOmnibox.hasFocus() && etOmnibox.text.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    private fun hideKeyboard(view: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun toggleToolbarVisibility() = with(binding) {
        val isVisible = toolbar.visibility == View.VISIBLE
        toolbar.visibility = if (isVisible) View.GONE else View.VISIBLE
        renderToggleIcon()
    }

    /** The arrow points the way the toolbar will slide: away from the screen edge it sits on. */
    private fun renderToggleIcon() = with(binding) {
        val visible = toolbar.visibility == View.VISIBLE
        val bottom = BrowserSettings.toolbarAtBottom(this@MainActivity)
        val pointsUp = visible != bottom
        btnToolbarToggle.setImageResource(if (pointsUp) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down)
    }

    /** Puts the toolbar (and find bar) at the top or the bottom, as chosen in settings. */
    private fun applyToolbarPosition() = with(binding) {
        val bottom = BrowserSettings.toolbarAtBottom(this@MainActivity)

        root.removeView(toolbar)
        root.removeView(findBar)
        if (bottom) {
            root.addView(findBar)
            root.addView(toolbar)
        } else {
            root.addView(toolbar, 0)
            root.addView(findBar, 1)
        }
        // The load bar sits on the edge of the toolbar that faces the page.
        toolbar.removeView(progress)
        toolbar.addView(progress, if (bottom) 0 else toolbar.childCount)

        val params = btnToolbarToggle.layoutParams as FrameLayout.LayoutParams
        params.gravity = (if (bottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.START
        params.topMargin = if (bottom) 0 else dp(6)
        params.bottomMargin = if (bottom) dp(6) else 0
        btnToolbarToggle.layoutParams = params
        renderToggleIcon()
    }

    // ---------------------------------------------------------------- menus

    private fun showOverflowMenu(anchor: View) {
        val url = activePane.url
        lifecycleScope.launch {
            val bookmarked = UrlUtils.isWebUrl(url) && BrowserRepository.isBookmarked(url)
            if (!isFinishing && !isDestroyed) buildOverflowMenu(anchor, bookmarked)
        }
    }

    private fun buildOverflowMenu(anchor: View, bookmarked: Boolean) {
        val canBookmark = UrlUtils.isWebUrl(activePane.url)
        val desktop = BrowserSettings.desktopSite(this)
        val profileName = profileNameOf(activePane)
        val webPage = UrlUtils.isWebUrl(activePane.url)
        val pageHost = UrlUtils.host(activePane.url).lowercase()

        val quick = listOf(
            MainMenu.Item(R.drawable.ic_arrow_forward, getString(R.string.menu_forward), activePane.webView.canGoForward()) {
                activePane.webView.goForward()
            },
            MainMenu.Item(R.drawable.ic_refresh, getString(R.string.menu_refresh)) { activePane.reload() },
            MainMenu.Item(
                if (bookmarked) R.drawable.ic_star else R.drawable.ic_star_border,
                getString(if (bookmarked) R.string.bookmark_remove else R.string.bookmark_add),
                canBookmark
            ) { toggleBookmark() },
            MainMenu.Item(R.drawable.ic_share, getString(R.string.menu_share)) { shareCurrentPage() }
        )

        val groups = listOf(
            listOf(
                MainMenu.Item(R.drawable.ic_person, getString(R.string.menu_profile, profileName)) {
                    profileDialogs.showSwitcher()
                },
                MainMenu.Item(R.drawable.ic_add, getString(R.string.tab_new)) { newTab() },
                MainMenu.Item(R.drawable.ic_incognito, getString(R.string.menu_incognito_tab)) { newIncognitoTab() },
                MainMenu.Item(R.drawable.ic_copy, getString(R.string.menu_duplicate_tab)) { duplicateTab() },
                MainMenu.Item(
                    R.drawable.ic_history,
                    getString(R.string.menu_reopen_tab),
                    closedTabs.isNotEmpty()
                ) { reopenClosedTab() },
                MainMenu.Item(
                    R.drawable.ic_close,
                    getString(R.string.menu_close_others),
                    tabs.size > 1
                ) { closeOtherTabs() }
            ),
            listOf(
                MainMenu.Item(R.drawable.ic_split, getString(R.string.menu_split_view), checked = splitEnabled) {
                    toggleSplit()
                },
                MainMenu.Item(
                    R.drawable.ic_swap,
                    getString(if (splitStacked) R.string.split_layout_stacked else R.string.split_layout_side),
                    splitEnabled
                ) { toggleSplitOrientation() },
                MainMenu.Item(
                    R.drawable.ic_fullscreen,
                    getString(if (fullscreen) R.string.menu_exit_fullscreen else R.string.menu_fullscreen)
                ) { toggleFullscreen() },
                MainMenu.Item(R.drawable.ic_desktop, getString(R.string.menu_desktop_site), checked = desktop) {
                    toggleDesktopSite()
                }
            ),
            listOf(
                MainMenu.Item(R.drawable.ic_search, getString(R.string.menu_find)) { openFind() },
                MainMenu.Item(R.drawable.ic_reader, getString(R.string.menu_reader), webPage) {
                    activePane.toggleReader()
                },
                MainMenu.Item(
                    R.drawable.ic_volume,
                    getString(if (reader?.isSpeaking() == true) R.string.menu_stop_reading else R.string.menu_read_aloud),
                    webPage
                ) { toggleReadAloud() },
                MainMenu.Item(R.drawable.ic_translate, getString(R.string.menu_translate), webPage) { translatePage() },
                MainMenu.Item(R.drawable.ic_print, getString(R.string.menu_print), webPage) { printPage() },
                MainMenu.Item(R.drawable.ic_home, getString(R.string.menu_add_home), webPage) { addToHomeScreen() },
                MainMenu.Item(
                    R.drawable.ic_shield,
                    if (webPage) getString(R.string.menu_block_ads_count, activePane.blockedCount)
                    else getString(R.string.menu_block_ads),
                    webPage,
                    checked = AdBlocker.isEnabledFor(pageHost)
                ) { toggleAdBlock(pageHost) }
            ),
            listOf(
                MainMenu.Item(R.drawable.ic_bookmark, getString(R.string.menu_bookmarks)) {
                    openLibrary(LibraryActivity.MODE_BOOKMARKS)
                },
                MainMenu.Item(R.drawable.ic_history, getString(R.string.menu_history)) {
                    openLibrary(LibraryActivity.MODE_HISTORY)
                },
                MainMenu.Item(R.drawable.ic_download, getString(R.string.menu_downloads)) { downloads.openDownloads() }
            ),
            listOf(
                MainMenu.Item(R.drawable.ic_delete, getString(R.string.menu_clear_data)) { clearDataDialog.show() },
                MainMenu.Item(R.drawable.ic_settings, getString(R.string.menu_settings)) {
                    BrowserSettings.showDialog(this, this)
                }
            )
        )
        mainMenu.show(anchor, quick, groups)
    }

    private fun shareCurrentPage() {
        val url = activePane.url
        if (url.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_SUBJECT, activePane.title)
        }
        startActivity(Intent.createChooser(send, getString(R.string.menu_share)))
    }

    // ---------------------------------------------------------------- bookmarks / history / settings

    private fun toggleBookmark() {
        val url = activePane.url
        if (!UrlUtils.isWebUrl(url)) return
        val title = activePane.title
        lifecycleScope.launch {
            if (BrowserRepository.isBookmarked(url)) {
                BrowserRepository.removeBookmark(url)
                Snackbar.make(binding.root, R.string.bookmark_removed, Snackbar.LENGTH_SHORT).show()
            } else {
                BrowserRepository.addBookmark(title, url)
                Snackbar.make(binding.root, R.string.bookmark_added, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun openLibrary(mode: String) {
        val profile = profiles.resolve(activePane.profileId)
        libraryLauncher.launch(
            Intent(this, LibraryActivity::class.java)
                .putExtra(LibraryActivity.EXTRA_MODE, mode)
                .putExtra(LibraryActivity.EXTRA_PROFILE, profile.id)
                .putExtra(LibraryActivity.EXTRA_PROFILE_NAME, profile.name)
        )
    }

    /** Mobile <-> desktop Chrome user agent, for every pane. Visible pages reload to pick it up. */
    private fun toggleDesktopSite() {
        BrowserSettings.setDesktopSite(this, !BrowserSettings.desktopSite(this))
        applySettingsToPanes(reload = true)
    }

    private fun translatePage() {
        val url = activePane.url
        if (!UrlUtils.isWebUrl(url)) return
        val language = Locale.getDefault().language.ifBlank { "en" }
        activePane.load("https://translate.google.com/translate?sl=auto&tl=$language&u=${Uri.encode(url)}")
    }

    /** The system print screen, which also offers "Save as PDF". */
    private fun printPage() {
        val name = activePane.title.ifBlank { UrlUtils.host(activePane.url) }.ifBlank { getString(R.string.app_name) }
        val manager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print(name, activePane.webView.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
    }

    private fun toggleReadAloud() {
        val current = reader
        if (current != null && current.isSpeaking()) {
            current.stop()
            return
        }
        val script = "(function(){return document.body ? document.body.innerText : '';})()"
        activePane.webView.evaluateJavascript(script) { raw ->
            val text = runCatching { (JSONTokener(raw).nextValue() as? String).orEmpty() }.getOrDefault("").trim()
            if (text.isBlank()) {
                Toast.makeText(this, R.string.tts_nothing, Toast.LENGTH_SHORT).show()
            } else {
                val engine = reader ?: PageReader(this) {
                    Toast.makeText(this, R.string.tts_failed, Toast.LENGTH_LONG).show()
                }.also { reader = it }
                engine.speak(text)
            }
        }
    }

    /** Hides the status and navigation bars; swiping from the screen edge shows them briefly. */
    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---------------------------------------------------------------- app lock

    private fun checkLock() {
        if (!BrowserSettings.appLock(this)) {
            if (locked) unlockNow()
            return
        }
        if (!locked && stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt > LOCK_AFTER_MS) locked = true
        if (locked) {
            showLockCover()
            if (!awaitingCredential) requestUnlock()
        }
    }

    private fun showLockCover() {
        val existing = lockCover
        if (existing != null) {
            existing.visibility = View.VISIBLE
            return
        }
        val cover = FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.chrome_background))
            isClickable = true
            isFocusable = true
            val button = Button(this@MainActivity).apply {
                text = getString(R.string.lock_unlock)
                setOnClickListener { requestUnlock() }
            }
            addView(
                button,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        addContentView(
            cover,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        lockCover = cover
    }

    private fun requestUnlock() {
        if (awaitingCredential) return
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val intent = keyguard.createConfirmDeviceCredentialIntent(getString(R.string.lock_prompt), null)
        if (intent == null) { // the phone no longer has a screen lock: nothing to ask for
            unlockNow()
            return
        }
        awaitingCredential = true
        runCatching { unlockLauncher.launch(intent) }.onFailure { awaitingCredential = false }
    }

    private fun unlockNow() {
        locked = false
        stoppedAt = 0L
        lockCover?.visibility = View.GONE
    }

    /** Ad and tracker blocking on or off for one site; the page reloads to apply it. */
    private fun toggleAdBlock(pageHost: String) {
        if (pageHost.isBlank()) return
        AdBlocker.setEnabledFor(pageHost, !AdBlocker.isEnabledFor(pageHost))
        activePane.reload()
    }

    // ---------------------------------------------------------------- BrowserSettings.Host

    override fun onSettingsChanged() {
        applySettingsToPanes(reload = false)
        applyToolbarPosition()
    }

    override fun exportBookmarks() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/html")
            .putExtra(Intent.EXTRA_TITLE, "boxweb-bookmarks.html")
        runCatching { exportBookmarksLauncher.launch(intent) }
    }

    override fun importBookmarks() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        runCatching { importBookmarksLauncher.launch(intent) }
    }

    private fun writeBookmarks(uri: Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val html = BookmarkFile.build(BrowserRepository.allBookmarks())
                    contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(html) }
                }.isSuccess
            }
            val message = if (ok) R.string.bookmarks_exported else R.string.bookmarks_export_failed
            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun readBookmarks(uri: Uri) {
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching {
                    val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                    BrowserRepository.importBookmarks(BookmarkFile.parse(text))
                }.getOrNull()
            }
            val message = if (added == null) {
                getString(R.string.bookmarks_import_failed)
            } else {
                getString(R.string.bookmarks_imported, added)
            }
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun applySettingsToPanes(reload: Boolean) {
        tabs.forEach { it.applyBrowserSettings() }
        if (reload) {
            listOfNotNull(paneA, if (splitEnabled) paneB else null)
                .filter { it.url.isNotBlank() }
                .forEach { it.reload() }
        }
    }

    // ---------------------------------------------------------------- find in page

    private fun setupFindBar() = with(binding) {
        etFind.doAfterTextChanged { runFind(it?.toString().orEmpty()) }
        etFind.setOnEditorActionListener { view, _, _ ->
            activePane.webView.findNext(true)
            hideKeyboard(view)
            true
        }
        btnFindNext.setOnClickListener { activePane.webView.findNext(true) }
        btnFindPrev.setOnClickListener { activePane.webView.findNext(false) }
        btnFindClose.setOnClickListener { closeFind() }
    }

    private fun openFind() {
        binding.findBar.visibility = View.VISIBLE
        binding.etFind.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(binding.etFind, InputMethodManager.SHOW_IMPLICIT)
        runFind(binding.etFind.text.toString())
    }

    private fun runFind(text: String) {
        if (text.isEmpty()) {
            activePane.webView.clearMatches()
            binding.tvFindCount.text = ""
        } else {
            activePane.webView.findAllAsync(text)
        }
    }

    private fun closeFind() {
        if (binding.findBar.visibility != View.VISIBLE) return
        binding.findBar.visibility = View.GONE
        tabs.forEach { it.webView.clearMatches() }
        hideKeyboard(binding.etFind)
        binding.etFind.text.clear()
        binding.tvFindCount.text = ""
        activePane.webView.requestFocus()
    }

    private companion object {
        const val PREFS = "ui_state"
        const val KEY_SPLIT = "split_enabled"
        const val KEY_STACKED = "split_stacked"
        const val KEY_RATIO = "split_ratio"
        const val MIN_RATIO = 0.2f
        const val DIVIDER_DP = 14
        const val KEY_TABS = "tabs"
        const val KEY_SLOT_A = "slot_a"
        const val KEY_SLOT_B = "slot_b"
        const val KEY_ACTIVE = "active_slot"
        const val KEY_URL_A = "url_a"         // single-page state of older versions
        const val KEY_PROFILE_A = "profile_a" // single-page state of older versions
        const val MAX_TABS = 12
        const val MAX_CLOSED = 15
        const val LOCK_AFTER_MS = 30_000L
    }
}

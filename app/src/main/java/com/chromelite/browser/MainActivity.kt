package com.chromelite.browser

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.chromelite.browser.data.BrowserRepository
import com.chromelite.browser.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), BrowserPane.Listener, ProfileDialogs.Host, TabSwitcher.Host {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var profiles: ProfileManager
    private lateinit var profileDialogs: ProfileDialogs
    private lateinit var downloads: DownloadController
    private lateinit var clearDataDialog: ClearDataDialog
    private lateinit var tabSwitcher: TabSwitcher

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

    /** Every open tab (each one a WebView bound to exactly one profile). */
    private val tabs = ArrayList<BrowserPane>()

    /** The tabs currently on screen: [paneA] always, [paneB] only while split view is on. */
    private lateinit var paneA: BrowserPane
    private var paneB: BrowserPane? = null

    /** The one of the two (or the only) visible tabs the toolbar and menus act on. */
    private lateinit var activePane: BrowserPane

    private var splitEnabled = false
    private var splitStacked = false // false = side by side, true = top & bottom

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        profiles = ProfileManager.get(this)
        profileDialogs = ProfileDialogs(this, profiles, this)
        downloads = DownloadController(this, profiles, binding.root)
        clearDataDialog = ClearDataDialog(
            this, profiles,
            currentProfile = { profiles.resolve(activePane.profileId) },
            livePanes = { tabs.toList() }
        )
        tabSwitcher = TabSwitcher(this, profiles, this)

        splitStacked = prefs.getBoolean(KEY_STACKED, false)
        restoreTabs()

        setupOmnibox()
        setupToolbar()
        setupFindBar()
        setupBackNavigation()
        applySplitLayout()
        render()

        // Only for a fresh launch: after the system restarts the app it would re-deliver the same link.
        if (savedInstanceState == null) webUrlFrom(intent)?.let { openLink(it) }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        webUrlFrom(intent)?.let { openLink(it) }
    }

    /** A link from another app opens in its own tab, in the profile of the tab being used. */
    private fun openLink(url: String) {
        tryNewTab(activePane.profileId, url)?.let { showTab(it) }
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
        tabs.forEach { it.destroy() }
        tabs.clear()
        super.onDestroy()
    }

    /** Remembers all tabs (address, title, profile) and which ones are on screen. */
    private fun saveState() {
        val array = JSONArray()
        tabs.forEach {
            array.put(
                JSONObject().put("url", it.url).put("title", it.title).put("profile", it.profileId)
            )
        }
        prefs.edit()
            .putString(KEY_TABS, array.toString())
            .putInt(KEY_SLOT_A, tabs.indexOf(paneA))
            .putInt(KEY_SLOT_B, paneB?.let { tabs.indexOf(it) } ?: -1)
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

        val swipe = SwipeRefreshLayout(this)
        swipe.addView(
            webView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        // Third-party cookies are needed by many sign-in flows (embedded OAuth frames).
        profiles.cookieManager(boundTo).setAcceptThirdPartyCookies(webView, true)

        val tab = BrowserPane(webView, swipe, boundTo, this)
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
        tab.destroy()

        if (wasActive) activePane = if (inA) paneA else (paneB ?: paneA)
        applySplitLayout()
        render()
    }

    override fun newTab() {
        tryNewTab(activePane.profileId, UrlUtils.HOME)?.let {
            showTab(it)
            binding.etOmnibox.requestFocus()
        }
    }

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
        return "$name  \u00B7  ${profiles.resolve(tab.profileId).name}"
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

    /** Applies orientation, 50/50 weights and divider direction for the current split state. */
    private fun applySplitLayout() = with(binding) {
        val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
        val line = maxOf(1, dp(1))

        splitContainer.orientation = if (splitStacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        val paneParams = {
            LinearLayout.LayoutParams(
                if (splitStacked) matchParent else 0,
                if (splitStacked) 0 else matchParent,
                1f
            )
        }
        paneAContainer.layoutParams = paneParams()
        paneBContainer.layoutParams = paneParams()
        splitDivider.layoutParams = LinearLayout.LayoutParams(
            if (splitStacked) matchParent else line,
            if (splitStacked) line else matchParent
        )

        val visibility = if (splitEnabled) View.VISIBLE else View.GONE
        paneBContainer.visibility = visibility
        splitDivider.visibility = visibility
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

    override fun onLongPress(pane: BrowserPane, hitType: Int, target: String) =
        downloads.onLongPress(pane, hitType, target)

    override fun onPageVisited(pane: BrowserPane, url: String, title: String) =
        BrowserRepository.recordVisit(pane.profileId, url, title)

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
        binding.btnTabs.setOnClickListener { tabSwitcher.show() }
        binding.btnMenu.setOnClickListener { showOverflowMenu(it) }
    }

    private fun navigate(input: String) {
        activePane.load(UrlUtils.resolve(input))
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
        val profile = profiles.resolve(activePane.profileId)
        tvProfile.text = profile.initial
        ViewCompat.setBackgroundTintList(tvProfile, ColorStateList.valueOf(profile.color))

        indicatorA.setBackgroundColor(profiles.resolve(paneA.profileId).color)
        paneB?.let { indicatorB.setBackgroundColor(profiles.resolve(it.profileId).color) }
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

    // ---------------------------------------------------------------- menus

    private fun showOverflowMenu(anchor: View) {
        val url = activePane.url
        lifecycleScope.launch {
            val bookmarked = UrlUtils.isWebUrl(url) && BrowserRepository.isBookmarked(url)
            if (!isFinishing && !isDestroyed) buildOverflowMenu(anchor, bookmarked)
        }
    }

    private fun buildOverflowMenu(anchor: View, bookmarked: Boolean) {
        val popup = PopupMenu(this, anchor, Gravity.END)
        popup.menuInflater.inflate(R.menu.menu_main, popup.menu)

        popup.menu.findItem(R.id.action_bookmark_toggle).apply {
            setTitle(if (bookmarked) R.string.bookmark_remove else R.string.bookmark_add)
            isEnabled = UrlUtils.isWebUrl(activePane.url)
        }
        popup.menu.findItem(R.id.action_desktop_site).isChecked = BrowserSettings.desktopSite(this)

        popup.menu.findItem(R.id.action_profile).title =
            getString(R.string.menu_profile, profiles.resolve(activePane.profileId).name)
        popup.menu.findItem(R.id.action_forward).isEnabled = activePane.webView.canGoForward()
        popup.menu.findItem(R.id.action_split).isChecked = splitEnabled
        popup.menu.findItem(R.id.action_split_orientation).apply {
            isEnabled = splitEnabled
            setTitle(if (splitStacked) R.string.split_layout_stacked else R.string.split_layout_side)
        }

        popup.setOnMenuItemClickListener { onMenuItem(it) }
        popup.show()
    }

    private fun onMenuItem(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_profile -> profileDialogs.showSwitcher()
            R.id.action_forward -> activePane.webView.goForward()
            R.id.action_refresh -> activePane.reload()
            R.id.action_split -> toggleSplit()
            R.id.action_new_tab -> newTab()
            R.id.action_split_orientation -> toggleSplitOrientation()
            R.id.action_share -> shareCurrentPage()
            R.id.action_bookmark_toggle -> toggleBookmark()
            R.id.action_find -> openFind()
            R.id.action_desktop_site -> toggleDesktopSite()
            R.id.action_bookmarks -> openLibrary(LibraryActivity.MODE_BOOKMARKS)
            R.id.action_history -> openLibrary(LibraryActivity.MODE_HISTORY)
            R.id.action_downloads -> downloads.openDownloads()
            R.id.action_clear_data -> clearDataDialog.show()
            R.id.action_settings -> BrowserSettings.showDialog(this) { applySettingsToPanes(reload = false) }
        }
        return true
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
        const val KEY_TABS = "tabs"
        const val KEY_SLOT_A = "slot_a"
        const val KEY_SLOT_B = "slot_b"
        const val KEY_ACTIVE = "active_slot"
        const val KEY_URL_A = "url_a"         // single-page state of older versions
        const val KEY_PROFILE_A = "profile_a" // single-page state of older versions
        const val MAX_TABS = 12
    }
}

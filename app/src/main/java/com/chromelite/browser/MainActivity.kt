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
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), BrowserPane.Listener, ProfileDialogs.Host {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var profiles: ProfileManager
    private lateinit var profileDialogs: ProfileDialogs
    private lateinit var downloads: DownloadController
    private lateinit var clearDataDialog: ClearDataDialog

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

    private lateinit var paneA: BrowserPane
    private var paneB: BrowserPane? = null
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
            livePanes = { listOfNotNull(paneA, paneB) }
        )

        splitEnabled = prefs.getBoolean(KEY_SPLIT, false)
        splitStacked = prefs.getBoolean(KEY_STACKED, false)

        paneA = createPane(
            binding.paneAContainer, binding.indicatorA,
            profiles.resolve(prefs.getString(KEY_PROFILE_A, null)).id
        )
        activePane = paneA
        paneA.load(webUrlFrom(intent) ?: prefs.getString(KEY_URL_A, null) ?: UrlUtils.HOME)
        if (splitEnabled) ensurePaneB()

        setupOmnibox()
        setupToolbar()
        setupFindBar()
        setupBackNavigation()
        applySplitLayout()
        render()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        webUrlFrom(intent)?.let { activePane.load(it) }
    }

    override fun onResume() {
        super.onResume()
        paneA.onResume()
        if (splitEnabled) paneB?.onResume()
    }

    override fun onPause() {
        saveState()
        // Persist every profile's cookies so a killed process does not lose the logins.
        listOfNotNull(paneA, paneB).map { it.profileId }.distinct().forEach { profiles.flush(it) }
        paneA.onPause()
        paneB?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        paneB?.destroy()
        paneA.destroy()
        super.onDestroy()
    }

    private fun saveState() {
        val editor = prefs.edit()
        paneA.url.takeIf { it.isNotBlank() }?.let { editor.putString(KEY_URL_A, it) }
        paneB?.url?.takeIf { it.isNotBlank() }?.let { editor.putString(KEY_URL_B, it) }
        editor.putString(KEY_PROFILE_A, paneA.profileId)
        paneB?.let { editor.putString(KEY_PROFILE_B, it.profileId) }
        editor.apply()
    }

    private fun webUrlFrom(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_VIEW) intent.dataString?.takeIf { UrlUtils.isWebUrl(it) } else null

    // ---------------------------------------------------------------- panes / split view

    /**
     * Builds a WebView bound to [profileId]. The profile has to be attached before the WebView
     * is configured or loads anything; if it cannot be applied the pane honestly reports the
     * default profile instead.
     */
    private fun createPane(container: FrameLayout, indicator: View, profileId: String): BrowserPane {
        val webView = WebView(this)
        val boundTo = if (profiles.attach(webView, profileId)) profileId else profiles.default.id

        val swipe = SwipeRefreshLayout(this)
        swipe.addView(
            webView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        container.addView(
            swipe, 0,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        // Third-party cookies are needed by many sign-in flows (embedded OAuth frames).
        profiles.cookieManager(boundTo).setAcceptThirdPartyCookies(webView, true)

        return BrowserPane(webView, swipe, indicator, boundTo, this)
    }

    /**
     * Pane B is created lazily the first time split view is turned on. It starts in a different
     * profile than pane A (creating one if needed) so two accounts can be used straight away.
     */
    private fun ensurePaneB(): BrowserPane {
        paneB?.let { return it }

        val stored = prefs.getString(KEY_PROFILE_B, null)
        val profileId = when {
            stored != null && profiles.exists(stored) -> stored
            else -> profiles.all().firstOrNull { it.id != paneA.profileId }?.id
                ?: profiles.create(getString(R.string.profile_auto_name))?.id
                ?: paneA.profileId
        }

        val pane = createPane(binding.paneBContainer, binding.indicatorB, profileId)
        paneB = pane
        pane.load(prefs.getString(KEY_URL_B, null) ?: UrlUtils.HOME)

        if (pane.profileId == paneA.profileId && !profiles.isSupported) {
            Snackbar.make(binding.root, R.string.profiles_split_shared_notice, Snackbar.LENGTH_LONG).show()
        }
        return pane
    }

    private fun setSplit(enabled: Boolean) {
        splitEnabled = enabled
        if (enabled) {
            ensurePaneB().onResume()
        } else {
            paneB?.onPause()
            if (activePane === paneB) activePane = paneA
        }
        prefs.edit().putBoolean(KEY_SPLIT, enabled).apply()
        applySplitLayout()
        render()
    }

    private fun toggleSplitOrientation() {
        splitStacked = !splitStacked
        prefs.edit().putBoolean(KEY_STACKED, splitStacked).apply()
        applySplitLayout()
        render()
    }

    private fun setActive(pane: BrowserPane) {
        if (pane === activePane) return
        closeFind()
        activePane = pane
        render()
    }

    /**
     * A WebView cannot change profile once it exists, so switching means replacing the pane's
     * WebView with a fresh one (new, empty session) that opens the same address.
     */
    private fun switchProfile(pane: BrowserPane, profileId: String) {
        if (pane.profileId == profileId) return

        val isA = pane === paneA
        val container = if (isA) binding.paneAContainer else binding.paneBContainer
        val indicator = if (isA) binding.indicatorA else binding.indicatorB
        val wasActive = pane === activePane
        val address = pane.url.ifBlank { UrlUtils.HOME }
        val wasLoadable = UrlUtils.isWebUrl(address)

        pane.destroy()
        val fresh = createPane(container, indicator, profileId)
        if (isA) paneA = fresh else paneB = fresh
        if (wasActive) activePane = fresh
        fresh.load(if (wasLoadable) address else UrlUtils.HOME)

        saveState()
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

    override fun profileIdsInUse(): Set<String> =
        listOfNotNull(paneA, if (splitEnabled) paneB else null).map { it.profileId }.toSet()

    override fun webViewFor(profileId: String): WebView? =
        listOfNotNull(paneA, paneB).firstOrNull { it.profileId == profileId }?.webView

    override fun switchCurrentPane(profileId: String) = switchProfile(activePane, profileId)

    /** The hidden pane B (split view off) may still hold a profile; drop it so it can be deleted. */
    override fun releaseHiddenPane(profileId: String) {
        val hidden = paneB ?: return
        if (!splitEnabled && hidden.profileId == profileId) {
            hidden.destroy()
            paneB = null
            prefs.edit().remove(KEY_PROFILE_B).remove(KEY_URL_B).apply()
        }
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
        binding.btnTabs.setOnClickListener { showPaneMenu(it) }
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

        tvTabCount.text = if (splitEnabled) "2" else "1"

        indicatorA.visibility = if (splitEnabled && pane === paneA) View.VISIBLE else View.INVISIBLE
        indicatorB.visibility = if (splitEnabled && pane === paneB) View.VISIBLE else View.INVISIBLE
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

    /** Tab-counter popup: switch between open panes and open/close split view. */
    private fun showPaneMenu(anchor: View) {
        val panes = listOfNotNull(paneA, if (splitEnabled) paneB else null)
        val popup = PopupMenu(this, anchor, Gravity.END)

        panes.forEachIndexed { index, pane ->
            val label = pane.title.ifBlank { UrlUtils.host(pane.url) }
                .ifBlank { getString(R.string.pane_untitled) }
                .take(32)
            val profileName = profiles.resolve(pane.profileId).name
            popup.menu.add(0, index, index, "${index + 1}  $label  ·  $profileName").apply {
                isCheckable = true
                isChecked = pane === activePane
            }
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.menu.add(
            1, ID_TOGGLE_SPLIT, ID_TOGGLE_SPLIT,
            getString(if (splitEnabled) R.string.pane_close_split else R.string.pane_open_split)
        )

        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == ID_TOGGLE_SPLIT) setSplit(!splitEnabled)
            else panes.getOrNull(item.itemId)?.let { setActive(it) }
            true
        }
        popup.show()
    }

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
            R.id.action_split -> setSplit(!splitEnabled)
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
        listOfNotNull(paneA, paneB).forEach { it.applyBrowserSettings() }
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
        listOfNotNull(paneA, paneB).forEach { it.webView.clearMatches() }
        hideKeyboard(binding.etFind)
        binding.etFind.text.clear()
        binding.tvFindCount.text = ""
        activePane.webView.requestFocus()
    }

    private companion object {
        const val PREFS = "ui_state"
        const val KEY_SPLIT = "split_enabled"
        const val KEY_STACKED = "split_stacked"
        const val KEY_URL_A = "url_a"
        const val KEY_URL_B = "url_b"
        const val KEY_PROFILE_A = "profile_a"
        const val KEY_PROFILE_B = "profile_b"
        const val ID_TOGGLE_SPLIT = 100
    }
}

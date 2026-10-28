package com.chromelite.browser

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
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
import android.webkit.WebView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import com.chromelite.browser.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity(), BrowserPane.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

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
        splitEnabled = prefs.getBoolean(KEY_SPLIT, false)
        splitStacked = prefs.getBoolean(KEY_STACKED, false)

        paneA = createPane(binding.paneAContainer, binding.indicatorA)
        activePane = paneA
        paneA.load(webUrlFrom(intent) ?: prefs.getString(KEY_URL_A, null) ?: UrlUtils.HOME)
        if (splitEnabled) ensurePaneB()

        setupOmnibox()
        setupToolbar()
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
        editor.apply()
    }

    private fun webUrlFrom(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_VIEW) intent.dataString?.takeIf { UrlUtils.isWebUrl(it) } else null

    // ---------------------------------------------------------------- panes / split view

    private fun createPane(container: FrameLayout, indicator: View): BrowserPane {
        val webView = WebView(this)
        container.addView(
            webView, 0,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        return BrowserPane(webView, indicator, this)
    }

    /** Pane B is created lazily the first time split view is turned on. */
    private fun ensurePaneB(): BrowserPane = paneB ?: createPane(binding.paneBContainer, binding.indicatorB).also {
        paneB = it
        it.load(prefs.getString(KEY_URL_B, null) ?: UrlUtils.HOME)
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

        tvTabCount.text = if (splitEnabled) "2" else "1"

        indicatorA.visibility = if (splitEnabled && pane === paneA) View.VISIBLE else View.INVISIBLE
        indicatorB.visibility = if (splitEnabled && pane === paneB) View.VISIBLE else View.INVISIBLE
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
            popup.menu.add(0, index, index, "${index + 1}  $label").apply {
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
        val popup = PopupMenu(this, anchor, Gravity.END)
        popup.menuInflater.inflate(R.menu.menu_main, popup.menu)

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
            R.id.action_forward -> activePane.webView.goForward()
            R.id.action_refresh -> activePane.reload()
            R.id.action_split -> setSplit(!splitEnabled)
            R.id.action_split_orientation -> toggleSplitOrientation()
            R.id.action_share -> shareCurrentPage()
            R.id.action_find -> comingSoon(item, 5)
            R.id.action_desktop_site -> comingSoon(item, 5)
            R.id.action_bookmarks -> comingSoon(item, 5)
            R.id.action_history -> comingSoon(item, 5)
            R.id.action_downloads -> comingSoon(item, 4)
            R.id.action_clear_data -> comingSoon(item, 5)
            R.id.action_settings -> comingSoon(item, 4)
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

    /** Menu entries whose features are built in later steps. */
    private fun comingSoon(item: MenuItem, step: Int) {
        Snackbar.make(binding.root, getString(R.string.coming_in_step, item.title, step), Snackbar.LENGTH_SHORT).show()
    }

    private companion object {
        const val PREFS = "ui_state"
        const val KEY_SPLIT = "split_enabled"
        const val KEY_STACKED = "split_stacked"
        const val KEY_URL_A = "url_a"
        const val KEY_URL_B = "url_b"
        const val ID_TOGGLE_SPLIT = 100
    }
}

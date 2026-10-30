package com.boxweb.browser

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.boxweb.browser.data.BrowserRepository
import com.boxweb.browser.databinding.ActivityLibraryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * History or bookmarks list (one screen, two modes). Picking an entry returns its address to the
 * browser through [EXTRA_URL].
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private lateinit var adapter: LibraryAdapter

    private var isHistory = true
    private var profileId = ProfileManager.DEFAULT_ID
    private var allRows: List<LibraryRow> = emptyList()
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isHistory = intent.getStringExtra(EXTRA_MODE) != MODE_BOOKMARKS
        profileId = intent.getStringExtra(EXTRA_PROFILE) ?: ProfileManager.DEFAULT_ID
        val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME).orEmpty()

        binding.toolbar.title = getString(if (isHistory) R.string.menu_history else R.string.menu_bookmarks)
        if (isHistory && profileName.isNotBlank()) binding.toolbar.subtitle = profileName
        binding.toolbar.setNavigationOnClickListener { finish() }
        if (isHistory) {
            binding.toolbar.inflateMenu(R.menu.menu_library)
            binding.toolbar.setOnMenuItemClickListener {
                if (it.itemId == R.id.action_clear_history) confirmClear()
                true
            }
        }

        adapter = LibraryAdapter(
            onOpen = { row ->
                setResult(RESULT_OK, Intent().putExtra(EXTRA_URL, row.url))
                finish()
            },
            onDelete = { row ->
                if (isHistory) BrowserRepository.deleteHistoryEntry(row.id) else BrowserRepository.deleteBookmark(row.id)
            }
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.list.itemAnimator = null

        binding.etSearch.doAfterTextChanged {
            query = it?.toString().orEmpty().trim()
            applyFilter()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val flow = if (isHistory) {
                    BrowserRepository.history(profileId).map { list ->
                        list.map { LibraryRow(it.id, it.title, it.url, it.visitedAt) }
                    }
                } else {
                    BrowserRepository.bookmarks().map { list ->
                        list.map { LibraryRow(it.id, it.title, it.url, it.createdAt) }
                    }
                }
                flow.collect {
                    allRows = it
                    applyFilter()
                }
            }
        }
    }

    private fun applyFilter() {
        val rows = if (query.isEmpty()) {
            allRows
        } else {
            allRows.filter { it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true) }
        }
        adapter.submitList(rows)
        binding.empty.setText(
            when {
                allRows.isNotEmpty() -> R.string.library_no_matches
                isHistory -> R.string.history_empty
                else -> R.string.bookmarks_empty
            }
        )
        binding.empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_clear_title)
            .setMessage(R.string.history_clear_message)
            .setPositiveButton(R.string.profiles_clear) { _, _ -> BrowserRepository.clearHistory(profileId) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_PROFILE_NAME = "profile_name"
        const val EXTRA_URL = "url"
        const val MODE_HISTORY = "history"
        const val MODE_BOOKMARKS = "bookmarks"
    }
}

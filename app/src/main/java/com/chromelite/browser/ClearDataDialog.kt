package com.chromelite.browser

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.chromelite.browser.data.BrowserRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * "Clear browsing data": history, cookies and site data, cached files.
 * Applies to the profile of the active pane, or to every profile.
 *
 * Cached files can only be emptied through a live WebView of that profile, so for profiles with
 * no open pane the cache is left alone (cookies and site storage are always cleared).
 */
class ClearDataDialog(
    private val activity: AppCompatActivity,
    private val profiles: ProfileManager,
    private val currentProfile: () -> BrowserProfile,
    private val livePanes: () -> List<BrowserPane>
) {

    fun show() {
        val checked = booleanArrayOf(true, true, true)
        val labels = arrayOf(
            activity.getString(R.string.clear_history),
            activity.getString(R.string.clear_site_data),
            activity.getString(R.string.clear_cache)
        )
        val profile = currentProfile()

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.menu_clear_data)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(activity.getString(R.string.clear_this_profile, profile.name)) { _, _ ->
                clear(checked, allProfiles = false)
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (profiles.all().size > 1) {
            builder.setNeutralButton(R.string.clear_all_profiles) { _, _ -> clear(checked, allProfiles = true) }
        }
        builder.show()
    }

    private fun clear(checked: BooleanArray, allProfiles: Boolean) {
        val (history, siteData, cache) = checked.toList()
        val ids = if (allProfiles) profiles.all().map { it.id } else listOf(currentProfile().id)

        if (history) {
            if (allProfiles) BrowserRepository.clearAllHistory() else BrowserRepository.clearHistory(ids.first())
        }
        ids.forEach { id ->
            if (siteData) profiles.clearSiteData(id)
            if (cache) livePanes().firstOrNull { it.profileId == id }?.webView?.apply {
                clearCache(true)
                clearFormData()
            }
        }
        Toast.makeText(activity, R.string.clear_done, Toast.LENGTH_SHORT).show()
    }
}

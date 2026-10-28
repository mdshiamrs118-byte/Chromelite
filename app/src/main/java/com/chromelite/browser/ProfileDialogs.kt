package com.chromelite.browser

import android.text.InputType
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Profile picker plus create / rename / clear / delete dialogs. */
class ProfileDialogs(
    private val activity: AppCompatActivity,
    private val manager: ProfileManager,
    private val host: Host
) {

    interface Host {
        /** Profile of the pane the toolbar is currently showing. */
        fun currentProfileId(): String

        /** Profiles that are bound to a visible, running pane. They cannot be deleted. */
        fun profileIdsInUse(): Set<String>

        /** A live WebView using this profile (to also empty its cache), if any. */
        fun webViewFor(profileId: String): WebView?

        /** Moves the current pane to another profile. */
        fun switchCurrentPane(profileId: String)

        /** Lets the activity release a hidden pane that still holds the profile. */
        fun releaseHiddenPane(profileId: String)

        /** Names or colors changed; re-draw the toolbar. */
        fun profilesChanged()
    }

    // ---------------------------------------------------------------- switcher

    fun showSwitcher() {
        if (!manager.isSupported) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.profiles_unavailable_title)
                .setMessage(R.string.profiles_unavailable_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val profiles = manager.all()
        val currentId = host.currentProfileId()
        val labels = profiles.map { (if (it.id == currentId) "✓  " else "  ") + it.name }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.profiles_switch_title)
            .setItems(labels.toTypedArray()) { _, which ->
                host.switchCurrentPane(profiles[which].id)
            }
            .setNeutralButton(R.string.profiles_new) { _, _ -> promptCreate() }
            .setNegativeButton(R.string.profiles_manage) { _, _ -> showManage() }
            .show()
    }

    // ---------------------------------------------------------------- manage

    private fun showManage() {
        val profiles = manager.all()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.profiles_manage)
            .setItems(profiles.map { it.name }.toTypedArray()) { _, which -> showActions(profiles[which]) }
            .setPositiveButton(android.R.string.cancel, null)
            .show()
    }

    private fun showActions(profile: BrowserProfile) {
        val isDefault = profile.id == ProfileManager.DEFAULT_ID
        val actions = buildList<Pair<String, () -> Unit>> {
            add(activity.getString(R.string.profiles_rename) to { promptRename(profile) })
            add(activity.getString(R.string.profiles_clear) to { confirmClear(profile) })
            if (!isDefault) add(activity.getString(R.string.profiles_delete) to { confirmDelete(profile) })
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(profile.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    // ---------------------------------------------------------------- actions

    private fun promptCreate() {
        promptName(R.string.profiles_new, "") { name ->
            val created = manager.create(name)
            if (created == null) {
                toast(R.string.profiles_create_failed)
            } else {
                host.profilesChanged()
                host.switchCurrentPane(created.id)
            }
        }
    }

    private fun promptRename(profile: BrowserProfile) {
        promptName(R.string.profiles_rename, profile.name) { name ->
            manager.rename(profile.id, name)
            host.profilesChanged()
        }
    }

    private fun confirmClear(profile: BrowserProfile) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.profiles_clear_title, profile.name))
            .setMessage(R.string.profiles_clear_message)
            .setPositiveButton(R.string.profiles_clear) { _, _ ->
                manager.clearData(profile.id, host.webViewFor(profile.id))
                toast(R.string.profiles_cleared)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(profile: BrowserProfile) {
        if (profile.id in host.profileIdsInUse()) {
            toast(R.string.profiles_delete_in_use)
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.profiles_delete_title, profile.name))
            .setMessage(R.string.profiles_delete_message)
            .setPositiveButton(R.string.profiles_delete) { _, _ ->
                host.releaseHiddenPane(profile.id)
                if (manager.delete(profile.id)) {
                    host.profilesChanged()
                } else {
                    toast(R.string.profiles_delete_failed)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- helpers

    private fun promptName(titleRes: Int, initial: String, onDone: (String) -> Unit) {
        val input = EditText(activity).apply {
            setText(initial)
            setSelection(initial.length)
            hint = activity.getString(R.string.profiles_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val frame = FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(titleRes)
            .setView(frame)
            .setPositiveButton(android.R.string.ok) { _, _ -> onDone(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(resId: Int) = Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show()
}

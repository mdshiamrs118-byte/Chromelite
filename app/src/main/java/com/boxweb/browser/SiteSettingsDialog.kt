package com.boxweb.browser

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * "Site settings" for one host: desktop mode and text size, each of which can follow the global
 * setting or be fixed for this site. Every tap changes one value and shows the dialog again.
 */
class SiteSettingsDialog(
    private val activity: AppCompatActivity,
    private val host: String,
    /** Called after each change; [desktopChanged] means the open pages of this site need a reload. */
    private val onChanged: (desktopChanged: Boolean) -> Unit
) {

    fun show() {
        val on = activity.getString(R.string.state_on)
        val off = activity.getString(R.string.state_off)
        fun state(value: Boolean) = if (value) on else off

        val desktopOverride = SiteSettings.desktopOverride(activity, host)
        val zoomOverride = SiteSettings.zoomOverride(activity, host)

        val desktopValue = if (desktopOverride == null) {
            activity.getString(R.string.site_default_value, state(BrowserSettings.desktopSite(activity)))
        } else {
            state(desktopOverride)
        }
        val zoomValue = if (zoomOverride == null) {
            activity.getString(R.string.site_default_value, "${BrowserSettings.textZoom(activity)}%")
        } else {
            "$zoomOverride%"
        }

        val entries = ArrayList<Pair<String, () -> Unit>>()

        // default -> on -> off -> default
        entries += activity.getString(R.string.site_desktop, desktopValue) to {
            val next: Boolean? = when (desktopOverride) {
                null -> true
                true -> false
                else -> null
            }
            SiteSettings.setDesktop(activity, host, next)
            changed(desktop = true)
        }

        // default -> each size in turn -> default
        entries += activity.getString(R.string.site_text_size, zoomValue) to {
            val steps = listOf<Int?>(null) + BrowserSettings.TEXT_SIZES
            val next = steps[(steps.indexOf(zoomOverride) + 1) % steps.size]
            SiteSettings.setTextZoom(activity, host, next)
            changed(desktop = false)
        }

        if (SiteSettings.hasOverride(activity, host)) {
            entries += activity.getString(R.string.site_reset) to {
                val hadDesktop = desktopOverride != null
                SiteSettings.clear(activity, host)
                changed(desktop = hadDesktop)
            }
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(SiteSettings.key(host))
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun changed(desktop: Boolean) {
        onChanged(desktop)
        show()
    }
}

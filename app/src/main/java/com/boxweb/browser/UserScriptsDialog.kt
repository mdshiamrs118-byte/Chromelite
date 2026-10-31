package com.boxweb.browser

import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** List, add, edit, switch off and delete user scripts. */
class UserScriptsDialog(
    private val activity: AppCompatActivity,
    private val onChanged: () -> Unit
) {

    fun show() {
        val scripts = UserScripts.all(activity)
        val builder = MaterialAlertDialogBuilder(activity).setTitle(R.string.userscripts_title)
        if (scripts.isEmpty()) {
            builder.setMessage(R.string.userscripts_empty)
        } else {
            val labels = scripts.map {
                val off = if (it.enabled) "" else activity.getString(R.string.userscript_off_prefix) + " "
                off + it.name + "  ·  " + it.match.ifBlank { "*" }
            }
            builder.setItems(labels.toTypedArray()) { _, which -> showActions(scripts[which]) }
        }
        builder
            .setPositiveButton(R.string.userscripts_add) { _, _ -> edit(null) }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun showActions(script: UserScript) {
        val toggleLabel = activity.getString(if (script.enabled) R.string.userscript_disable else R.string.userscript_enable)
        val items = arrayOf(activity.getString(R.string.userscript_edit), toggleLabel, activity.getString(R.string.userscript_delete))
        MaterialAlertDialogBuilder(activity)
            .setTitle(script.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> edit(script)
                    1 -> {
                        UserScripts.put(activity, script.copy(enabled = !script.enabled))
                        onChanged()
                        show()
                    }
                    else -> {
                        UserScripts.delete(activity, script.id)
                        onChanged()
                        show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> show() }
            .show()
    }

    private fun edit(existing: UserScript?) {
        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()

        fun field(hint: Int, text: String, multiline: Boolean): EditText = EditText(activity).apply {
            setHint(hint)
            setText(text)
            if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                minLines = 6
                gravity = Gravity.TOP or Gravity.START
            } else {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine()
            }
        }

        val name = field(R.string.userscript_name_hint, existing?.name.orEmpty(), false)
        val site = field(R.string.userscript_site_hint, existing?.match ?: "*", false)
        val code = field(R.string.userscript_code_hint, existing?.code.orEmpty(), true)

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(name)
            addView(site)
            addView(code)
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(if (existing == null) R.string.userscripts_add else R.string.userscript_edit)
            .setView(box)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val scriptName = name.text.toString().trim()
                val scriptCode = code.text.toString()
                if (scriptName.isEmpty() || scriptCode.isBlank()) {
                    Toast.makeText(activity, R.string.userscript_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    UserScripts.put(
                        activity,
                        UserScript(
                            id = existing?.id ?: UserScripts.newId(),
                            name = scriptName,
                            match = site.text.toString().trim().ifEmpty { "*" },
                            code = scriptCode,
                            enabled = existing?.enabled ?: true
                        )
                    )
                    onChanged()
                }
                show()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> show() }
            .show()
    }
}

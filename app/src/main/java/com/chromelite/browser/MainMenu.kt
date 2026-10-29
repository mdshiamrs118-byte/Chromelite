package com.chromelite.browser

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.chromelite.browser.databinding.ItemMenuBinding
import com.chromelite.browser.databinding.ItemMenuQuickBinding
import com.chromelite.browser.databinding.PopupMenuBinding

/**
 * The overflow menu: a rounded card with a row of quick actions on top and the grouped list below.
 * Rows can carry a switch ([Item.checked] != null). Taking a choice closes the menu first.
 */
class MainMenu(private val activity: AppCompatActivity) {

    class Item(
        @DrawableRes val icon: Int,
        val title: String,
        val enabled: Boolean = true,
        /** null = a plain row, true / false = a row with a switch in that state. */
        val checked: Boolean? = null,
        val onClick: () -> Unit
    )

    private var popup: PopupWindow? = null

    fun show(anchor: View, quick: List<Item>, groups: List<List<Item>>) {
        dismiss()
        val inflater = LayoutInflater.from(activity)
        val b = PopupMenuBinding.inflate(inflater)

        val window = PopupWindow(b.root, dp(MENU_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup = window

        // Clipping to the rounded outline keeps row highlights inside the corners.
        b.card.clipToOutline = true

        quick.forEach { item ->
            val q = ItemMenuQuickBinding.inflate(inflater, b.quickRow, false)
            q.icon.setImageResource(item.icon)
            q.icon.contentDescription = item.title
            q.root.alpha = if (item.enabled) 1f else DISABLED_ALPHA
            if (item.enabled) {
                q.root.setOnClickListener {
                    window.dismiss()
                    item.onClick()
                }
            }
            b.quickRow.addView(q.root)
        }

        groups.forEachIndexed { index, group ->
            if (index > 0) b.list.addView(divider())
            group.forEach { item ->
                val r = ItemMenuBinding.inflate(inflater, b.list, false)
                r.icon.setImageResource(item.icon)
                r.title.text = item.title
                item.checked?.let {
                    r.toggle.visibility = View.VISIBLE
                    r.toggle.isChecked = it
                }
                r.root.alpha = if (item.enabled) 1f else DISABLED_ALPHA
                if (item.enabled) {
                    r.root.setOnClickListener {
                        window.dismiss()
                        item.onClick()
                    }
                }
                b.list.addView(r.root)
            }
        }

        // On short screens the list scrolls instead of running off the bottom.
        b.list.measure(
            View.MeasureSpec.makeMeasureSpec(dp(MENU_WIDTH_DP - 24), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val cap = (activity.resources.displayMetrics.heightPixels * 0.6f).toInt()
        b.scroll.layoutParams = b.scroll.layoutParams.apply { height = minOf(b.list.measuredHeight, cap) }

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.isOutsideTouchable = true
        window.animationStyle = R.style.MenuPopupAnimation
        window.showAsDropDown(anchor, dp(4), 0, Gravity.END)
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    private fun divider(): View =
        View(activity).apply {
            setBackgroundColor(ContextCompat.getColor(activity, R.color.chrome_divider))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(1) / 2)).apply {
                setMargins(dp(16), dp(4), dp(16), dp(4))
            }
        }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val MENU_WIDTH_DP = 284
        const val DISABLED_ALPHA = 0.38f
    }
}

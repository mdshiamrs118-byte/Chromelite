package com.boxweb.browser

import android.graphics.Bitmap
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Shows a page address as a QR code, so another phone can scan it. */
object QrShare {

    fun show(activity: AppCompatActivity, address: String) {
        val bitmap = runCatching { encode(address, QR_PIXELS) }.getOrNull()
        if (bitmap == null) {
            Toast.makeText(activity, R.string.qr_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()

        // White around the code even in the dark theme: scanners need the contrast.
        val image = ImageView(activity).apply {
            setImageBitmap(bitmap)
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = address
        }
        val label = TextView(activity).apply {
            text = address
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(ContextCompat.getColor(activity, R.color.chrome_text_secondary))
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(image, LinearLayout.LayoutParams(dp(260), dp(260)))
            addView(
                label,
                LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
            )
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.qr_title)
            .setView(box)
            .setPositiveButton(R.string.dialog_close, null)
            .show()
    }

    private fun encode(text: String, size: Int): Bitmap {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height) { i ->
            if (matrix.get(i % width, i / width)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private const val QR_PIXELS = 800
}

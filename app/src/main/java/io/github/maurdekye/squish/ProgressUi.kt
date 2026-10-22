package io.github.maurdekye.squish

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** The dialog shown while files are checked and squished. Safe to call from any thread. */
class ProgressUi(private val activity: Activity, onCancel: () -> Unit) {
    private class Row(val status: TextView, val bar: ProgressBar)

    private val main = Handler(Looper.getMainLooper())
    private val message = TextView(activity)
    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val rows = mutableListOf<Row>()
    private val dialog: AlertDialog

    init {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(message)
            addView(list)
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.app_name)
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .setCancelable(false)
            .create()
    }

    fun show(text: CharSequence) = ui {
        message.text = text
        if (!dialog.isShowing) dialog.show()
    }

    fun hide() = ui { if (dialog.isShowing) dialog.dismiss() }

    fun setFiles(names: List<String>) = ui {
        list.removeAllViews()
        rows.clear()
        for (name in names) {
            val title = TextView(activity).apply {
                text = name
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(12), 0, 0)
            }
            val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
            val status = TextView(activity).apply {
                text = activity.getString(R.string.waiting)
                textSize = 13f
            }
            list.addView(title)
            list.addView(bar)
            list.addView(status)
            rows += Row(status, bar)
        }
    }

    fun progress(index: Int, fraction: Float) = ui {
        rows.getOrNull(index)?.bar?.progress = (fraction.coerceIn(0f, 1f) * 1000).roundToInt()
    }

    fun status(index: Int, text: CharSequence) = ui { rows.getOrNull(index)?.status?.text = text }

    fun done(index: Int, text: CharSequence) = ui {
        rows.getOrNull(index)?.let {
            it.bar.progress = 1000
            it.status.text = text
        }
    }

    private fun ui(block: () -> Unit) {
        val run = { if (!activity.isDestroyed) block() }
        if (Looper.myLooper() == Looper.getMainLooper()) run() else main.post(run)
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
}

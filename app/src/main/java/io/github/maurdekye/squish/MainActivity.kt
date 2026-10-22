package io.github.maurdekye.squish

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/** Settings (the upload limit), how-to, and a way to try the squisher without Discord. */
class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var settings: Settings
    private lateinit var results: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        applyInsets(findViewById(R.id.root))
        setUpLimit()
        setUpCodec()
        results = findViewById(R.id.test_results)
        findViewById<Button>(R.id.test_button).setOnClickListener { pickForTest() }
        findViewById<TextView>(R.id.footer).text = getString(R.string.version, BuildConfig.VERSION_NAME)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** The app draws edge to edge, so keep the content clear of the status and navigation bars. */
    private fun applyInsets(root: View) {
        val content = findViewById<View>(R.id.content)
        val left = content.paddingLeft
        val top = content.paddingTop
        val right = content.paddingRight
        val bottom = content.paddingBottom
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                content.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                content.setPadding(
                    left + insets.systemWindowInsetLeft,
                    top + insets.systemWindowInsetTop,
                    right + insets.systemWindowInsetRight,
                    bottom + insets.systemWindowInsetBottom,
                )
            }
            insets
        }
    }

    private fun setUpLimit() {
        val group = findViewById<RadioGroup>(R.id.limit_group)
        val custom = findViewById<EditText>(R.id.limit_custom_mb)
        val presets = mapOf(
            R.id.limit_free to Settings.DEFAULT_LIMIT_MB,
            R.id.limit_basic to Settings.NITRO_BASIC_MB,
            R.id.limit_nitro to Settings.NITRO_MB,
        )
        val current = settings.limitMb
        val preset = presets.entries.firstOrNull { it.value == current }?.key
        group.check(preset ?: R.id.limit_custom)
        custom.setText(current.toString())
        custom.isEnabled = preset == null
        group.setOnCheckedChangeListener { _, id ->
            val mb = presets[id]
            custom.isEnabled = mb == null
            if (mb != null) {
                settings.limitMb = mb
                custom.setText(mb.toString())
            } else {
                custom.text.toString().toIntOrNull()?.let { settings.limitMb = it }
                custom.requestFocus()
            }
        }
        custom.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!custom.isEnabled) return
                val mb = s.toString().toIntOrNull() ?: return
                if (mb in Settings.MIN_LIMIT_MB..Settings.MAX_LIMIT_MB) settings.limitMb = mb
            }
        })
    }

    private fun setUpCodec() {
        val group = findViewById<RadioGroup>(R.id.codec_group)
        group.check(if (settings.videoCodec == VideoCodec.H265) R.id.codec_h265 else R.id.codec_h264)
        if (!VideoCodec.hasEncoder(VideoCodec.H265)) {
            findViewById<View>(R.id.codec_h265).isEnabled = false
            findViewById<TextView>(R.id.codec_note).setText(R.string.codec_unsupported)
        }
        group.setOnCheckedChangeListener { _, id ->
            settings.videoCodec = if (id == R.id.codec_h265) VideoCodec.H265 else VideoCodec.H264
        }
    }

    private fun pickForTest() {
        try {
            startActivityForResult(MediaPick.mediaIntent(null, MediaPick.DISCORD_MAX_FILES), REQUEST_TEST)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_TEST || resultCode != RESULT_OK || data == null) return
        val uris = MediaPick.urisOf(data)
        if (uris.isEmpty()) return
        SquishRunner(this, scope, keepOriginals = false) { picked -> if (picked != null) showResults(picked) }
            .start(uris)
    }

    private fun showResults(picked: List<Picked>) {
        results.removeAllViews()
        for (p in picked) {
            val item = p.item
            val text = TextView(this).apply {
                textSize = 15f
                text = if (item == null) {
                    getString(R.string.test_kept, p.info.name, Formats.size(p.info.size))
                } else {
                    val result = getString(R.string.test_squished, p.info.name, Formats.size(p.info.size), Formats.size(item.squishedFile.length()))
                    if (p.detail == null) result else "$result (${p.detail})"
                }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 0)
                addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            if (item != null) {
                row.addView(Button(this).apply {
                    setText(R.string.test_view)
                    setOnClickListener { view(item) }
                })
            }
            results.addView(row)
        }
    }

    private fun view(item: Item) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(ItemStore.uri(this, item, alwaysSquished = true), item.squishedMime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val REQUEST_TEST = 1
    }
}

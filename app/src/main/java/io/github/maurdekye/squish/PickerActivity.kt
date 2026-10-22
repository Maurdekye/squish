package io.github.maurdekye.squish

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlin.math.min

/**
 * Answers GET_CONTENT. Opens the system photo picker, squishes what is too big for Discord, and
 * returns the files to the app that asked.
 */
class PickerActivity : Activity() {
    private val scope = MainScope()
    private lateinit var mode: Mode
    private var allowMultiple = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = Callers.modeFor(callingPackage)
        allowMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        logRequest()
        if (savedInstanceState == null) pickMedia()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun logRequest() {
        val extras = intent.extras?.let { b -> b.keySet().joinToString { "$it=${b.get(it)}" } }
        val launchedFrom = if (Build.VERSION.SDK_INT >= 34) launchedFromPackage else "?"
        Log.i(TAG, "request: caller=$callingPackage launchedFrom=$launchedFrom referrer=$referrer mode=$mode intent=$intent extras=[$extras]")
    }

    // The kinds of media the requesting app accepts: "image/*", "video/*", or null for both.
    private fun mediaFilter(): String? {
        val requested = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList()
            ?: listOfNotNull(intent.type).flatMap { it.split('|') }
        val types = requested.ifEmpty { listOf("*/*") }
        val images = types.any { it == "*/*" || it.startsWith("image/") }
        val videos = types.any { it == "*/*" || it.startsWith("video/") }
        return when {
            images && !videos -> "image/*"
            videos && !images -> "video/*"
            else -> null
        }
    }

    private fun maxItems(): Int = when {
        !allowMultiple -> 1
        mode == Mode.OTHER -> MediaPick.maxPickable()
        else -> min(MediaPick.maxPickable(), MediaPick.DISCORD_MAX_FILES)
    }

    private fun pickMedia() {
        val filter = mediaFilter()
        try {
            startActivityForResult(MediaPick.mediaIntent(filter, maxItems()), REQUEST_MEDIA)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no photo picker", e)
            browseFiles()
        }
    }

    private fun browseFiles() {
        val type = intent.type?.takeIf { '|' !in it } ?: "*/*"
        val mimeTypes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
        try {
            startActivityForResult(MediaPick.documentIntent(type, mimeTypes, allowMultiple), REQUEST_FILES)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no file browser", e)
            cancelPick()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uris = if (resultCode == RESULT_OK && data != null) MediaPick.urisOf(data) else emptyList()
        if (uris.isEmpty()) {
            offerChoices()
            return
        }
        Log.i(TAG, "picked ${uris.size}: $uris")
        handle(uris.take(maxItems()))
    }

    /** The user backed out of the picker: offer the file browser too, or leave. */
    private fun offerChoices() {
        AlertDialog.Builder(this)
            .setTitle(R.string.pick_title)
            .setItems(arrayOf(getString(R.string.pick_media), getString(R.string.pick_files))) { _, which ->
                if (which == 0) pickMedia() else browseFiles()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelPick() }
            .setOnCancelListener { cancelPick() }
            .show()
    }

    private fun handle(uris: List<Uri>) {
        if (mode == Mode.OTHER) {
            returnFiles(uris, uris.map { contentResolver.getType(it) ?: "*/*" })
            return
        }
        SquishRunner(this, scope, keepOriginals = mode == Mode.UNKNOWN) { picked ->
            if (picked == null) {
                cancelPick()
            } else {
                returnFiles(
                    picked.map { p -> p.item?.let { ItemStore.uri(this, it) } ?: p.info.uri },
                    picked.map { it.item?.squishedMime ?: it.info.mime },
                )
            }
        }.start(uris)
    }

    private fun returnFiles(uris: List<Uri>, mimes: List<String>) {
        val result = Intent().apply {
            clipData = MediaPick.clipOf(getString(R.string.app_name), uris, mimes)
            if (uris.size == 1) data = uris[0]
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Log.i(TAG, "returning ${uris.size}: $uris")
        setResult(RESULT_OK, result)
        finish()
    }

    private fun cancelPick() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private companion object {
        const val TAG = "Squish"
        const val REQUEST_MEDIA = 1
        const val REQUEST_FILES = 2
    }
}

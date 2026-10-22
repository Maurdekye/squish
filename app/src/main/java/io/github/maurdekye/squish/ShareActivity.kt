package io.github.maurdekye.squish

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/** "Squish for Discord" in the share sheet: shrink what's too big, then share it all to Discord. */
class ShareActivity : Activity() {
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris()
        Log.i(TAG, "share from $referrer: $uris")
        when {
            uris.isEmpty() -> fail(R.string.nothing_shared)
            !discordInstalled() -> fail(R.string.no_discord)
            else -> SquishRunner(this, scope, keepOriginals = false) { picked ->
                if (picked != null) sendToDiscord(picked)
                finish()
            }.start(uris.take(MediaPick.DISCORD_MAX_FILES))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(): List<Uri> {
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
        }
        if (uris.isEmpty()) {
            intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNullTo(uris) { clip.getItemAt(it).uri } }
        }
        return uris.distinct()
    }

    private fun discordInstalled() = runCatching { packageManager.getPackageInfo(Callers.DISCORD, 0) }.isSuccess

    private fun sendToDiscord(picked: List<Picked>) {
        val uris = picked.map { p -> p.item?.let { ItemStore.uri(this, it) } ?: p.info.uri }
        val mimes = picked.map { it.item?.squishedMime ?: it.info.mime }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = commonType(mimes)
        send.clipData = MediaPick.clipOf(getString(R.string.app_name), uris, mimes)
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { send.putExtra(Intent.EXTRA_TEXT, it) }
        send.setPackage(Callers.DISCORD)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Log.i(TAG, "sharing to Discord: $uris")
        try {
            startActivity(send)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_discord, Toast.LENGTH_LONG).show()
        }
    }

    private fun commonType(mimes: List<String>): String {
        val distinct = mimes.distinct()
        if (distinct.size == 1) return distinct[0]
        val families = distinct.map { it.substringBefore('/') }.distinct()
        return if (families.size == 1) "${families[0]}/*" else "*/*"
    }

    private fun fail(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private companion object {
        const val TAG = "Squish"
    }
}

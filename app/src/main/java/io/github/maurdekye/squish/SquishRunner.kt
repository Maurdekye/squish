package io.github.maurdekye.squish

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

/**
 * One picked file and what Squish made of it. [item] is null when the original goes as is.
 * [detail] describes the squished video, e.g. "720p H.264".
 */
class Picked(val info: MediaInfo, val item: Item?, val detail: String? = null)

/**
 * Checks the picked files, warns about results that will look poor, then squishes whatever is over
 * the limit, one file at a time, behind a progress dialog. [onDone] gets the results on the main
 * thread, or null if the user cancelled.
 */
class SquishRunner(
    private val activity: Activity,
    private val scope: CoroutineScope,
    /** Set when the final reader is unknown, so the provider can still serve the original. */
    private val keepOriginals: Boolean,
    private val onDone: (List<Picked>?) -> Unit,
) {
    private val settings = Settings(activity)
    private val limitBytes = settings.limitBytes
    private val limitMb = settings.limitMb
    private val ui = ProgressUi(activity) { cancel() }
    private var job: Job? = null
    private var finished = false

    fun start(uris: List<Uri>) {
        // A long video can take a minute; don't let the screen time out on it.
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui.show(activity.getString(R.string.checking_files))
        job = scope.launch {
            try {
                run(uris)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "squish failed", e)
                Toast.makeText(activity, e.message ?: e.toString(), Toast.LENGTH_LONG).show()
                finish(null)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        finish(null)
    }

    private suspend fun run(uris: List<Uri>) {
        val infos = withContext(Dispatchers.IO) { uris.map { MediaProbe.probe(activity, it) } }
        val codec = settings.videoCodec.takeIf { it == VideoCodec.H264 || VideoCodec.hasEncoder(it) } ?: VideoCodec.H264
        val plans = infos.map { Planner.plan(it, limitBytes, codec) }
        Log.i(TAG, "limit $limitBytes, codec $codec (chosen ${settings.videoCodec}), plans: $plans")
        if (plans.all { it == Plan.Keep }) return finish(infos.map { Picked(it, null) })

        val warnings = warnings(infos, plans)
        if (warnings.isNotEmpty()) {
            ui.hide()
            val go = ask(
                activity.getString(R.string.warn_title),
                warnings.joinToString("\n\n"),
                activity.getString(R.string.warn_continue),
            )
            if (!go) return finish(null)
        }

        ui.show(activity.getString(R.string.squishing))
        ui.setFiles(infos.map { it.name })
        val results = ArrayList<Picked>()
        for ((i, info) in infos.withIndex()) {
            val plan = plans[i]
            val item = if (plan is Plan.Image || plan is Plan.Video) {
                try {
                    squishOne(info, plan, i)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "couldn't squish ${info.name}", e)
                    ui.hide()
                    val sendOriginal = ask(
                        activity.getString(R.string.error_title),
                        activity.getString(R.string.error_body, info.name, e.message ?: e.javaClass.simpleName),
                        activity.getString(R.string.error_send_original),
                    )
                    if (!sendOriginal) return finish(null)
                    ui.show(activity.getString(R.string.squishing))
                    null
                }
            } else {
                null
            }
            ui.done(
                i,
                if (item == null) activity.getString(R.string.sent_as_is)
                else activity.getString(R.string.done_size, Formats.size(info.size), Formats.size(item.squishedFile.length())),
            )
            val detail = if (item != null && plan is Plan.Video) "${plan.shortSide}p ${plan.codec.label}" else null
            results += Picked(info, item, detail)
        }
        finish(results)
    }

    private suspend fun squishOne(info: MediaInfo, plan: Plan, index: Int): Item {
        val (id, dir) = ItemStore.newDir(activity)
        try {
            val out = File(dir, Item.SQUISHED)
            val progress: (Float) -> Unit = { ui.progress(index, it) }
            val mime = when (plan) {
                is Plan.Image -> ImageSquisher.squish(activity, info.uri, plan.maxBytes, out, progress)
                is Plan.Video -> VideoSquisher.squish(activity, info, plan, out, progress) {
                    ui.status(index, activity.getString(R.string.retrying))
                }
                else -> error("nothing to squish in $plan")
            }
            if (keepOriginals) keepOriginal(info, File(dir, Item.ORIGINAL))
            val item = Item(id, dir, info.uri, info.name, info.mime, info.size, squishedName(info.name, mime), mime)
            ItemStore.save(item)
            Log.i(TAG, "squished ${info.name}: ${info.size} -> ${out.length()} bytes as ${item.squishedName}")
            return item
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
    }

    /**
     * The provider may later have to serve the original to an app that isn't Discord, after this
     * screen (and the temporary read grant that came with the pick) is gone.
     */
    private suspend fun keepOriginal(info: MediaInfo, copy: File) {
        val resolver = activity.contentResolver
        val kept = runCatching {
            resolver.takePersistableUriPermission(info.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (kept) return
        withContext(Dispatchers.IO) {
            val input = resolver.openInputStream(info.uri) ?: throw IOException("can't read ${info.name}")
            input.use { copy.outputStream().use { out -> it.copyTo(out) } }
        }
    }

    private fun warnings(infos: List<MediaInfo>, plans: List<Plan>): List<String> =
        infos.zip(plans).mapNotNull { (info, plan) ->
            when {
                plan is Plan.Video && plan.poor -> activity.getString(
                    R.string.warn_poor_video, info.name, Formats.duration(info.durationMs), plan.shortSide,
                )
                plan is Plan.CantShrink -> activity.getString(
                    R.string.warn_cant_shrink, info.name, Formats.size(info.size), reasonText(plan.reason),
                )
                else -> null
            }
        }

    private fun reasonText(reason: Plan.Reason): String = when (reason) {
        Plan.Reason.GIF -> activity.getString(R.string.reason_gif)
        Plan.Reason.NOT_MEDIA -> activity.getString(R.string.reason_other)
        Plan.Reason.UNREADABLE -> activity.getString(R.string.reason_unreadable)
        Plan.Reason.TOO_LONG -> activity.getString(R.string.reason_too_long, limitMb)
    }

    private suspend fun ask(title: String, message: String, positive: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val dialog = AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positive) { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> if (cont.isActive) cont.resume(false) }
                .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                .show()
            cont.invokeOnCancellation {
                activity.runOnUiThread { if (!activity.isDestroyed && dialog.isShowing) dialog.dismiss() }
            }
        }

    private fun finish(results: List<Picked>?) {
        if (finished) return
        finished = true
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui.hide()
        onDone(results)
    }

    private fun squishedName(original: String, mime: String): String {
        val base = original.substringBeforeLast('.').ifBlank { "squished" }
        val ext = when (mime) {
            "image/jpeg" -> "jpg"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: mime.substringAfter('/')
        }
        return "$base.$ext"
    }

    private companion object {
        const val TAG = "Squish"
    }
}

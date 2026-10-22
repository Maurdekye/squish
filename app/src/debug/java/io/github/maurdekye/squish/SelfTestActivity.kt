package io.github.maurdekye.squish

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sin
import kotlin.random.Random

/**
 * Debug-only self-test: makes a synthetic photo and video in the app's own cache, squishes them
 * with the real engine, and logs the results under the tag SquishSelfTest. Touches no user media.
 * Extras: "codecs" (comma list of VideoCodec names, default all), "photoLimitMb", "videoLimitMb".
 */
@OptIn(UnstableApi::class)
class SelfTestActivity : Activity() {
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val codecs = intent.getStringExtra("codecs")?.split(',')?.map { VideoCodec.valueOf(it.trim()) }
            ?: VideoCodec.entries
        val photoLimitMb = intent.getIntExtra("photoLimitMb", 5)
        val videoLimitMb = intent.getIntExtra("videoLimitMb", 20)
        scope.launch {
            try {
                val dir = File(cacheDir, "selftest").apply { deleteRecursively(); mkdirs() }
                val photo = makePhoto(File(dir, "photo.png"))
                testPhoto(photo, photoLimitMb)
                val video = makeVideo(photo, File(dir, "source.mp4"))
                for (codec in codecs) testVideo(video, codec, videoLimitMb)
                Log.i(TAG, "SELFTEST DONE")
            } catch (e: Throwable) {
                Log.e(TAG, "SELFTEST FAILED", e)
            } finally {
                finish()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** A 12 MP gradient with grain, so it compresses like a real photo rather than a flat image. */
    private suspend fun makePhoto(out: File): File = withContext(Dispatchers.Default) {
        val w = 4000
        val h = 3000
        val random = Random(42)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val n = random.nextInt(-24, 25)
                pixels[y * w + x] = Color.rgb(
                    (x * 255 / w + n).coerceIn(0, 255),
                    (y * 255 / h + n).coerceIn(0, 255),
                    ((x + y) * 255 / (w + h) - n).coerceIn(0, 255),
                )
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        bitmap.setHasAlpha(false)
        out.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        Log.i(TAG, "photo source: ${out.length()} bytes")
        out
    }

    private suspend fun testPhoto(photo: File, limitMb: Int) {
        val info = withContext(Dispatchers.IO) { MediaProbe.probe(this@SelfTestActivity, Uri.fromFile(photo)) }
        val plan = Planner.plan(info, limitMb * 1_000_000L)
        Log.i(TAG, "photo plan: $plan")
        if (plan !is Plan.Image) {
            Log.e(TAG, "SELFTEST photo: expected an image plan")
            return
        }
        val out = File(photo.parentFile, "photo-squished")
        val start = SystemClock.elapsedRealtime()
        val mime = ImageSquisher.squish(this, info.uri, plan.maxBytes, out) {}
        Log.i(
            TAG,
            "SELFTEST photo: ${info.size} -> ${out.length()} bytes as $mime in ${SystemClock.elapsedRealtime() - start} ms " +
                "(max ${plan.maxBytes}) ok=${out.length() in 1..plan.maxBytes}",
        )
    }

    /** 15 s of the photo slowly turning, encoded at a high bitrate so it's well over the limit. */
    private suspend fun makeVideo(photo: File, out: File): File = withContext(Dispatchers.Main) {
        val done = CompletableDeferred<ExportResult>()
        val transformer = Transformer.Builder(this@SelfTestActivity)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(this@SelfTestActivity)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(30_000_000).build())
                    .build(),
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    done.complete(exportResult)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    done.completeExceptionally(exportException)
                }
            })
            .build()
        val turn = MatrixTransformation { presentationTimeUs ->
            val s = presentationTimeUs / 1_000_000f
            Matrix().apply {
                postRotate(s * 24f)
                postScale(1.5f + 0.2f * sin(s), 1.5f + 0.2f * sin(s))
            }
        }
        val item = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(Uri.fromFile(photo)).setImageDurationMs(15_000).build(),
        )
            .setFrameRate(30)
            .setEffects(
                Effects(
                    emptyList(),
                    listOf(Presentation.createForWidthAndHeight(1920, 1080, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP), turn),
                ),
            )
            .build()
        transformer.start(Composition.Builder(EditedMediaItemSequence.withVideoFrom(listOf(item))).build(), out.absolutePath)
        done.await()
        Log.i(TAG, "video source: ${out.length()} bytes")
        out
    }

    private suspend fun testVideo(video: File, codec: VideoCodec, limitMb: Int) {
        val info = withContext(Dispatchers.IO) { MediaProbe.probe(this@SelfTestActivity, Uri.fromFile(video)) }
        val plan = Planner.plan(info, limitMb * 1_000_000L, codec)
        Log.i(TAG, "video plan ($codec): $plan")
        if (plan !is Plan.Video) {
            Log.e(TAG, "SELFTEST video $codec: expected a video plan")
            return
        }
        val out = File(video.parentFile, "video-${codec.name}.mp4")
        val start = SystemClock.elapsedRealtime()
        VideoSquisher.squish(this, info, plan, out, {}) { Log.i(TAG, "video $codec: retrying") }
        val result = withContext(Dispatchers.IO) { MediaProbe.probe(this@SelfTestActivity, Uri.fromFile(out)) }
        Log.i(
            TAG,
            "SELFTEST video $codec: ${info.size} -> ${out.length()} bytes, ${result.width}x${result.height}, " +
                "track ${videoTrackMime(out)}, in ${SystemClock.elapsedRealtime() - start} ms " +
                "(max ${plan.maxBytes}) ok=${out.length() in 1..plan.maxBytes}",
        )
    }

    private fun videoTrackMime(file: File): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                .firstOrNull { it?.startsWith("video/") == true }
        } finally {
            extractor.release()
        }
    }

    private companion object {
        const val TAG = "SquishSelfTest"
    }
}
